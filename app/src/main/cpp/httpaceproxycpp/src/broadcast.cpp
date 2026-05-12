#include "httpaceproxycpp/broadcast.hpp"
#include "httpaceproxycpp/util.hpp"

#include <algorithm>
#include <chrono>
#include <sstream>
#include <thread>

namespace httpace {

ChunkQueue::ChunkQueue(std::size_t max_chunks) : max_chunks_(std::max<std::size_t>(2, max_chunks)) {}

PushResult ChunkQueue::push(std::vector<char> chunk, std::chrono::milliseconds wait) {
    std::unique_lock<std::mutex> lock(mutex_);
    if (closed_) return PushResult::Closed;
    if (chunks_.size() >= max_chunks_ && wait.count() > 0) {
        cv_space_.wait_for(lock, wait, [&] {
            return closed_ || chunks_.size() < max_chunks_;
        });
        if (closed_) return PushResult::Closed;
    }
    PushResult result = PushResult::Ok;
    if (chunks_.size() >= max_chunks_) {
        chunks_.pop_front();
        result = PushResult::DroppedOldest;
    }
    chunks_.push_back(std::move(chunk));
    cv_data_.notify_one();
    return result;
}

bool ChunkQueue::pop(std::vector<char>& chunk) {
    std::unique_lock<std::mutex> lock(mutex_);
    cv_data_.wait(lock, [&] { return closed_ || !chunks_.empty(); });
    if (chunks_.empty()) return false;
    chunk = std::move(chunks_.front());
    chunks_.pop_front();
    cv_space_.notify_one();
    return true;
}

void ChunkQueue::close() {
    std::lock_guard<std::mutex> lock(mutex_);
    closed_ = true;
    cv_data_.notify_all();
    cv_space_.notify_all();
}

std::size_t ChunkQueue::size() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return chunks_.size();
}

Broadcast::Broadcast(std::string infohash, Config config, HttpClient& http_client,
                     std::map<std::string, std::string> start_params)
    : infohash_(std::move(infohash)),
      config_(std::move(config)),
      http_client_(http_client),
      start_params_(std::move(start_params)),
      ace_(std::make_shared<AceClient>(config_, "Broadcast_" + infohash_.substr(0, 8))) {
    ace_->authenticate();
}

Broadcast::~Broadcast() { stop(); }

std::shared_ptr<StreamClient> Broadcast::add_client(const std::string& client_ip,
                                                    const std::string& user_agent,
                                                    const std::string& channel_name,
                                                    const std::string& channel_icon) {
    auto client = std::make_shared<StreamClient>();
    client->session_id = std::to_string(reinterpret_cast<std::uintptr_t>(client.get()));
    client->client_ip = client_ip;
    client->user_agent = user_agent;
    client->channel_name = channel_name;
    client->channel_icon = channel_icon.empty() ? "http://static.acestream.net/sites/acestream/img/ACE-logo.png" : channel_icon;
    client->connection_time = unix_time();
    client->last_activity = client->connection_time;
    client->queue = std::make_shared<ChunkQueue>(static_cast<std::size_t>(std::max(2, config_.client_queue_size)));
    client->ace = ace_;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        clients_.push_back(client);
    }
    return client;
}

void Broadcast::remove_client(const std::shared_ptr<StreamClient>& client) {
    if (!client) return;
    client->queue->close();
    {
        std::lock_guard<std::mutex> lock(mutex_);
        clients_.erase(std::remove_if(clients_.begin(), clients_.end(), [&](const auto& weak) {
            auto locked = weak.lock();
            return !locked || locked == client;
        }), clients_.end());
    }
    if (client_count() == 0) stop();
}

std::size_t Broadcast::client_count() const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::size_t count = 0;
    for (const auto& weak : clients_) if (!weak.expired()) ++count;
    return count;
}

std::vector<std::shared_ptr<StreamClient>> Broadcast::clients() const {
    std::vector<std::shared_ptr<StreamClient>> out;
    std::lock_guard<std::mutex> lock(mutex_);
    for (const auto& weak : clients_) {
        if (auto client = weak.lock()) out.push_back(client);
    }
    return out;
}

void Broadcast::start_once() {
    bool expected = false;
    if (started_.compare_exchange_strong(expected, true)) {
        running_ = true;
        stream_thread_ = std::thread(&Broadcast::stream_loop, this);
    }
}

void Broadcast::stop() {
    bool expected = false;
    if (!stopped_.compare_exchange_strong(expected, true)) return;
    running_ = false;
    for (auto& client : clients()) client->queue->close();
    if (ace_) {
        try { ace_->stop_broadcast(); } catch (...) {}
        try { ace_->shutdown(); } catch (...) {}
    }
    if (stream_thread_.joinable() && stream_thread_.get_id() != std::this_thread::get_id()) stream_thread_.join();
}

void Broadcast::stream_loop() {
    try {
        auto started = ace_->start_broadcast(start_params_);
        auto url = rewrite_url_host_port(url_decode(started.url), config_.ace_host, std::to_string(config_.ace_http_port));
        log_line("INFO", "[" + infohash_.substr(0, 8) + "] stream URL " + url);
        if (ends_with(parse_url(url).path, ".m3u8")) stream_hls_url(url);
        else stream_http_url(url);
    } catch (const std::exception& e) {
        log_line("ERROR", "[" + infohash_.substr(0, 8) + "] stream failed: " + std::string(e.what()));
    }
    running_ = false;
    for (auto& client : clients()) client->queue->close();
}

void Broadcast::stream_http_url(const std::string& url) {
    http_client_.stream(url, [&](const char* data, std::size_t size) {
        broadcast_chunk(data, size);
        if (!running_ || client_count() == 0) {
            running_ = false;
            return false;
        }
        return true;
    }, running_, 5, config_.video_timeout, std::max(1, config_.curl_stream_buffer));
}

void Broadcast::stream_hls_url(const std::string& url) {
    std::vector<std::string> seen;
    while (running_ && client_count() > 0) {
        try {
            auto response = http_client_.get(url, {}, config_.video_timeout);
            auto base = parse_url(url);
            for (const auto& raw_line : split(response.body, '\n', false)) {
                auto line = trim(raw_line);
                if (line.empty() || starts_with(line, "#")) continue;
                std::string segment = line;
                if (!starts_with(segment, "http://") && !starts_with(segment, "https://")) {
                    auto base_path = base.path;
                    auto slash = base_path.find_last_of('/');
                    base_path = slash == std::string::npos ? "/" : base_path.substr(0, slash + 1);
                    segment = base.scheme + "://" + base.authority + base_path + segment;
                }
                if (std::find(seen.begin(), seen.end(), segment) != seen.end()) continue;
                stream_http_url(segment);
                seen.push_back(segment);
                if (seen.size() > 50) seen.erase(seen.begin());
                if (!running_ || client_count() == 0) break;
            }
        } catch (const std::exception& e) {
            log_line("ERROR", "HLS refresh failed: " + std::string(e.what()));
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }
    }
}

void Broadcast::broadcast_chunk(const char* data, std::size_t size) {
    std::vector<char> chunk(data, data + size);
    auto write_timeout = std::max(1, config_.client_write_timeout);
    auto wait = std::chrono::milliseconds(write_timeout * 1000 / 4);
    auto now = unix_time();
    for (auto& client : clients()) {
        auto result = client->queue->push(chunk, wait);
        if (result == PushResult::Closed) continue;
        if (result == PushResult::DroppedOldest) {
            client->dropped_chunks.fetch_add(1, std::memory_order_relaxed);
            if (now - client->last_activity.load() > write_timeout) {
                if (!client->stuck_logged.exchange(true)) {
                    log_line("WARNING", "[" + client->client_ip + "] client too slow ("
                             + std::to_string(client->dropped_chunks.load()) + " chunks dropped in "
                             + std::to_string(write_timeout) + "s), closing");
                    client->queue->close();
                }
            }
        } else {
            client->dropped_chunks.store(0, std::memory_order_relaxed);
        }
    }
}

BroadcastManager::BroadcastManager(Config config, HttpClient& http_client)
    : config_(std::move(config)), http_client_(http_client) {}

BroadcastManager::~BroadcastManager() { stop_all(); }

std::shared_ptr<Broadcast> BroadcastManager::get_or_create(const std::string& infohash,
                                                           const std::map<std::string, std::string>& params) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = broadcasts_.find(infohash);
    if (it != broadcasts_.end()) return it->second;
    auto broadcast = std::make_shared<Broadcast>(infohash, config_, http_client_, params);
    broadcasts_[infohash] = broadcast;
    return broadcast;
}

std::shared_ptr<Broadcast> BroadcastManager::find(const std::string& infohash) const {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = broadcasts_.find(infohash);
    return it == broadcasts_.end() ? nullptr : it->second;
}

void BroadcastManager::remove_if_empty(const std::string& infohash) {
    std::shared_ptr<Broadcast> removed;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = broadcasts_.find(infohash);
        if (it != broadcasts_.end() && it->second->client_count() == 0) {
            removed = it->second;
            broadcasts_.erase(it);
        }
    }
    if (removed) removed->stop();
}

std::size_t BroadcastManager::broadcast_count() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return broadcasts_.size();
}

std::size_t BroadcastManager::client_count() const {
    std::size_t total = 0;
    std::lock_guard<std::mutex> lock(mutex_);
    for (const auto& [_, broadcast] : broadcasts_) total += broadcast->client_count();
    return total;
}

std::vector<std::shared_ptr<StreamClient>> BroadcastManager::all_clients() const {
    std::vector<std::shared_ptr<StreamClient>> out;
    std::lock_guard<std::mutex> lock(mutex_);
    for (const auto& [_, broadcast] : broadcasts_) {
        auto clients = broadcast->clients();
        out.insert(out.end(), clients.begin(), clients.end());
    }
    return out;
}

void BroadcastManager::stop_all() {
    std::map<std::string, std::shared_ptr<Broadcast>> copy;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        copy.swap(broadcasts_);
    }
    for (auto& [_, broadcast] : copy) broadcast->stop();
}

} // namespace httpace
