# Stereo movie and shared eye precision — 2026-11-08

The `video_one` example has context/browser/preview, screen placement, editable shader and
transport boxes. The shader uses `moduleHere { +movie }`; ConfiguresModule applies movie
resources and eye-target settings through Parameters' unary-plus operator.
`StereoVideoSurface` decodes one packed frame to P010 on a worker, uploads once in
`MultiviewStereoTarget.beforeScene`, and uses `gl_ViewIndex` to select the corresponding half.
Its curved strip mesh remains room-locked and uses the current predicted eye matrices. The
movie has no audio, full-scene compositor or previous-eye accumulation. Width is arc length.

## Precision changes shared with text_one and dust_six

- Multiview eye targets default to RGBA16F; an already initialized OpenXR target supplies its
  selected format instead. This removes the eight-bit final-eye quantization in both documents.
- OpenXR selects advertised RGBA16F/16-bit/ten-bit formats ahead of the old eight-bit defaults.
  `swapchainColorFormat` and `highPrecisionSwapchain` expose the result; unsupported legacy
  runtimes warn. The new movie example refuses an eight-bit runtime rather than hiding it.
- `PuppetBrowserVrPanel` inherits the target format. Its former hardcoded B8G8R8A8_SRGB pipeline
  attachment would be incompatible with a float eye, even though the browser texture itself
  may remain eight-bit. Browser source precision does not constrain the movie/scene attachment.
- Equal-format eye images still use CopyImage. Different formats use BlitImage, so float/UNORM
  and RGB/BGR changes are conversions rather than incompatible bitwise reinterpretations.
- WiVRn 26.6.2's server bypasses `layer_squasher` for a single projection (including projection
  with depth). Field submits one projection and draws its browser into it. Thus the squasher's
  R8 intermediate is not on this path. Re-audit if adding separate OpenXR composition layers.

## What was actually checked

`four.stereo-video.precision` is a real Vulkan smoke: a lossless FFV1 ten-bit ramp is decoded
through VideoTexture/P010, uploaded through StereoVideoSurface, shaded with its actual fragment
shader and read from the shared float eye image. A pixel-aligned test vertex substitutes for
perspective to make the numerical check meaningful; it is not a substitute image generator.
All 1,024 source levels survive. Eye swap reverses the ramp. The same test covers a paused seek
and background playback looping. `StereoVideoPlacementTest` checks geometry and format policy.

The actual practice movie was probed as 3840x1080, 24 fps, yuv420p10le, BT.709/limited. Its native
per-eye aspect is 16:9, with no squeeze. Real GPU playback and a paused seek to 180 s/frame 4320
were checked in the preview. A separate browser/preview run checked that both scene modules use
the float attachment. These private movie captures are not published documentation assets.

The original scrub failure was a stale decoded frame satisfying a paused seek. Explicit seeks
now carry request identity through publication; old ready frames cannot satisfy them. Timestamp
seeks remain separate from the strict CFR `prepareFrame` API. Large explicit forward seeks jump
to a keyframe and decode on the worker. Stream-duration fallback covers Matroska container
lengths; decoded-frame colour tags and explicit swscale range/matrix setup cover late metadata.

## Runtime boundaries still needing the connected headset

The current host config requests `bit-depth: 10` and AV1/NVENC, retaining HID forwarding. The
old config is `~/.config/wivrn/config.before-video-one-10bit-20261108.json`. A request is not a
negotiation result. Verify actual OpenXR formats and the server's `(av1 10-bit)` settings on
connection, plus the headset decoder's selected format.

The local WiVRn 26.6.2 client source (`client/scenes/stream.cpp`, supported_color_formats) only
selects R8G8B8A8_SRGB/B8G8R8A8_SRGB for its final headset eye swapchain. Do not describe this as
proven end-to-end ten-bit headset composition. A client-side change needs runtime format
capability checks, correct transfer handling in the reprojection/defoveation paths and real
headset testing. Adding a float format to a list alone is insufficient. Headset OS compositor
and physical panel precision are a further boundary, not established by encoder bit depth.

## Host repair after reboot

Both the packaged and locally patched WiVRn binaries referenced removed FFmpeg 8/Boost 1.91
shared libraries. The already existing reconnect-fixed source at
`~/temp/wivrn-reconnect-fix` was cleanly rebuilt against installed libavcodec 63/libavutil 61 and
Boost 1.92. Its source and existing upstream pacer fix were unchanged. The rebuilt server was
installed into the same user-local prefix, preserving the system binary and service override.
The previous executable remains beside it as `wivrn-server.before-system-abi-20261108`.
The packaged OpenXR client libraries resolved their dependencies. The user service starts and
advertises a0; headset negotiation remains a separate check.

Rebuild: `cmake --build ~/temp/wivrn-reconnect-fix/build --clean-first --target wivrn-server -j 8`.
Use the existing prefix/override; do not invent compatibility symlinks between FFmpeg majors.


## Speed and exposure extension

`StereoVideoSurface.speed` continuously re-anchors the monotonic media clock. The default
selection is stride 1 through 4×, stride 2 above it. Optional `skipNonReferenceFrames` asks
libavcodec for `AVDISCARD_NONREF`; required references are retained. Precise seek/prepareFrame
bypass both. A bounded eight-picture queue preserves short decoder bursts; saturation drops
the oldest picture and reports a count. One frame uploads per headset frame, outside rendering.

`VideoFrameAccumulator` owns two packed-source-resolution RGBA16F targets. Each selected upload
records a ping-pong pass before either eye renders. RGB contains a normalized linear-light
weighted mean; alpha is 1, not weight mass. A CPU double scalar tracks decayed mass and supplies
the normalized incoming fraction. This avoids FP16 overflow and weight stagnation. All pixels
receive the same temporal weights; eye swap/flip and current pose apply afterwards. No readback,
GPU wait or previous-eye reprojection is introduced by accumulation. Ordinary sharp playback
bypasses its pass. Clear/file/seek/wrap/re-enable reset history; paused clear seeds the held frame.

The real GPU precision smoke now inverts the ramp in frame two and verifies a ~0.5 linear
average, source-frame gating, paused clear and bypass, as well as 1024 distinct sharp levels.
Validation exposed an initially undefined history descriptor; invalid history now binds the
renderer-owned initialized fallback, and the repeat was validation-clean.

On this machine, `StereoVideoDecodeProbe` over the first 12 media seconds of
`thisLand_3D_SBS.mp4` (3840×1080, HEVC Main10, 24 fps) measured 10.86× at stride 1,
13.23× at stride 2, and 15.21× at stride 2 with non-reference skipping. These are isolated
CPU decode/conversion/copy results, with an active VR session elsewhere, not an end-to-end
120 Hz guarantee or a whole-movie benchmark. The probe takes an explicit local filename and
never publishes footage. Keep this distinction when interpreting the transport status.

Connected 120 Hz run with accumulation enabled: 2× measured 1.997× media speed,
47.93 selected pictures/sec and 119.82 renders/sec; 8× measured 7.998× media speed,
95.98 selected pictures/sec and 119.31 renders/sec. Neither interval dropped queued pictures.
The short measurement intervals were three seconds after one second of warmup each, with
non-reference skipping disabled. This validates this segment and configuration, not every GOP
or all later load. Playback was returned to 1× sharp afterwards.


## Material cadence

`materialFramesPerSecond` accepts 0 (uncapped), 24 or 12. `VideoMaterialCadence` permits at
most one publication per wall-time bucket; stalls skip buckets instead of causing catch-up
bursts. `VideoTexture.update(command, uploadFrame=false)` still requests current background
decode time but leaves the GPU texture unchanged. On capped ticks, `preferLatestDecodedFrame`
consumes only the newest queued picture and closes intermediate pictures. This prevents both
sharp video and accumulation from running late at the capped rate. `omittedMaterialFrames`
counts intentional omissions separately from bounded-queue saturation. Defaults preserve
existing VideoTexture callers. Sharp/accumulated images hold; per-eye poses continue normally.
Clear requests also wait for a material tick. Invalid accumulation views bind an initialized
fallback even before the first capped pass records.

Unit tests verify 24/12 publications per second at a synthetic 120 Hz, no catch-up bursts after
stalls, and immediate mode changes. A connected 8× run with accumulation measured 23.997/11.980
material and accumulation updates/sec, 7.999×/8.014× media progression, and 116.32/117.47 renders/sec.
Each measurement covers three seconds following one second of warmup. Existing float16/10-bit
GPU precision and clear/held-frame tests also pass without validation errors.
