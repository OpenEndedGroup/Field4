# Stereo video in a room

A full-resolution side-by-side movie can play on a curved screen in VR, with the same eye image
shown in a desktop preview. The [video_one starting point](offline.html?file=assets/agent_transcripts/video_one_vr/metadata.edn)
provides file selection, a live scrubber, playback controls and screen placement. It starts with a bundled stereo demo; choose another local file at any time. It has no audio
or scene blur compositor.

Open the starting place, then launch **1 · Headset / preview / Field pane**, **2 · Stereo screen placement**,
**4 · Video shader**, **5 · Temporal accumulation shader**, and **3 · Movie / transport**, in that order.
The supplied launch macro does this for you. The bundled movie starts automatically. To use your own,
enter its path in **Full SBS file** and press **Load movie**. Left half
maps to the left eye; **Swap left and right** reverses that mapping. The screen starts at the
native per-eye aspect ratio, `(file width / 2) / height`. No horizontal squeeze is assumed.
The file remains at its packed source resolution until the video shader samples it.

**Arc width** sets the screen's width in metres, measured along the curve. **Bend** controls its
horizontal angle: zero is flat, positive brings the edges forward. The displayed aspect ratio,
center and yaw/pitch/roll are independent controls. **Bring screen in front** places the screen
upright along the current horizontal gaze direction.

The scrubber pauses at the chosen time; **Playing** resumes. Decoding and compressed-video
seeking happen on a worker, with successive seek requests coalesced. Opening another file is
an explicit operation and can briefly wait for old GPU references to drain.

## Try the starting place

[Open video_one →](offline.html?file=assets/agent_transcripts/video_one_vr/metadata.edn)

The original demo is a three-second loop: three moving spheres and a floor grid, viewed from two
cameras 64 mm apart. It is full side-by-side, 1280 × 360 at 24 fps, with ten-bit YUV 4:2:0 codes
and a BT.709 colour matrix. Each eye receives a 640 × 360 image. This is an intentionally small
source for trying the player; larger files keep their original resolution. The demo is silent.
[Download the source movie](assets/stereo_video/stereo-demo.mkv).

```diffstatic_runner {data-lang=kotlin capture="native-window" native-wait-frames="8" screenshot="assets/stereo_video/player.png" macro-file="../../dev_docs/agents/transcripts/docs_capture/video_one_native.yaml" capture-box="video1_context" wait="1" timeout="240" capture-profile="linux-nvidia" embed=true hidden=true name="stereo-video-player" alt="Field's native stereo-video preview: a curved screen carrying cyan, orange and violet spheres above a floor grid."}
// Decode the bundled ten-bit SBS movie and draw its left-eye view on the room's curved screen.
```

```diffstatic_runner {data-lang=kotlin capture="native-window" native-wait-frames="8" video="assets/stereo_video/playback.mp4" duration="6" fps="24" macro-file="../../dev_docs/agents/transcripts/docs_capture/video_one_motion.yaml" capture-box="video1_context" wait="1" timeout="240" capture-profile="linux-nvidia" embed=true hidden=true name="stereo-video-playback" alt="Native Field playback of the moving stereo calibration movie on its curved screen."}
// The movie advances while the preview reads the actual eye image.
```

Try these controls in order:

| Gesture | Result |
| --- | --- |
| Pause and scrub | Select a source picture without moving the screen |
| Swap left and right | Reverse which packed half each eye sees |
| Bend, then Arc width | Curve and resize the room-locked screen |
| 8x blur | Increase movie speed and accumulate a moving exposure |
| Material: 12 fps | Hold the source material between ticks while the headset keeps rendering |
| 1x sharp | Return to ordinary speed with accumulation disabled |

The native figures above run the real decoder, P010 uploads, movie shader and float eye targets.
The demo source is authored by `tools/generate_stereo_video_demo.py`; it is separate from those
player captures. The playback preview is an encoded recording, not a precision measurement.

## Headset and preview

Set `headsetMode = true` in box 1 before creating a Vulkan renderer, connect WiVRn, and launch
in a fresh Field process. OpenXR supplies the eye dimensions. The preview samples those same
images; it does not render another version of the scene. Desktop-only mode uses a stereo
camera without requiring a headset.

With `withFieldBrowser = true`, Field's live browser appears in a companion window and on a
movable pane in the headset. It uses the same controller and keyboard/mouse support as
[Field inside VR](field_vr_ide.md). The movie preview has its own window. The browser can be
hidden in the headset or omitted entirely by changing the setup flag.

## Colour precision

Movies decode to P010, retaining ten-bit luma/chroma in sixteen-bit storage. The shader uses the
file's colour matrix and range tags, and renders into floating-point eye images. The shared
OpenXR path prefers a floating-point or ten-bit swapchain over an eight-bit format, so
`text_one` and other multiview documents benefit too. The movie example reports the selected
format and refuses an eight-bit OpenXR swapchain rather than silently reducing precision.
The desktop preview can still quantize to the monitor's output format.

This is an SDR player, including ten-bit SDR material. Tagged PQ/HLG movies need an HDR display
path and are refused. Unspecified SDR matrix/range tags default to BT.709/limited.

Application precision and transport precision are separate. Configure a supported ten-bit
WiVRn encoder—AV1 or HEVC—with `bit-depth: 10`, then verify the negotiated encoder and decoder
on the connected headset. A ten-bit request or a floating-point application target alone does
not establish the complete path. Field submits the movie and its browser in one projection
layer, using WiVRn's direct projection path rather than its layer-squashing intermediate.

**4 · Video shader** contains editable `vertex`/`fragment` tabs and uses
`moduleHere { +movie }`. **Reload Shader** (Alt+R) reloads its current pipeline without
reopening the file; the push-constant comments appear in Kotlin completion.

The editable source and guide are in `templates/video_one/`. `build_transcript.py --video
/path/to/movie.mp4` produces create/launch/stop macros for a local file. Add `--headset` to
select OpenXR and `--no-browser` to omit Field's browser pane.

The WiVRn 26.6.2 Quest client source also selects an eight-bit sRGB eye swapchain after
video decoding. Ten-bit transport therefore does not by itself establish ten-bit final
headset composition. A higher-precision client path must be negotiated and tested separately.

## Speed and motion exposure

The transport has a continuous **Speed** control up to 8×. Above 4×, optional alternate-frame
selection reduces conversion/uploads: a 24 fps movie at 8× contributes up to 96 pictures/sec.
FFmpeg still decodes necessary reference pictures; optional non-reference skipping can reduce
work further, depending on the codec. Headset rendering continues at the runtime refresh rate.

**Accumulation mix**, **Persistence half-life (s)** and **Incoming frame weight** control a
full-resolution RGBA16F temporal exposure in linear light. Only newly selected source frames
are added. The exposure lives in the movie's coordinates, so head motion stays sharp. **Clear
accumulation** resets it; scrubbing, looping and file replacement also reset it. **8x blur**
and **1x sharp** provide quick cuts between fast exposure and ordinary playback.

Box **5 · Temporal accumulation shader** contains the editable accumulation shader, using
Field's standard shader reload. Box 4 remains the final screen shader. Decode timing, skipped
queue samples and exposure sample counts appear in the transport status. Playback speed is
limited by actual decode throughput, with a bounded queue rather than growing latency.


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

## Code and ownership

`StereoVideoSurface` is the reusable player. A document owns its editable draw pipeline with
`moduleHere { +movie }`; the movie supplies the eye targets and texture bindings. The source
image is uploaded once before either eye draws. `movie.placement` controls the screen, while
`movie.play()`, `movie.pause()`, `movie.seek(seconds)` and `movie.speed` control its transport.
`movie.materialFramesPerSecond` accepts `0`, `12` or `24`; zero is uncapped.

Stop transport first and the context last. The screen box owns the movie lifetime, and the
shader boxes own their pipelines. The supplied stop macro follows that order. The starting
place contains a guide and editable source for each layer. `templates/video_one/` contains
the template sources and transcript builder. No personal movie files are bundled.
