#include "httpaceproxycpp/ace_client.hpp"
#include "httpaceproxycpp/util.hpp"

#include <arpa/inet.h>
#include <cerrno>
#include <cstring>
#include <netdb.h>
#include <netinet/in.h>
#include <sstream>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

namespace httpace {
namespace {

void close_fd(int& fd) {
    if (fd >= 0) {
        ::close(fd);
        fd = -1;
    }
}

bool send_all_fd(int fd, const std::string& data) {
    const char* ptr = data.data();
    std::size_t left = data.size();
    while (left > 0) {
#ifdef MSG_NOSIGNAL
        ssize_t n = ::send(fd, ptr, left, MSG_NOSIGNAL);
#else
        ssize_t n = ::send(fd, ptr, left, 0);
#endif
        if (n <= 0) return false;
        ptr += n;
        left -= static_cast<std::size_t>(n);
    }
    return true;
}

std::vector<std::string> split_command_line(const std::string& line) {
    return split(trim(line), ' ', false);
}

std::string param_value(const std::map<std::string, std::string>& params, const std::string& key, const std::string& fallback = "0") {
    auto it = params.find(key);
    return it == params.end() || it->second.empty() ? fallback : it->second;
}

std::string command_ready(const std::string& request_key, const std::string& product_key) {
    auto first_dash = product_key.find('-');
    auto prefix = first_dash == std::string::npos ? product_key : product_key.substr(0, first_dash);
    return "READY key=" + prefix + "-" + sha1_hex(request_key + product_key);
}

std::map<std::string, std::string> parse_status(std::string value) {
    auto fields = split(value, ';', false);
    if (fields.size() > 1 && (fields[0] == "main:wait" || fields[0] == "main:seekprebuf")) {
        fields.erase(fields.begin() + 1);
    } else if (fields.size() > 2 && (fields[0] == "main:buf" || fields[0] == "main:prebuf")) {
        fields.erase(fields.begin() + 1, fields.begin() + 3);
    }
    static const std::vector<std::string> names = {
        "status", "total_progress", "immediate_progress", "speed_down", "http_speed_down",
        "speed_up", "peers", "http_peers", "downloaded", "http_downloaded", "uploaded"
    };
    std::map<std::string, std::string> out;
    for (std::size_t i = 0; i < fields.size() && i < names.size(); ++i) {
        auto v = fields[i];
        if (starts_with(v, "main:")) v = v.substr(5);
        out[names[i]] = v;
    }
    return out;
}

} // namespace

AceClient::AceClient(Config config, std::string title)
    : config_(std::move(config)), title_(std::move(title)) {
    connect_socket();
    running_ = true;
    reader_ = std::thread(&AceClient::reader_loop, this);
}

AceClient::~AceClient() {
    running_ = false;
    close_fd(fd_);
    cv_.notify_all();
    if (reader_.joinable()) reader_.join();
}

void AceClient::connect_socket() {
    fd_ = ::socket(AF_INET, SOCK_STREAM, 0);
    if (fd_ < 0) throw AceError("socket failed");
    timeval timeout{};
    timeout.tv_sec = config_.ace_connect_timeout;
    setsockopt(fd_, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(fd_, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));

    addrinfo hints{};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    addrinfo* result = nullptr;
    auto port = std::to_string(config_.ace_api_port);
    if (::getaddrinfo(config_.ace_host.c_str(), port.c_str(), &hints, &result) != 0) {
        close_fd(fd_);
        throw AceError("cannot resolve AceStream host " + config_.ace_host);
    }
    bool connected = false;
    for (auto* rp = result; rp; rp = rp->ai_next) {
        if (::connect(fd_, rp->ai_addr, rp->ai_addrlen) == 0) {
            connected = true;
            break;
        }
    }
    freeaddrinfo(result);
    if (!connected) {
        close_fd(fd_);
        throw AceError("there are no alive AceStream Engines found");
    }
}

void AceClient::authenticate() {
    write_line("HELLOBG version=4");
    auto hellots = wait_for("HELLOTS", config_.ace_result_timeout);
    auto params = parse_key_values(hellots, 1);
    auto key = params["key"];
    write_line(command_ready(key, config_.ace_key));
    try {
        wait_for("AUTH", config_.ace_result_timeout);
    } catch (const AceError&) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!messages_["NOTREADY"].empty()) throw AceError("AceStream engine returned NOTREADY");
        throw;
    }
    int version_code = 0;
    try { version_code = std::stoi(params["version_code"]); } catch (...) {}
    if (version_code >= 3003600) write_line("SETOPTIONS use_stop_notifications=1");
}

Json AceClient::load_async(const std::map<std::string, std::string>& params) {
    write_line(command_loadasync(params));
    auto msg = wait_for("LOADRESP", config_.ace_result_timeout);
    if (msg.size() < 3) throw AceError("malformed LOADRESP");
    std::string json_text;
    for (std::size_t i = 2; i < msg.size(); ++i) json_text += msg[i];
    json_text = url_decode(json_text);
    return Json::parse(json_text);
}

Json AceClient::content_info(const std::map<std::string, std::string>& params) {
    auto info = load_async(params);
    auto status = static_cast<int>(info["status"].as_number(-1));
    if (status == 1 || status == 2) return info;
    auto message = info["message"].as_string("LOADASYNC returned error");
    throw AceError(message);
}

AceStartParams AceClient::start_broadcast(const std::map<std::string, std::string>& params) {
    write_line(command_start(params));
    auto msg = wait_for("START", config_.video_timeout);
    auto kv = parse_key_values(msg, 1);
    if (config_.video_seekback > 0 && kv.contains("stream") && !ends_with(kv["url"], ".m3u8")) {
        auto event = wait_for("EVENT", config_.ace_result_timeout);
        auto ev = parse_key_values(event, 2);
        int last = 0;
        try { last = std::stoi(ev["last"]); } catch (...) {}
        if (last > 0) {
            write_line("LIVESEEK " + std::to_string(last - config_.video_seekback));
            msg = wait_for("START", config_.video_timeout);
            kv = parse_key_values(msg, 1);
        }
    }
    AceStartParams out;
    out.fields = kv;
    out.url = kv["url"];
    out.infohash = kv["infohash"];
    out.file_index = kv["file_index"];
    out.stream = kv.contains("stream");
    if (out.url.empty()) throw AceError("START URL not received");
    return out;
}

std::map<std::string, std::string> AceClient::status(int timeout_seconds) {
    try {
        auto msg = wait_for("STATUS", timeout_seconds);
        if (msg.size() < 2) return {{"status", "error"}};
        return parse_status(msg[1]);
    } catch (...) {
        return {{"status", "error"}};
    }
}

void AceClient::stop_broadcast() {
    try { write_line("STOP"); } catch (...) {}
}

void AceClient::shutdown() {
    try { write_line("SHUTDOWN"); } catch (...) {}
}

void AceClient::reader_loop() {
    std::string current;
    char c = 0;
    while (running_) {
        ssize_t n = ::recv(fd_, &c, 1, 0);
        if (n <= 0) break;
        if (c == '\n') {
            auto line = trim(current);
            current.clear();
            if (!line.empty()) {
                auto tokens = split_command_line(line);
                if (!tokens.empty()) {
                    log_line("DEBUG", "[" + title_ + "] <<< " + url_decode(line));
                    if (tokens[0] == "EVENT" && tokens.size() > 1 && tokens[1] == "getuserdata") {
                        write_line("USERDATA [{\"gender\": " + std::to_string(config_.ace_sex) + "}, {\"age\": " + std::to_string(config_.ace_age) + "}]");
                    }
                    push_message(tokens);
                }
            }
        } else if (c != '\r') {
            current.push_back(c);
        }
    }
    running_ = false;
    cv_.notify_all();
}

void AceClient::write_line(const std::string& line) {
    std::string data = line + "\r\n";
    log_line("DEBUG", "[" + title_ + "] >>> " + line);
    if (!send_all_fd(fd_, data)) throw AceError("error writing data to AceEngine API port");
}

std::vector<std::string> AceClient::wait_for(const std::string& command, int timeout_seconds) {
    std::unique_lock<std::mutex> lock(mutex_);
    bool ok = cv_.wait_for(lock, std::chrono::seconds(timeout_seconds), [&] {
        return !running_ || !messages_[command].empty();
    });
    if (!ok || messages_[command].empty()) throw AceError("Engine response timeout waiting for " + command);
    auto msg = messages_[command].front();
    messages_[command].pop_front();
    return msg;
}

void AceClient::push_message(const std::vector<std::string>& tokens) {
    std::lock_guard<std::mutex> lock(mutex_);
    messages_[tokens[0]].push_back(tokens);
    cv_.notify_all();
}

std::map<std::string, std::string> AceClient::parse_key_values(const std::vector<std::string>& tokens, std::size_t start) {
    std::map<std::string, std::string> out;
    for (std::size_t i = start; i < tokens.size(); ++i) {
        auto pos = tokens[i].find('=');
        if (pos != std::string::npos) out[tokens[i].substr(0, pos)] = tokens[i].substr(pos + 1);
    }
    return out;
}

std::string AceClient::command_loadasync(const std::map<std::string, std::string>& params) {
    if (params.contains("url")) {
        return "LOADASYNC " + param_value(params, "sessionID") + " TORRENT " + param_value(params, "url", "") + " " +
               param_value(params, "developer_id") + " " + param_value(params, "affiliate_id") + " " + param_value(params, "zone_id");
    }
    if (params.contains("infohash")) {
        return "LOADASYNC " + param_value(params, "sessionID") + " INFOHASH " + param_value(params, "infohash", "") + " " +
               param_value(params, "developer_id") + " " + param_value(params, "affiliate_id") + " " + param_value(params, "zone_id");
    }
    if (params.contains("data")) {
        return "LOADASYNC " + param_value(params, "sessionID") + " RAW " + param_value(params, "data", "") + " " +
               param_value(params, "developer_id") + " " + param_value(params, "affiliate_id") + " " + param_value(params, "zone_id");
    }
    if (params.contains("content_id")) {
        return "LOADASYNC " + param_value(params, "sessionID") + " PID " + param_value(params, "content_id", "");
    }
    throw AceError("LOADASYNC params do not contain a supported request type");
}

std::string AceClient::command_start(const std::map<std::string, std::string>& params) {
    auto stream_type = param_value(params, "stream_type", "output_format=http");
    if (params.contains("url")) {
        return "START TORRENT " + param_value(params, "url", "") + " " + param_value(params, "file_indexes") + " " +
               param_value(params, "developer_id") + " " + param_value(params, "affiliate_id") + " " +
               param_value(params, "zone_id") + " " + param_value(params, "stream_id") + " " + stream_type;
    }
    if (params.contains("infohash")) {
        return "START INFOHASH " + param_value(params, "infohash", "") + " " + param_value(params, "file_indexes") + " " +
               param_value(params, "developer_id") + " " + param_value(params, "affiliate_id") + " " +
               param_value(params, "zone_id") + " " + stream_type;
    }
    if (params.contains("content_id")) {
        return "START PID " + param_value(params, "content_id", "") + " " + param_value(params, "file_indexes") + " " + stream_type;
    }
    if (params.contains("data")) {
        return "START RAW " + param_value(params, "data", "") + " " + param_value(params, "file_indexes") + " " +
               param_value(params, "developer_id") + " " + param_value(params, "affiliate_id") + " " +
               param_value(params, "zone_id") + " " + stream_type;
    }
    if (params.contains("direct_url")) {
        return "START URL " + param_value(params, "direct_url", "") + " " + param_value(params, "file_indexes") + " " +
               param_value(params, "developer_id") + " " + param_value(params, "affiliate_id") + " " +
               param_value(params, "zone_id") + " " + stream_type;
    }
    if (params.contains("efile_url")) {
        return "START EFILE " + param_value(params, "efile_url", "") + " " + stream_type;
    }
    throw AceError("START params do not contain a supported request type");
}

} // namespace httpace
