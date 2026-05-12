#pragma once

#include <map>
#include <set>
#include <string>
#include <vector>

namespace httpace {

struct Config {
    std::string ace_host = "127.0.0.1";
    int ace_api_port = 62062;
    int ace_http_port = 6878;
    std::string http_host = "0.0.0.0";
    int http_port = 8888;
    int max_connections = 10;
    int max_concurrent_channels = 5;
    int ace_connect_timeout = 5;
    int ace_result_timeout = 5;
    int video_timeout = 30;
    int video_seekback = 0;
    int client_queue_size = 256;
    int client_write_timeout = 15;
    int curl_stream_buffer = 1048576;
    bool use_chunked = true;
    bool firewall = false;
    bool firewall_blacklist_mode = false;
    std::vector<std::string> firewall_ranges = {"127.0.0.1/32", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"};
    std::string enabled_plugins = "userm3u,custom,aio,stat,statplugin";
    std::string aio_plugins = "none";
    std::string user_m3u_sources;
    std::string custom_playlist_path;
    std::string root_dir = ".";
    std::map<std::string, std::string> acestream_type = {{"output_format", "http"}};
    std::string ace_key = "n51LvQoTlJzNGaFxseRK-uvnvX-sD4Vm5Axwmc4UcoD-jruxmKsuJaH0eVgE";
    int ace_age = 3;
    int ace_sex = 1;
    std::vector<std::string> fake_user_agents = {"Mozilla/5.0 IMC plugin Macintosh"};

    std::string stream_type_string() const;
    bool plugin_enabled(const std::string& name) const;
    bool aio_includes(const std::string& name) const;
};

Config load_config(int argc, char** argv);

} // namespace httpace
