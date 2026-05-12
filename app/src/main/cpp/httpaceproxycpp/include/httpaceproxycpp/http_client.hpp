#pragma once

#include <atomic>
#include <functional>
#include <map>
#include <string>

namespace httpace {

struct HttpClientResponse {
    long status = 0;
    std::string url;
    std::string body;
    std::map<std::string, std::string> headers;
};

class HttpClient {
public:
    HttpClient();
    ~HttpClient();

    HttpClientResponse get(const std::string& url,
                           const std::map<std::string, std::string>& headers = {},
                           long timeout_seconds = 30,
                           bool follow_redirects = true) const;

    bool stream(const std::string& url,
                const std::function<bool(const char*, std::size_t)>& on_chunk,
                const std::atomic<bool>& running,
                long connect_timeout_seconds,
                long read_timeout_seconds,
                long buffer_size = 1048576) const;
};

} // namespace httpace
