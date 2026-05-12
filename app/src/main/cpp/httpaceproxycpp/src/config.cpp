#include "httpaceproxycpp/config.hpp"
#include "httpaceproxycpp/util.hpp"

#include <cstdlib>
#include <filesystem>
#include <sstream>

namespace httpace {
namespace {

std::string getenv_string(const char* name, const std::string& fallback) {
    const char* value = std::getenv(name);
    return value && *value ? std::string(value) : fallback;
}

int getenv_int(const char* name, int fallback) {
    const char* value = std::getenv(name);
    if (!value || !*value) return fallback;
    try { return std::stoi(value); } catch (...) { return fallback; }
}

std::set<std::string> csv_set(const std::string& value) {
    std::set<std::string> out;
    for (auto item : split(value, ',', false)) {
        item = lower(trim(item));
        if (!item.empty()) out.insert(item);
    }
    return out;
}

} // namespace

std::string Config::stream_type_string() const {
    std::ostringstream out;
    bool first = true;
    for (const auto& [k, v] : acestream_type) {
        if (!first) out << ' ';
        first = false;
        out << k << '=' << v;
    }
    return out.str();
}

bool Config::plugin_enabled(const std::string& name) const {
    auto enabled = lower(trim(enabled_plugins));
    if (enabled == "all") return true;
    if (enabled.empty()) return false;
    return csv_set(enabled).contains(lower(name));
}

bool Config::aio_includes(const std::string& name) const {
    auto enabled = lower(trim(aio_plugins));
    if (enabled == "all") return true;
    if (enabled.empty() || enabled == "none") return false;
    return csv_set(enabled).contains(lower(name));
}

Config load_config(int argc, char** argv) {
    Config cfg;
    cfg.ace_host = getenv_string("ACE_HOST", getenv_string("ACESTREAM_HOST", cfg.ace_host));
    cfg.ace_api_port = getenv_int("ACE_API_PORT", getenv_int("ACESTREAM_API_PORT", cfg.ace_api_port));
    cfg.ace_http_port = getenv_int("ACE_HTTP_PORT", getenv_int("ACESTREAM_HTTP_PORT", cfg.ace_http_port));
    cfg.http_host = getenv_string("ACEPROXY_HOST", cfg.http_host);
    cfg.http_port = getenv_int("ACEPROXY_PORT", cfg.http_port);
    cfg.max_connections = getenv_int("MAX_CONNECTIONS", cfg.max_connections);
    cfg.max_concurrent_channels = getenv_int("MAX_CONCURRENT_CHANNELS", cfg.max_concurrent_channels);
    cfg.enabled_plugins = getenv_string("ENABLED_PLUGINS", cfg.enabled_plugins);
    cfg.aio_plugins = getenv_string("AIO_PLUGINS", cfg.aio_plugins);
    cfg.user_m3u_sources = getenv_string("USER_M3U_SOURCES", cfg.user_m3u_sources);
    cfg.custom_playlist_path = getenv_string("CUSTOM_PLAYLIST_PATH", cfg.custom_playlist_path);
    cfg.ace_connect_timeout = getenv_int("ACE_CONNECT_TIMEOUT", cfg.ace_connect_timeout);
    cfg.ace_result_timeout = getenv_int("ACE_RESULT_TIMEOUT", cfg.ace_result_timeout);
    cfg.video_timeout = getenv_int("VIDEO_TIMEOUT", cfg.video_timeout);
    cfg.video_seekback = getenv_int("VIDEO_SEEKBACK", cfg.video_seekback);
    cfg.client_queue_size = getenv_int("CLIENT_QUEUE_SIZE", cfg.client_queue_size);
    cfg.client_write_timeout = getenv_int("CLIENT_WRITE_TIMEOUT", cfg.client_write_timeout);
    cfg.curl_stream_buffer = getenv_int("CURL_STREAM_BUFFER", cfg.curl_stream_buffer);

    if (argc > 0 && argv && argv[0]) {
        std::filesystem::path exe(argv[0]);
        auto dir = exe.has_parent_path() ? exe.parent_path() : std::filesystem::current_path();
        if (std::filesystem::exists(dir / "http")) cfg.root_dir = dir.string();
        else cfg.root_dir = std::filesystem::current_path().string();
    } else {
        cfg.root_dir = std::filesystem::current_path().string();
    }
    return cfg;
}

} // namespace httpace
