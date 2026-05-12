#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <string>
#include <thread>
#include <vector>

namespace httpace {

struct HttpRequest {
    std::string method;
    std::string target;
    std::string path;
    std::string query;
    std::string version;
    std::map<std::string, std::string> headers;
    std::string body;
    std::string client_ip;

    std::string header(const std::string& name, const std::string& fallback = "") const;
};

struct HttpResponse {
    int status = 200;
    std::string reason = "OK";
    std::map<std::string, std::string> headers;
    std::string body;
    bool close = true;
    bool raw_sent = false;
};

class ClientConnection {
public:
    explicit ClientConnection(int fd);
    ~ClientConnection();

    ClientConnection(const ClientConnection&) = delete;
    ClientConnection& operator=(const ClientConnection&) = delete;

    int fd() const { return fd_; }
    bool send_all(const void* data, std::size_t size);
    bool send_text(const std::string& value);
    bool send_response_headers(int status, const std::string& reason,
                               const std::map<std::string, std::string>& headers);
    void close();

private:
    int fd_ = -1;
};

using HttpHandler = std::function<void(const HttpRequest&, ClientConnection&)>;

class HttpServer {
public:
    HttpServer(std::string host, int port, HttpHandler handler);
    ~HttpServer();

    void start();
    void stop();
    void join();
    void set_client_send_timeout(int seconds) { client_send_timeout_ = seconds; }

private:
    void accept_loop();
    void handle_client(int client_fd, std::string client_ip);

    std::string host_;
    int port_;
    HttpHandler handler_;
    int listen_fd_ = -1;
    int client_send_timeout_ = 0;
    std::atomic<bool> running_{false};
    std::thread accept_thread_;
};

std::string status_reason(int status);
bool parse_http_request(int fd, const std::string& client_ip, HttpRequest& request);
void send_simple_response(ClientConnection& connection, int status, const std::string& content_type,
                          const std::string& body,
                          std::map<std::string, std::string> extra_headers = {});

} // namespace httpace
