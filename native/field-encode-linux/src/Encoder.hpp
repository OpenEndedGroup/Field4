#pragma once
// Wraps an NVENC session for low-latency HEVC/H.264, input via registered CUDA device pointers
// (see WIRE_FORMAT.md's Linux capture row: Vulkan external memory -> CUDA -> NVENC
// CUDADEVICEPTR). Mirrors native/field-encode/Sources/field-encode/Encoder.swift's shape:
// construct with session params, `encode()` a ring slot, get a callback with a complete
// Annex-B access unit. Unlike VideoToolbox, NVENC's bitstream output IS Annex-B already
// (with inline VPS/SPS/PPS on every IDR via repeatSPSPPS) — no NAL rewriting needed here.
#include "CudaContext.hpp"

#include <cstring>
#include <functional>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace field_encode {

class Encoder {
public:
    // (data, len, keyframe, seq, ptsMicros) -> void. `data` is only valid for the callback's
    // duration (points at the locked NVENC bitstream buffer).
    using AccessUnitHandler = std::function<void(const uint8_t*, size_t, bool, uint32_t, uint64_t)>;

    Encoder() = default;
    ~Encoder() { close(); }

    // Throws std::runtime_error on failure (caller wraps in try/catch -> {"ev":"error",...}).
    void open(int width, int height, int fps, int bitrateKbps, bool hevc, int idrIntervalFrames) {
        auto& ctx = CudaContext::instance();
        ctx.ensureLoaded();
        nv_ = ctx.nv();
        hevc_ = hevc;
        width_ = width;
        height_ = height;

        NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS openParams{};
        openParams.version = NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS_VER;
        openParams.deviceType = NV_ENC_DEVICE_TYPE_CUDA;
        openParams.device = ctx.cuContext();
        openParams.apiVersion = NVENCAPI_VERSION;
        checkNvenc(nv_->nvEncOpenEncodeSessionEx(&openParams, &session_), "nvEncOpenEncodeSessionEx");

        GUID codecGuid = hevc ? NV_ENC_CODEC_HEVC_GUID : NV_ENC_CODEC_H264_GUID;
        GUID presetGuid = NV_ENC_PRESET_P4_GUID; // balanced speed/quality; tuningInfo below biases it low-latency

        NV_ENC_PRESET_CONFIG presetCfg{};
        presetCfg.version = NV_ENC_PRESET_CONFIG_VER;
        presetCfg.presetCfg.version = NV_ENC_CONFIG_VER;
        checkNvenc(nv_->nvEncGetEncodePresetConfigEx(session_, codecGuid, presetGuid,
                                                      NV_ENC_TUNING_INFO_ULTRA_LOW_LATENCY, &presetCfg),
                   "nvEncGetEncodePresetConfigEx");
        config_ = presetCfg.presetCfg;
        config_.version = NV_ENC_CONFIG_VER;
        // Main profile (not AUTOSELECT — the visionOS receiver traced AUTOSELECT to silently
        // picking High profile/tier at this bitrate) and no B-frames.
        config_.profileGUID = hevc ? NV_ENC_HEVC_PROFILE_MAIN_GUID : NV_ENC_H264_PROFILE_MAIN_GUID;
        config_.gopLength = idrIntervalFrames;
        config_.frameIntervalP = 1; // no B-frames: matches Mac's AllowFrameReordering=false

        // Cap short-term rate to ~1.5x average (mirrors VideoToolbox's DataRateLimits on the Mac
        // sender) and keep the VBV shallow (~0.5s) for a live-coding "correct within 2 frames"
        // stream rather than a buffered/quality-first one.
        auto& rc = config_.rcParams;
        rc.version = NV_ENC_RC_PARAMS_VER;
        rc.rateControlMode = NV_ENC_PARAMS_RC_CBR;
        rc.averageBitRate = uint32_t(bitrateKbps) * 1000;
        rc.maxBitRate = uint32_t(bitrateKbps) * 1500;
        rc.vbvBufferSize = rc.averageBitRate / 2;
        rc.vbvInitialDelay = rc.vbvBufferSize;
        // The P4 preset's multi-pass/lookahead rate control can restructure references in ways a
        // simple decoder DPB doesn't expect (see below) — force single-pass, no lookahead, so the
        // GOP stays a flat, predictable IPPP.
        rc.multiPass = NV_ENC_MULTI_PASS_DISABLED;
        rc.lookaheadLevel = NV_ENC_LOOKAHEAD_LEVEL_0;
        rc.lookaheadDepth = 0;

        // Tried explicitly signaling VUI colorimetry (BT.709 full-range) here to chase a
        // washed-out-color report — reverted: it correlated with the real visionOS receiver
        // hanging after the first decoded frame (one clean decode, then nothing — not just a
        // stale-until-next-IDR freeze, an actual stall), confirmed worse than the pre-VUI
        // baseline in a live side-by-side. Left unset (matches Mac/VideoToolbox's default
        // handling) — the color path was independently confirmed pixel-correct once this was
        // reverted, so leave VUI alone.
        //
        // The real bug (found by the visionOS receiver side via byte-level bitstream diff against
        // the known-good Mac encoder): P-frames decoded with noErr but concealed to grey — the
        // classic VideoToolbox signature for "P-frame references a picture the decoder isn't
        // retaining." Root cause: the P4 preset defaults to a multi-reference/hierarchical
        // structure (via AUTOSELECT profile + preset-chosen numRefL0/L1) that a plain
        // single-reference decoder DPB doesn't track. Fix: pin every P-frame to reference only the
        // immediately preceding picture.
        if (hevc) {
            auto& hc = config_.encodeCodecConfig.hevcConfig;
            hc.repeatSPSPPS = 1;
            hc.disableSPSPPS = 0;
            hc.idrPeriod = idrIntervalFrames;
            hc.tier = NV_ENC_TIER_HEVC_MAIN;
            // Main tier's max bitrate at the auto-selected level (4.0, level_idc=120 — confirmed
            // via ffprobe) is 10 Mbps per HEVC Annex A; our 20-30 Mbps request is only conformant
            // at High tier, so NVENC was silently forcing High tier regardless of the line above.
            // Pin a level whose Main-tier ceiling comfortably covers this bitrate range instead of
            // leaving it AUTOSELECT.
            hc.level = NV_ENC_LEVEL_HEVC_51; // Main tier max 40 Mbps
            hc.numRefL0 = NV_ENC_NUM_REF_FRAMES_1;
            hc.numRefL1 = NV_ENC_NUM_REF_FRAMES_1;
            hc.maxNumRefFramesInDPB = 1;
            hc.useBFramesAsRef = NV_ENC_BFRAME_REF_MODE_DISABLED;
        } else {
            auto& hc = config_.encodeCodecConfig.h264Config;
            hc.repeatSPSPPS = 1;
            hc.disableSPSPPS = 0;
            hc.idrPeriod = idrIntervalFrames;
            hc.numRefL0 = NV_ENC_NUM_REF_FRAMES_1;
            hc.numRefL1 = NV_ENC_NUM_REF_FRAMES_1;
            hc.maxNumRefFrames = 1;
            hc.useBFramesAsRef = NV_ENC_BFRAME_REF_MODE_DISABLED;
        }

        // Verification log: confirm what's actually about to be sent to NVENC, not just what the
        // code above intends — read back from config_ itself, right before the call that consumes
        // it, so a future "did my setting actually take?" question can be answered from this one
        // line instead of re-deriving it from a live bitstream diff.
        if (hevc) {
            auto& hc = config_.encodeCodecConfig.hevcConfig;
            bool isMainProfile = std::memcmp(&config_.profileGUID, &NV_ENC_HEVC_PROFILE_MAIN_GUID, sizeof(GUID)) == 0;
            std::cerr << "field-encode: nvenc config check: profile=" << (isMainProfile ? "Main" : "other")
                      << " tier=" << hc.tier << " level=" << hc.level
                      << " numRefL0=" << hc.numRefL0 << " numRefL1=" << hc.numRefL1
                      << " maxNumRefFramesInDPB=" << hc.maxNumRefFramesInDPB
                      << " multiPass=" << config_.rcParams.multiPass
                      << " lookaheadLevel=" << config_.rcParams.lookaheadLevel
                      << " avgBitrateKbps=" << (config_.rcParams.averageBitRate / 1000)
                      << " maxBitrateKbps=" << (config_.rcParams.maxBitRate / 1000) << "\n";
        }

        NV_ENC_INITIALIZE_PARAMS initParams{};
        initParams.version = NV_ENC_INITIALIZE_PARAMS_VER;
        initParams.encodeGUID = codecGuid;
        initParams.presetGUID = presetGuid;
        initParams.encodeWidth = width;
        initParams.encodeHeight = height;
        initParams.darWidth = width;
        initParams.darHeight = height;
        initParams.frameRateNum = fps;
        initParams.frameRateDen = 1;
        initParams.enableEncodeAsync = 0; // required: async mode is Windows-only
        initParams.enablePTD = 1;         // encoder decides P/I; we still force IDR explicitly
        initParams.encodeConfig = &config_;
        initParams.maxEncodeWidth = width;
        initParams.maxEncodeHeight = height;
        initParams.tuningInfo = NV_ENC_TUNING_INFO_ULTRA_LOW_LATENCY;
        initParams.bufferFormat = NV_ENC_BUFFER_FORMAT_ARGB; // byte order B,G,R,A — matches VK_FORMAT_B8G8R8A8_UNORM

        checkNvenc(nv_->nvEncInitializeEncoder(session_, &initParams), "nvEncInitializeEncoder");

        NV_ENC_CREATE_BITSTREAM_BUFFER bs{};
        bs.version = NV_ENC_CREATE_BITSTREAM_BUFFER_VER;
        checkNvenc(nv_->nvEncCreateBitstreamBuffer(session_, &bs), "nvEncCreateBitstreamBuffer");
        bitstreamBuffer_ = bs.bitstreamBuffer;
    }

    // Register CUDA device pointer `ptr` (row pitch `pitch` bytes) as ring slot `slot`. Call
    // once per slot at setup, before any `encode(slot, ...)`.
    void registerSurface(int slot, CUdeviceptr ptr, uint32_t pitch) {
        if ((int)slots_.size() <= slot) slots_.resize(slot + 1);
        NV_ENC_REGISTER_RESOURCE reg{};
        reg.version = NV_ENC_REGISTER_RESOURCE_VER;
        reg.resourceType = NV_ENC_INPUT_RESOURCE_TYPE_CUDADEVICEPTR;
        reg.width = width_;
        reg.height = height_;
        reg.pitch = pitch;
        reg.resourceToRegister = reinterpret_cast<void*>(ptr);
        reg.bufferFormat = NV_ENC_BUFFER_FORMAT_ARGB;
        reg.bufferUsage = NV_ENC_INPUT_IMAGE;
        checkNvenc(nv_->nvEncRegisterResource(session_, &reg), "nvEncRegisterResource(slot " + std::to_string(slot) + ")");
        slots_[slot].registered = reg.registeredResource;
        slots_[slot].valid = true;
    }

    void encode(int slot, uint32_t seq, uint64_t ptsMicros, bool forceIdr, const AccessUnitHandler& onAccessUnit) {
        if (slot < 0 || (size_t)slot >= slots_.size() || !slots_[slot].valid) {
            throw std::runtime_error("encode: slot " + std::to_string(slot) + " not registered");
        }

        NV_ENC_MAP_INPUT_RESOURCE map{};
        map.version = NV_ENC_MAP_INPUT_RESOURCE_VER;
        map.registeredResource = slots_[slot].registered;
        checkNvenc(nv_->nvEncMapInputResource(session_, &map), "nvEncMapInputResource");

        NV_ENC_PIC_PARAMS pic{};
        pic.version = NV_ENC_PIC_PARAMS_VER;
        pic.inputWidth = width_;
        pic.inputHeight = height_;
        pic.inputBuffer = map.mappedResource;
        pic.bufferFmt = map.mappedBufferFmt;
        pic.outputBitstream = bitstreamBuffer_;
        pic.inputTimeStamp = ptsMicros;
        pic.pictureStruct = NV_ENC_PIC_STRUCT_FRAME;
        if (forceIdr) pic.encodePicFlags |= NV_ENC_PIC_FLAG_FORCEIDR;

        NVENCSTATUS st = nv_->nvEncEncodePicture(session_, &pic);
        if (st != NV_ENC_SUCCESS && st != NV_ENC_ERR_NEED_MORE_INPUT) {
            nv_->nvEncUnmapInputResource(session_, map.mappedResource);
            throw std::runtime_error("nvEncEncodePicture failed (status " + std::to_string(st) + ")");
        }
        if (st == NV_ENC_ERR_NEED_MORE_INPUT) {
            // Shouldn't happen with frameIntervalP=1 (no B-frame reordering) — no output this call.
            nv_->nvEncUnmapInputResource(session_, map.mappedResource);
            return;
        }

        NV_ENC_LOCK_BITSTREAM lock{};
        lock.version = NV_ENC_LOCK_BITSTREAM_VER;
        lock.outputBitstream = bitstreamBuffer_;
        checkNvenc(nv_->nvEncLockBitstream(session_, &lock), "nvEncLockBitstream");
        bool keyframe = lock.pictureType == NV_ENC_PIC_TYPE_IDR;
        onAccessUnit(reinterpret_cast<const uint8_t*>(lock.bitstreamBufferPtr), lock.bitstreamSizeInBytes,
                     keyframe, seq, ptsMicros);
        nv_->nvEncUnlockBitstream(session_, bitstreamBuffer_);
        nv_->nvEncUnmapInputResource(session_, map.mappedResource);
    }

    void setBitrate(int kbps) {
        if (!session_) return;
        config_.rcParams.averageBitRate = uint32_t(kbps) * 1000;
        config_.rcParams.maxBitRate = uint32_t(kbps) * 1500;
        NV_ENC_RECONFIGURE_PARAMS reconfig{};
        reconfig.version = NV_ENC_RECONFIGURE_PARAMS_VER;
        reconfig.reInitEncodeParams.version = NV_ENC_INITIALIZE_PARAMS_VER;
        reconfig.reInitEncodeParams.encodeGUID = hevc_ ? NV_ENC_CODEC_HEVC_GUID : NV_ENC_CODEC_H264_GUID;
        reconfig.reInitEncodeParams.encodeWidth = width_;
        reconfig.reInitEncodeParams.encodeHeight = height_;
        reconfig.reInitEncodeParams.maxEncodeWidth = width_;
        reconfig.reInitEncodeParams.maxEncodeHeight = height_;
        reconfig.reInitEncodeParams.encodeConfig = &config_;
        reconfig.reInitEncodeParams.enableEncodeAsync = 0;
        reconfig.reInitEncodeParams.enablePTD = 1;
        nv_->nvEncReconfigureEncoder(session_, &reconfig);
    }

    void close() {
        if (!session_ || !nv_) { session_ = nullptr; return; }
        for (auto& s : slots_) if (s.valid) nv_->nvEncUnregisterResource(session_, s.registered);
        slots_.clear();
        if (bitstreamBuffer_) nv_->nvEncDestroyBitstreamBuffer(session_, bitstreamBuffer_);
        bitstreamBuffer_ = nullptr;
        nv_->nvEncDestroyEncoder(session_);
        session_ = nullptr;
    }

private:
    struct Slot { NV_ENC_REGISTERED_PTR registered = nullptr; bool valid = false; };

    void checkNvenc(NVENCSTATUS st, const std::string& what) {
        if (st != NV_ENC_SUCCESS) throw std::runtime_error(what + " failed (status " + std::to_string(st) + ")");
    }

    NV_ENCODE_API_FUNCTION_LIST* nv_ = nullptr;
    void* session_ = nullptr;
    NV_ENC_OUTPUT_PTR bitstreamBuffer_ = nullptr;
    NV_ENC_CONFIG config_{};
    std::vector<Slot> slots_;
    bool hevc_ = true;
    int width_ = 0, height_ = 0;
};

} // namespace field_encode
