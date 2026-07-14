#pragma once
// Lazily-loaded, process-wide CUDA driver + NVENC API tables, dlopen'd against the driver's
// runtime libraries (libcuda.so.1 / libnvidia-encode.so.1 — shipped by `nvidia-utils`, not the
// CUDA toolkit). No nvcc / CUDA toolkit / Video Codec SDK install required to build or run this;
// see third_party/ffnvcodec/NOTICE.md.
// NB: don't pre-define FFNV_DYNLINK_CUDA_H — that's dynlink_cuda.h's OWN include guard, and
// pre-defining it makes dynlink_cuda.h skip its entire body (looking already-included) while
// dynlink_loader.h's "#ifdef FFNV_DYNLINK_CUDA_H -> declare the full CudaFunctions struct" checks
// still fire, so every member (cuInit, CUdeviceptr, ...) silently vanishes. Just include the
// loader; it includes dynlink_cuda.h itself and the guard sets correctly from there.
#include "../third_party/ffnvcodec/dynlink_loader.h"

#include <stdexcept>
#include <string>

namespace field_encode {

// One CUDA context for the process's lifetime. NVENC is opened per-stream (Encoder), but shares
// this context (NV_ENC_DEVICE_TYPE_CUDA, device = this CUcontext).
class CudaContext {
public:
    static CudaContext& instance() {
        static CudaContext ctx;
        return ctx;
    }

    // Idempotent; throws std::runtime_error with a human-readable message on failure (surfaced
    // by main.cpp as an {"ev":"error",...} event before exit, matching the Swift side's
    // fail-fast-and-log posture).
    void ensureLoaded() {
        if (loaded_) return;
        if (cuda_load_functions(&cuda_, nullptr) < 0 || cuda_ == nullptr) {
            throw std::runtime_error("failed to load libcuda.so.1 (is the NVIDIA driver installed?)");
        }
        if (nvenc_load_functions(&nvenc_, nullptr) < 0 || nvenc_ == nullptr) {
            throw std::runtime_error("failed to load libnvidia-encode.so.1");
        }
        checkCu(cuda_->cuInit(0), "cuInit");
        CUdevice dev;
        checkCu(cuda_->cuDeviceGet(&dev, 0), "cuDeviceGet(0)");
        checkCu(cuda_->cuCtxCreate(&cuContext_, 0, dev), "cuCtxCreate");

        NV_ENCODE_API_FUNCTION_LIST list{};
        list.version = NV_ENCODE_API_FUNCTION_LIST_VER;
        NVENCSTATUS st = nvenc_->NvEncodeAPICreateInstance(&list);
        if (st != NV_ENC_SUCCESS) {
            throw std::runtime_error("NvEncodeAPICreateInstance failed (status " + std::to_string(st) + ")");
        }
        nvencApi_ = list;
        loaded_ = true;
    }

    CudaFunctions* cu() { return cuda_; }
    NV_ENCODE_API_FUNCTION_LIST* nv() { return &nvencApi_; }
    CUcontext cuContext() const { return cuContext_; }

    void checkCu(CUresult r, const std::string& what) {
        if (r == CUDA_SUCCESS) return;
        const char* msg = nullptr;
        if (cuda_) cuda_->cuGetErrorString(r, &msg);
        throw std::runtime_error(what + " failed: " + (msg ? msg : "unknown CUDA error") + " (" + std::to_string(r) + ")");
    }

private:
    CudaContext() = default;
    bool loaded_ = false;
    CudaFunctions* cuda_ = nullptr;
    NvencFunctions* nvenc_ = nullptr;
    CUcontext cuContext_ = nullptr;
    NV_ENCODE_API_FUNCTION_LIST nvencApi_{};
};

} // namespace field_encode
