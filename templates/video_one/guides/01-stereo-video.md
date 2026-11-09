# A room for a stereo movie

`video_one` is a small side-by-side movie player.
**4 · Video shader** has editable `vertex` and `fragment` tabs. Use **Reload Shader** (Alt+R) in that box to reload
them without restarting playback. `moduleHere { +movie }` supplies the standard reload
actions and typed Kotlin accessors; `+movie` supplies its eye target and texture bindings. `uv` is the full per-eye coordinate, `eye` is 0/1, and
`luma`/`chroma` sample the packed P010 planes. The push constants document screen and
colour-conversion parameters. The **Swap eyes** button mirrors the transport checkbox.
 The file is decoded once; each eye samples
its own half on a shared room-locked cylindrical screen. The desktop preview samples the
actual eye image. There is no scene blur stack or audio; optional temporal accumulation happens directly in video coordinates.

## Start

Launch **1 · Headset / preview / Field pane**, **2 · Stereo screen placement**,
**4 · Video shader**, **5 · Temporal accumulation shader**, then **3 · Movie / transport**. Enter a local path in **Full SBS file** and press **Load movie**.
`~` expands to your home directory. A failed open keeps the preceding movie intact and
reports the error. The current file loops at its end.

For WiVRn, connect the headset, set `headsetMode = true` in box 1 and start in a fresh Field
process. OpenXR provides the eye dimensions; the desktop-only fallback uses a fixed size.
Changing WiVRn resolution needs the same restart as `text_one`. Set `withFieldBrowser = false`
if you want only the movie; otherwise the Field editor has its own companion window and a
sharp pane in the headset. The second native window is the headset mirror. Hide the VR pane
with **Show Field pane in headset**; restore it using the desktop/remote Field controls.
Controller grip positioning and the headset keyboard/mouse path come from `FieldVrWorkspace`.

## Screen

- **Arc width** is the width measured along the curved surface in metres.
- **Bend** is the total horizontal bend angle. Zero is flat. Positive brings both edges toward
  the viewer; negative takes them away. Height remains width divided by displayed aspect.
- **Displayed aspect ratio** controls the geometry, not cropping. A new file starts at its
  native per-eye ratio `(packed width / 2) / height`: no assumed horizontal squeeze.
- **Center X/Y/Z**, **Yaw**, **Pitch** and **Roll** place the screen in the room. Head movement
  changes the view of this fixed surface rather than moving the screen with your head.
- **Bring screen in front** places it upright at the current horizontal gaze direction, using
  **Recenter distance**. **Bring Field pane in front** independently recenters the editor.

Left half goes to the left eye by default. **Swap left and right** reverses this; **Flip
vertically** is available for unusually oriented files. Sampling clamps separately within
each half so bilinear filtering cannot pull pixels from the other eye across the center seam.

## Transport

**Playing** pauses/resumes. **Seek (seconds)** pauses at the requested position; resume with
**Playing**. Successive seeks are coalesced and decoded in the background rather than queued
as a long sequence of render-thread operations. The displayed position follows uploaded
frames and the scrubber follows it without generating one document edit per playback tick.
**Back to start** also pauses. Compressed files seek to a nearby keyframe before decoding the
requested frame; a long GOP can make a seek take longer than ordinary playback.

## Precision

The decoder supplies P010: ten-bit luma/chroma values in sixteen-bit storage. Colour conversion
uses the file's matrix/range tags (BT.709, BT.601 or BT.2020 non-constant luminance; unspecified
SDR defaults to BT.709/limited). The movie stays at its original packed resolution until the
shader projects it onto the screen. Its native chroma subsampling is not recoverable detail.
This player is SDR: tagged PQ/HLG content is refused rather than silently tone-mapped.

Shared multiview targets now default to linear RGBA16F, and OpenXR prefers an advertised
RGBA16F/16-bit or ten-bit swapchain over an eight-bit one. This also benefits `text_one` and
`dust_six` after a fresh launch. Browser textures can be eight-bit without forcing the entire
eye image to eight-bit. Legacy runtimes can still require a lower-precision swapchain; check
the format/precision report in box 1. The mirror may quantize to the desktop display format.

A floating-point application target does **not** prove a ten-bit headset stream. WiVRn's
negotiated encoder and decoder must also be checked. Configure a supported AV1 or HEVC encoder with `bit-depth: 10`; the live connection
is the authority. WiVRn's
single-projection-layer fast path bypasses its eight-bit layer-squashing intermediate; adding
separate OpenXR composition layers would require auditing that path again. The Field browser
here is part of the application projection, not a separate OpenXR layer.

## Code

`StereoVideoSurface` owns the decoder, mesh and one pre-scene upload hook; the shader box
owns its `moduleHere` pipeline. Its
`placement` exposes the screen geometry; `load`, `play`, `pause` and `seek` handle transport.
Closing box 2 closes its owned movie; stop box 3 first so its render loop is not using a closed
surface. Stop box 1 last. The supplied stop macro uses this order. A new file is an explicit
open operation and can briefly wait for old GPU references to drain; ordinary playback and
scrubbing do not wait for the device to go idle.

The WiVRn 26.6.2 Quest client source also selects an eight-bit sRGB eye swapchain after
video decoding. Ten-bit transport therefore does not by itself establish ten-bit final
headset composition. A higher-precision client path must be negotiated and tested separately.

## Speed and temporal exposure

**Speed** continuously changes the media clock from 0.1× to 8× without seeking. At more than
4×, **Alternate frames above 4x** keeps even source-frame indices before conversion/upload.
For 24 fps material, 2× supplies 48 pictures/sec; 8× with alternate frames supplies up to 96.
The decoder still reconstructs required reference pictures. **Skip non-reference pictures**
optionally asks FFmpeg to omit disposable pictures too; codec/GOP structure determines how
much work that saves and which additional pictures survive. Exact scrubs ignore both options.
The headset continues rendering at its runtime cadence with current poses.

**Accumulation mix** blends sharp video with a full-resolution linear-light running mean in
ping-pong RGBA16F images. It is video-local, before screen projection: head movement never
gets baked into the exposure. **Persistence half-life (s)** decays old samples in real time;
**Incoming frame weight** sets the weight of each newly uploaded selected frame. Repeated
headset draws of a held source image never add extra weight. Zero half-life replaces history
with each new sample. Zero incoming weight holds the old image; after a clear it stays sharp
until a positive-weight sample arrives. CPU double-precision scalar normalization keeps long
exposures from overflowing FP16 or losing small additions to a large weight sum.

**Clear accumulation**, seek, loop wrap and file replacement discard the old exposure. Enabling
a previously bypassed accumulation starts fresh. A clear while paused seeds the held frame.
Setting mix to zero skips the accumulation pass. The two textures use 16 bytes per packed
source pixel together (about 63 MiB for 3840×1080), allocated on first use.

**8x blur** selects speed 8×, mix 1 and resumes; **1x sharp** cuts back to speed 1× and mix 0.
**2x sharp** selects speed 2× and mix 0. **2x blur** selects speed 2×, mix 1, a 4-second half-life and resumes. Other shortcuts retain persistence; all retain input weight. Box **5 · Temporal accumulation
shader** owns the editable accumulation `vertex`/`fragment`; launch it before Movie / transport.
It uses `moduleHere(configuration)` for standard Alt+R. `weights.x` is the normalized incoming
fraction, and `weights.y` says whether the preceding image is valid. The final movie shader
still lives in box 4, with `accumulatedImage` and `pc.accumulation` available for further grading.

Status shows decode time, selection stride, bounded-queue drops and exposure sample count.
A drop means an intermediate sample was omitted to keep playback responsive; no unbounded
backlog is kept. Sustainable speed depends on the movie's codec, resolution and decoder cost.


## Material cadence

**Material: unlimited**, **Material: 24 fps** and **Material: 12 fps** set a wall-time publication
rate independently of playback speed. The status reports the selected cap. Decode continues
following the playback clock; capped ticks upload only the newest ready picture and discard
intermediate queued samples. They do not play those samples later as a delayed backlog. At
8×/12 fps, a 24 fps movie advances approximately sixteen source frames per material tick.

The sharp texture and temporal exposure both hold between ticks. Accumulation adds each
selected material frame once; persistence remains a real-time half-life. Headset rendering,
screen placement and current eye poses stay at the runtime cadence. New files, scrubbing and
clear requests take effect on the next permitted material tick. Display timing quantizes tick
presentation; missed ticks are skipped, never replayed as a burst. Selecting unlimited restores
ordinary per-source-frame publication. This is an apparent frame-rate effect, not a 12/24 Hz VR
render loop, and it does not interpolate optical flow between source pictures.
