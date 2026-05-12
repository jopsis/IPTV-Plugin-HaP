#include "httpaceproxycpp/http_client.hpp"
#include "httpaceproxycpp/util.hpp"

#include <arpa/inet.h>
#include <jni.h>
#include <netdb.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <sstream>

namespace httpace {
namespace {

JavaVM* g_vm = nullptr;
jclass g_bridge_class = nullptr;
jmethodID g_fetch_method = nullptr;

struct EnvHandle {
    JNIEnv* env = nullptr;
    bool detach = false;
    ~EnvHandle() {
        if (detach && g_vm) g_vm->DetachCurrentThread();
    }
};

EnvHandle get_env() {
    if (!g_vm) throw std::runtime_error("JavaVM is not initialized");
    EnvHandle handle;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&handle.env), JNI_VERSION_1_6) == JNI_OK) return handle;
    if (g_vm->AttachCurrentThread(&handle.env, nullptr) != JNI_OK) {
        throw std::runtime_error("AttachCurrentThread failed");
    }
    handle.detach = true;
    return handle;
}

std::string jstr(JNIEnv* env, jstring value) {
    if (!value) return "";
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

jobjectArray make_header_array(JNIEnv* env, const std::map<std::string, std::string>& headers) {
    auto string_class = env->FindClass("java/lang/String");
    auto arr = env->NewObjectArray(static_cast<jsize>(headers.size() * 2), string_class, nullptr);
    jsize index = 0;
    for (const auto& [key, value] : headers) {
        auto jkey = env->NewStringUTF(key.c_str());
        auto jvalue = env->NewStringUTF(value.c_str());
        env->SetObjectArrayElement(arr, index++, jkey);
        env->SetObjectArrayElement(arr, index++, jvalue);
        env->DeleteLocalRef(jkey);
        env->DeleteLocalRef(jvalue);
    }
    env->DeleteLocalRef(string_class);
    return arr;
}

void close_fd(int& fd) {
    if (fd >= 0) {
        ::close(fd);
        fd = -1;
    }
}

bool send_all(int fd, const std::string& data) {
    const char* ptr = data.data();
    std::size_t left = data.size();
    while (left > 0) {
        ssize_t n = ::send(fd, ptr, left, 0);
        if (n <= 0) return false;
        ptr += n;
        left -= static_cast<std::size_t>(n);
    }
    return true;
}

int connect_tcp(const ParsedUrl& parsed, long connect_timeout_seconds) {
    addrinfo hints{};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    addrinfo* result = nullptr;
    auto port = parsed.port.empty() ? std::string("80") : parsed.port;
    if (::getaddrinfo(parsed.host.c_str(), port.c_str(), &hints, &result) != 0) {
        throw std::runtime_error("cannot resolve " + parsed.host);
    }
    int fd = -1;
    for (auto* rp = result; rp; rp = rp->ai_next) {
        fd = ::socket(rp->ai_family, rp->ai_socktype, rp->ai_protocol);
        if (fd < 0) continue;
        timeval timeout{connect_timeout_seconds, 0};
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
        if (::connect(fd, rp->ai_addr, rp->ai_addrlen) == 0) break;
        close_fd(fd);
    }
    freeaddrinfo(result);
    if (fd < 0) throw std::runtime_error("connect failed to " + parsed.host + ":" + port);
    return fd;
}

std::string request_target(const ParsedUrl& parsed) {
    auto target = parsed.path.empty() ? "/" : parsed.path;
    if (!parsed.query.empty()) target += "?" + parsed.query;
    return target;
}

bool recv_append(int fd, std::string& buffer, const std::atomic<bool>& running) {
    if (!running.load()) return false;
    char tmp[16384];
    ssize_t n = ::recv(fd, tmp, sizeof(tmp), 0);
    if (n <= 0) return false;
    buffer.append(tmp, static_cast<std::size_t>(n));
    return true;
}

std::map<std::string, std::string> parse_response_headers(const std::string& headers) {
    std::map<std::string, std::string> out;
    auto lines = split(headers, '\n', false);
    for (std::size_t i = 1; i < lines.size(); ++i) {
        auto line = trim(lines[i]);
        auto colon = line.find(':');
        if (colon == std::string::npos) continue;
        out[lower(trim(line.substr(0, colon)))] = trim(line.substr(colon + 1));
    }
    return out;
}

bool parse_chunk_size(const std::string& line, std::size_t& size) {
    auto clean = trim(line);
    auto semicolon = clean.find(';');
    if (semicolon != std::string::npos) clean = clean.substr(0, semicolon);
    if (clean.empty()) return false;
    char* end = nullptr;
    auto value = std::strtoull(clean.c_str(), &end, 16);
    if (!end || *end != '\0') return false;
    size = static_cast<std::size_t>(value);
    return true;
}

bool stream_chunked_body(int fd,
                         std::string pending,
                         const std::function<bool(const char*, std::size_t)>& on_chunk,
                         const std::atomic<bool>& running,
                         std::size_t& total_bytes) {
    while (running.load()) {
        auto line_end = pending.find("\r\n");
        while (line_end == std::string::npos) {
            if (!recv_append(fd, pending, running)) return false;
            line_end = pending.find("\r\n");
        }

        std::size_t chunk_size = 0;
        if (!parse_chunk_size(pending.substr(0, line_end), chunk_size)) return false;
        pending.erase(0, line_end + 2);
        if (chunk_size == 0) return true;

        while (pending.size() < chunk_size + 2) {
            if (!recv_append(fd, pending, running)) return false;
        }

        if (!on_chunk(pending.data(), chunk_size)) return false;
        total_bytes += chunk_size;
        pending.erase(0, chunk_size);
        if (pending.rfind("\r\n", 0) != 0) return false;
        pending.erase(0, 2);
    }
    return false;
}

} // namespace

HttpClient::HttpClient() = default;
HttpClient::~HttpClient() = default;

HttpClientResponse HttpClient::get(const std::string& url,
                                   const std::map<std::string, std::string>& headers,
                                   long timeout_seconds,
                                   bool follow_redirects) const {
    if (!g_bridge_class || !g_fetch_method) throw std::runtime_error("Native HTTP bridge is not initialized");
    auto handle = get_env();
    JNIEnv* env = handle.env;
    auto jurl = env->NewStringUTF(url.c_str());
    auto jheaders = make_header_array(env, headers);
    auto result = static_cast<jobjectArray>(env->CallStaticObjectMethod(
        g_bridge_class, g_fetch_method, jurl, jheaders,
        static_cast<jint>(timeout_seconds), static_cast<jboolean>(follow_redirects)));
    env->DeleteLocalRef(jurl);
    env->DeleteLocalRef(jheaders);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        throw std::runtime_error("Java HTTP bridge threw an exception");
    }
    if (!result) throw std::runtime_error("Java HTTP bridge returned null");

    HttpClientResponse response;
    response.status = std::stol(jstr(env, static_cast<jstring>(env->GetObjectArrayElement(result, 0))));
    response.url = jstr(env, static_cast<jstring>(env->GetObjectArrayElement(result, 1)));
    auto header_lines = split(jstr(env, static_cast<jstring>(env->GetObjectArrayElement(result, 2))), '\n', false);
    response.body = jstr(env, static_cast<jstring>(env->GetObjectArrayElement(result, 3)));
    env->DeleteLocalRef(result);
    if (response.status == 0) throw std::runtime_error(response.body.empty() ? "HTTP fetch failed" : response.body);

    for (const auto& line : header_lines) {
        auto tab = line.find('\t');
        if (tab != std::string::npos) response.headers[lower(trim(line.substr(0, tab)))] = trim(line.substr(tab + 1));
    }
    return response;
}

bool HttpClient::stream(const std::string& url,
                        const std::function<bool(const char*, std::size_t)>& on_chunk,
                        const std::atomic<bool>& running,
                        long connect_timeout_seconds,
                        long read_timeout_seconds,
                        long) const {
    auto parsed = parse_url(url);
    if (parsed.scheme != "http") {
        throw std::runtime_error("native streaming currently supports only http URLs: " + url);
    }
    log_line("INFO", "native stream GET " + url);
    int fd = connect_tcp(parsed, connect_timeout_seconds);
    timeval read_timeout{read_timeout_seconds, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &read_timeout, sizeof(read_timeout));

    std::ostringstream request;
    request << "GET " << request_target(parsed) << " HTTP/1.1\r\n"
            << "Host: " << parsed.authority << "\r\n"
            << "User-Agent: HaP\r\n"
            << "Accept: */*\r\n"
            << "Connection: keep-alive\r\n\r\n";
    if (!send_all(fd, request.str())) {
        close_fd(fd);
        return false;
    }

    std::string buffer;
    buffer.reserve(8192);
    while (running.load() && buffer.find("\r\n\r\n") == std::string::npos) {
        if (!recv_append(fd, buffer, running)) {
            close_fd(fd);
            log_line("ERROR", "native stream ended before headers: " + url);
            return false;
        }
        if (buffer.size() > 1024 * 1024) {
            close_fd(fd);
            log_line("ERROR", "native stream headers too large: " + url);
            return false;
        }
    }

    auto header_end = buffer.find("\r\n\r\n");
    auto body_start = header_end == std::string::npos ? buffer.size() : header_end + 4;
    auto header_text = header_end == std::string::npos ? std::string() : buffer.substr(0, header_end);
    auto first_line_end = header_text.find("\r\n");
    auto first_line = first_line_end == std::string::npos ? header_text : header_text.substr(0, first_line_end);
    auto headers = parse_response_headers(header_text);
    auto transfer_encoding = lower(headers["transfer-encoding"]);
    bool chunked = transfer_encoding.find("chunked") != std::string::npos;
    log_line("INFO", "native stream response " + first_line
            + " chunked=" + std::string(chunked ? "true" : "false")
            + " initial_body=" + std::to_string(buffer.size() - body_start));

    std::size_t total_bytes = 0;
    bool ok = true;
    if (chunked) {
        ok = stream_chunked_body(fd, buffer.substr(body_start), on_chunk, running, total_bytes);
    } else {
        if (body_start < buffer.size()) {
            auto size = buffer.size() - body_start;
            ok = on_chunk(buffer.data() + body_start, size);
            total_bytes += size;
        }
        while (ok && running.load()) {
            std::string chunk;
            if (!recv_append(fd, chunk, running)) break;
            total_bytes += chunk.size();
            ok = on_chunk(chunk.data(), chunk.size());
        }
    }

    close_fd(fd);
    auto normal_finish = ok || !running.load();
    log_line(normal_finish ? "INFO" : "ERROR", "native stream finished bytes=" + std::to_string(total_bytes) + " url=" + url);
    return ok;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jopsis_httpaceserveproxy_HttpAceProxyNative_nativeInitHttpBridge(JNIEnv* env, jclass, jclass bridge_class) {
    if (g_bridge_class) env->DeleteGlobalRef(g_bridge_class);
    g_bridge_class = static_cast<jclass>(env->NewGlobalRef(bridge_class));
    g_fetch_method = env->GetStaticMethodID(
        g_bridge_class, "fetch",
        "(Ljava/lang/String;[Ljava/lang/String;IZ)[Ljava/lang/String;");
    if (!g_fetch_method) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "NativeHttpBridge.fetch signature mismatch");
    }
}

} // namespace httpace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    httpace::g_vm = vm;
    return JNI_VERSION_1_6;
}
