# field-encode-linux

The Linux **sender co-process** for the visionOS stereo/flat docks (see
`dev_docs/vision_frontend.md` → "Transport & encode" and "The sender seam", and
`../field-encode/WIRE_FORMAT.md` → "Linux sender addendum"). It takes a Vulkan image the Field
backend rendered into, imports its exported memory into CUDA, HEVC/H.264-encodes it with NVENC,
and streams fragmented Annex-B over UDP — the same control + wire protocol as the Mac
(`../field-encode/`) sender, so the visionOS receiver never learns which backend produced the
stream.

Unlike the Mac sender, this helper never allocates the surface ring itself (a helper process can't
allocate a *Vulkan* image) — the JVM producer allocates it and hands the real fds over via
SCM_RIGHTS on a Unix domain socket (`fdSocket`), not as global IDs in the JSON; see the
WIRE_FORMAT.md addendum for why (a `/proc/<pid>/fd/<fd>` duplication approach was tried first and
doesn't work for these specific fds — see below).

**The receiver** (the other end of this stream) is the visionOS shell — unchanged by this
backend; see `../field-encode/README.md`. **The producer** that drives this helper from Field is
`afield.four.stream.FieldRTTStreamer` (JVM), same class used for the Mac backend — it branches
internally on OS.

## Build & run

No CUDA toolkit or NVIDIA Video Codec SDK install needed — only the driver's runtime libraries
(`libcuda.so.1`, `libnvidia-encode.so.1`, shipped by `nvidia-utils`). The dlopen-based headers
(`third_party/ffnvcodec/`, vendored from `nv-codec-headers`/`ffnvcodec-headers`) declare everything
needed to build.

```sh
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j$(nproc)          # -> build/field-encode-linux
```

The JVM's fd handoff also needs the tiny JNI shim in `../field-fdpass/` built once
(`cmake -B build && cmake --build build` there too) — see that directory's README.

### Boot it (self-test — packets into the void, no RTT needed)

```sh
./build/field-encode-linux --selftest --dest 127.0.0.1:9000 --frames 120
```

Allocates its own CUDA device buffers, fills them with an animated pattern (via `cuMemcpyHtoD` —
there's no device kernel here, no nvcc on this build), encodes + streams `--frames` frames to
`--dest`. Same CLI shape as the Mac sender's `--selftest`, and also available as a stdin command
(`{"cmd":"selftest","dest":"…","frames":120}`).

### Real driving (over stdin/stdout by a JVM `ProcessBuilder`)

The JVM side is `afield.four.stream.FieldEncodeProcess` + `afield.four.stream.FieldRTTStreamer`
(same classes as the Mac backend — see `FieldEncodeProcess.defaultBinaryPath()`, which picks this
binary automatically on Linux). See WIRE_FORMAT.md's Linux addendum for the exact `open` shape
(`fdSocket` + `{size,rowPitch}` surfaces, fds sent separately via SCM_RIGHTS) — driving it by hand
over a raw stdin pipe isn't practical since the fd handoff needs a real sender process holding the
Vulkan-exported fd open, not just a JSON string.

## Status

**Verified end-to-end against the real visionOS receiver**, not just loopback: a real
Vulkan-rendered RTT → `VK_KHR_external_memory_fd` export → SCM_RIGHTS handoff →
`cuImportExternalMemory` → NVENC encode → UDP → the actual Field4Vision app, sustained 90+ seconds
over the real network (Tailscale), with the receiver reporting "0 dropped, 0 malformed" across
3300+ datagrams / 1600+ frames. Decoded pixel values matched the sent color exactly (round-tripped
a known RGB value through YUV and back) — NVENC's default color encoding needs no explicit VUI
colorimetry signaling.

Also verified in isolation: builds against dlopen'd `libcuda.so.1`/`libnvidia-encode.so.1` (an RTX
4090, driver 610.43.02, no CUDA toolkit installed); the full control-loop parse/dispatch + error
paths over stdin; the full encode→packetize→UDP path via `--selftest` for both HEVC and H.264 — an
independent Python loopback reassembler confirmed every fragment/frame delivered in order with
correct `KEYFRAME`/`FRAME_END` flags, periodic IDR at the configured interval, and on-target
bitrate; the reassembled Annex-B stream round-tripped through `ffprobe`/`ffmpeg -f null` with zero
decode errors.

**Root cause of the "grey/white flicker" seen throughout early live testing, found via a
byte-level bitstream diff against the known-good Mac encoder (done by the visionOS receiver
side): a duplicate-`frameSeq` bug, and it was on the producer side, not the encoder or receiver.**
`FieldRTTStreamer`'s automatic per-frame trigger
(`Renderer.afterCurrentGraphicsFrameComplete { encoder.frame(...) }`) turned out to be working
correctly all along (confirmed by inspecting `Renderer`'s `graphicsCompletionCallbacks` queue
directly — small and steadily draining, not backlogged). Early ad hoc testing wrongly concluded it
"never fires" (checked too soon after stream start) and layered a manual polling thread on top as
a workaround; that workaround then ran *concurrently* with the real mechanism, both independently
calling `encoder.frame()` for overlapping frame numbers against different actual ring-buffer
content each time. On the wire this showed up as two different encoded frames sharing one
`frameSeq` — the receiver correctly treats the second as a stale duplicate and drops it per
WIRE_FORMAT.md's rules, which silently breaks the P-frame reference chain (only self-contained
IDRs survive), exactly matching the observed "occasional correct color flash, grey otherwise."
**Do not add a manual/parallel frame-trigger loop alongside `FieldRTTStreamer.start()` — the
built-in mechanism, registered automatically in `renderer.renderList`, is sufficient on its own.**
This was purely an artifact of ad hoc live-patching during debugging, not a bug in the shipped
`FieldRTTStreamer`/`Renderer` code itself.

Separately, real (and worth keeping) NVENC config fixes landed in `Encoder.hpp` while chasing this:
`profileGUID = AUTOSELECT` was silently resolving to HEVC High profile/tier (the auto-selected
Level 4.0's Main-tier bitrate ceiling, 10 Mbps, is below the 20-30 Mbps requested — pinning tier
alone without also raising the level gets silently overridden back to High), and the P4 preset's
default lookahead/multi-pass rate control could pick a multi-reference structure. Now pinned:
`NV_ENC_HEVC_PROFILE_MAIN_GUID`/`NV_ENC_H264_PROFILE_MAIN_GUID`, `tier = NV_ENC_TIER_HEVC_MAIN`,
`level = NV_ENC_LEVEL_HEVC_51` (40 Mbps Main-tier ceiling), `numRefL0`/`numRefL1 =
NV_ENC_NUM_REF_FRAMES_1`, `maxNumRefFramesInDPB = 1`, `useBFramesAsRef = DISABLED`, `multiPass =
NV_ENC_MULTI_PASS_DISABLED`, `lookaheadLevel = NV_ENC_LOOKAHEAD_LEVEL_0`. A verification log line
right before `nvEncInitializeEncoder` reads these back from `config_` so a future "did my NVENC
setting actually take?" question can be answered from one log line instead of a live bitstream
diff.

**Do not** re-add VUI colorimetry signaling to `Encoder.hpp` — it was tried once to chase what
turned out to be the duplicate-`frameSeq` bug above (misdiagnosed as a colorspace issue at the
time), reverted after correlating with the receiver hanging after the first frame. NVENC's default
color encoding needs no explicit VUI signaling — a known test color round-tripped through
YUV encode/decode to the exact right RGB values with none.

**Open item, likely receiver-side** (see `~/field4_visionos.zip`'s `RTT/VideoDecoder.swift`): a
~70 second delay was observed once between first packet arrival and first successful decode,
despite zero packet loss/malformation reported the entire time. This predates the duplicate-`frameSeq`
fix above and may or may not be related (dropped/stale frames could plausibly stall a decode-session
bring-up state machine) — worth re-checking now that the sender emits a clean, unique-`frameSeq`
stream.

## Correctness gate (producer side)

Same as the Mac sender: only send `frame` for surface *i* after the GPU render into it has
completed. `FieldRTTStreamer` is intended to do this via `Renderer.afterCurrentGraphicsFrameComplete`
for both backends, but see the open item above — that mechanism needs verification/fixing before
relying on it for anything beyond a manual/experimental drive loop.
