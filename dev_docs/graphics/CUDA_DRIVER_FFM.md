# CUDA driver API over FFM, sharing memory with Field's Vulkan renderer

Field can now run CUDA work on the GPU its Vulkan renderer draws with, reading and writing the
renderer's own buffers in place. It binds the CUDA driver library with `java.lang.foreign`; there is
no JNI and no toolkit dependency. Linux only, and optional: on macOS, or on a machine without the
NVIDIA driver, every entry point fails with a reason and the proof skips.

## The pieces

- `src/jvmMain/kotlin/afield/cuda/CudaVulkan.kt`: the box-facing layer the user guide
  (`public/docs/cuda.md`) and the `cuda_particles` starting point teach. `forRenderer` caches one
  session per renderer and closes it in `beforeDeviceCleanup`. `sharedBuffer` is keyed by name and
  size, `compile`/`module` by source, `everyFrame` by name. `everyFrame` puts a wait for the last
  kernel and a signal after the frame onto the frame submission (`externalGraphicsSync`), then
  enqueues the kernel behind that signal. It uses one timeline, and the blocking `run` uses a
  second, because a timeline's signals have to be submitted in increasing order and a frame's
  signal is submitted after it is recorded. The frame path does no queue-family ownership
  transfers; the semaphores give the execution and memory ordering, and NVIDIA keeps exclusive
  buffers' contents across the external family. `run` does do the transfers.
- `src/jvmMain/kotlin/afield/cuda/CudaDriver.kt`: about 40 driver entry points from `libcuda.so.1`.
  It covers `cuInit`, devices with their names, UUIDs and compute capability, the primary context
  (`current { }` push/pops it around every call, so any thread may use it), streams, events,
  `cuModuleLoadDataEx` with the JIT error log, `cuLaunchKernel`, device memory, and the four interop
  calls (`cuImportExternalMemory` / `cuExternalMemoryGetMappedBuffer`, `cuImportExternalSemaphore`,
  and signal and wait on a timeline value). `CudaDriver.loaded` is a `Result`.
- `src/jvmMain/kotlin/afield/cuda/Nvrtc.kt`: CUDA C to PTX at runtime. NVRTC comes with the toolkit,
  not the driver, so it searches `FIELD_NVRTC_LIBRARY`, then the loader, then conda package caches
  (`~/.local/share/{micromamba,mamba}`, `~/miniforge3`, ...). On a0 it finds conda-forge's
  `cuda-nvrtc-12.8.93`, and a CUDA 13.0 driver JITs its PTX without complaint. Without NVRTC, ship
  PTX as text: `CudaInteropProof.FILL_PTX` is hand-written PTX 7.0 for `sm_52`.
- `VulkanBuffer(..., exportOpaqueFd = true)`: a dedicated, device-local, unmapped allocation made
  through `vmaCreateDedicatedBuffer` with `VkExportMemoryAllocateInfo` on `pMemoryAllocateNext`.
  `exportOpaqueFd()` returns a fresh fd on every call, because each import consumes one, and
  `exportedAllocationSize` is the size importers must be given. `PathTracingRenderer(exportAccumulation
  = true)` and `OidnComputeDenoiser(exportGuides = true)` allocate their accumulation and guide
  buffers this way.
- `ExportedTimelineSemaphore` (`afield.graphics.vulkan.device`): an exportable timeline semaphore,
  `submit(command, waitValue, signalValue, fence)` through `vkQueueSubmit2` under the global queue
  lock, the `VK_QUEUE_FAMILY_EXTERNAL` release and acquire barriers, and `deviceUuid(renderer)`.
- The `Oidn` FFM class (in `OidnSpike.kt`) gained `Device(type, uuid)` (`oidnNewDeviceByUUID`),
  `sharedBuffer(fd, size)`, `sharedTimeline(fd)`, async wait, signal and execute.

## What the drivers insist on

- **Dedicated in, dedicated out.** Field's exportable buffers are dedicated allocations, so the
  importer has to declare it: `CUDA_EXTERNAL_MEMORY_DEDICATED`, or
  `OIDN_EXTERNAL_MEMORY_TYPE_FLAG_DEDICATED` (`1 << 30`) for OIDN. The NVENC image path found the same
  rule for images (see the comments on `Image.exportOpaqueFd`).
- **Exact size.** Import with the allocation size, not the buffer's requested size. At 1080p `vec4`
  the two happen to agree (33,177,600 bytes), but they need not.
- **The fd changes hands on success.** CUDA and OIDN both take ownership of the fd when an import
  succeeds, and the caller keeps it when the import fails. Never import one fd twice.
- **The same GPU.** `VkPhysicalDeviceIDProperties.deviceUUID` equals `cuDeviceGetUuid` byte for byte
  (a0: `GPU-466dd949-dde0-584b-deee-20092096af2e`), and OIDN takes the same 16 bytes.
- **Timeline semaphores.** CUDA imports them as `CU_EXTERNAL_SEMAPHORE_HANDLE_TYPE_TIMELINE_SEMAPHORE_FD`
  (9), OIDN as `OIDN_EXTERNAL_SEMAPHORE_TYPE_FLAG_TIMELINE_SEMAPHORE_FD` (`1 << 7`). One counter serves
  every exchange: Vulkan signals N, CUDA or OIDN waits for N and signals N+1, and Vulkan waits for N+1.
  Nothing waits on the CPU in between.
- **Struct layouts** (64-bit Linux), written by hand in `CudaDriver.kt`: the external memory handle
  descriptor is 104 bytes (size at 24, flags at 32), the buffer descriptor 88, the semaphore handle
  descriptor 96, and the signal and wait parameters 144 (the fence value at 0).

## The proof: `afield.cuda.CudaInteropProof`

```
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command "run-jvm-main afield.cuda.CudaInteropProof --arg=--out --arg /home/marc/cuda-interop/run1 --timeout 1500"
```

On a0, run it with `WAYLAND_DISPLAY=wayland-1 XDG_RUNTIME_DIR=/run/user/1000`. Its options are
`--oidn <lib dir>` (default `~/oidn-spike/oidn-2.5.1.x86_64.linux/lib`, or `FIELD_OIDN_LIBRARY_DIR`)
and `--spp N` (default 32). It exits 0 only when every check passes, and exits 0 with
`CUDA INTEROP SKIPPED: <reason>` where there is no CUDA.

The run behind these numbers was a0 (RTX 4090, driver 620.70.06, CUDA 13.05) on 2026-11-14,
measuring a 1920×1080 scene at 32 spp. Another session was running OIDN timings on the same GPU
that afternoon, so the figures are a ceiling.

**Proof 1: native OIDN on CUDA, zero-copy.** OIDN opens its CUDA device by the Vulkan UUID. It
imports the tracer's accumulation buffer, the port's albedo and normal guides and an output buffer,
all four as fds, and denoises under the shared timeline semaphore.

| | ms |
|---|---|
| Vulkan release → OIDN wait/filter/signal → Vulkan acquire, `high` (median of 15) | 6.99 (min 6.88) |
| the same, `balanced` | 5.34 |
| `oidnExecuteFilter` alone on the shared buffers, `high` / `balanced` | 6.48 / 4.95 |
| Field's Vulkan port, same inputs (fenced submit, cooperative matrix) | 12.27 |

So the Vulkan round trip costs about 0.5 ms on top of OIDN itself, and the denoise needs no readback
and no upload. Earlier measurements of the copy route were 6.5 ms and 4.9 ms for the filter alone,
and those excluded copying 3 × 33 MB in and 33 MB out. Checks:

- the output read through Vulkan equals the output read through `oidnReadBuffer`, maximum difference
  0.0, which shows the two APIs alias the same memory;
- native CUDA OIDN agrees with Field's port at 71.4 dB PSNR (sRGB), against 30.9 dB for the noisy
  input.

**Proof 2: a kernel writes a buffer that Field displays.** The kernel waits on the timeline, fills
an exported buffer and signals. The path tracer then resolves its `output` texture from that buffer
(`resolveFrom`), and the texture is read back.

| kernel | kernel (CUDA events) | Vulkan→CUDA→Vulkan round trip | CUDA / Vulkan / displayed max diff |
|---|---|---|---|
| hand-written PTX `field_fill` | 0.039 ms | 0.32 ms | 0 / 0 / 0 |
| NVRTC 12.8 `field_rings` (`--fmad=false`) | 0.017 ms | 0.23 ms | 0 / 0 / 0 |

The expected values are computed on the CPU. Both kernels avoid anything the CPU cannot reproduce
bit for bit (IEEE division, no FMA contraction), so the comparison is exact.

Proof 3 checks `CudaVulkan`: `forRenderer` returns one session on the Vulkan GPU, a second
`sharedBuffer` call with the same name returns the same buffer, `run`'s writes are visible to Vulkan
afterwards, and `everyFrame` ran on all 30 pumped frames, leaving the last frame's value in every
cell. The entrypoint is the `cuda.interop.smoke` FIELD_TEST; it skips (exit 0) without CUDA.

## Docs and starting point

`public/docs/cuda.md` ("CUDA Beside the Renderer") is built around `dev_docs/agents/transcripts/cuda_particles.yaml`.
That scene is a renderer, a CUDA box (NVRTC-compiled `seed` and `flow` kernels, `everyFrame`) and
lesson 3b's particle drawing reading the shared buffer. Its figure is `capture-profile="linux-nvidia"`
and was captured on a0 (`FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_CAPTURE_PROFILES=linux-nvidia
... build_zensical_docs.py --page cuda.md`). The transcript is COMPILE_ONLY in CI.

## Not done

- **Driving a denoise from the frame loop.** The proof submits its own command buffers. A box-facing
  path needs the release and the signal in a frame submission (`Renderer.externalGraphicsSync`
  already adds semaphore points to one), with OIDN's output shown through `resolveFrom`.
- **Windows.** The binding loads `libcuda.so.1` only; `nvcuda.dll` and Win32 handles are not bound.
- **cuBLAS, cuDNN, ONNX Runtime CUDA.** Each is another FFM library that receives the `CUdeviceptr`s
  this binding produces, on the primary context it retains.
- **macOS.** The skip path, which is the `os.name` check in `CudaDriver.loaded`, has not been run
  end to end on a Mac.
