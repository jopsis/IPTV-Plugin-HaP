#pragma once

#include "httpaceproxycpp/ace_client.hpp"
#include "httpaceproxycpp/broadcast.hpp"
#include "httpaceproxycpp/config.hpp"
#include "httpaceproxycpp/http_client.hpp"
#include "httpaceproxycpp/http_server.hpp"
#include "httpaceproxycpp/plugins.hpp"

#include <chrono>
#include <memory>
#include <mutex>
#include <string>

namespace httpace {

class Proxy {
public:
    explicit Proxy(Config config);
    ~Proxy();

    void start();
    void stop();
    void handle_http(const HttpRequest& request, ClientConnection& connection);

    Config& config() { return config_; }
    const Config& config() const { return config_; }
    BroadcastManager& broadcasts() { return broadcasts_; }
    PluginRegistry& plugins() { return plugins_; }
    HttpClient& http_client() { return http_client_; }

    Json clients_json();
    Json status_json();
    Json plugins_json();
    Json check_channel_light(const std::string& plugin, const std::string& channel, const std::string& content_id);
    Json check_channel_peers(const std::string& content_id, int max_wait);

private:
    bool is_fake_request(const HttpRequest& request) const;
    bool check_firewall(const std::string& client_ip) const;
    void handle_static(const HttpRequest& request, ClientConnection& connection, const std::string& root_prefix);
    void handle_core_stream(RequestContext& ctx);
    Json get_content_info(const std::map<std::string, std::string>& params);
    Json acestream_engine_status();
    void send_error(ClientConnection& connection, int status, const std::string& message);

    Config config_;
    HttpClient http_client_;
    BroadcastManager broadcasts_;
    PluginRegistry plugins_;
    std::unique_ptr<HttpServer> server_;
    std::mutex idle_mutex_;
    std::unique_ptr<AceClient> idle_ace_;
    std::mutex ace_status_mutex_;
    std::chrono::steady_clock::time_point ace_status_time_{};
    Json ace_status_cache_;
};

} // namespace httpace
