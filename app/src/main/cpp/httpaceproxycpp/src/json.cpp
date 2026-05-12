#include "httpaceproxycpp/json.hpp"

#include <cctype>
#include <cmath>
#include <iomanip>
#include <sstream>
#include <stdexcept>

namespace httpace {
namespace {

const Json null_json;

class Parser {
public:
    explicit Parser(const std::string& text) : text_(text) {}

    Json parse() {
        skip_ws();
        Json value = parse_value();
        skip_ws();
        if (pos_ != text_.size()) throw std::runtime_error("extra data after JSON value");
        return value;
    }

private:
    Json parse_value() {
        skip_ws();
        if (pos_ >= text_.size()) throw std::runtime_error("unexpected end of JSON");
        char c = text_[pos_];
        if (c == '"') return Json(parse_string());
        if (c == '{') return Json(parse_object());
        if (c == '[') return Json(parse_array());
        if (c == 't') { consume("true"); return Json(true); }
        if (c == 'f') { consume("false"); return Json(false); }
        if (c == 'n') { consume("null"); return Json(nullptr); }
        return Json(parse_number());
    }

    Json::object parse_object() {
        expect('{');
        Json::object out;
        skip_ws();
        if (peek('}')) { ++pos_; return out; }
        while (true) {
            skip_ws();
            auto key = parse_string();
            skip_ws();
            expect(':');
            out[key] = parse_value();
            skip_ws();
            if (peek('}')) { ++pos_; break; }
            expect(',');
        }
        return out;
    }

    Json::array parse_array() {
        expect('[');
        Json::array out;
        skip_ws();
        if (peek(']')) { ++pos_; return out; }
        while (true) {
            out.push_back(parse_value());
            skip_ws();
            if (peek(']')) { ++pos_; break; }
            expect(',');
        }
        return out;
    }

    std::string parse_string() {
        expect('"');
        std::string out;
        while (pos_ < text_.size()) {
            char c = text_[pos_++];
            if (c == '"') return out;
            if (c == '\\') {
                if (pos_ >= text_.size()) throw std::runtime_error("invalid JSON escape");
                char e = text_[pos_++];
                switch (e) {
                    case '"': out.push_back('"'); break;
                    case '\\': out.push_back('\\'); break;
                    case '/': out.push_back('/'); break;
                    case 'b': out.push_back('\b'); break;
                    case 'f': out.push_back('\f'); break;
                    case 'n': out.push_back('\n'); break;
                    case 'r': out.push_back('\r'); break;
                    case 't': out.push_back('\t'); break;
                    case 'u': {
                        if (pos_ + 4 > text_.size()) throw std::runtime_error("short unicode escape");
                        auto hex = text_.substr(pos_, 4);
                        pos_ += 4;
                        char* end = nullptr;
                        long cp = std::strtol(hex.c_str(), &end, 16);
                        if (!end || *end != '\0') throw std::runtime_error("bad unicode escape");
                        if (cp < 0x80) out.push_back(static_cast<char>(cp));
                        else if (cp < 0x800) {
                            out.push_back(static_cast<char>(0xc0 | (cp >> 6)));
                            out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
                        } else {
                            out.push_back(static_cast<char>(0xe0 | (cp >> 12)));
                            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
                            out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
                        }
                        break;
                    }
                    default: throw std::runtime_error("unknown JSON escape");
                }
            } else {
                out.push_back(c);
            }
        }
        throw std::runtime_error("unterminated JSON string");
    }

    double parse_number() {
        auto start = pos_;
        if (peek('-')) ++pos_;
        while (pos_ < text_.size() && std::isdigit(static_cast<unsigned char>(text_[pos_]))) ++pos_;
        if (peek('.')) {
            ++pos_;
            while (pos_ < text_.size() && std::isdigit(static_cast<unsigned char>(text_[pos_]))) ++pos_;
        }
        if (peek('e') || peek('E')) {
            ++pos_;
            if (peek('+') || peek('-')) ++pos_;
            while (pos_ < text_.size() && std::isdigit(static_cast<unsigned char>(text_[pos_]))) ++pos_;
        }
        return std::stod(text_.substr(start, pos_ - start));
    }

    void skip_ws() {
        while (pos_ < text_.size() && std::isspace(static_cast<unsigned char>(text_[pos_]))) ++pos_;
    }

    bool peek(char c) const { return pos_ < text_.size() && text_[pos_] == c; }

    void expect(char c) {
        if (!peek(c)) throw std::runtime_error(std::string("expected '") + c + "'");
        ++pos_;
    }

    void consume(const char* literal) {
        std::string lit(literal);
        if (text_.substr(pos_, lit.size()) != lit) throw std::runtime_error("expected " + lit);
        pos_ += lit.size();
    }

    const std::string& text_;
    std::size_t pos_ = 0;
};

void dump_impl(const Json& value, std::ostringstream& out, int indent, int depth) {
    auto pad = [&](int extra = 0) {
        if (indent >= 0) out << "\n" << std::string((depth + extra) * indent, ' ');
    };
    if (value.is_null()) out << "null";
    else if (value.is_bool()) out << (value.as_bool() ? "true" : "false");
    else if (value.is_number()) {
        double n = value.as_number();
        if (std::floor(n) == n) out << static_cast<long long>(n);
        else out << n;
    } else if (value.is_string()) {
        out << '"' << json_escape(value.as_string()) << '"';
    } else if (value.is_array()) {
        out << "[";
        const auto& arr = value.as_array();
        for (std::size_t i = 0; i < arr.size(); ++i) {
            if (i) out << ",";
            pad(1);
            dump_impl(arr[i], out, indent, depth + 1);
        }
        if (!arr.empty()) pad();
        out << "]";
    } else {
        out << "{";
        const auto& obj = value.as_object();
        std::size_t i = 0;
        for (const auto& [k, v] : obj) {
            if (i++) out << ",";
            pad(1);
            out << '"' << json_escape(k) << "\":";
            if (indent >= 0) out << " ";
            dump_impl(v, out, indent, depth + 1);
        }
        if (!obj.empty()) pad();
        out << "}";
    }
}

} // namespace

Json::Json() : value_(nullptr) {}
Json::Json(std::nullptr_t) : value_(nullptr) {}
Json::Json(bool value) : value_(value) {}
Json::Json(double value) : value_(value) {}
Json::Json(int value) : value_(static_cast<double>(value)) {}
Json::Json(std::string value) : value_(std::move(value)) {}
Json::Json(const char* value) : value_(std::string(value ? value : "")) {}
Json::Json(array value) : value_(std::move(value)) {}
Json::Json(object value) : value_(std::move(value)) {}

bool Json::is_null() const { return std::holds_alternative<std::nullptr_t>(value_); }
bool Json::is_bool() const { return std::holds_alternative<bool>(value_); }
bool Json::is_number() const { return std::holds_alternative<double>(value_); }
bool Json::is_string() const { return std::holds_alternative<std::string>(value_); }
bool Json::is_array() const { return std::holds_alternative<array>(value_); }
bool Json::is_object() const { return std::holds_alternative<object>(value_); }

bool Json::as_bool(bool fallback) const { return is_bool() ? std::get<bool>(value_) : fallback; }
double Json::as_number(double fallback) const { return is_number() ? std::get<double>(value_) : fallback; }
std::string Json::as_string(std::string fallback) const { return is_string() ? std::get<std::string>(value_) : fallback; }
const Json::array& Json::as_array() const {
    static const array empty;
    return is_array() ? std::get<array>(value_) : empty;
}
const Json::object& Json::as_object() const {
    static const object empty;
    return is_object() ? std::get<object>(value_) : empty;
}

const Json& Json::operator[](const std::string& key) const {
    if (!is_object()) return null_json;
    auto& obj = std::get<object>(value_);
    auto it = obj.find(key);
    return it == obj.end() ? null_json : it->second;
}

const Json& Json::operator[](std::size_t index) const {
    if (!is_array()) return null_json;
    auto& arr = std::get<array>(value_);
    return index >= arr.size() ? null_json : arr[index];
}

bool Json::contains(const std::string& key) const {
    return is_object() && std::get<object>(value_).contains(key);
}

std::string Json::dump(int indent) const {
    std::ostringstream out;
    dump_impl(*this, out, indent, 0);
    return out.str();
}

Json Json::parse(const std::string& text) {
    return Parser(text).parse();
}

Json::object object(std::initializer_list<std::pair<const std::string, Json>> values) {
    return Json::object(values);
}

Json::array array(std::initializer_list<Json> values) {
    return Json::array(values);
}

std::string json_escape(const std::string& value) {
    std::ostringstream out;
    for (unsigned char c : value) {
        switch (c) {
            case '"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (c < 0x20) out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << static_cast<int>(c);
                else out << static_cast<char>(c);
        }
    }
    return out.str();
}

} // namespace httpace
