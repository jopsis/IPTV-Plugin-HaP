#include "httpaceproxycpp/http_server.hpp"
#include "httpaceproxycpp/util.hpp"

#include <arpa/inet.h>
#include <cerrno>
#include <csignal>
#include <cstring>
#include <iostream>
#include <netdb.h>
#include <netinet/in.h>
#include <sstream>
#include <stdexcept>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
#include <unistd.h>

namespace httpace {
namespace {

void close_fd(int& fd) {
    if (fd >= 0) {
        ::close(fd);
        fd = -1;
    }
}

bool send_fd_all(int fd, const void* data, std::size_t size) {
    const char* ptr = static_cast<const char*>(data);
    while (size > 0) {
#ifdef MSG_NOSIGNAL
        ssize_t sent = ::send(fd, ptr, size, MSG_NOSIGNAL);
#else
        ssize_t sent = ::send(fd, ptr, size, 0);
#endif
        if (sent <= 0) return false;
        ptr += sent;
        size -= static_cast<std::size_t>(sent);
    }
    return true;
}

std::string read_until_headers(int fd) {
    std::string buffer;
    buffer.reserve(8192);
    char tmp[4096];
    while (buffer.find("\r\n\r\n") == std::string::npos) {
        ssize_t n = ::recv(fd, tmp, sizeof(tmp), 0);
        if (n <= 0) return "";
        buffer.append(tmp, static_cast<std::size_t>(n));
        if (buffer.size() > 1024 * 128) return "";
    }
    return buffer;
}

} // namespace

std::string HttpRequest::header(const std::string& name, const std::string& fallback) const {
    auto it = headers.find(lower(name));
    return it == headers.end() ? fallback : it->second;
}

ClientConnection::ClientConnection(int fd) : fd_(fd) {}
ClientConnection::~ClientConnection() { close(); }

bool ClientConnection::send_all(const void* data, std::size_t size) {
    return fd_ >= 0 && send_fd_all(fd_, data, size);
}

bool ClientConnection::send_text(const std::string& value) {
    return send_all(value.data(), value.size());
}

bool ClientConnection::send_response_headers(int status, const std::string& reason,
                                             const std::map<std::string, std::string>& headers) {
    std::ostringstream out;
    out << "HTTP/1.1 " << status << " " << reason << "\r\n";
    for (const auto& [k, v] : headers) out << k << ": " << v << "\r\n";
    out << "\r\n";
    auto data = out.str();
    return send_text(data);
}

void ClientConnection::close() { close_fd(fd_); }

HttpServer::HttpServer(std::string host, int port, HttpHandler handler)
    : host_(std::move(host)), port_(port), handler_(std::move(handler)) {}

HttpServer::~HttpServer() { stop(); join(); }

void HttpServer::start() {
    if (running_) return;
#ifndef _WIN32
    std::signal(SIGPIPE, SIG_IGN);
#endif
    listen_fd_ = ::socket(AF_INET, SOCK_STREAM, 0);
    if (listen_fd_ < 0) throw std::runtime_error("socket failed");
    int yes = 1;
    setsockopt(listen_fd_, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(port_));
    if (host_.empty() || host_ == "0.0.0.0") addr.sin_addr.s_addr = INADDR_ANY;
    else if (::inet_pton(AF_INET, host_.c_str(), &addr.sin_addr) != 1) {
        close_fd(listen_fd_);
        throw std::runtime_error("invalid listen host: " + host_);
    }
    if (::bind(listen_fd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        auto err = std::string(std::strerror(errno));
        close_fd(listen_fd_);
        throw std::runtime_error("bind failed: " + err);
    }
    if (::listen(listen_fd_, 128) < 0) {
        auto err = std::string(std::strerror(errno));
        close_fd(listen_fd_);
        throw std::runtime_error("listen failed: " + err);
    }
    running_ = true;
    accept_thread_ = std::thread(&HttpServer::accept_loop, this);
}

void HttpServer::stop() {
    running_ = false;
    if (listen_fd_ >= 0) ::shutdown(listen_fd_, SHUT_RDWR);
    close_fd(listen_fd_);
}

void HttpServer::join() {
    if (accept_thread_.joinable()) accept_thread_.join();
}

void HttpServer::accept_loop() {
    while (running_) {
        sockaddr_in client{};
        socklen_t len = sizeof(client);
        int fd = ::accept(listen_fd_, reinterpret_cast<sockaddr*>(&client), &len);
        if (fd < 0) {
            if (running_) log_line("ERROR", "accept failed");
            continue;
        }
        char ip[INET_ADDRSTRLEN] = {0};
        ::inet_ntop(AF_INET, &client.sin_addr, ip, sizeof(ip));
        if (client_send_timeout_ > 0) {
            timeval tv{client_send_timeout_, 0};
            ::setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
        }
        std::thread(&HttpServer::handle_client, this, fd, std::string(ip)).detach();
    }
}

void HttpServer::handle_client(int client_fd, std::string client_ip) {
    ClientConnection connection(client_fd);
    try {
        HttpRequest request;
        if (!parse_http_request(client_fd, client_ip, request)) return;
        handler_(request, connection);
    } catch (const std::exception& e) {
        send_simple_response(connection, 500, "text/plain", e.what());
    }
}

std::string status_reason(int status) {
    switch (status) {
        case 200: return "OK";
        case 304: return "Not Modified";
        case 400: return "Bad Request";
        case 401: return "Unauthorized";
        case 403: return "Forbidden";
        case 404: return "Not Found";
        case 500: return "Internal Server Error";
        case 501: return "Not Implemented";
        case 503: return "Service Unavailable";
        default: return "Status";
    }
}

bool parse_http_request(int fd, const std::string& client_ip, HttpRequest& request) {
    auto raw = read_until_headers(fd);
    if (raw.empty()) return false;
    auto header_end = raw.find("\r\n\r\n");
    auto head = raw.substr(0, header_end);
    std::istringstream in(head);
    std::string request_line;
    std::getline(in, request_line);
    request_line = trim(request_line);
    auto parts = split(request_line, ' ', false);
    if (parts.size() < 3) return false;
    request.method = parts[0];
    request.target = parts[1];
    request.version = parts[2];
    request.client_ip = client_ip;
    auto q = request.target.find('?');
    request.path = q == std::string::npos ? request.target : request.target.substr(0, q);
    request.query = q == std::string::npos ? "" : request.target.substr(q + 1);

    std::string line;
    while (std::getline(in, line)) {
        line = trim(line);
        if (line.empty()) continue;
        auto colon = line.find(':');
        if (colon == std::string::npos) continue;
        request.headers[lower(trim(line.substr(0, colon)))] = trim(line.substr(colon + 1));
    }
    return true;
}

void send_simple_response(ClientConnection& connection, int status, const std::string& content_type,
                          const std::string& body,
                          std::map<std::string, std::string> extra_headers) {
    extra_headers["Content-Type"] = content_type;
    extra_headers["Content-Length"] = std::to_string(body.size());
    extra_headers["Connection"] = "close";
    connection.send_response_headers(status, status_reason(status), extra_headers);
    if (!body.empty()) connection.send_text(body);
}

} // namespace httpace
