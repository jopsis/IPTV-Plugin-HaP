#pragma once

#include <chrono>
#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace httpace {

std::string trim(std::string_view value);
std::string lower(std::string_view value);
bool starts_with(std::string_view value, std::string_view prefix);
bool ends_with(std::string_view value, std::string_view suffix);
std::vector<std::string> split(std::string_view value, char delimiter, bool keep_empty = true);
std::string join(const std::vector<std::string>& values, std::string_view delimiter);
std::string url_decode(std::string_view value);
std::string url_encode(std::string_view value, std::string_view safe = "");
std::map<std::string, std::string> parse_query(std::string_view query);
std::string query_get(std::string_view query, std::string_view key, std::string_view fallback = "");
std::string shell_quote_for_log(std::string_view value);
std::string basename_no_ext(std::string_view path);
std::string extension_of(std::string_view path);
std::string mime_type_for_path(std::string_view path);
std::string md5_hex(std::string_view value);
std::string sha1_hex(std::string_view value);
std::string http_date_now();
std::int64_t unix_time();
std::string format_duration(std::chrono::seconds seconds);
std::string read_file_binary(const std::string& path);
bool path_is_safe_relative(std::string_view path);
std::string replace_all(std::string value, std::string_view needle, std::string_view replacement);
std::string regex_escape(std::string_view value);

struct ParsedUrl {
    std::string scheme;
    std::string host;
    std::string port;
    std::string path;
    std::string query;
    std::string authority;
};

ParsedUrl parse_url(std::string_view url);
std::string build_url(const ParsedUrl& parsed);
std::string rewrite_url_host_port(std::string_view url, std::string_view host, std::string_view port);

void log_line(const std::string& level, const std::string& message);

} // namespace httpace
