#include "httpaceproxycpp/util.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <chrono>
#include <cstring>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <regex>
#include <sstream>

#ifdef HTTPACEPROXYCPP_ANDROID
#include <android/log.h>
#endif

namespace httpace {
namespace {

std::string hex_digest(const std::vector<std::uint8_t>& bytes) {
    std::ostringstream out;
    out << std::hex << std::setfill('0');
    for (auto b : bytes) out << std::setw(2) << static_cast<int>(b);
    return out.str();
}

std::uint32_t left_rotate(std::uint32_t value, std::uint32_t bits) {
    return (value << bits) | (value >> (32 - bits));
}

std::uint32_t read_be32(const std::vector<std::uint8_t>& data, std::size_t offset) {
    return (static_cast<std::uint32_t>(data[offset]) << 24) |
           (static_cast<std::uint32_t>(data[offset + 1]) << 16) |
           (static_cast<std::uint32_t>(data[offset + 2]) << 8) |
           static_cast<std::uint32_t>(data[offset + 3]);
}

std::uint32_t read_le32(const std::vector<std::uint8_t>& data, std::size_t offset) {
    return static_cast<std::uint32_t>(data[offset]) |
           (static_cast<std::uint32_t>(data[offset + 1]) << 8) |
           (static_cast<std::uint32_t>(data[offset + 2]) << 16) |
           (static_cast<std::uint32_t>(data[offset + 3]) << 24);
}

void append_le32(std::vector<std::uint8_t>& out, std::uint32_t value) {
    out.push_back(static_cast<std::uint8_t>(value & 0xff));
    out.push_back(static_cast<std::uint8_t>((value >> 8) & 0xff));
    out.push_back(static_cast<std::uint8_t>((value >> 16) & 0xff));
    out.push_back(static_cast<std::uint8_t>((value >> 24) & 0xff));
}

} // namespace

std::string trim(std::string_view value) {
    std::size_t begin = 0;
    while (begin < value.size() && std::isspace(static_cast<unsigned char>(value[begin]))) ++begin;
    std::size_t end = value.size();
    while (end > begin && std::isspace(static_cast<unsigned char>(value[end - 1]))) --end;
    return std::string(value.substr(begin, end - begin));
}

std::string lower(std::string_view value) {
    std::string out(value);
    std::transform(out.begin(), out.end(), out.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return out;
}

bool starts_with(std::string_view value, std::string_view prefix) {
    return value.size() >= prefix.size() && value.substr(0, prefix.size()) == prefix;
}

bool ends_with(std::string_view value, std::string_view suffix) {
    return value.size() >= suffix.size() && value.substr(value.size() - suffix.size()) == suffix;
}

std::vector<std::string> split(std::string_view value, char delimiter, bool keep_empty) {
    std::vector<std::string> result;
    std::size_t start = 0;
    while (start <= value.size()) {
        auto pos = value.find(delimiter, start);
        if (pos == std::string_view::npos) pos = value.size();
        if (keep_empty || pos > start) result.emplace_back(value.substr(start, pos - start));
        if (pos == value.size()) break;
        start = pos + 1;
    }
    return result;
}

std::string join(const std::vector<std::string>& values, std::string_view delimiter) {
    std::ostringstream out;
    for (std::size_t i = 0; i < values.size(); ++i) {
        if (i) out << delimiter;
        out << values[i];
    }
    return out.str();
}

std::string url_decode(std::string_view value) {
    std::string out;
    out.reserve(value.size());
    for (std::size_t i = 0; i < value.size(); ++i) {
        char c = value[i];
        if (c == '%' && i + 2 < value.size()) {
            auto hex = std::string(value.substr(i + 1, 2));
            char* end = nullptr;
            long parsed = std::strtol(hex.c_str(), &end, 16);
            if (end && *end == '\0') {
                out.push_back(static_cast<char>(parsed));
                i += 2;
            } else {
                out.push_back(c);
            }
        } else if (c == '+') {
            out.push_back(' ');
        } else {
            out.push_back(c);
        }
    }
    return out;
}

std::string url_encode(std::string_view value, std::string_view safe) {
    static constexpr char hex[] = "0123456789ABCDEF";
    std::string out;
    for (unsigned char c : value) {
        bool ok = std::isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~' || safe.find(static_cast<char>(c)) != std::string_view::npos;
        if (ok) {
            out.push_back(static_cast<char>(c));
        } else {
            out.push_back('%');
            out.push_back(hex[c >> 4]);
            out.push_back(hex[c & 15]);
        }
    }
    return out;
}

std::map<std::string, std::string> parse_query(std::string_view query) {
    std::map<std::string, std::string> result;
    for (const auto& part : split(query, '&', false)) {
        auto pos = part.find('=');
        std::string key = url_decode(pos == std::string::npos ? part : part.substr(0, pos));
        std::string value = pos == std::string::npos ? "" : url_decode(std::string_view(part).substr(pos + 1));
        if (!key.empty() && !result.contains(key)) result[key] = value;
    }
    return result;
}

std::string query_get(std::string_view query, std::string_view key, std::string_view fallback) {
    auto values = parse_query(query);
    auto it = values.find(std::string(key));
    return it == values.end() ? std::string(fallback) : it->second;
}

std::string shell_quote_for_log(std::string_view value) {
    return "'" + replace_all(std::string(value), "'", "'\\''") + "'";
}

std::string basename_no_ext(std::string_view path) {
    std::string s(path);
    auto slash = s.find_last_of("/\\");
    auto base = slash == std::string::npos ? s : s.substr(slash + 1);
    auto dot = base.find_last_of('.');
    return dot == std::string::npos ? base : base.substr(0, dot);
}

std::string extension_of(std::string_view path) {
    auto s = std::string(path);
    auto slash = s.find_last_of("/\\");
    auto dot = s.find_last_of('.');
    if (dot == std::string::npos || (slash != std::string::npos && dot < slash)) return "";
    return lower(std::string_view(s).substr(dot + 1));
}

std::string mime_type_for_path(std::string_view path) {
    auto ext = extension_of(path);
    if (ext == "html" || ext == "htm") return "text/html; charset=utf-8";
    if (ext == "js") return "text/javascript; charset=utf-8";
    if (ext == "css") return "text/css; charset=utf-8";
    if (ext == "json") return "application/json; charset=utf-8";
    if (ext == "m3u" || ext == "m3u8") return "audio/mpegurl; charset=utf-8";
    if (ext == "png") return "image/png";
    if (ext == "jpg" || ext == "jpeg") return "image/jpeg";
    if (ext == "svg") return "image/svg+xml";
    if (ext == "ts" || ext == "mpegts") return "video/MP2T";
    if (ext == "mp4") return "video/mp4";
    if (ext == "mkv") return "video/x-matroska";
    if (ext == "avi") return "video/x-msvideo";
    return "application/octet-stream";
}

std::string sha1_hex(std::string_view value) {
    std::vector<std::uint8_t> data(value.begin(), value.end());
    std::uint64_t bit_len = static_cast<std::uint64_t>(data.size()) * 8;
    data.push_back(0x80);
    while ((data.size() % 64) != 56) data.push_back(0);
    for (int i = 7; i >= 0; --i) data.push_back(static_cast<std::uint8_t>((bit_len >> (i * 8)) & 0xff));

    std::uint32_t h0 = 0x67452301;
    std::uint32_t h1 = 0xEFCDAB89;
    std::uint32_t h2 = 0x98BADCFE;
    std::uint32_t h3 = 0x10325476;
    std::uint32_t h4 = 0xC3D2E1F0;

    for (std::size_t chunk = 0; chunk < data.size(); chunk += 64) {
        std::array<std::uint32_t, 80> w{};
        for (int i = 0; i < 16; ++i) w[i] = read_be32(data, chunk + i * 4);
        for (int i = 16; i < 80; ++i) w[i] = left_rotate(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
        auto a = h0, b = h1, c = h2, d = h3, e = h4;
        for (int i = 0; i < 80; ++i) {
            std::uint32_t f = 0, k = 0;
            if (i < 20) { f = (b & c) | ((~b) & d); k = 0x5A827999; }
            else if (i < 40) { f = b ^ c ^ d; k = 0x6ED9EBA1; }
            else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDC; }
            else { f = b ^ c ^ d; k = 0xCA62C1D6; }
            auto temp = left_rotate(a, 5) + f + e + k + w[i];
            e = d; d = c; c = left_rotate(b, 30); b = a; a = temp;
        }
        h0 += a; h1 += b; h2 += c; h3 += d; h4 += e;
    }

    std::vector<std::uint8_t> digest;
    for (auto h : {h0, h1, h2, h3, h4}) {
        digest.push_back(static_cast<std::uint8_t>((h >> 24) & 0xff));
        digest.push_back(static_cast<std::uint8_t>((h >> 16) & 0xff));
        digest.push_back(static_cast<std::uint8_t>((h >> 8) & 0xff));
        digest.push_back(static_cast<std::uint8_t>(h & 0xff));
    }
    return hex_digest(digest);
}

std::string md5_hex(std::string_view value) {
    static constexpr std::array<std::uint32_t, 64> s = {
        7,12,17,22, 7,12,17,22, 7,12,17,22, 7,12,17,22,
        5,9,14,20, 5,9,14,20, 5,9,14,20, 5,9,14,20,
        4,11,16,23, 4,11,16,23, 4,11,16,23, 4,11,16,23,
        6,10,15,21, 6,10,15,21, 6,10,15,21, 6,10,15,21
    };
    static constexpr std::array<std::uint32_t, 64> k = {
        0xd76aa478,0xe8c7b756,0x242070db,0xc1bdceee,0xf57c0faf,0x4787c62a,0xa8304613,0xfd469501,
        0x698098d8,0x8b44f7af,0xffff5bb1,0x895cd7be,0x6b901122,0xfd987193,0xa679438e,0x49b40821,
        0xf61e2562,0xc040b340,0x265e5a51,0xe9b6c7aa,0xd62f105d,0x02441453,0xd8a1e681,0xe7d3fbc8,
        0x21e1cde6,0xc33707d6,0xf4d50d87,0x455a14ed,0xa9e3e905,0xfcefa3f8,0x676f02d9,0x8d2a4c8a,
        0xfffa3942,0x8771f681,0x6d9d6122,0xfde5380c,0xa4beea44,0x4bdecfa9,0xf6bb4b60,0xbebfbc70,
        0x289b7ec6,0xeaa127fa,0xd4ef3085,0x04881d05,0xd9d4d039,0xe6db99e5,0x1fa27cf8,0xc4ac5665,
        0xf4292244,0x432aff97,0xab9423a7,0xfc93a039,0x655b59c3,0x8f0ccc92,0xffeff47d,0x85845dd1,
        0x6fa87e4f,0xfe2ce6e0,0xa3014314,0x4e0811a1,0xf7537e82,0xbd3af235,0x2ad7d2bb,0xeb86d391
    };

    std::vector<std::uint8_t> data(value.begin(), value.end());
    std::uint64_t bit_len = static_cast<std::uint64_t>(data.size()) * 8;
    data.push_back(0x80);
    while ((data.size() % 64) != 56) data.push_back(0);
    for (int i = 0; i < 8; ++i) data.push_back(static_cast<std::uint8_t>((bit_len >> (8 * i)) & 0xff));

    std::uint32_t a0 = 0x67452301;
    std::uint32_t b0 = 0xefcdab89;
    std::uint32_t c0 = 0x98badcfe;
    std::uint32_t d0 = 0x10325476;

    for (std::size_t chunk = 0; chunk < data.size(); chunk += 64) {
        std::array<std::uint32_t, 16> m{};
        for (int i = 0; i < 16; ++i) m[i] = read_le32(data, chunk + i * 4);
        auto a = a0, b = b0, c = c0, d = d0;
        for (int i = 0; i < 64; ++i) {
            std::uint32_t f = 0, g = 0;
            if (i < 16) { f = (b & c) | ((~b) & d); g = i; }
            else if (i < 32) { f = (d & b) | ((~d) & c); g = (5 * i + 1) % 16; }
            else if (i < 48) { f = b ^ c ^ d; g = (3 * i + 5) % 16; }
            else { f = c ^ (b | (~d)); g = (7 * i) % 16; }
            auto temp = d;
            d = c;
            c = b;
            b = b + left_rotate(a + f + k[i] + m[g], s[i]);
            a = temp;
        }
        a0 += a; b0 += b; c0 += c; d0 += d;
    }

    std::vector<std::uint8_t> digest;
    append_le32(digest, a0);
    append_le32(digest, b0);
    append_le32(digest, c0);
    append_le32(digest, d0);
    return hex_digest(digest);
}

std::string http_date_now() {
    auto now = std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());
    std::tm tm{};
#ifdef _WIN32
    gmtime_s(&tm, &now);
#else
    gmtime_r(&now, &tm);
#endif
    char buffer[128];
    std::strftime(buffer, sizeof(buffer), "%a, %d %b %Y %H:%M:%S GMT", &tm);
    return buffer;
}

std::int64_t unix_time() {
    return std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
}

std::string format_duration(std::chrono::seconds seconds) {
    auto total = seconds.count();
    auto h = total / 3600;
    auto m = (total % 3600) / 60;
    auto s = total % 60;
    std::ostringstream out;
    out << std::setfill('0') << std::setw(2) << h << ":" << std::setw(2) << m << ":" << std::setw(2) << s;
    return out.str();
}

std::string read_file_binary(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) throw std::runtime_error("cannot open file: " + path);
    std::ostringstream out;
    out << in.rdbuf();
    return out.str();
}

bool path_is_safe_relative(std::string_view path) {
    if (path.empty() || path.front() == '/') return false;
    for (const auto& part : split(path, '/', false)) {
        if (part == "." || part == "..") return false;
    }
    return true;
}

std::string replace_all(std::string value, std::string_view needle, std::string_view replacement) {
    if (needle.empty()) return value;
    std::size_t pos = 0;
    while ((pos = value.find(needle, pos)) != std::string::npos) {
        value.replace(pos, needle.size(), replacement);
        pos += replacement.size();
    }
    return value;
}

std::string regex_escape(std::string_view value) {
    static const std::regex re(R"([.^$|()\[\]{}*+?\\])");
    return std::regex_replace(std::string(value), re, R"(\$&)");
}

ParsedUrl parse_url(std::string_view url) {
    ParsedUrl parsed;
    std::string s(url);
    auto scheme_pos = s.find("://");
    if (scheme_pos != std::string::npos) {
        parsed.scheme = lower(std::string_view(s).substr(0, scheme_pos));
        auto rest = s.substr(scheme_pos + 3);
        auto slash = rest.find('/');
        parsed.authority = slash == std::string::npos ? rest : rest.substr(0, slash);
        auto hostport = parsed.authority;
        auto colon = hostport.rfind(':');
        if (colon != std::string::npos && hostport.find(']') == std::string::npos) {
            parsed.host = hostport.substr(0, colon);
            parsed.port = hostport.substr(colon + 1);
        } else {
            parsed.host = hostport;
        }
        auto path_query = slash == std::string::npos ? "/" : rest.substr(slash);
        auto q = path_query.find('?');
        parsed.path = q == std::string::npos ? path_query : path_query.substr(0, q);
        parsed.query = q == std::string::npos ? "" : path_query.substr(q + 1);
    } else {
        auto q = s.find('?');
        parsed.path = q == std::string::npos ? s : s.substr(0, q);
        parsed.query = q == std::string::npos ? "" : s.substr(q + 1);
    }
    if (parsed.path.empty()) parsed.path = "/";
    return parsed;
}

std::string build_url(const ParsedUrl& parsed) {
    std::ostringstream out;
    if (!parsed.scheme.empty()) {
        out << parsed.scheme << "://";
        if (!parsed.authority.empty()) out << parsed.authority;
        else {
            out << parsed.host;
            if (!parsed.port.empty()) out << ":" << parsed.port;
        }
    }
    out << (parsed.path.empty() ? "/" : parsed.path);
    if (!parsed.query.empty()) out << "?" << parsed.query;
    return out.str();
}

std::string rewrite_url_host_port(std::string_view url, std::string_view host, std::string_view port) {
    auto parsed = parse_url(url);
    parsed.host = std::string(host);
    parsed.port = std::string(port);
    parsed.authority = parsed.host + ":" + parsed.port;
    return build_url(parsed);
}

void log_line(const std::string& level, const std::string& message) {
    auto now = std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());
    std::tm tm{};
#ifdef _WIN32
    localtime_s(&tm, &now);
#else
    localtime_r(&now, &tm);
#endif
    char buffer[64];
    std::strftime(buffer, sizeof(buffer), "%Y-%m-%d %H:%M:%S", &tm);
#ifdef HTTPACEPROXYCPP_ANDROID
    int priority = ANDROID_LOG_INFO;
    if (level == "ERROR") priority = ANDROID_LOG_ERROR;
    else if (level == "WARNING") priority = ANDROID_LOG_WARN;
    else if (level == "DEBUG") priority = ANDROID_LOG_DEBUG;
    __android_log_print(priority, "HTTPAceProxyCPP", "[%s] %s %s", buffer, level.c_str(), message.c_str());
#endif
    std::cerr << "[" << buffer << "] " << level << " " << message << std::endl;
}

} // namespace httpace
