// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/vespalib/net/tls/tls_crypto_engine.h>
#include <vespa/vespalib/stllike/string.h>

#include <chrono>
#include <condition_variable>
#include <mutex>
#include <thread>

namespace vespalib::net::tls {

class AutoReloadingTlsCryptoEngine : public AbstractTlsCryptoEngine {
public:
    using EngineSP     = std::shared_ptr<TlsCryptoEngine>;
    using TimeInterval = std::chrono::steady_clock::duration;
private:
    mutable std::mutex      _mutex;
    std::condition_variable _cond;
    bool                    _shutdown;
    const vespalib::string  _config_file_path;
    EngineSP                _current_engine; // Access must be under _mutex
    TimeInterval            _reload_interval;
    std::thread             _reload_thread;

    void run_reload_loop();
    void try_replace_current_engine(std::unique_lock<std::mutex>& held_lock);
    std::chrono::steady_clock::time_point make_future_reload_time_point() const noexcept;

public:
    explicit AutoReloadingTlsCryptoEngine(vespalib::string config_file_path,
                                          TimeInterval reload_interval = std::chrono::seconds(3600));
    ~AutoReloadingTlsCryptoEngine() override;

    AutoReloadingTlsCryptoEngine(const AutoReloadingTlsCryptoEngine&) = delete;
    AutoReloadingTlsCryptoEngine& operator=(const AutoReloadingTlsCryptoEngine&) = delete;
    AutoReloadingTlsCryptoEngine(AutoReloadingTlsCryptoEngine&&) = delete;
    AutoReloadingTlsCryptoEngine& operator=(AutoReloadingTlsCryptoEngine&&) = delete;

    EngineSP acquire_current_engine() const;

    CryptoSocket::UP create_crypto_socket(SocketHandle socket, bool is_server) override;
    std::unique_ptr<TlsCryptoSocket> create_tls_crypto_socket(SocketHandle socket, bool is_server) override;
};

}
