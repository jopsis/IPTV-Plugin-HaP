#pragma once

#include "httpaceproxycpp/config.hpp"
#include "httpaceproxycpp/http_client.hpp"
#include "httpaceproxycpp/http_server.hpp"
#include "httpaceproxycpp/json.hpp"
#include "httpaceproxycpp/playlist.hpp"

#include <chrono>
#include <condition_variable>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <set>
#include <string>
#include <thread>
#include <vector>

namespace httpace {

class Proxy;

struct RequestContext {
    HttpRequest request;
    ClientConnection& connection;
    std::string path;
    std::string query;
    std::vector<std::string> parts;
    std::string reqtype;
    std::string ext = "m3u8";
    std::string channel_name;
    std::string channel_icon;
    bool rewritten = false;

    void rewrite_to(const std::string& new_path);
};

class Plugin {
public:
    virtual ~Plugin() = default;
    virtual std::string name() const = 0;
    virtual std::vector<std::string> handlers() const = 0;
    virtual bool handle(RequestContext& ctx) = 0;
    virtual std::vector<PlaylistItem> playlist_items() const { return {}; }
    virtual std::map<std::string, std::string> channels() const { return {}; }
    virtual std::map<std::string, std::string> picons() const { return {}; }
    virtual std::size_t channel_count() const { return channels().size(); }
};

class PluginRegistry {
public:
    void add(std::shared_ptr<Plugin> plugin);
    std::shared_ptr<Plugin> by_handler(const std::string& handler) const;
    std::map<std::string, std::shared_ptr<Plugin>> handlers() const;
    std::vector<std::shared_ptr<Plugin>> unique_plugins() const;

private:
    mutable std::mutex mutex_;
    std::map<std::string, std::shared_ptr<Plugin>> handlers_;
};

class PlaylistPlugin : public Plugin {
public:
    PlaylistPlugin(Config config, HttpClient& http_client, std::string plugin_name,
                   std::string header, int update_minutes);
    ~PlaylistPlugin() override;

    bool handle(RequestContext& ctx) override;
    std::vector<std::string> handlers() const override { return {plugin_name_}; }
    std::string name() const override { return plugin_name_; }
    std::vector<PlaylistItem> playlist_items() const override;
    std::map<std::string, std::string> channels() const override;
    std::map<std::string, std::string> picons() const override;
    std::size_t channel_count() const override;
    bool refresh_if_needed();

protected:
    virtual bool refresh() = 0;
    void set_playlist(PlaylistGenerator playlist,
                      std::map<std::string, std::string> channels,
                      std::map<std::string, std::string> picons);
    bool rewrite_channel(RequestContext& ctx, const std::string& channel_name, const std::string& ext);

    Config config_;
    HttpClient& http_client_;
    std::string plugin_name_;
    std::string header_;
    int update_minutes_;
    mutable std::mutex mutex_;
    PlaylistGenerator playlist_;
    std::map<std::string, std::string> channels_;
    std::map<std::string, std::string> picons_;
    std::string etag_;
    std::chrono::steady_clock::time_point playlist_time_;
    std::thread updater_;
    bool stop_updater_ = false;
    std::mutex updater_mutex_;
    std::condition_variable updater_cv_;
};

std::vector<std::shared_ptr<Plugin>> create_plugins(Config config, HttpClient& http_client, Proxy& proxy);

} // namespace httpace
