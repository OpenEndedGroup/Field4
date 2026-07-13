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
| `frame`    | `surface:int` (index into `surfaces`), `seq:u32`, `ptsMicros:u64`, `forceIdr?:bool` | Encode surface *i* now. `seq` is the producer's monotonic frame counter and rides through to the wire header. |
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

### Packet header (20 bytes, big-endian)

```
offset size field       notes
0      1    version     = 1
1      1    flags       bit0 KEYFRAME  bit1 FRAME_END  bit2 PARAM_SETS(informational)  bit3 STEREO_SBS
2      1    codec       0 = HEVC, 1 = H.264
3      1    reserved    = 0
4      4    frameSeq    u32, the producer's `seq`
8      2    fragIndex   u16, 0-based fragment within this frame
10     2    fragCount   u16, total fragments in this frame
12     8    ptsMicros   u64, presentation timestamp (microseconds)
20     …    payload     Annex-B byte range for this fragment
```

Default payload ≤ **1200 bytes** (safe under a 1500 MTU; raise if jumbo frames are on).
`KEYFRAME` is set on **every** fragment of an IDR frame; `FRAME_END` marks the last
fragment (`fragIndex == fragCount-1`). `STEREO_SBS` (also set on every fragment, a
stream-level fact) means the access unit is a **side-by-side stereo pair** (left|right in
one double-wide frame): the receiver presents it per-eye (Camera Index Switch sampling the
L/R halves). The producer owns making the content actually SBS; the flag only carries intent.

### Receiver reassembly

- Accumulate fragments by `frameSeq`. When all `fragCount` are present, concatenate by
  `fragIndex` → a complete Annex-B access unit → feed the decoder.
- If a fragment is missing when `FRAME_END`'s frame should be complete (or a newer
  `frameSeq` arrives first), **discard the whole frame**. If the discarded frame was a
  reference, output freezes until the next `KEYFRAME` — bounded by `idrIntervalMs`.
- No jitter buffer beyond what reassembly needs: the surface is world-locked, so present
  the newest complete frame and drop stragglers.

## Implementing the receiver (visionOS)

This document is the authoritative contract; the receiver in the visionOS app
(`~/temp/field4_visionos`) implements against it, not against the sender's code. Shape:

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

## Versioning

Bump `version` on any header change. Senders and receivers reject mismatched versions
loudly (an `error` event / a log line) rather than misparsing.
