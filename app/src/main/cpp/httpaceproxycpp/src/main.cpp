#include "httpaceproxycpp/config.hpp"
#include "httpaceproxycpp/proxy.hpp"
#include "httpaceproxycpp/util.hpp"

#include <atomic>
#include <csignal>
#include <exception>

namespace {
std::atomic<httpace::Proxy*> g_proxy{nullptr};

void handle_signal(int) {
    if (auto* proxy = g_proxy.load()) proxy->stop();
}
} // namespace

int main(int argc, char** argv) {
    try {
        auto config = httpace::load_config(argc, argv);
        httpace::log_line("INFO", "HTTPAceProxyCPP starting");
        httpace::log_line("INFO", "AceStream engine " + config.ace_host + ":" + std::to_string(config.ace_api_port));
        httpace::Proxy proxy(config);
        g_proxy.store(&proxy);
        std::signal(SIGTERM, handle_signal);
        std::signal(SIGINT, handle_signal);
        proxy.start();
        g_proxy.store(nullptr);
        return 0;
    } catch (const std::exception& e) {
        httpace::log_line("ERROR", e.what());
        return 1;
    }
}
