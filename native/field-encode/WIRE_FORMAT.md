# field-encode wire format

The contract shared by **every** sender (Mac/VideoToolbox, Linux/NVENC) and the visionOS
receiver. Getting this right on the first (Mac) sender is what makes the Linux sender a
drop-in and keeps the receiver untouched. Two channels:

1. **Control** — newline-delimited JSON, producer → helper on the helper's **stdin**;
   helper → producer events on **stdout**; human logs on **stderr**.
2. **Media** — a one-way UDP stream of fragmented HEVC/H.264 access units.

Nothing on the media channel is retransmitted. Loss is tolerated by (a) discarding an
incomplete frame and (b) periodic IDR so a dropped reference self-heals within
`idrIntervalMs`.

## Control channel (stdin/stdout, JSON per line)

Producer → helper (`stdin`):

| `cmd`      | fields | meaning |
|------------|--------|---------|
| `open`     | `surfaces:[u32]`, `width`, `height`, `fps`, `bitrateKbps`, `dest:"host:port"`, `codec?:"hevc"\|"h264"`, `idrIntervalMs?` | Resolve the given global `IOSurfaceID`s, create the encoder, open the UDP socket. `surfaces` is a small ring (2–3) the producer renders into round-robin. `dest` is resolved with `getaddrinfo`, so `host` may be a DNS/Tailscale MagicDNS name (`mi2`), an IPv4 literal, or a bracketed IPv6 literal (`[fd7a:…]:9000`). |
| `frame`    | `surface:int` (index into `surfaces`), `seq:u32`, `ptsMicros:u64`, `forceIdr?:bool`, `poseFrameId?:u64`, `poseDisplayTimeNanos?:u64` | Encode surface *i* now. `seq` is the producer's monotonic frame counter and rides through to the wire header. The two pose fields identify the head pose this frame was rendered from and populate the v2 pose tail; supply **both or neither** (half a pose would set `POSE_VALID` over a zero timestamp and the receiver would reproject to the wrong instant). |
| `bitrate`  | `kbps:int` | Retarget average bitrate live. |
| `close`    | — | Flush, tear down. |

Helper → producer (`stdout`): `{"ev":"opened"}`, `{"ev":"encoded","seq":N,"bytes":B}`,
`{"ev":"error","msg":"…"}`, `{"ev":"closed"}`.

The control channel carries **no pixels** — only the surface IDs (once, at `open`), a
per-frame "surface *i* is ready" tick, and encoder controls. The producer owns the
correctness gate: only send `frame` for surface *i* once the GPU render into it has
completed (wait the render fence; a Vulkan↔Metal shared timeline semaphore is the later
optimization).

## Media channel (UDP)

Each access unit (one encoded frame) is serialized to **Annex-B** (`00 00 00 01` start
codes; on keyframes the VPS/SPS/PPS param sets are prepended inline so a mid-stream joiner
can start at the next IDR), then split into MTU-sized fragments. Every fragment is one UDP
datagram: a fixed 20-byte header + payload.

### Packet header (36 bytes for v2, 20 for v1, big-endian)

```
offset size field                notes
0      1    version              = 2 (see "Two live versions" below; 1 is still valid)
1      1    flags                bit0 KEYFRAME  bit1 FRAME_END  bit2 PARAM_SETS(informational)
                                 bit3 STEREO_SBS  bit4 POSE_VALID
2      1    codec                0 = HEVC, 1 = H.264
3      1    reserved             = 0
4      4    frameSeq             u32, the producer's `seq`
8      2    fragIndex            u16, 0-based fragment within this frame
10     2    fragCount            u16, total fragments in this frame
12     8    ptsMicros            u64, presentation timestamp (microseconds)
--- v2 only, the "pose tail" ------------------------------------------------------------
20     8    poseFrameId          u64, the head's compositor frame index
28     8    poseDisplayTimeNanos u64, that frame's predicted display time in the HEAD's clock
--------------------------------------------------------------------------------------
36     …    payload              Annex-B byte range for this fragment (20 on a v1 packet)
```

#### Two live versions, both permanent

Version 2 appends the pose tail; version 1 has no tail. **Both are valid and neither is going away.**
The macOS sender emits v2 because its head is a Vision Pro streaming its pose back up
(`__visionpose__`); the Linux/NVENC sender emits v1 because its host tracks locally through OpenXR
and has no uplinked pose to report. A receiver must parse both, keyed on the version byte, and reject
anything else rather than misparse it.

#### The pose tail

`POSE_VALID` (bit 4) says whether the tail means anything — a flag, not a sentinel, because
compositor frame index 0 is perfectly legitimate. The tail bytes are always written on a v2 packet
even when the flag is clear, so a receiver's parse stays a length check rather than a per-packet
branch. Like `KEYFRAME` and `STEREO_SBS`, the tail rides **every** fragment of a frame.

`poseDisplayTimeNanos` is in the **head's own clock**, not the sender's, because the only thing that
consumes it is the head: it hands the value straight back to
`WorldTrackingProvider.queryDeviceAnchor(atTimestamp:)` and sets `drawable.deviceAnchor` from the
result, which is how a streamed frame gets reprojected to where the head actually is instead of to
where it was when the Mac started drawing.

`ptsMicros` is deliberately **not** reused for this, even though it is nominally a presentation
timestamp. The producer stamps it on the ideal cadence grid (`seq * interval`), not from a clock,
because a real timestamp bakes render-tick quantisation into the stream and a receiver scheduling
against it faithfully reproduces the irregularity — see the rationale at `FieldRTTStreamer`'s submit
path. The two fields are different quantities with different consumers. `frameSeq` is likewise not
reusable: it is also the ring-slot selector and the `ptsMicros` basis.

Default payload ≤ **1200 bytes** (safe under a 1500 MTU; raise if jumbo frames are on).
`KEYFRAME` is set on **every** fragment of an IDR frame; `FRAME_END` marks the last
fragment (`fragIndex == fragCount-1`). `STEREO_SBS` (also set on every fragment, a
stream-level fact) means the access unit is a **side-by-side stereo pair** (left|right in
one double-wide frame): the receiver presents it per-eye (Camera Index Switch sampling the
L/R halves). The producer owns making the content actually SBS; the flag only carries intent.

### Receiver reassembly

- Accumulate fragments by `frameSeq`. When all `fragCount` are present, concatenate by
  `fragIndex` → a complete Annex-B access unit → feed the decoder. Carry the pose tail onto the
  completed frame, taking it from whichever fragment has it rather than overwriting from a later
  one, so an interleaved v1 fragment from a restarted sender cannot erase a pose already held.
- If a fragment is missing when `FRAME_END`'s frame should be complete (or a newer
  `frameSeq` arrives first), **discard the whole frame**. If the discarded frame was a
  reference, output freezes until the next `KEYFRAME` — bounded by `idrIntervalMs`.
- No jitter buffer beyond what reassembly needs: the surface is world-locked, so present
  the newest complete frame and drop stragglers.

## Implementing the receiver (visionOS)

This document is the authoritative contract; the receiver in the visionOS app
(`../field-visionos`) implements against it, not against the sender's code. Shape:

1. **UDP socket** bound to the `dest` port. One `recvfrom` loop.
2. **Reassembly** keyed by `frameSeq` (see rules above) → a complete Annex-B access unit.
3. **Decode** with VideoToolbox: build a `CMVideoFormatDescription` from the inline
   VPS/SPS/PPS on the first `KEYFRAME`, then feed access units to a `VTDecompressionSession`
   (or `AVSampleBufferDisplayLayer`). Output is a `CVPixelBuffer`.
4. **Present** by copying the `CVPixelBuffer` into a RealityKit `LowLevelTexture`, sampled by
   a `ShaderGraphMaterial`: plain full-frame for a flat dock, or a **Camera Index Switch**
   node sampling the left/right halves for the stereo dock (SBS is just a wider frame — no
   protocol difference). Present in a dimmed `ImmersiveSpace`.

Local references on this machine for the present step: `~/temp/openimmersive` (stereo/immersive
video player) and Apple's `ConvertingSideBySide3DVideoToMultiviewHEVC…` sample.

## Linux sender addendum: fd handoff instead of `alloc`

The Linux sender is **`native/field-encode-linux/`** (CMake/C++, NVIDIA driver's `libcuda.so.1` +
`libnvidia-encode.so.1` via dlopen — see its README). Everything above is unchanged (same control
commands, same 20-byte media header, same fragmentation/reassembly rules); only how `open.surfaces`
is populated differs, because surface *ownership* is reversed from Mac:

- **Mac**: the helper allocates the IOSurface ring (`alloc`) because a surface born via
  `IOSurfaceCreate` carries a real pixelFormat that CoreVideo can wrap, unlike MoltenVK's export.
  The JVM looks the ring up by global `IOSurfaceID` and imports it as the RTT backing.
- **Linux**: there is no `alloc` command — a helper process can't allocate a *Vulkan* image, so the
  JVM (which owns the Vulkan render) allocates the RTT ring directly, with each color image's
  backing memory made exportable (`VK_KHR_external_memory_fd`, `VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT`
  — the NVIDIA-Vulkan-to-NVIDIA-CUDA same-GPU handle type, not the generic cross-vendor
  `VK_EXT_external_memory_dma_buf`, which would need a DRM format modifier this same-vendor path
  doesn't). See `afield.four.texture.VulkanExternalMemory` / `Image.exportOpaqueFd()`.

So on Linux, `open` carries an `fdSocket` path and `surfaces` is an array of `{size,rowPitch}`
objects (no fd/pid in the JSON at all — see below for why):

```json
{"cmd":"open","fdSocket":"/tmp/field-encode-abc123.sock",
 "surfaces":[{"size":3686400,"rowPitch":5120}, ...],
 "width":1280,"height":720,"fps":60,"bitrateKbps":20000,"dest":"host:port"}
```

| field      | meaning |
|------------|---------|
| `fdSocket` | path to an AF_UNIX SOCK_STREAM socket the helper binds+listens on, for the fd handoff below |
| `size`     | the exact Vulkan allocation size (`VkMemoryRequirements.size`) — CUDA's `cuImportExternalMemory` requires an exact match |
| `rowPitch` | the image's actual linear row pitch (`vkGetImageSubresourceLayout`, not `width * bytesPerPixel` — VK_IMAGE_TILING_LINEAR may pad rows for alignment) |

**A raw fd number can't travel over JSON** — it's a per-process file descriptor, not a system-wide
handle like an IOSurfaceID, so the actual fds cross via **SCM_RIGHTS over the `fdSocket` Unix
domain socket**: the helper binds+listens on it as part of handling `open`, then the producer
connects once and sends every ring surface's fd in a single `sendmsg()` call (one SCM_RIGHTS
control message carrying all of them, in the same order as the `surfaces` array) plus a 1-byte
dummy payload. The helper `accept()`s one connection and `recvmsg()`s them out in that same order.

*Why not simpler options first considered:* duplicating the fd out of the producer's
`/proc/<pid>/fd/<fd>` (no native glue needed) works for anonymous-inode files (pipes, eventfds,
real dma-bufs) but **not** for NVIDIA's `VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT` export,
which turned out to be backed by `/dev/nvidiactl` (a real device-special file): reopening that
path performs a *fresh* `open()` against the driver, yielding an fd that's numerically valid but
semantically unrelated to the original memory object — `cuImportExternalMemory` then fails with
`CUDA_ERROR_UNKNOWN` (999). SCM_RIGHTS duplicates the actual kernel `struct file*` regardless of
what's behind it, which is why it's the mechanism actually used, at the cost of needing a small
JNI shim on the JVM side (`native/field-fdpass/`) since Java has no standard-library
`sendmsg`/ancillary-data support. That JNI class is deliberately kept off Field4's main jvmMain
classpath — see `afield.four.texture.UnixFdPass`'s doc comment — because a class declaring a
native method breaks Field4's live box/REPL execution if it's ever loaded from that classpath.

The Vulkan-side memory allocation must also be *dedicated*
(`VkMemoryDedicatedAllocateInfo`/`CUDA_EXTERNAL_MEMORY_DEDICATED`) or the same
`cuImportExternalMemory` error occurs — NVIDIA's driver tags opaque-fd memory exported from an
image as dedicated internally, and the CUDA import must declare the same.

The producer must keep its own fd open for the whole streaming session — the helper only ever
holds a duplicate (obtained via the kernel, not a copy of the number), and closes it on `close`.

No `NV_ENC_INPUT_RESOURCE_TYPE_CUDAARRAY`/optimal-tiling path: the imported memory is registered
with NVENC as `NV_ENC_INPUT_RESOURCE_TYPE_CUDADEVICEPTR` (a flat pointer + pitch), which is why the
Vulkan image is `VK_IMAGE_TILING_LINEAR` rather than `VK_IMAGE_TILING_OPTIMAL` on this path — simpler
to get right than a CUDA mipmapped-array registration, at a render-throughput cost that's irrelevant
at the resolutions/framerates this streams.

There is no `alloc`-equivalent event on Linux; `open` still replies `{"ev":"opened"}` or
`{"ev":"error",...}` same as Mac. `frame`/`bitrate`/`close`/`selftest` are byte-for-byte identical
on both senders — `selftest` allocates its own CUDA device buffers and needs no fd handoff at all.

## Versioning

Bump `version` on any header change. Senders and receivers reject mismatched versions
loudly (an `error` event / a log line) rather than misparsing.
