# StackTrinket → Field·4

Port of `~/temp/aaaframe/madp/stacktrinket.html` (compiler `stacksl.py`, served by the madp
`server.py` as `POST /api/stacksl-compile`): a StackSL editor whose program is lowered to
`vec4 stack(vec4 input)` and drawn every frame into a ping-pong RGBA16F target, linear filtered and
repeating, with the previous frame bound as `sampler2D state`. A side panel shows the lowered GLSL
(line numbered), the helpers it pulled in, the dangling-wire count and the stack depth; a toolbar
offers 15 presets, Reset, Pause, Format and Sweep.

## Shape in Field

One renderer box (window + renderer), then one box per program beneath it. Each program box holds:

- **`stackSL`** — the program, exactly what the demo's editor holds;
- **`code`** — one call, `stackFeedback()`, plus a comment linking `[[property stackSL]]`.

Launching a program box replaces whichever program the window was showing (all use one keyed name),
so the boxes play the part of the preset menu. Editing `stackSL` while it runs recompiles and swaps
the pipeline in place; the state survives the edit, as it does in the demo.

## Shared code

`afield.lang.stacksl.StackSLFeedback` with `Box.stackFeedback { … }`: the demo's renderer as one
call.

- A float16 `PingPongRenderToTexture` (no depth, linear + repeat sampler, the window's size unless
  `width`/`height` say otherwise) cached per renderer and size, because nothing frees an RTT.
- A built-in fullscreen fragment shader around `{{ stack.auto.program@state }}`: `state` is the
  previous frame, input is `vec4(uv, hash, seconds)` (Field's lanes), the hash is the demo's per-pixel
  per-frame `fract(sin(dot(uv * res + frame, …)))`, and the result is clamped to 0..1 in all four
  channels (alpha is a memory channel too: *four memory bands* stores one in it).
- Follows the box's `stackSL` property: a changed program that compiles replaces the pipeline;
  one that does not keeps the last good picture and shows its diagnostics.
- Steps at most `fps` times a second (60 by default). Feedback decays are per step, and Field's
  window draws far faster than a browser's 60 Hz (measured: persistent bloom saturated about four
  times too soon before this), so the rate is part of the look. Measured after the change: 358
  steps in 5.97 s.
- `reset()` clears both images and restarts time and frame; `paused` holds the picture.
- Displays through `blitToScreen` with a display gamma of 2.2 (see below).
- Writes the state with `uv.y = 0` in image row 0. Field's render targets put clip `y = +1` in row
  0, so the usual fullscreen vertex shader stores the picture upside down relative to
  `texture(state, uv)`, and every feedback step flipped it: the first captures of feedback fog
  were mirror-symmetric top to bottom (flip difference 1.6/255). The blit reads it bottom-up.
  Checked with `uv saturate` (green top-left, red bottom-right, as in WebGL) and a `prev` band
  that stays at the top.
- A **lowering panel** in the box's output (`lowering` channel): status line (ok / errors), stack
  depth with the demo's guidance ("N leftover values under the returned top"), helpers, dangling
  wires, diagnostics as `line:col` with the offending token, and the line-numbered lowered GLSL in a
  collapsible block. Field's `stack.auto` report only shows warnings and depth problems; it never
  showed the GLSL.

## Dialect

- **Input lanes.** The demo passes `vec4(uv, time, hash)`; Field's convention (and its `time` /
  `hash` words) is `vec4(uv, hash, time)`. The presets use the words, never the raw lanes, so they
  carry over unchanged; the host passes Field's order.
- **Compilers.** `stacksl.py` is a June 2026 port of Field's compiler. The lowered GLSL of the
  presets is the same line for line apart from the time lane (`program_in.w` for `stack_in.z`);
  `fbm` keeps its 0..0.96875 range in both (Marc, 2026-10-16: leave it).
- **Definitions must be net zero.** Field rejects *subroutine cse* as written: its rings push a
  value without taking one ("Definition 'ring-a' is unbalanced"). The port starts each ring with
  `drop`, which discards the `input` it is called with and that the page's version ignored.
- **Display.** WebGL writes the shader's values straight into an 8-bit canvas the browser shows as
  sRGB; Field's swapchain is sRGB (or linear scRGB) and encodes linear output. The state stays
  exactly the demo's numbers; only the blit applies `pow(c, 2.2)` so the window shows what the
  browser shows. `displayGamma = 1.0` turns that off.

## Presets shipped

Lead: **pulse ring**. Presets: **feedback fog**, **persistent bloom**, **store channels**,
**subroutine cse**, **four memory bands**, **long memory ecology** — the feedback ones, since
feedback is what the demo adds to the StackSL gallery. **Braided transport** is left out: its reads
take in about 1.9 times the old picture each frame, and it is solid white within seconds in the
demo itself.

The palette presets are in colour, not monochrome: the cosine palette's phase offsets
(0, 0.1, 0.2) give blue, orange, magenta and green, in the demo and here. The stateless presets (gather luma, ryb
field, oklab grade, wave clocks, noisy compass, wire ribbons) already have equivalents in the
`stacksl_gallery` starting point.

## Dropped

- **Sweep** (ten variants of the number under the cursor, as thumbnails). A feedback program's
  variants each need their own ping-pong target run from black; not done here. Edits recompile
  live, and `slSweep` covers stateless programs.
- **Format**, **local saves**, **agent chat**: Field's editor, documents + history, AI4.
- **Pause / Reset buttons**: `paused` and `reset()` on the returned object; re-running the box
  (`once`-cached target) keeps the state, as the demo's recompile does.

## Verification

References come from a harness that is the page's own fragment wrapper and ping-pong (RGBA16F,
linear, repeat), compiled by the madp server's `/api/stacksl-compile`, stepped at a fixed 60 fps
in WebGL2 and read back at 5 s and 15 s; spot-checked against the live page (braided transport
white, long memory ecology grey static in both). Field captures are `native-screenshot`s of the
window 5 s and 15 s after launching each box. All seven match in structure and colour, the rings
are round in Field's square window where the 4:3 canvas stretched them, and differences in colour
phase come from the time the capture fell at. The ecology's grain has the same statistics at
native resolution (mean 138, standard deviation 69 vs 70).

What the references showed about the presets themselves: *long memory ecology* is grain because
`dup average … + +` adds the average back every frame and `mirror` folds the climbing values;
without it the reads (about 1.65× the old picture) fill into a pale cyan fog. The box comment says
so.

## The jam box (2026-10-21)

The classroom shader jam in StackSL, over `afield.media.webrtc` (`dev_docs/WEBRTC_SOURCES_SINKS.md`).

- **Receive.** `stackFeedback { input("remote", stream) }` binds up to three textures beside `state`
  as `sampleTextures[1..3]`, so `sample1`/`gather1`/`texelSize1` read them; no new StackSL word.
  Each input is copied, once per step, into an image the size of the state and stored the way the
  state is (row 0 = `uv.y 0`): Field's textures keep their top row first, so sampled as they are at
  `uv` they read upside down beside `state`. A WebRTC stream's paints are `B8G8R8A8_SRGB`, so
  sampling decoded them; the copy re-encodes sRGB sources, so the program reads the picture's own
  numbers (the values a browser ShaderTrinket's `iChannel` gives).
- **Verified** live against a browser ShaderTrinket (aaaframe `shadertrinket` page, driven by
  `dev_docs/webrtc_latency/shadertrinket_driver.py` with an asymmetric, animated test card) on a
  local aaaframe in local-auth mode: the card arrived the right way up (square top-left, band at
  the bottom), and before the re-encode its 0.6 band read 0.31; after, 1.0 and 0.6 read 0.992 and
  0.596. The card's 0.08 ground arrives as 0.043: the video codec crushes the darkest levels.
- The stream is stretched to the state's shape, as ShaderTrinket's `iChannel` is.
- **Publish.** `webrtcPublish(feedback.picture, "stacktrinket", 640, 640)` (ShaderTrinket's helper,
  `afield/media/webrtc/WebRTCPublish.kt`). The state is float and bottom row first, so the host
  offers `picture`: the newest step copied top row first into `R8G8B8A8_UNORM` display values,
  which `webrtcPublish` reads as display values and copies in row order. Made on first use.
- **Verified both ways** (2026-10-21, Field's Electron app, local aaaframe on 8792): a browser page
  published the card, the jam box ran `0 sample1` and published it back, and a second browser page
  received `@student/stacktrinket` into `iChannel0` at 640×640, about 30 fps. Round trip: the card's
  1.0 and 0.6 came back as 0.996 and 0.600, the right way up. The shipped program, a grey memory of
  the stream, published while the box's list picked `@student/card` and skipped its own stream.
- Building it needed `-Pkotlin.daemon.jvmargs=-Xmx11g`: the merged main ran 7g and 9g out of heap.
