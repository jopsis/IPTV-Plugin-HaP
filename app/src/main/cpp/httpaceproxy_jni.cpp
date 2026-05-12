#include "httpaceproxycpp/config.hpp"
#include "httpaceproxycpp/proxy.hpp"
#include "httpaceproxycpp/util.hpp"

#include <jni.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>

namespace {

std::mutex g_mutex;
std::unique_ptr<httpace::Proxy> g_proxy;
std::atomic<bool> g_running{false};

std::string jstr(JNIEnv* env, jstring value) {
    if (!value) return "";
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

void throw_java(JNIEnv* env, const std::exception& e) {
    auto cls = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(cls, e.what());
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_jopsis_httpaceserveproxy_HttpAceProxyNative_nativeStart(
    JNIEnv* env,
    jclass,
    jstring ace_host,
    jint ace_api_port,
    jint ace_http_port,
    jstring http_host,
    jint http_port,
    jstring root_dir,
    jstring enabled_plugins,
    jstring aio_plugins,
    jstring user_m3u_sources,
    jstring custom_playlist_path,
    jint max_connections,
    jint max_concurrent_channels) {
    try {
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            if (g_proxy) throw std::runtime_error("HTTPAceProxy is already running");

            httpace::Config config;
            config.ace_host = jstr(env, ace_host);
            config.ace_api_port = ace_api_port;
            config.ace_http_port = ace_http_port;
            config.http_host = jstr(env, http_host);
            config.http_port = http_port;
            config.root_dir = jstr(env, root_dir);
            config.enabled_plugins = jstr(env, enabled_plugins);
            config.aio_plugins = jstr(env, aio_plugins);
            config.user_m3u_sources = jstr(env, user_m3u_sources);
            config.custom_playlist_path = jstr(env, custom_playlist_path);
            config.max_connections = max_connections;
            config.max_concurrent_channels = max_concurrent_channels;
            if (config.http_host == "0.0.0.0") {
                config.firewall = true;
            }
            config.ace_connect_timeout = 5;
            config.ace_result_timeout = 8;
            config.video_timeout = 30;
            config.client_write_timeout = 15;
            config.curl_stream_buffer = 512 * 1024;
            g_proxy = std::make_unique<httpace::Proxy>(std::move(config));
            g_running.store(true);
        }

        {
            std::unique_lock<std::mutex> lock(g_mutex);
            auto* proxy = g_proxy.get();
            lock.unlock();
            proxy->start();
        }

        std::lock_guard<std::mutex> lock(g_mutex);
        g_proxy.reset();
        g_running.store(false);
    } catch (const std::exception& e) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_proxy.reset();
        g_running.store(false);
        throw_java(env, e);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_jopsis_httpaceserveproxy_HttpAceProxyNative_nativeStop(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_proxy) g_proxy->stop();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jopsis_httpaceserveproxy_HttpAceProxyNative_nativeIsRunning(JNIEnv*, jclass) {
    return g_running.load() ? JNI_TRUE : JNI_FALSE;
}
