#pragma once

#include <functional>
#include <map>
#include <optional>
#include <string>
#include <vector>

namespace httpace {

struct PlaylistItem {
    std::string name;
    std::string url;
    std::string group;
    std::string tvg;
    std::string tvgid;
    std::string logo;
    double availability = 0.0;
};

class PlaylistGenerator {
public:
    explicit PlaylistGenerator(std::string header = default_header(),
                               std::string channel_template = default_channel_template());

    void add_item(PlaylistItem item);
    const std::vector<PlaylistItem>& items() const { return items_; }
    std::string export_m3u(const std::string& hostport,
                           const std::string& path,
                           const std::string& query,
                           bool parse_url = false,
                           bool empty_header = false) const;
    std::string etag() const;
    bool empty() const { return items_.empty(); }
    std::size_t size() const { return items_.size(); }

    static std::string default_header();
    static std::string default_channel_template();
    static std::string epg_header(const std::string& tvg_url, int tvg_shift, bool quote_url = false);

private:
    std::string render_item(PlaylistItem item, const std::map<std::string, std::string>& params,
                            bool parse_url) const;
    std::string header_;
    std::string channel_template_;
    std::vector<PlaylistItem> items_;
};

std::map<std::string, std::string> parse_extinf_attrs(const std::string& line);
std::string parse_extinf_name(const std::string& line);
std::optional<std::string> extract_acestream_content_url(const std::string& raw_url);
std::vector<PlaylistItem> parse_m3u_acestream_items(const std::string& body,
                                                    std::map<std::string, std::string>& channels,
                                                    std::map<std::string, std::string>& picons);

} // namespace httpace
