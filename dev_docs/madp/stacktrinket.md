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
- `reset()` clears both images and restarts time and frame; `paused` holds the picture.
- Displays through `blitToScreen` with a display gamma of 2.2 (see below).
- A **lowering panel** in the box's output (`lowering` channel): status line (ok / errors), stack
  depth with the demo's guidance ("N leftover values under the returned top"), helpers, dangling
  wires, diagnostics as `line:col` with the offending token, and the line-numbered lowered GLSL in a
  collapsible block. Field's `stack.auto` report only shows warnings and depth problems; it never
  showed the GLSL.

## Dialect

- **Input lanes.** The demo passes `vec4(uv, time, hash)`; Field's convention (and its `time` /
  `hash` words) is `vec4(uv, hash, time)`. The presets use the words, never the raw lanes, so they
  carry over unchanged; the host passes Field's order.
- **Compilers.** `stacksl.py` is a June 2026 port of Field's compiler. Every word the shipped
  presets use lowers to the same helper bodies in both (checked against the py GLSL for each);
  `fbm` keeps its 0..0.96875 range in both (Marc, 2026-10-16: leave it).
- **Display.** WebGL writes the shader's values straight into an 8-bit canvas the browser shows as
  sRGB; Field's swapchain is sRGB (or linear scRGB) and encodes linear output. The state stays
  exactly the demo's numbers; only the blit applies `pow(c, 2.2)` so the window shows what the
  browser shows. `displayGamma = 1.0` turns that off.

## Presets shipped

Lead: **pulse ring**. Presets: **feedback fog**, **persistent bloom**, **store channels**,
**braided transport**, **four memory bands**, **long memory ecology** — the feedback ones, since
feedback is what the demo adds to the StackSL gallery. The stateless presets (gather luma, ryb
field, oklab grade, wave clocks, noisy compass, wire ribbons) already have equivalents in the
`stacksl_gallery` starting point.

## Dropped

- **Sweep** (ten variants of the number under the cursor, as thumbnails): Field's answer is
  `stack.auto` live numbers — every literal is a uniform the box's sliders and keyframes can turn
  without a recompile — and `slSweep` for stateless programs. A feedback program's variants would
  need ten ping-pong targets each run from black; not worth it for the starting point.
- **Format**, **local saves**, **agent chat**: Field's editor, documents + history, AI4.
- **Pause / Reset buttons**: `paused` and `reset()` on the returned object; re-running the box
  (`once`-cached target) keeps the state, as the demo's recompile does.
