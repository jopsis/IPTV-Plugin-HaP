#include "httpaceproxycpp/playlist.hpp"
#include "httpaceproxycpp/json.hpp"
#include "httpaceproxycpp/util.hpp"

#include <algorithm>
#include <regex>
#include <sstream>

namespace httpace {
namespace {

std::string fill_template(std::string templ, const PlaylistItem& item) {
    std::map<std::string, std::string> values = {
        {"name", item.name}, {"url", item.url}, {"group", item.group}, {"tvg", item.tvg},
        {"tvgid", item.tvgid}, {"logo", item.logo}, {"availability", std::to_string(item.availability)}
    };
    for (const auto& [k, v] : values) templ = replace_all(templ, "{" + k + "}", v);
    return templ;
}

std::string fill_header_template(std::string templ, const std::map<std::string, std::string>& values) {
    for (const auto& [k, v] : values) templ = replace_all(templ, "{" + k + "}", v);
    return templ;
}

std::string channel_url_from_direct_url(const PlaylistItem& item,
                                        const std::map<std::string, std::string>& params) {
    auto parsed = parse_url(item.url);
    auto name = url_encode(replace_all(replace_all(item.name, "\"", "'"), ",", "."), "");
    auto ext = params.at("ext");
    auto host = params.at("hostport");
    auto query = params.at("query");
    auto with_query = [&](const std::string& path) {
        return "http://" + host + path + (query.empty() ? "" : "?" + query);
    };
    if (parsed.scheme == "acestream") return with_query("/content_id/" + parsed.host + "/" + name + "." + ext);
    if (parsed.scheme == "infohash") return with_query("/infohash/" + parsed.host + "/" + name + "." + ext);
    if ((parsed.scheme == "http" || parsed.scheme == "https") &&
        (ends_with(item.url, ".acelive") || ends_with(item.url, ".acestream") || ends_with(item.url, ".acemedia") || ends_with(item.url, ".torrent"))) {
        return with_query("/url/" + url_encode(item.url, "") + "/" + name + "." + ext);
    }
    if (!item.url.empty() && std::all_of(item.url.begin(), item.url.end(), ::isdigit)) {
        return with_query("/channels/play?id=" + item.url);
    }
    return item.url;
}

PlaylistItem item_from_m3u_extinf_line(const std::string& extinf_line, const std::string& url, const std::string& fallback_group) {
    auto attrs = parse_extinf_attrs(extinf_line);
    PlaylistItem item;
    item.name = parse_extinf_name(extinf_line);
    item.tvg = attrs.contains("tvg-name") ? attrs["tvg-name"] : item.name;
    item.tvgid = attrs.contains("tvg-id") ? attrs["tvg-id"] : "";
    item.group = attrs.contains("group-title") ? attrs["group-title"] : fallback_group;
    item.logo = attrs.contains("tvg-logo") ? attrs["tvg-logo"] : "";
    item.url = url;
    return item;
}

std::string channel_key_suffix(const std::string& url) {
    auto parsed = parse_url(url);
    auto value = parsed.host.empty() ? sha1_hex(url) : parsed.host;
    if (value.empty()) value = sha1_hex(url);
    return value.substr(0, std::min<std::size_t>(12, value.size()));
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

bool is_infohash(const std::string& value) {
    static const std::regex hash_re(R"(^[A-Fa-f0-9]{40}$)");
    return std::regex_match(value, hash_re);
}

std::string first_string(const Json& value) {
    if (value.is_string()) return trim(value.as_string());
    if (!value.is_array()) return "";
    for (const auto& item : value.as_array()) {
        auto text = first_string(item);
        if (!text.empty()) return text;
    }
    return "";
}

std::string api_item_group(const Json& item, const std::string& fallback_group) {
    auto group = first_string(item["categories"]);
    if (group.empty()) group = first_string(item["category"]);
    if (group.empty()) group = first_string(item["country"]);
    if (group.empty()) group = first_string(item["language"]);
    return group.empty() ? fallback_group : group;
}

std::string api_item_icon(const Json& item, const std::string& fallback_icon) {
    auto icon = trim(item["icon"].as_string());
    if (icon.empty()) icon = trim(item["logo"].as_string());
    if (icon.empty()) icon = trim(item["tvg_logo"].as_string());
    return icon.empty() ? fallback_icon : icon;
}

void add_acestream_api_value(const Json& value,
                             std::vector<PlaylistItem>& items,
                             std::map<std::string, std::string>& channels,
                             std::map<std::string, std::string>& picons,
                             const std::string& fallback_name,
                             const std::string& fallback_group,
                             const std::string& fallback_icon);

void add_acestream_api_object(const Json& object,
                              std::vector<PlaylistItem>& items,
                              std::map<std::string, std::string>& channels,
                              std::map<std::string, std::string>& picons,
                              const std::string& fallback_name,
                              const std::string& fallback_group,
                              const std::string& fallback_icon) {
    if (object.contains("result")) {
        add_acestream_api_value(object["result"], items, channels, picons, fallback_name, fallback_group, fallback_icon);
        return;
    }
    if (object.contains("results")) {
        add_acestream_api_value(object["results"], items, channels, picons, fallback_name, fallback_group, fallback_icon);
        return;
    }
    if (object.contains("items")) {
        auto parent_name = trim(object["name"].as_string(fallback_name));
        auto parent_group = api_item_group(object, fallback_group);
        auto parent_icon = api_item_icon(object, fallback_icon);
        add_acestream_api_value(object["items"], items, channels, picons, parent_name, parent_group, parent_icon);
        return;
    }

    auto infohash = trim(object["infohash"].as_string());
    if (!is_infohash(infohash)) return;

    PlaylistItem item;
    item.name = trim(object["name"].as_string(fallback_name));
    if (item.name.empty()) item.name = infohash.substr(0, 12);
    item.group = api_item_group(object, fallback_group);
    item.logo = api_item_icon(object, fallback_icon);
    item.availability = object["availability"].as_number(0.0);
    item.tvg = item.name;
    item.tvgid = item.name;

    auto ace_url = "acestream://" + lower(infohash);
    auto key = unique_channel_key(item.name, ace_url, channels);
    if (key.empty()) return;
    item.url = url_encode(key, "");
    channels[key] = ace_url;
    picons[key] = item.logo;
    items.push_back(std::move(item));
}

void add_acestream_api_value(const Json& value,
                             std::vector<PlaylistItem>& items,
                             std::map<std::string, std::string>& channels,
                             std::map<std::string, std::string>& picons,
                             const std::string& fallback_name,
                             const std::string& fallback_group,
                             const std::string& fallback_icon) {
    if (value.is_array()) {
        for (const auto& item : value.as_array()) {
            add_acestream_api_value(item, items, channels, picons, fallback_name, fallback_group, fallback_icon);
        }
    } else if (value.is_object()) {
        add_acestream_api_object(value, items, channels, picons, fallback_name, fallback_group, fallback_icon);
    }
}

} // namespace

PlaylistGenerator::PlaylistGenerator(std::string header, std::string channel_template)
    : header_(std::move(header)), channel_template_(std::move(channel_template)) {}

void PlaylistGenerator::add_item(PlaylistItem item) {
    if (item.tvg.empty()) item.tvg = item.name;
    if (item.tvgid.empty()) item.tvgid = item.name;
    if (item.logo.empty()) item.logo = "";
    items_.push_back(std::move(item));
}

std::string PlaylistGenerator::export_m3u(const std::string& hostport,
                                          const std::string& path,
                                          const std::string& query,
                                          bool parse_url,
                                          bool empty_header) const {
    std::vector<PlaylistItem> sorted = items_;
    std::stable_sort(sorted.begin(), sorted.end(), [](const PlaylistItem& a, const PlaylistItem& b) {
        if (a.group != b.group) return a.group < b.group;
        return a.name < b.name;
    });
    std::map<std::string, std::string> params = {
        {"hostport", hostport},
        {"path", path},
        {"query", query},
        {"ext", query_get(query, "ext", "ts")}
    };

    std::string out = empty_header ? "#EXTM3U\n" : fill_header_template(header_, params);
    for (auto item : sorted) out += render_item(std::move(item), params, parse_url);
    return out;
}

std::string PlaylistGenerator::etag() const {
    std::string joined;
    for (const auto& item : items_) joined += item.name;
    return "\"" + md5_hex(joined) + "\"";
}

std::string PlaylistGenerator::default_header() {
    return "#EXTM3U deinterlace=1 m3uautoload=1 cache=1000\n";
}

std::string PlaylistGenerator::default_channel_template() {
    return "#EXTINF:-1 group-title=\"{group}\" tvg-name=\"{tvg}\" tvg-id=\"{tvgid}\" tvg-logo=\"{logo}\",{name}\n#EXTGRP:{group}\n{url}\n";
}

std::string PlaylistGenerator::epg_header(const std::string& tvg_url, int tvg_shift, bool quote_url) {
    std::ostringstream out;
    out << "#EXTM3U";
    if (!tvg_url.empty()) {
        out << " x-tvg-url=";
        if (quote_url) out << '"';
        out << tvg_url;
        if (quote_url) out << '"';
    }
    out << " tvg-shift=" << tvg_shift << " deinterlace=1 m3uautoload=1 cache=1000\n";
    return out.str();
}

std::string PlaylistGenerator::render_item(PlaylistItem item, const std::map<std::string, std::string>& params,
                                           bool parse_url) const {
    item.name = replace_all(replace_all(item.name, "\"", "'"), ",", ".");
    if (!params.at("path").empty() && ends_with(params.at("path"), "channel")) {
        std::string value = item.url.empty() ? url_encode(item.name, "") : item.url;
        item.url = "http://" + params.at("hostport") + params.at("path") + "/" + value + "." + params.at("ext") +
                   (params.at("query").empty() ? "" : "?" + params.at("query"));
    } else if (!parse_url) {
        item.url = channel_url_from_direct_url(item, params);
    }
    return fill_template(channel_template_, item);
}

std::map<std::string, std::string> parse_extinf_attrs(const std::string& line) {
    std::map<std::string, std::string> attrs;
    static const std::regex attr_re(R"REGEX(([A-Za-z0-9_-]+)="([^"]*)")REGEX");
    for (auto it = std::sregex_iterator(line.begin(), line.end(), attr_re); it != std::sregex_iterator(); ++it) {
        attrs[(*it)[1].str()] = (*it)[2].str();
    }
    return attrs;
}

std::string parse_extinf_name(const std::string& line) {
    auto comma = line.rfind(',');
    return comma == std::string::npos ? "Unknown Channel" : trim(line.substr(comma + 1));
}

std::optional<std::string> extract_acestream_content_url(const std::string& raw_url) {
    auto url = trim(raw_url);
    if (starts_with(url, "acestream://")) return url;
    if (starts_with(url, "infohash://")) return url;

    auto parsed = parse_url(url);
    auto query = parse_query(parsed.query);
    if (query.contains("id") && !query["id"].empty()) return "acestream://" + query["id"];
    if (query.contains("infohash") && !query["infohash"].empty()) return "acestream://" + query["infohash"];
    if (query.contains("content_id") && !query["content_id"].empty()) return "acestream://" + query["content_id"];
    auto lower_path = lower(parsed.path);
    if ((parsed.scheme == "http" || parsed.scheme == "https") &&
        (ends_with(lower_path, ".acelive") || ends_with(lower_path, ".acestream") || ends_with(lower_path, ".acemedia") || ends_with(lower_path, ".torrent"))) {
        return url;
    }

    static const std::regex bare_hash_re(R"(^[A-Fa-f0-9]{40}$)");
    if (std::regex_match(url, bare_hash_re)) return "acestream://" + url;

    return std::nullopt;
}

std::vector<PlaylistItem> parse_m3u_acestream_items(const std::string& body,
                                                    std::map<std::string, std::string>& channels,
                                                    std::map<std::string, std::string>& picons) {
    std::vector<PlaylistItem> items;
    auto lines = split(body, '\n', true);
    std::string current_group = "Unknown";
    std::string current_group_logo;

    for (std::size_t i = 0; i < lines.size(); ++i) {
        auto line = trim(lines[i]);
        if (starts_with(line, "#EXTGRP:")) {
            auto attrs = parse_extinf_attrs(line);
            if (attrs.contains("group-title")) current_group = attrs["group-title"];
            if (attrs.contains("group-logo")) current_group_logo = attrs["group-logo"];
            continue;
        }
        if (!starts_with(line, "#EXTINF:")) continue;

        std::optional<std::string> ace_url;
        std::size_t url_index = i + 1;
        for (; url_index < lines.size(); ++url_index) {
            auto candidate = trim(lines[url_index]);
            if (candidate.empty()) continue;
            if (starts_with(candidate, "#EXTINF:")) break;
            if (starts_with(candidate, "#")) continue;
            ace_url = extract_acestream_content_url(candidate);
            break;
        }
        if (!ace_url) continue;
        i = url_index;

        auto item = item_from_m3u_extinf_line(line, url_encode(parse_extinf_name(line), ""), current_group);
        if (item.group.empty() || item.group == "Unknown") item.group = current_group;
        if (item.logo.empty()) item.logo = current_group_logo;

        auto key = unique_channel_key(item.name, *ace_url, channels);
        if (key.empty()) continue;

        item.url = url_encode(key, "");
        channels[key] = *ace_url;
        picons[key] = item.logo;
        items.push_back(item);
    }
    return items;
}

std::vector<PlaylistItem> parse_acestream_api_items(const std::string& body,
                                                    std::map<std::string, std::string>& channels,
                                                    std::map<std::string, std::string>& picons,
                                                    const std::string& fallback_group) {
    std::vector<PlaylistItem> items;
    auto root = Json::parse(body);
    auto group = trim(fallback_group);
    if (group.empty()) group = "AceStream API";
    add_acestream_api_value(root, items, channels, picons, "", group, "");
    return items;
}

} // namespace httpace
