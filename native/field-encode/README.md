# field-encode

The macOS **sender co-process** for the visionOS stereo/flat docks (see
`dev_docs/vision_frontend.md` → "Transport & encode" and "The sender seam"). It takes an
IOSurface the Field backend rendered into, HEVC/H.264-encodes it with VideoToolbox, and
streams fragmented Annex-B over UDP. The visionOS client decodes with VideoToolbox and
presents via a `LowLevelTexture` (+ Camera Index Switch for stereo).

It is a thin, replaceable bridge — *not* native code fused into the JVM. The Linux/NVENC
sender is a separate binary behind the **same** control + UDP contract (`WIRE_FORMAT.md`),
so the visionOS receiver never learns which backend produced the stream.

**The receiver** (the other end of this stream) is the visionOS shell at
**`../field-visionos`** — see its `Field4Vision/RTT/` (UDP reassembly → VideoToolbox decode →
RealityKit) and its `README.md`. **The producer** that drives this helper from Field is
`afield.four.stream.FieldRTTStreamer` (JVM). Contract shared by all three: `WIRE_FORMAT.md`.

## Build & run

```sh
swift build                       # -> .build/debug/field-encode
swift build -c release            # -> .build/release/field-encode
```

### Boot it (self-test — packets into the void, no RTT needed)

```sh
./.build/release/field-encode --selftest --dest 127.0.0.1:9000 --frames 120
```

The helper allocates its own IOSurfaces, fills them with an animated pattern, and streams
`--frames` encoded HEVC frames to `--dest`. Point `--dest` at the headset (or a local UDP
listener) and watch the packets fly. The same `selftest` is also a stdin command
(`{"cmd":"selftest","dest":"…","frames":120}`).

### Real driving (over stdin/stdout by a JVM `ProcessBuilder`)

```sh
printf '%s\n' \
  '{"cmd":"open","surfaces":[<IOSurfaceID>],"width":3840,"height":1080,"fps":60,"bitrateKbps":40000,"dest":"mi2:9000"}' \
  '{"cmd":"frame","surface":0,"seq":1,"ptsMicros":16666}' \
  | ./.build/debug/field-encode
```

The JVM side is `afield.four.stream.FieldEncodeProcess` in the field4 repo.

`surfaces` is a small ring (2–3) of **global** `IOSurfaceID`s the producer renders into
round-robin; `frame` says "surface *i* is ready, encode it now." SBS is the producer's
concern — a double-wide (or over-under) frame is just a wider IOSurface; the receiver's
Camera Index Switch splits it. See `WIRE_FORMAT.md` for the full protocol.

## Status

- **Verified:** builds (Swift 6.3); control-loop parse/dispatch + error paths; full
  encode→packetize→UDP via `--selftest` (a synthetic IOSurface pattern) — a loopback
  receiver confirmed correct fragmentation, all frames delivered, periodic IDR keyframes,
  and on-target bitrate; and a JVM `ProcessBuilder` launching + driving the helper over
  stdin/stdout end-to-end (`FieldEncodeProcess`).
- **Not yet exercised** (needs the real producer + a receiver): encode from Field's *actual*
  RTT IOSurface (vs. the synthetic self-test surface), and UDP reassembly + decode on the
  visionOS side. IOSurface-backing the RTT (MoltenVK + `VK_EXT_metal_objects`) is the
  producer's responsibility (owner: renderer); the receiver is spec'd in `WIRE_FORMAT.md`.

## Correctness gate (producer side)

Only send `frame` for surface *i* after the GPU render into it has completed. Minimal:
wait the Vulkan render fence before the `frame` line. Optimization: a Vulkan↔Metal shared
timeline semaphore so the encoder GPU-waits without a CPU stall.
