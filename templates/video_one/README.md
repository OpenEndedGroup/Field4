# A room for a stereo movie

Play a side-by-side stereo movie on a curved screen you can move around the room. The bundled
demo loads automatically; use `FIELD_STEREO_VIDEO` or the file control to choose another local movie.
Each frame is decoded once, and each eye samples its own half. The desktop preview shows the eye image.
The player has no audio. Optional motion exposure accumulates movie frames while the rest of the
scene stays sharp.

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

**Playing** pauses or resumes. **Seek (seconds)** pauses at the chosen position; resume with
**Playing**. Seeking runs in the background, and successive requests are combined. The displayed
position and scrubber follow the uploaded frames without making a document edit on every tick.
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

For a ten-bit headset stream, configure a supported AV1 or HEVC encoder with `bit-depth: 10`,
then check the encoder and decoder negotiated by the live WiVRn connection. Floating-point eye
images alone cannot establish the precision of the whole path. WiVRn's single-projection-layer
fast path bypasses its eight-bit layer-squashing intermediate; adding separate OpenXR layers would
require checking that path again. The Field browser here is part of the application projection.

## Code

**4 · Video shader** has editable `vertex` and `fragment` tabs. Use **Reload Shader** (Alt+R)
to reload them without restarting playback. `moduleHere { +movie }` supplies the reload actions,
typed Kotlin accessors, eye target and texture bindings. `uv` is the full per-eye coordinate,
`eye` is 0/1, and `luma`/`chroma` sample the packed P010 planes. The push constants document the
screen and colour conversion. **Swap eyes** mirrors the transport checkbox.

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

**Material: unlimited**, **Material: 24 fps** and **Material: 12 fps** set how often the movie
image updates, independently of playback speed. Decode continues on the playback clock; each capped
tick uploads the newest ready picture and discards intermediate samples. At 8×/12 fps, a 24 fps
movie advances approximately sixteen source frames per material tick. The status shows the cap.

The sharp texture and temporal exposure both hold between ticks. Accumulation adds each
selected material frame once; persistence remains a real-time half-life. Headset rendering,
screen placement and current eye poses stay at the runtime cadence. New files, scrubbing and
clear requests take effect on the next permitted material tick. Display timing quantizes tick
presentation; missed ticks are skipped. Selecting unlimited restores ordinary per-source-frame
updates. The effect holds the movie image while VR keeps rendering at its usual rate; there is no
optical-flow interpolation between source pictures.

## Paired exposure cadence

**Paired exposure cadence** gives accumulated sections a paired-frame rhythm. At the default
**Exposure pairs / second = 12**, one accumulated image is held for 1/12 real second. The first
half is optionally softened and darkened; the second half shows the same exposure clear.
That is 12 pairs, or 24 half-steps, per second even when source playback is 8×. This option
starts off; enable it alongside **8x blur**, or leave it enabled while a score cuts between
sharp and accumulated sections. Sharp sections bypass it automatically.

**Soft-half blur sigma (px)** controls a Gaussian in original video pixels; **Soft-half blur
mix** blends it with the full-resolution held exposure. Zero sigma or zero mix preserves the
full-resolution image. **Soft-half darkening** removes a fraction of linear-light brightness:
0.08 removes eight percent. The default sigma is 2 pixels, mix 1 and darkening 0.08.

The exposure continues accumulating selected source frames normally. Only its displayed copy
is held for each pair; the separate **Material: 12 fps** control limits source contributions
and is not required for this effect. Pause freezes both the held exposure and its current
half; seek, clear and loop wrap start a fresh exposure. Missed pairs are skipped, never queued.

A full-resolution RGBA16F snapshot and two Gaussian passes are prepared before the eye draws,
once per pair (and when the blur radius changes). Every tap stays inside its source eye.
There is no previous headset image, readback or extra queued GPU frame: current eye poses
still draw at the headset rate. Holding the material is the intentional apparent-framerate
effect. The extra images are allocated lazily and retained until movie replacement or close;
3840 × 1080 uses about 55 MiB beyond the temporal accumulator.

The API is `movie.exposureCadenceEnabled`, `exposurePairsPerSecond`, `exposureBlurSigma`,
`exposureBlurMix` and `exposureDarkening`. Box 4's `accumulatedImage` is the held full-resolution
exposure while enabled, and `softenedImage` is its Gaussian version. `pc.accumulation.z` is the
soft-half blur blend; `.w` is its linear darkening. Both are zero during the clear half.
