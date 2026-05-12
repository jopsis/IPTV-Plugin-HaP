#include "httpaceproxycpp/plugins.hpp"
#include "httpaceproxycpp/proxy.hpp"
#include "httpaceproxycpp/util.hpp"

#include <algorithm>
#include <filesystem>
#include <sstream>
#include <thread>

namespace httpace {
namespace {

constexpr const char* kEpgUrl = "";
constexpr const char* kBrowserUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

void send_bytes(ClientConnection& connection, int status, const std::string& content_type, const std::string& body,
                std::map<std::string, std::string> headers = {}) {
    headers["Content-Type"] = content_type;
    headers["Content-Length"] = std::to_string(body.size());
    headers["Connection"] = "close";
    connection.send_response_headers(status, status_reason(status), headers);
    connection.send_text(body);
}

std::string host_header(const RequestContext& ctx) {
    return ctx.request.header("host", "localhost:8888");
}

std::string channel_name_from_request(const RequestContext& ctx) {
    auto base = ctx.parts.empty() ? "" : ctx.parts.back();
    return url_decode(basename_no_ext(base));
}

std::string ext_from_request(const RequestContext& ctx) {
    auto ext = extension_of(ctx.path);
    return ext.empty() ? "m3u8" : ext;
}

std::string channel_key_suffix(const std::string& url) {
    auto parsed = parse_url(url);
    auto value = parsed.host.empty() ? sha1_hex(url) : parsed.host;
    if (value.empty()) value = sha1_hex(url);
    return value.substr(0, std::min<std::size_t>(12, value.size()));
}

struct UserM3uSourceEntry {
    std::string name;
    std::string url;
};

std::vector<UserM3uSourceEntry> parse_user_m3u_sources(const std::string& value) {
    std::vector<UserM3uSourceEntry> out;
    for (auto line : split(value, '\n', false)) {
        line = trim(line);
        if (line.empty()) continue;
        auto tab = line.find('\t');
        if (tab == std::string::npos) continue;
        auto name = trim(line.substr(0, tab));
        auto url = trim(line.substr(tab + 1));
        if (!name.empty() && !url.empty()) out.push_back(UserM3uSourceEntry{name, url});
    }
    return out;
}

std::string unique_channel_key(const std::string& name,
                               const std::string& url,
                               const std::map<std::string, std::string>& channels) {
    if (!channels.contains(name)) return name;
    if (channels.at(name) == url) return "";

    auto suffix = channel_key_suffix(url);
    auto key = name + " [" + suffix + "]";
    int n = 2;
    while (channels.contains(key)) {
        if (channels.at(key) == url) return "";
        key = name + " [" + suffix + "-" + std::to_string(n++) + "]";
    }
    return key;
}

} // namespace

void RequestContext::rewrite_to(const std::string& new_path) {
    path = new_path;
    auto q = path.find('?');
    if (q != std::string::npos) {
        query = path.substr(q + 1);
        path = path.substr(0, q);
    }
    parts = split(path, '/', true);
    reqtype = parts.size() > 1 ? lower(parts[1]) : "";
    rewritten = true;
}

void PluginRegistry::add(std::shared_ptr<Plugin> plugin) {
    std::lock_guard<std::mutex> lock(mutex_);
    for (const auto& handler : plugin->handlers()) handlers_[lower(handler)] = plugin;
}

std::shared_ptr<Plugin> PluginRegistry::by_handler(const std::string& handler) const {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = handlers_.find(lower(handler));
    return it == handlers_.end() ? nullptr : it->second;
}

std::map<std::string, std::shared_ptr<Plugin>> PluginRegistry::handlers() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return handlers_;
}

std::vector<std::shared_ptr<Plugin>> PluginRegistry::unique_plugins() const {
    std::vector<std::shared_ptr<Plugin>> out;
    std::set<Plugin*> seen;
    std::lock_guard<std::mutex> lock(mutex_);
    for (const auto& [_, plugin] : handlers_) {
        if (seen.insert(plugin.get()).second) out.push_back(plugin);
    }
    return out;
}

PlaylistPlugin::PlaylistPlugin(Config config, HttpClient& http_client, std::string plugin_name,
                               std::string header, int update_minutes)
    : config_(std::move(config)),
      http_client_(http_client),
      plugin_name_(std::move(plugin_name)),
      header_(std::move(header)),
      update_minutes_(update_minutes),
      playlist_(header_) {
    playlist_time_ = std::chrono::steady_clock::time_point{};
    if (update_minutes_ > 0) {
        updater_ = std::thread([this] {
            while (!stop_updater_) {
                std::unique_lock<std::mutex> lock(updater_mutex_);
                if (updater_cv_.wait_for(lock, std::chrono::minutes(update_minutes_), [this] { return stop_updater_; })) break;
                lock.unlock();
                refresh_if_needed();
            }
        });
    }
}

PlaylistPlugin::~PlaylistPlugin() {
    {
        std::lock_guard<std::mutex> lock(updater_mutex_);
        stop_updater_ = true;
    }
    updater_cv_.notify_all();
    if (updater_.joinable()) updater_.join();
}

bool PlaylistPlugin::handle(RequestContext& ctx) {
    refresh_if_needed();
    if (ctx.path.find("/" + plugin_name_ + "/channel/") == 0) {
        if (!(ends_with(ctx.path, ".ts") || ends_with(ctx.path, ".m3u8"))) {
            send_bytes(ctx.connection, 404, "text/plain", "Invalid path: must end with .ts or .m3u8");
            return true;
        }
        return rewrite_channel(ctx, channel_name_from_request(ctx), ext_from_request(ctx));
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!etag_.empty() && etag_ == ctx.request.header("if-none-match")) {
            ctx.connection.send_response_headers(304, status_reason(304), {{"Connection", "close"}});
            return true;
        }
        auto body = playlist_.export_m3u(host_header(ctx), "/" + plugin_name_ + "/channel", ctx.query, true);
        std::map<std::string, std::string> headers = {{"Access-Control-Allow-Origin", "*"}};
        if (!etag_.empty() && ctx.request.version == "HTTP/1.1") headers["ETag"] = etag_;
        send_bytes(ctx.connection, 200, "audio/mpegurl; charset=utf-8", body, headers);
    }
    return true;
}

std::vector<PlaylistItem> PlaylistPlugin::playlist_items() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return playlist_.items();
}

std::map<std::string, std::string> PlaylistPlugin::channels() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return channels_;
}

std::map<std::string, std::string> PlaylistPlugin::picons() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return picons_;
}

std::size_t PlaylistPlugin::channel_count() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return channels_.size();
}

bool PlaylistPlugin::refresh_if_needed() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto age = std::chrono::steady_clock::now() - playlist_time_;
        if (!playlist_.empty() && age < std::chrono::minutes(30)) return true;
    }
    try { return refresh(); } catch (const std::exception& e) {
        log_line("ERROR", "[" + plugin_name_ + "] refresh failed: " + e.what());
        return false;
    }
}

void PlaylistPlugin::set_playlist(PlaylistGenerator playlist,
                                  std::map<std::string, std::string> channels,
                                  std::map<std::string, std::string> picons) {
    std::lock_guard<std::mutex> lock(mutex_);
    playlist_ = std::move(playlist);
    channels_ = std::move(channels);
    picons_ = std::move(picons);
    etag_ = playlist_.etag();
    playlist_time_ = std::chrono::steady_clock::now();
}

bool PlaylistPlugin::rewrite_channel(RequestContext& ctx, const std::string& channel_name, const std::string& ext) {
    std::string url;
    std::string icon;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = channels_.find(channel_name);
        if (it == channels_.end()) {
            send_bytes(ctx.connection, 404, "text/plain", "[" + plugin_name_ + "] unknown channel: " + channel_name);
            return true;
        }
        url = it->second;
        icon = picons_.contains(channel_name) ? picons_[channel_name] : "";
    }
    auto parsed = parse_url(url);
    std::string new_path;
    if (parsed.scheme == "acestream") new_path = "/content_id/" + parsed.host + "/" + channel_name + "." + ext;
    else if (parsed.scheme == "infohash") new_path = "/infohash/" + parsed.host + "/" + channel_name + "." + ext;
    else if (parsed.scheme == "http" || parsed.scheme == "https") new_path = "/url/" + url_encode(url, "") + "/" + channel_name + "." + ext;
    else {
        send_bytes(ctx.connection, 404, "text/plain", "Unsupported channel URL scheme");
        return true;
    }
    ctx.channel_name = channel_name;
    ctx.channel_icon = icon;
    ctx.rewrite_to(new_path);
    return false;
}

class UserM3uPlugin : public PlaylistPlugin {
public:
    UserM3uPlugin(Config cfg, HttpClient& client)
        : PlaylistPlugin(std::move(cfg), client, "userm3u", PlaylistGenerator::epg_header(kEpgUrl, 0, true), 60) {}
protected:
    bool refresh() override {
        PlaylistGenerator playlist(header_);
        std::map<std::string, std::string> channels;
        std::map<std::string, std::string> picons;
        auto sources = parse_user_m3u_sources(config_.user_m3u_sources);

        for (const auto& source : sources) {
            try {
                auto response = http_client_.get(source.url, {{"User-Agent", kBrowserUserAgent}}, 60, true);
                for (auto& item : parse_m3u_acestream_items(response.body, channels, picons)) {
                    if (item.group.empty() || item.group == "Unknown") item.group = source.name;
                    playlist.add_item(item);
                }
            } catch (const std::exception& e) {
                log_line("ERROR", "[userm3u] source failed " + source.name + ": " + e.what());
            }
        }

        set_playlist(std::move(playlist), std::move(channels), std::move(picons));
        log_line("INFO", "[userm3u] playlist generated with " + std::to_string(channel_count()) + " channels from " + std::to_string(sources.size()) + " sources");
        return true;
    }
};

class CustomPlugin : public PlaylistPlugin {
public:
    CustomPlugin(Config cfg, HttpClient& client)
        : PlaylistPlugin(std::move(cfg), client, "custom", PlaylistGenerator::epg_header(kEpgUrl, 0, true), 0) {}
protected:
    bool refresh() override {
        PlaylistGenerator playlist(header_);
        std::map<std::string, std::string> channels;
        std::map<std::string, std::string> picons;

        if (!config_.custom_playlist_path.empty() && std::filesystem::exists(config_.custom_playlist_path)) {
            auto body = read_file_binary(config_.custom_playlist_path);
            for (auto& item : parse_m3u_acestream_items(body, channels, picons)) {
                if (item.group.empty() || item.group == "Unknown") item.group = "Custom";
                playlist.add_item(item);
            }
        }

        set_playlist(std::move(playlist), std::move(channels), std::move(picons));
        log_line("INFO", "[custom] playlist generated with " + std::to_string(channel_count()) + " channels");
        return true;
    }
};

class AioPlugin : public Plugin {
public:
    AioPlugin(Config cfg, Proxy& proxy) : config_(std::move(cfg)), proxy_(proxy) {}
    std::string name() const override { return "aio"; }
    std::vector<std::string> handlers() const override { return {"aio"}; }
    bool handle(RequestContext& ctx) override {
        PlaylistGenerator generator(PlaylistGenerator::epg_header(kEpgUrl, 0, true));
        std::set<Plugin*> processed;
        auto handlers = proxy_.plugins().handlers();
        for (const auto& [handler, plugin] : handlers) {
            if (handler == "aio" || handler == "stat" || handler == "statplugin" || handler == "torrenttv_api") continue;
            if (!config_.aio_includes(handler)) continue;
            if (!processed.insert(plugin.get()).second) continue;
            auto channels = plugin->channels();
            for (auto item : plugin->playlist_items()) {
                auto key = url_decode(item.url);
                if (channels.contains(key)) item.url = channels[key];
                else if (channels.contains(item.name)) item.url = channels[item.name];
                if (item.group.empty()) item.group = handler;
                generator.add_item(item);
            }
        }
        auto body = generator.export_m3u(host_header(ctx), "", ctx.query, false);
        send_bytes(ctx.connection, 200, "audio/mpegurl; charset=utf-8", body);
        return true;
    }
private:
    Config config_;
    Proxy& proxy_;
};

class StatPlugin : public Plugin {
public:
    StatPlugin(Config cfg, Proxy& proxy) : config_(std::move(cfg)), proxy_(proxy) {}
    std::string name() const override { return "stat"; }
    std::vector<std::string> handlers() const override { return {"stat"}; }
    bool handle(RequestContext& ctx) override {
        auto action = query_get(ctx.query, "action");
        if (action == "get_clients") {
            send_bytes(ctx.connection, 200, "application/json; charset=utf-8", proxy_.clients_json().dump());
            return true;
        }
        if (action == "get_status") {
            send_bytes(ctx.connection, 200, "application/json; charset=utf-8", proxy_.status_json().dump());
            return true;
        }
        std::string relative = "index.html";
        if (ctx.path != "/stat") {
            relative = ctx.path.substr(std::string("/stat/").size());
        }
        if (!path_is_safe_relative(relative)) {
            send_bytes(ctx.connection, 404, "text/plain", "Not Found");
            return true;
        }
        try {
            auto full = std::filesystem::path(config_.root_dir) / "http" / relative;
            auto body = read_file_binary(full.string());
            send_bytes(ctx.connection, 200, mime_type_for_path(relative), body);
        } catch (...) {
            send_bytes(ctx.connection, 404, "text/plain", "Not Found");
        }
        return true;
    }
private:
    Config config_;
    Proxy& proxy_;
};

class StatpluginPlugin : public Plugin {
public:
    StatpluginPlugin(Config cfg, Proxy& proxy) : config_(std::move(cfg)), proxy_(proxy) {}
    std::string name() const override { return "statplugin"; }
    std::vector<std::string> handlers() const override { return {"statplugin"}; }
    bool handle(RequestContext& ctx) override {
        auto action = query_get(ctx.query, "action");
        if (action == "get_plugins") {
            send_bytes(ctx.connection, 200, "application/json; charset=utf-8", proxy_.plugins_json().dump(2));
        } else if (action == "check_channel") {
            auto data = proxy_.check_channel_light(query_get(ctx.query, "plugin"), query_get(ctx.query, "channel"), query_get(ctx.query, "content_id"));
            send_bytes(ctx.connection, 200, "application/json; charset=utf-8", data.dump(2));
        } else if (action == "check_peers") {
            int max_wait = 15;
            try { max_wait = std::stoi(query_get(ctx.query, "max_wait", "15")); } catch (...) {}
            max_wait = std::min(30, std::max(5, max_wait));
            auto data = proxy_.check_channel_peers(query_get(ctx.query, "content_id"), max_wait);
            send_bytes(ctx.connection, 200, "application/json; charset=utf-8", data.dump(2));
        } else {
            try {
                auto full = std::filesystem::path(config_.root_dir) / "http" / "statplugin" / "index.html";
                send_bytes(ctx.connection, 200, "text/html; charset=utf-8", read_file_binary(full.string()));
            } catch (...) {
                send_bytes(ctx.connection, 500, "text/plain", "Internal Server Error");
            }
        }
        return true;
    }
private:
    Config config_;
    Proxy& proxy_;
};

std::vector<std::shared_ptr<Plugin>> create_plugins(Config config, HttpClient& http_client, Proxy& proxy) {
    std::vector<std::shared_ptr<Plugin>> plugins;
    auto add = [&](const std::string& name, const std::function<std::shared_ptr<Plugin>()>& factory) {
        if (config.plugin_enabled(name)) {
            auto plugin = factory();
            plugins.push_back(std::move(plugin));
            log_line("INFO", "enabled plugin: " + name);
        }
    };
    add("userm3u", [&] { return std::make_shared<UserM3uPlugin>(config, http_client); });
    add("custom", [&] { return std::make_shared<CustomPlugin>(config, http_client); });
    add("aio", [&] { return std::make_shared<AioPlugin>(config, proxy); });
    add("stat", [&] { return std::make_shared<StatPlugin>(config, proxy); });
    add("statplugin", [&] { return std::make_shared<StatpluginPlugin>(config, proxy); });

    for (auto& plugin : plugins) {
        if (auto playlist = std::dynamic_pointer_cast<PlaylistPlugin>(plugin)) {
            playlist->refresh_if_needed();
        }
    }
    return plugins;
}

} // namespace httpace
