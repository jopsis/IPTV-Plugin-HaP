#include "httpaceproxycpp/proxy.hpp"
#include "httpaceproxycpp/util.hpp"

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <string_view>
#include <thread>

#include <arpa/inet.h>
#include <sys/utsname.h>

namespace httpace {
namespace {

bool is_video_extension(const std::string& path) {
    static const std::set<std::string> exts = {
        "avi", "flv", "m2ts", "mkv", "mpeg", "mpeg4", "mpegts", "mpg4", "mp4",
        "mpg", "mov", "mpv", "qt", "ts", "wmv", "m3u8"
    };
    return exts.contains(extension_of(path));
}

std::string header_host(const HttpRequest& request) {
    return request.header("host", "localhost:8888");
}

std::string map_reqtype_alias(std::string reqtype) {
    if (reqtype == "torrent") return "url";
    if (reqtype == "pid") return "content_id";
    return reqtype;
}

bool is_core_reqtype(const std::string& reqtype) {
    static const std::set<std::string> types = {"content_id", "url", "infohash", "direct_url", "data", "efile_url"};
    return types.contains(reqtype);
}

std::string value_or(const std::map<std::string, std::string>& values, const std::string& key, const std::string& fallback = "") {
    auto it = values.find(key);
    return it == values.end() ? fallback : it->second;
}

bool ipv4_to_uint(std::string_view ip, std::uint32_t& out) {
    in_addr addr{};
    std::string value(ip);
    if (::inet_pton(AF_INET, value.c_str(), &addr) != 1) return false;
    out = ntohl(addr.s_addr);
    return true;
}

bool ipv4_matches_range(const std::string& client_ip, const std::string& range) {
    auto slash = range.find('/');
    if (slash == std::string::npos) return client_ip == range;

    int prefix = -1;
    try {
        prefix = std::stoi(range.substr(slash + 1));
    } catch (...) {
        return false;
    }
    if (prefix < 0 || prefix > 32) return false;

    std::uint32_t client = 0;
    std::uint32_t network = 0;
    if (!ipv4_to_uint(client_ip, client) || !ipv4_to_uint(range.substr(0, slash), network)) return false;

    std::uint32_t mask = prefix == 0 ? 0 : (0xffffffffu << (32 - prefix));
    return (client & mask) == (network & mask);
}

std::string os_platform() {
    struct utsname info {};
    if (uname(&info) == 0) {
        return std::string(info.sysname) + " " + info.release + " " + info.machine;
    }
    return "unknown";
}

Json::object memory_info() {
    double total = 0.0;
    double available = 0.0;
    std::ifstream meminfo("/proc/meminfo");
    std::string key;
    double value = 0.0;
    std::string unit;
    while (meminfo >> key >> value >> unit) {
        if (key == "MemTotal:") total = value * 1024.0;
        else if (key == "MemAvailable:") available = value * 1024.0;
    }
    double used = total > available ? total - available : 0.0;
    return Json::object{{"total", total}, {"used", used}, {"available", available}};
}

Json::object disk_info() {
    try {
        auto space = std::filesystem::space("/");
        double total = static_cast<double>(space.capacity);
        double free = static_cast<double>(space.available);
        double used = total > free ? total - free : 0.0;
        return Json::object{{"total", total}, {"used", used}, {"free", free}};
    } catch (...) {
        return Json::object{{"total", 0}, {"used", 0}, {"free", 0}};
    }
}

Json::array cpu_percent() {
    struct CpuSample {
        unsigned long long idle = 0;
        unsigned long long total = 0;
    };

    std::ifstream stat("/proc/stat");
    std::vector<CpuSample> samples;
    std::string label;
    while (stat >> label) {
        if (!starts_with(label, "cpu") || label == "cpu") {
            std::string rest;
            std::getline(stat, rest);
            continue;
        }
        unsigned long long user = 0, nice = 0, system = 0, idle = 0, iowait = 0;
        unsigned long long irq = 0, softirq = 0, steal = 0, guest = 0, guest_nice = 0;
        stat >> user >> nice >> system >> idle >> iowait >> irq >> softirq >> steal >> guest >> guest_nice;
        samples.push_back(CpuSample{
            idle + iowait,
            user + nice + system + idle + iowait + irq + softirq + steal + guest + guest_nice
        });
    }

    static std::mutex cpu_mutex;
    static std::vector<CpuSample> previous;
    std::lock_guard<std::mutex> lock(cpu_mutex);

    Json::array out;
    if (samples.empty()) {
        auto cores = std::max(1u, std::thread::hardware_concurrency());
        for (unsigned int i = 0; i < cores; ++i) out.push_back(0);
        return out;
    }

    for (std::size_t i = 0; i < samples.size(); ++i) {
        double percent = 0.0;
        if (i < previous.size()) {
            auto total_delta = samples[i].total - previous[i].total;
            auto idle_delta = samples[i].idle - previous[i].idle;
            if (total_delta > 0 && total_delta >= idle_delta) {
                percent = (static_cast<double>(total_delta - idle_delta) * 100.0) / static_cast<double>(total_delta);
            }
        }
        out.push_back(percent);
    }
    previous = std::move(samples);
    return out;
}

Json system_info() {
    auto cpu = cpu_percent();
    return Json::object{
        {"os_platform", os_platform()},
        {"cpu_nums", static_cast<double>(cpu.size())},
        {"cpu_percent", cpu},
        {"cpu_freq", nullptr},
        {"cpu_temp", nullptr},
        {"mem_info", memory_info()},
        {"disk_info", disk_info()}
    };
}

} // namespace

Proxy::Proxy(Config config)
    : config_(std::move(config)), broadcasts_(config_, http_client_) {
    auto plugins = create_plugins(config_, http_client_, *this);
    for (auto& plugin : plugins) plugins_.add(plugin);
}

Proxy::~Proxy() { stop(); }

void Proxy::start() {
    server_ = std::make_unique<HttpServer>(config_.http_host, config_.http_port,
        [this](const HttpRequest& request, ClientConnection& connection) { handle_http(request, connection); });
    server_->set_client_send_timeout(config_.client_write_timeout);
    server_->start();
    log_line("INFO", "HTTPAceProxyCPP started at " + config_.http_host + ":" + std::to_string(config_.http_port));
    server_->join();
}

void Proxy::stop() {
    broadcasts_.stop_all();
    if (server_) server_->stop();
}

void Proxy::handle_http(const HttpRequest& request, ClientConnection& connection) {
    log_line("INFO", "[" + request.client_ip + "] " + request.method + " " + request.target);
    if (config_.firewall && !check_firewall(request.header("x-forwarded-for", request.client_ip))) {
        send_error(connection, 401, "Dropping connection due to firewall rules");
        return;
    }
    if (request.method != "GET" && request.method != "HEAD") {
        send_error(connection, 400, "Bad Request");
        return;
    }
    if (request.method == "HEAD" || is_fake_request(request)) {
        connection.send_response_headers(200, status_reason(200), {
            {"Content-Type", "video/mp2t"},
            {"Connection", "close"}
        });
        return;
    }

    RequestContext ctx{request, connection, request.path, request.query, split(request.path, '/', true), "", "m3u8"};
    for (int redirects = 0; redirects < 4; ++redirects) {
        ctx.rewritten = false;
        ctx.parts = split(ctx.path, '/', true);
        ctx.reqtype = ctx.parts.size() > 1 ? lower(ctx.parts[1]) : "";
        if (auto plugin = plugins_.by_handler(ctx.reqtype)) {
            bool handled = plugin->handle(ctx);
            if (handled) return;
            if (ctx.rewritten) continue;
        }
        ctx.reqtype = map_reqtype_alias(ctx.reqtype);
        if (is_core_reqtype(ctx.reqtype)) {
            handle_core_stream(ctx);
            return;
        }
        send_error(connection, 400, "Bad Request");
        return;
    }
    send_error(connection, 500, "Too many internal rewrites");
}

bool Proxy::is_fake_request(const HttpRequest& request) const {
    auto ua = request.header("user-agent");
    if (ua.empty()) return false;
    if (std::find(config_.fake_user_agents.begin(), config_.fake_user_agents.end(), ua) != config_.fake_user_agents.end()) return true;
    if (ua == "Lavf/55.33.100" && request.header("range").empty()) return true;
    if (ua == "GStreamer souphttpsrc (compatible; LG NetCast.TV-2013) libsoup/2.34.2" && request.header("icy-metadata") != "1") return true;
    return false;
}

bool Proxy::check_firewall(const std::string& client_ip) const {
    bool in_range = false;
    for (const auto& range : config_.firewall_ranges) {
        if (ipv4_matches_range(client_ip, range)) {
            in_range = true;
            break;
        }
    }
    return !((config_.firewall_blacklist_mode && in_range) || (!config_.firewall_blacklist_mode && !in_range));
}

void Proxy::handle_static(const HttpRequest&, ClientConnection& connection, const std::string& root_prefix) {
    try {
        if (!path_is_safe_relative(root_prefix)) throw std::runtime_error("unsafe path");
        auto full = std::filesystem::path(config_.root_dir) / "http" / root_prefix;
        auto body = read_file_binary(full.string());
        send_simple_response(connection, 200, mime_type_for_path(root_prefix), body);
    } catch (...) {
        send_error(connection, 404, "Not Found");
    }
}

void Proxy::handle_core_stream(RequestContext& ctx) {
    if (ctx.parts.size() < 3) {
        send_error(ctx.connection, 400, "Bad Request");
        return;
    }
    if (config_.max_connections > 0 && static_cast<int>(broadcasts_.client_count()) >= config_.max_connections) {
        send_error(ctx.connection, 403, "Maximum client connections reached, can't serve request");
        return;
    }
    if (!is_video_extension(ctx.path)) {
        send_error(ctx.connection, 501, "request seems valid but no valid video extension was provided");
        return;
    }

    std::map<std::string, std::string> params = {
        {"file_indexes", "0"}, {"developer_id", "0"}, {"affiliate_id", "0"}, {"zone_id", "0"}, {"stream_id", "0"},
        {"stream_type", config_.stream_type_string()},
        {"sessionID", std::to_string(reinterpret_cast<std::uintptr_t>(&ctx))},
    };
    static const std::vector<std::string> start_params = {"file_indexes", "developer_id", "affiliate_id", "zone_id", "stream_id"};
    for (std::size_t i = 3; i < ctx.parts.size() && i - 3 < start_params.size(); ++i) {
        if (!ctx.parts[i].empty() && std::all_of(ctx.parts[i].begin(), ctx.parts[i].end(), ::isdigit)) {
            params[start_params[i - 3]] = ctx.parts[i];
        }
    }
    auto req_value = url_decode(ctx.parts[2]);
    params[ctx.reqtype] = req_value;

    std::string infohash;
    std::string channel_name = ctx.channel_name.empty() ? url_decode(basename_no_ext(ctx.path)) : ctx.channel_name;
    if (channel_name.empty()) channel_name = "NoNameChannel";

    try {
        if (ctx.reqtype == "direct_url" || ctx.reqtype == "efile_url") {
            infohash = sha1_hex(ctx.path);
        } else {
            auto info = get_content_info(params);
            infohash = info["infohash"].as_string();
            if (channel_name == "NoNameChannel" && info["files"].is_array()) {
                int wanted = 0;
                try { wanted = std::stoi(params["file_indexes"]); } catch (...) {}
                for (const auto& file : info["files"].as_array()) {
                    if (file.is_array() && file.as_array().size() >= 2 &&
                        static_cast<int>(file[1].as_number(-1)) == wanted) {
                        channel_name = file[0].as_string(channel_name);
                        break;
                    }
                }
            }
        }
        if (infohash.empty()) infohash = sha1_hex(ctx.reqtype + ":" + req_value);

        if (config_.max_concurrent_channels > 0 && broadcasts_.broadcast_count() >= static_cast<std::size_t>(config_.max_concurrent_channels) &&
            !broadcasts_.find(infohash)) {
            send_error(ctx.connection, 503, "Maximum concurrent channels reached, try again later");
            return;
        }

        auto broadcast = broadcasts_.get_or_create(infohash, params);
        auto client = broadcast->add_client(
            ctx.request.header("x-forwarded-for", ctx.request.client_ip),
            ctx.request.header("user-agent", ""),
            channel_name,
            ctx.channel_icon);
        broadcast->start_once();

        bool chunked = config_.use_chunked && ctx.request.version == "HTTP/1.1";
        std::map<std::string, std::string> headers = {
            {"Content-Type", mime_type_for_path(ctx.path)},
            {"Accept-Ranges", "none"},
            {"Connection", chunked ? "keep-alive" : "close"}
        };
        if (chunked) {
            headers["Transfer-Encoding"] = "chunked";
            headers["Keep-Alive"] = "timeout=" + std::to_string(config_.video_timeout) + ", max=100";
        }
        ctx.connection.send_response_headers(200, status_reason(200), headers);

        std::vector<char> chunk;
        while (client->queue->pop(chunk)) {
            bool ok;
            if (chunked) {
                std::ostringstream prefix;
                prefix << std::hex << chunk.size() << "\r\n";
                ok = ctx.connection.send_text(prefix.str())
                  && ctx.connection.send_all(chunk.data(), chunk.size())
                  && ctx.connection.send_text("\r\n");
            } else {
                ok = ctx.connection.send_all(chunk.data(), chunk.size());
            }
            if (!ok) break;
            client->bytes_sent.fetch_add(static_cast<std::uint64_t>(chunk.size()), std::memory_order_relaxed);
            client->last_activity.store(unix_time(), std::memory_order_relaxed);
        }
        if (chunked) ctx.connection.send_text("0\r\n\r\n");
        broadcast->remove_client(client);
        broadcasts_.remove_if_empty(infohash);
    } catch (const std::exception& e) {
        send_error(ctx.connection, 500, e.what());
    }
}

Json Proxy::get_content_info(const std::map<std::string, std::string>& params) {
    std::lock_guard<std::mutex> lock(idle_mutex_);
    try {
        if (!idle_ace_ || !idle_ace_->alive()) {
            idle_ace_ = std::make_unique<AceClient>(config_, "idleAce");
            idle_ace_->authenticate();
        }
        return idle_ace_->content_info(params);
    } catch (...) {
        idle_ace_.reset();
        throw;
    }
}

void Proxy::send_error(ClientConnection& connection, int status, const std::string& message) {
    log_line(status >= 500 ? "ERROR" : "WARNING", message);
    send_simple_response(connection, status, "text/plain", message);
}

Json Proxy::clients_json() {
    Json::array clients;
    for (const auto& client : broadcasts_.all_clients()) {
        clients.push_back(Json::object{
            {"sessionID", client->session_id},
            {"channelIcon", client->channel_icon},
            {"channelName", client->channel_name},
            {"clientIP", client->client_ip},
            {"userAgent", client->user_agent},
            {"bytesSent", static_cast<double>(client->bytes_sent.load(std::memory_order_relaxed))},
            {"megabytesSent", static_cast<double>(client->bytes_sent.load(std::memory_order_relaxed)) / (1024.0 * 1024.0)},
            {"startTime", static_cast<double>(client->connection_time)},
            {"durationTime", format_duration(std::chrono::seconds(unix_time() - client->connection_time))},
            {"stat", Json::object{}}
        });
    }
    return Json::object{
        {"status", "success"},
        {"total_clients", static_cast<double>(broadcasts_.client_count())},
        {"active_broadcasts", static_cast<double>(broadcasts_.broadcast_count())},
        {"clients_data", clients}
    };
}

Json Proxy::status_json() {
    Json::array loaded;
    for (const auto& plugin : plugins_.unique_plugins()) {
        Json::array handlers;
        for (const auto& handler : plugin->handlers()) handlers.push_back(handler);
        loaded.push_back(Json::object{
            {"name", plugin->name()},
            {"channels", static_cast<double>(plugin->channel_count())},
            {"status", "loaded"},
            {"handlers", handlers}
        });
    }
    return Json::object{
        {"status", "success"},
        {"sys_info", system_info()},
        {"server_config", Json::object{
            {"aceproxy", Json::object{{"host", config_.http_host}, {"port", config_.http_port}}},
            {"acestream", Json::object{{"host", config_.ace_host}, {"api_port", config_.ace_api_port}, {"http_port", config_.ace_http_port}}},
            {"limits", Json::object{{"max_connections", config_.max_connections}, {"max_concurrent_channels", config_.max_concurrent_channels}}},
            {"plugins", Json::object{{"enabled", config_.enabled_plugins}}}
        }},
        {"server_info", Json::object{
            {"runtime", "C++20"},
            {"os_name", "native"},
            {"acestream_engine", acestream_engine_status()},
            {"plugins_loaded", loaded}
        }},
        {"connection_info", Json::object{
            {"max_clients", config_.max_connections},
            {"total_clients", static_cast<double>(broadcasts_.client_count())},
            {"active_broadcasts", static_cast<double>(broadcasts_.broadcast_count())}
        }},
        {"clients_data", clients_json()["clients_data"]}
    };
}

Json Proxy::acestream_engine_status() {
    {
        std::lock_guard<std::mutex> lock(ace_status_mutex_);
        auto age = std::chrono::steady_clock::now() - ace_status_time_;
        if (!ace_status_cache_.is_null() && age < std::chrono::seconds(10)) return ace_status_cache_;
    }

    Json status = Json::object{
        {"status", "disconnected"},
        {"version", "unknown"},
        {"host", config_.ace_host},
        {"api_port", config_.ace_api_port}
    };

    try {
        auto url = "http://" + config_.ace_host + ":" + std::to_string(config_.ace_api_port) +
                   "/webui/api/service?method=get_version&format=json";
        auto response = http_client_.get(url, {{"User-Agent", "HTTPAceProxyCPP"}}, 2, false);
        if (response.status >= 200 && response.status < 300) {
            auto data = Json::parse(response.body);
            auto version = data["result"]["version"].as_string("unknown");
            status = Json::object{
                {"status", "connected"},
                {"version", version},
                {"host", config_.ace_host},
                {"api_port", config_.ace_api_port}
            };
        } else {
            status = Json::object{
                {"status", "disconnected"},
                {"version", "unknown"},
                {"host", config_.ace_host},
                {"api_port", config_.ace_api_port},
                {"error", "HTTP status " + std::to_string(response.status)}
            };
        }
    } catch (const std::exception& http_error) {
        try {
            AceClient ace(config_, "EngineStatus");
            ace.authenticate();
            status = Json::object{
                {"status", "connected"},
                {"version", "unknown"},
                {"host", config_.ace_host},
                {"api_port", config_.ace_api_port},
                {"transport", "control"}
            };
        } catch (const std::exception& control_error) {
            status = Json::object{
                {"status", "disconnected"},
                {"version", "unknown"},
                {"host", config_.ace_host},
                {"api_port", config_.ace_api_port},
                {"error", std::string(http_error.what()) + "; control: " + control_error.what()}
            };
        }
    }

    {
        std::lock_guard<std::mutex> lock(ace_status_mutex_);
        ace_status_cache_ = status;
        ace_status_time_ = std::chrono::steady_clock::now();
    }
    return status;
}

Json Proxy::plugins_json() {
    Json::array plugin_array;
    for (const auto& plugin : plugins_.unique_plugins()) {
        if (plugin->name() == "stat" || plugin->name() == "statplugin" || plugin->name() == "aio") continue;
        Json::array channels;
        auto channel_map = plugin->channels();
        auto picons = plugin->picons();
        auto add_channel = [&](const std::string& display_name, const std::string& key, const std::string& url) {
            auto parsed = parse_url(url);
            if (parsed.scheme != "acestream") return;
            channels.push_back(Json::object{
                {"name", display_name},
                {"content_id", parsed.host},
                {"logo", picons.contains(key) ? picons[key] : ""},
                {"status", "unknown"},
                {"last_check", nullptr},
                {"infohash", ""}
            });
        };

        auto items = plugin->playlist_items();
        if (!items.empty()) {
            std::set<std::string> seen_keys;
            for (const auto& item : items) {
                auto key = url_decode(item.url);
                if (!key.empty() && channel_map.contains(key)) {
                    if (seen_keys.insert(key).second) add_channel(item.name, key, channel_map[key]);
                } else if (channel_map.contains(item.name)) {
                    if (seen_keys.insert(item.name).second) add_channel(item.name, item.name, channel_map[item.name]);
                }
            }
        } else {
            for (const auto& [name, url] : channel_map) add_channel(name, name, url);
        }
        if (!channels.empty()) {
            plugin_array.push_back(Json::object{
                {"name", plugin->name()},
                {"total_channels", static_cast<double>(channels.size())},
                {"channels", channels}
            });
        }
    }
    return Json::object{
        {"status", "success"},
        {"plugins", plugin_array},
        {"total_plugins", static_cast<double>(plugin_array.size())}
    };
}

Json Proxy::check_channel_light(const std::string& plugin_name, const std::string& channel, const std::string& content_id) {
    std::string cid = content_id;
    if (cid.empty()) {
        auto plugin = plugins_.by_handler(plugin_name);
        if (!plugin) return Json::object{{"status", "error"}, {"error", "Plugin not found: " + plugin_name}};
        auto channels = plugin->channels();
        if (!channels.contains(channel)) return Json::object{{"status", "error"}, {"error", "Channel not found: " + channel}};
        auto parsed = parse_url(channels[channel]);
        cid = parsed.scheme == "acestream" ? parsed.host : "";
    }
    if (cid.empty()) return Json::object{{"status", "error"}, {"error", "content_id required"}};
    try {
        AceClient ace(config_, "Check_" + cid.substr(0, 8));
        ace.authenticate();
        auto info = ace.content_info({{"content_id", cid}, {"sessionID", "0"}});
        ace.shutdown();
        return Json::object{{"status", "success"}, {"cached", false}, {"available", true}, {"infohash", info["infohash"].as_string()}, {"checked_at", static_cast<double>(unix_time())}};
    } catch (const std::exception& e) {
        return Json::object{{"status", "success"}, {"cached", false}, {"available", false}, {"error", e.what()}, {"checked_at", static_cast<double>(unix_time())}};
    }
}

Json Proxy::check_channel_peers(const std::string& content_id, int max_wait) {
    if (content_id.empty()) return Json::object{{"status", "error"}, {"error", "content_id required"}};
    try {
        AceClient ace(config_, "PeerCheck_" + content_id.substr(0, 8));
        ace.authenticate();
        auto info = ace.content_info({{"content_id", content_id}, {"sessionID", "0"}});
        auto params = std::map<std::string, std::string>{{"content_id", content_id}, {"file_indexes", "0"}, {"stream_type", config_.stream_type_string()}};
        ace.start_broadcast(params);
        int peers = 0;
        int http_peers = 0;
        std::string status_text = "unknown";
        auto start = std::chrono::steady_clock::now();
        while (std::chrono::steady_clock::now() - start < std::chrono::seconds(max_wait)) {
            auto stat = ace.status(1);
            status_text = value_or(stat, "status", status_text);
            try { peers = std::stoi(value_or(stat, "peers", "0")); } catch (...) {}
            try { http_peers = std::stoi(value_or(stat, "http_peers", "0")); } catch (...) {}
            if (peers > 0 || http_peers > 0 || status_text == "dl" || status_text == "prebuf" || status_text == "buf") break;
        }
        ace.stop_broadcast();
        ace.shutdown();
        int total = peers + http_peers;
        return Json::object{
            {"status", "success"}, {"cached", false}, {"available", total > 0 || status_text == "dl" || status_text == "prebuf" || status_text == "buf"},
            {"peers", peers}, {"http_peers", http_peers}, {"total_peers", total}, {"status_text", status_text},
            {"infohash", info["infohash"].as_string()}, {"checked_at", static_cast<double>(unix_time())}
        };
    } catch (const std::exception& e) {
        return Json::object{{"status", "error"}, {"error", e.what()}, {"available", false}};
    }
}

} // namespace httpace
