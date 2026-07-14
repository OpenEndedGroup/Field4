#pragma once
// A minimal one-way UDP sender to a fixed "host:port". No retransmit, no congestion control —
// deliberate (see WIRE_FORMAT.md). Mirrors native/field-encode/Sources/field-encode/UDPSender.swift:
// `host` is resolved with getaddrinfo, so it accepts DNS/Tailscale MagicDNS names (`mi2`), IPv4
// literals, and bracketed IPv6 literals (`[fd7a:...]:9000`).
#include <cstdint>
#include <cstring>
#include <netdb.h>
#include <string>
#include <sys/socket.h>
#include <unistd.h>
#include <vector>

namespace field_encode {

class UDPSender {
public:
    // Returns nullptr (via ok()==false) on a malformed dest or resolution failure.
    explicit UDPSender(const std::string& dest) {
        auto sep = dest.find_last_of(':');
        if (sep == std::string::npos) return;
        std::string host = dest.substr(0, sep);
        std::string portStr = dest.substr(sep + 1);
        if (!host.empty() && host.front() == '[' && host.back() == ']') {
            host = host.substr(1, host.size() - 2);
        }
        if (host.empty() || portStr.empty()) return;

        addrinfo hints{};
        hints.ai_family = AF_UNSPEC;
        hints.ai_socktype = SOCK_DGRAM;
        hints.ai_protocol = IPPROTO_UDP;
        addrinfo* res = nullptr;
        if (getaddrinfo(host.c_str(), portStr.c_str(), &hints, &res) != 0 || res == nullptr) return;

        fd_ = socket(res->ai_family, SOCK_DGRAM, 0);
        if (fd_ < 0) { freeaddrinfo(res); return; }
        addrLen_ = res->ai_addrlen;
        std::memcpy(&storage_, res->ai_addr, res->ai_addrlen);
        freeaddrinfo(res);

        int sndbuf = 4 * 1024 * 1024;
        setsockopt(fd_, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));
        ok_ = true;
    }

    ~UDPSender() { if (fd_ >= 0) close(fd_); }

    bool ok() const { return ok_; }

    void send(const uint8_t* data, size_t len) const {
        if (!ok_) return;
        sendto(fd_, data, len, 0, reinterpret_cast<const sockaddr*>(&storage_), addrLen_);
    }
    void send(const std::vector<uint8_t>& bytes) const { send(bytes.data(), bytes.size()); }

private:
    int fd_ = -1;
    bool ok_ = false;
    sockaddr_storage storage_{};
    socklen_t addrLen_ = 0;
};

} // namespace field_encode
