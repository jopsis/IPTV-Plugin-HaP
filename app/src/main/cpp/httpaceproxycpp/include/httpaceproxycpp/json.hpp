#pragma once

#include <map>
#include <optional>
#include <string>
#include <variant>
#include <vector>

namespace httpace {

class Json {
public:
    using array = std::vector<Json>;
    using object = std::map<std::string, Json>;

    Json();
    Json(std::nullptr_t);
    Json(bool value);
    Json(double value);
    Json(int value);
    Json(std::string value);
    Json(const char* value);
    Json(array value);
    Json(object value);

    bool is_null() const;
    bool is_bool() const;
    bool is_number() const;
    bool is_string() const;
    bool is_array() const;
    bool is_object() const;

    bool as_bool(bool fallback = false) const;
    double as_number(double fallback = 0.0) const;
    std::string as_string(std::string fallback = "") const;
    const array& as_array() const;
    const object& as_object() const;

    const Json& operator[](const std::string& key) const;
    const Json& operator[](std::size_t index) const;

    bool contains(const std::string& key) const;
    std::string dump(int indent = -1) const;

    static Json parse(const std::string& text);

private:
    std::variant<std::nullptr_t, bool, double, std::string, array, object> value_;
};

Json::object object(std::initializer_list<std::pair<const std::string, Json>> values);
Json::array array(std::initializer_list<Json> values);
std::string json_escape(const std::string& value);

} // namespace httpace
