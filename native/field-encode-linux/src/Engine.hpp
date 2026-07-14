#pragma once
// Owns the encoder + UDP sender, dispatches control commands, and fragments each access unit
// onto the wire. Mirrors native/field-encode/Sources/field-encode/Engine.swift's shape, with
// one structural difference forced by the platform (see WIRE_FORMAT.md "Linux fd handoff"):
// ownership of surface allocation is reversed. On Mac the helper allocates IOSurfaces (global
// IDs are cheap to share); on Linux the *producer* (JVM, which owns the Vulkan render) allocates
// the image and exports an opaque fd, because Vulkan images can't be born inside this helper. So
// there's no `alloc` command here — `open` carries an `fdSocket` path + `surfaces: [{size,
// rowPitch}, ...]`, and this helper receives the real fds via SCM_RIGHTS over that socket (see
// [receiveFds] for why /proc/<pid>/fd/<fd> duplication doesn't work for these specific fds).
#include "CudaContext.hpp"
#include "Encoder.hpp"
#include "Json.hpp"
#include "UDPSender.hpp"
#include "Wire.hpp"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <memory>
#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <vector>

namespace field_encode {

inline void log(const std::string& msg) {
    std::cerr << "field-encode: " << msg << "\n";
}

class Engine {
public:
    void handle(const JsonValue& cmd) {
        std::string kind = cmd.getString("cmd");
        try {
            if (kind == "open") open(cmd);
            else if (kind == "frame") frame(cmd);
            else if (kind == "bitrate") { encoder_.setBitrate((int)cmd.getInt("kbps")); }
            else if (kind == "selftest") selftest(cmd);
            else if (kind == "close") { cleanupOwned(); encoder_.close(); emitEvent("closed"); }
            else emitError("unknown cmd " + kind);
        } catch (const std::exception& e) {
            emitError(e.what());
        }
    }

private:
    Encoder encoder_;
    std::unique_ptr<UDPSender> sender_;
    bool codecHEVC_ = true;
    bool stereoSbs_ = false;
    int width_ = 0, height_ = 0;

    // Slots this process owns and must clean up (selftest device buffers; imported-fd buffers
    // are cuMemFree'd here too since cuExternalMemoryGetMappedBuffer's CUdeviceptr belongs to us
    // once mapped, independent of the external memory handle it came from).
    struct OwnedSlot { CUdeviceptr ptr = 0; CUexternalMemory extMem = nullptr; };
    std::vector<OwnedSlot> owned_;

    // --- open: real producer path (Linux fd handoff) ---
    void open(const JsonValue& cmd) {
        width_ = (int)cmd.getInt("width");
        height_ = (int)cmd.getInt("height");
        int fps = (int)cmd.getInt("fps");
        int bitrateKbps = (int)cmd.getInt("bitrateKbps");
        std::string dest = cmd.getString("dest");
        codecHEVC_ = cmd.getString("codec", "hevc") != "h264";
        int idrIntervalMs = (int)cmd.getInt("idrIntervalMs", 1000);
        int idrFrames = std::max(1, (idrIntervalMs * std::max(1, fps)) / 1000);
        stereoSbs_ = cmd.getBool("stereo", false);

        sender_ = std::make_unique<UDPSender>(dest);
        if (!sender_->ok()) { emitError("bad dest " + dest); sender_.reset(); return; }

        if (!cmd.has("surfaces") || !cmd.at("surfaces").isArray() || cmd.at("surfaces").items().empty()) {
            emitError("open with no surfaces (Linux sender requires a fdSocket + {size,rowPitch} objects)");
            return;
        }
        std::string fdSocket = cmd.getString("fdSocket");
        if (fdSocket.empty()) { emitError("open with no fdSocket (Linux sender requires SCM_RIGHTS handoff)"); return; }

        encoder_.open(width_, height_, fps, bitrateKbps, codecHEVC_, idrFrames);
        auto& ctx = CudaContext::instance();

        const auto& surfaces = cmd.at("surfaces").items();
        std::vector<int> fds = receiveFds(fdSocket, surfaces.size());
        if (fds.size() != surfaces.size()) {
            emitError("expected " + std::to_string(surfaces.size()) + " fds via " + fdSocket + ", got " + std::to_string(fds.size()));
            return;
        }

        for (size_t slot = 0; slot < surfaces.size(); slot++) {
            long long size = surfaces[slot].getInt("size");
            uint32_t rowPitch = (uint32_t)surfaces[slot].getInt("rowPitch");

            CUDA_EXTERNAL_MEMORY_HANDLE_DESC memDesc{};
            memDesc.type = CU_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD;
            memDesc.handle.fd = fds[slot]; // ownership transfers to CUDA on success
            memDesc.size = (unsigned long long)size;
            // Must match the Vulkan side's VkMemoryDedicatedAllocateInfo (Image.kt) — NVIDIA's
            // driver tags opaque-fd memory exported from an image as dedicated, and omitting this
            // flag here makes cuImportExternalMemory fail with CUDA_ERROR_UNKNOWN (999). Not in
            // the vendored dynlink_cuda.h, so inlined: CUDA_EXTERNAL_MEMORY_DEDICATED = 0x1.
            memDesc.flags = 0x1;
            CUexternalMemory extMem = nullptr;
            ctx.checkCu(ctx.cu()->cuImportExternalMemory(&extMem, &memDesc), "cuImportExternalMemory");

            CUDA_EXTERNAL_MEMORY_BUFFER_DESC bufDesc{};
            bufDesc.offset = 0;
            bufDesc.size = (unsigned long long)size;
            CUdeviceptr ptr = 0;
            ctx.checkCu(ctx.cu()->cuExternalMemoryGetMappedBuffer(&ptr, extMem, &bufDesc), "cuExternalMemoryGetMappedBuffer");

            owned_.push_back({ptr, extMem});
            encoder_.registerSurface((int)slot, ptr, rowPitch);
        }
        emitEvent("opened");
    }

    // Linux never gets an fd's IOSurfaceID equivalent to look up "by global name" — a dma-buf/
    // opaque fd is a per-process file descriptor number, not a system-wide handle. The first design
    // tried duplicating it out of the producer's /proc/<pid>/fd/<fd> (cheap, no native glue) —
    // verified to work for anonymous-inode files (pipes, eventfds, real dma-bufs), but NOT for
    // NVIDIA's VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT export, which turned out to be backed
    // by /dev/nvidiactl (a real device-special file): reopening that path performs a *fresh* open()
    // against the driver, yielding an fd that's numerically valid but unrelated to the original
    // memory object — cuImportExternalMemory then fails with CUDA_ERROR_UNKNOWN (999). SCM_RIGHTS
    // duplicates the actual kernel `struct file*` regardless of what's behind it, so it's the
    // correct fix: bind+listen on `path` (the producer already created it and is about to connect
    // — see afield.four.texture.UnixFdPass on the JVM side), accept one connection, and recvmsg
    // `count` fds out of a single SCM_RIGHTS control message (the producer packs them all into one
    // sendmsg call, in surface order).
    std::vector<int> receiveFds(const std::string& path, size_t count) {
        std::vector<int> result;
        int listenFd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (listenFd < 0) return result;

        ::unlink(path.c_str());
        struct sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        std::strncpy(addr.sun_path, path.c_str(), sizeof(addr.sun_path) - 1);
        if (bind(listenFd, (struct sockaddr*)&addr, sizeof(addr)) != 0 || listen(listenFd, 1) != 0) {
            close(listenFd); return result;
        }

        struct pollfd pfd{listenFd, POLLIN, 0};
        if (poll(&pfd, 1, 5000) <= 0) { close(listenFd); ::unlink(path.c_str()); return result; } // 5s: the producer connects immediately after sending `open`

        int connFd = accept(listenFd, nullptr, nullptr);
        close(listenFd);
        ::unlink(path.c_str());
        if (connFd < 0) return result;

        char dummy;
        struct iovec iov{&dummy, 1};
        size_t cmsgSpace = CMSG_SPACE(sizeof(int) * count);
        std::vector<char> cmsgBuf(cmsgSpace);
        struct msghdr msg{};
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        msg.msg_control = cmsgBuf.data();
        msg.msg_controllen = cmsgSpace;

        if (recvmsg(connFd, &msg, 0) >= 0) {
            for (struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg); cmsg != nullptr; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
                if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS) {
                    size_t n = (cmsg->cmsg_len - CMSG_LEN(0)) / sizeof(int);
                    const int* recvd = (const int*)CMSG_DATA(cmsg);
                    for (size_t i = 0; i < n; i++) result.push_back(recvd[i]);
                }
            }
        }
        close(connFd);
        return result;
    }

    // cuMemFree is only valid for cuMemAlloc*-owned pointers (selftest buffers); an
    // externally-imported buffer's mapping is released via cuDestroyExternalMemory instead —
    // calling cuMemFree on it is undefined per the CUDA driver API.
    void cleanupOwned() {
        auto& ctx = CudaContext::instance();
        for (auto& o : owned_) {
            if (o.extMem) ctx.cu()->cuDestroyExternalMemory(o.extMem);
            else if (o.ptr) ctx.cu()->cuMemFree(o.ptr);
        }
        owned_.clear();
    }

    void frame(const JsonValue& cmd) {
        int slot = (int)cmd.getInt("surface");
        uint32_t seq = (uint32_t)cmd.getInt("seq");
        uint64_t pts = (uint64_t)cmd.getInt("ptsMicros");
        bool forceIdr = cmd.getBool("forceIdr", false);
        encoder_.encode(slot, seq, pts, forceIdr,
            [&](const uint8_t* data, size_t len, bool keyframe, uint32_t s, uint64_t p) {
                sendAccessUnit(data, len, keyframe, s, p);
            });
    }

    // --- selftest: self-contained, no producer/fd needed. Allocates its own CUDA buffers, fills
    // an animated pattern via cuMemcpy2D (there is no device kernel here — no nvcc/toolkit on this
    // build), encodes + streams `frames` frames paced at fps. Mirrors Engine.swift's selftest. ---
    void selftest(const JsonValue& cmd) {
        width_ = (int)cmd.getInt("width", 1280);
        height_ = (int)cmd.getInt("height", 720);
        int fps = (int)cmd.getInt("fps", 60);
        int bitrateKbps = (int)cmd.getInt("bitrateKbps", 20000);
        std::string dest = cmd.getString("dest");
        codecHEVC_ = cmd.getString("codec", "hevc") != "h264";
        int idrIntervalMs = (int)cmd.getInt("idrIntervalMs", 1000);
        int idrFrames = std::max(1, (idrIntervalMs * std::max(1, fps)) / 1000);
        int frames = (int)cmd.getInt("frames", 120);
        stereoSbs_ = false;

        sender_ = std::make_unique<UDPSender>(dest);
        if (!sender_->ok()) { emitError("bad dest " + dest); sender_.reset(); return; }
        encoder_.open(width_, height_, fps, bitrateKbps, codecHEVC_, idrFrames);

        auto& ctx = CudaContext::instance();
        constexpr int ringSize = 3;
        size_t pitch = 0;
        std::vector<CUdeviceptr> ring(ringSize);
        for (int i = 0; i < ringSize; i++) {
            size_t p = 0;
            ctx.checkCu(ctx.cu()->cuMemAllocPitch(&ring[i], &p, (size_t)width_ * 4, (size_t)height_, 4),
                        "cuMemAllocPitch");
            pitch = p; // same for every slot (same width/height/element size)
            owned_.push_back({ring[i], nullptr});
            encoder_.registerSurface(i, ring[i], (uint32_t)pitch);
        }

        std::vector<uint8_t> hostPattern((size_t)pitch * height_);
        emitEvent("selftestStart", (uint32_t)frames);
        double intervalSec = 1.0 / std::max(1, fps);
        for (int i = 0; i < frames; i++) {
            fillTestPattern(hostPattern, pitch, i);
            ctx.checkCu(ctx.cu()->cuMemcpyHtoD(ring[i % ringSize], hostPattern.data(), hostPattern.size()), "cuMemcpyHtoD");
            uint64_t pts = (uint64_t)(i * intervalSec * 1'000'000.0);
            encoder_.encode(i % ringSize, (uint32_t)i, pts, i == 0,
                [&](const uint8_t* data, size_t len, bool keyframe, uint32_t s, uint64_t p) {
                    sendAccessUnit(data, len, keyframe, s, p);
                });
            usleep((useconds_t)(intervalSec * 1'000'000.0));
        }
        usleep(150'000); // let the last packets flush
        emitEvent("selftestDone", (uint32_t)frames);
    }

    static void fillTestPattern(std::vector<uint8_t>& host, size_t pitch, int frame) {
        // Matches the Mac selftest's moving-diagonal BGRA pattern (Engine.swift::fillTestPattern).
        size_t width = pitch / 4; // upper bound; only the real width's worth of pixels matter downstream
        for (size_t y = 0; y < host.size() / pitch; y++) {
            uint8_t* row = host.data() + y * pitch;
            for (size_t x = 0; x < width; x++) {
                uint8_t* p = row + x * 4;
                p[0] = uint8_t((x + y) & 0xff);              // B
                p[1] = uint8_t((y + frame * 2) & 0xff);       // G
                p[2] = uint8_t((x + frame * 4) & 0xff);       // R
                p[3] = 255;                                    // A
            }
        }
    }

    void sendAccessUnit(const uint8_t* data, size_t len, bool keyframe, uint32_t seq, uint64_t pts) {
        if (!sender_ || !sender_->ok() || len == 0) return;
        uint8_t codec = codecHEVC_ ? Wire::codecHEVC : Wire::codecH264;
        uint16_t fragCount = uint16_t((len + Wire::maxPayload - 1) / Wire::maxPayload);

        size_t offset = 0, sent = 0;
        uint16_t frag = 0;
        while (offset < len) {
            size_t chunk = std::min(Wire::maxPayload, len - offset);
            uint8_t flags = 0;
            if (keyframe) flags |= Wire::flagKeyframe;
            if (frag == fragCount - 1) flags |= Wire::flagFrameEnd;
            if (stereoSbs_) flags |= Wire::flagStereoSbs;
            std::vector<uint8_t> pkt = Wire::header(flags, codec, seq, frag, fragCount, pts);
            pkt.insert(pkt.end(), data + offset, data + offset + chunk);
            sender_->send(pkt);
            sent += pkt.size();
            offset += chunk;
            frag++;
        }
        emitEvent("encoded", seq, (int64_t)sent);
    }

    void emitEvent(const std::string& ev, int64_t seq = -1, int64_t bytes = -1) {
        JsonValue v = JsonValue::makeObject();
        v.set("ev", JsonValue::makeString(ev));
        if (seq >= 0) v.set("seq", JsonValue::makeNumber((double)seq));
        if (bytes >= 0) v.set("bytes", JsonValue::makeNumber((double)bytes));
        std::cout << v.dump() << "\n";
        std::cout.flush();
    }

    void emitError(const std::string& msg) {
        JsonValue v = JsonValue::makeObject();
        v.set("ev", JsonValue::makeString("error"));
        v.set("msg", JsonValue::makeString(msg));
        std::cout << v.dump() << "\n";
        std::cout.flush();
        log("error: " + msg);
    }
};

} // namespace field_encode
