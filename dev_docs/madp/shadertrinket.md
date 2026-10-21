# ShaderTrinket → Field·4: port design

Source: `~/temp/aaaframe/madp/shadertrinket.html` (served at `:9982/shadertrinket.html`). A
ShaderToy-style Image pass in a WebGL2 canvas: the student edits `mainImage(out vec4 fragColor, in
vec2 fragCoord)` in Monaco, it recompiles as they type, and errors are underlined on their own
lines. The toolbar has a preset menu (17 shaders), **Camera** (webcam → `iChannel0`), **Publish**
(the canvas as a WebRTC stream named `@login/suffix`) and **Receive** (a classmate's stream into a
chosen `iChannel`). The classroom jam (everyone publishing, everyone receiving each other) is the
point of the demo.

## The Field idea

**The shader is a box's `fragment` property; the canvas is a texture the window shows.** A
ShaderTrinket box is an ordinary `shadertoyHere` box beneath the renderer box. Its `fragment`
property holds one template line and the student's `mainImage`, exactly as they would type it into
ShaderTrinket or ShaderToy. It draws into the document's **ShaderTrinket canvas**, a
`RenderToTexture` the renderer window shows letterboxed. Because the canvas is a texture, the same
picture can be published (the sink copies it), and it can be another shader's `iChannel`.

Presets are boxes. Each preset box draws into the canvas under the same name, so launching one
replaces what the window shows, the way picking a preset from ShaderTrinket's menu does. Editing a
box's `fragment` and reloading (alt-R) is the recompile-as-you-type loop.

## API mapping

| shadertrinket.html | Field |
| --- | --- |
| the Monaco editor (Image pass) | the box's `fragment` property: `{{ shadertoy.image }}` then `mainImage` |
| `iResolution`, `iTime`, `iTimeDelta`, `iFrameRate`, `iFrame`, `iDate` | same names, bound by `shadertoyHere`; `iResolution` is the canvas size |
| `iMouse` | from the renderer window, mapped into canvas pixels (xy follows the cursor, zw is the click, negative when released, as ShaderToy) |
| `iChannel0..3`, `iChannelResolution`, `iChannelTime` | `shadertoyHere { textures("iChannel0" to t) }`; the resolution is the bound texture's |
| the canvas | `shadertoyCanvas()`: a per-renderer `RenderToTexture`, shown in the window |
| Camera button | `textures("iChannel0" to webcam())`; `webcam()` opens the camera once per renderer and keeps it across re-runs |
| Publish (`@login/suffix`) | the jam box: `publishShadertoyCanvas("field-shader")`, a `webrtcSink` fed from the canvas |
| Receive into iChannelN | the Live Stream box: `webrtcSource(...)` + `webrtcPublishers()` pick list, bound as `iChannel2` |
| preset menu | one box per preset |
| error bar / underlines on Image lines | Field's shader error markers on the `fragment` property, on the student's own lines |
| AI agent loop, ghost completion, local saves | not ported: AI4, Field's completion, documents + history |
| Buffer A–D passes, Common tab | not in ShaderTrinket's UI either; a second `shadertoyHere` box drawing into its own canvas and bound as a channel covers "another box's output" |

## Shared code (src/jvmMain)

1. **`shadertoyHere` fixes** (`afield/graphics/shader/shaders.kt`), each its own commit:
   - `fragCoord` is y-up. Vulkan's `gl_FragCoord` has its origin at the top left, and the seeded
     `main` passed it straight through, so every ShaderToy program drew upside down while `iMouse`
     was already y-up.
   - Colours are display values. WebGL writes `fragColor` to the canvas as-is; Field's window is
     `B8G8R8A8_SRGB` (or linear scRGB with HDR on), so the same number came out brighter. The
     `main` that calls `mainImage` decodes sRGB to linear before writing. (Probe, 2026-10-20: a
     `uv.y` ramp drew mid-grey as ~0.73, and a bottom-left red quadrant drew top-left.)
   - `textures(...)` given in the block, or an `iChannelN` found by name, now wins over the 1×1
     dummy channel; the dummies were consulted first, so nothing could be bound.
   - `iChannelResolution` reports the bound texture's size instead of `(1, 1, 1)`.
   - `{{ shadertoy.image }}`: the preamble *and* the `main` that calls `mainImage`, so the student's
     `fragment` is one line plus their code. The old `{{ shadertoy.fragmentPreamble }}` stays.
2. **Channels flip** (`ShadertoyChannels.kt`): every Field texture holds its rows top-down (camera
   frames, browser pages, render targets, because of the negative-height viewport), but ShaderToy
   textures are bottom-up, so `texture(iChannel0, fragCoord / iResolution.xy)` would show the
   camera upside down. A bound channel is therefore drawn through a small flip pass into a target of
   its own size (a pre-target, cached per source) that also re-encodes an sRGB source, which GLSL cannot do for a sampler any other way:
   `texture` can be neither overloaded nor wrapped, and samplers can be passed to user functions.
3. **`shadertoyCanvas()`**: the canvas target, its window composite, and `iResolution`/`iMouse` for
   the modules drawn into it.
4. **`webcam()`**: a `WebcamTexture` per renderer and device, registered as a pre-target so it
   uploads each frame, and a black channel plus a box error when there is no camera.
5. **`publishShadertoyCanvas(name)`**: `webrtcSink` + a copy pass from the canvas. Uses
   `afield.media.webrtc` (owned by the WebRTC session) without editing it.

## Starting point: `shadertrinket`

- `shadertrinket_renderer`: the window + renderer (the jam starting point's renderer box).
- `shadertrinket_lead` (**Flat Rings**, the default program) — the lead box.
- Presets, chosen to show the range: **Classic Plasma** (colour), **Star Tunnel** (loops),
  **Mandelbrot Glow** (iteration), **SDF Sphere Trace** (ray marching), **Camera Input Demo**
  (`iChannel0` = webcam), **Live Stream Input Demo** (`iChannel2` = a classmate's stream, with the
  publisher pick list).
- `shadertrinket_publish`: publish mine (the canvas, as `@login/field-shader`).

## Dropped, and why

- The agent loop, ghost completion and Copy-LLM-Intro: AI4 and Field's completion.
- Live recompile on every keystroke: Field recompiles on alt-R / alt-shift-enter, which keeps a bad
  edit from blanking the window.
- `iSampleRate`, `iChannel` cube maps and 3D textures: ShaderTrinket's presets use none.

## Verified (2026-10-20, M-series Mac, KosmicKrisp)

Native window captures through the backdoor (`native-screenshot`), compared with the browser demo
at `:9982` captured in Chromium at the same canvas size (960×540):

- **Orientation.** Before: a `uv.y < 0.5` red quadrant drew top-left. After: bottom-left, and a
  canvas bound as another shader's `iChannel0` reads back the same way up.
- **Colour.** A 0.5 grey displays as 0x80, as in WebGL. Flat Rings' teal, amber and blue and SDF
  Sphere Trace's glow match the browser's by eye; the remaining differences are animation phase.
- **`iChannelResolution`** reports the bound canvas's 320×180.
- **Error lines.** A bad identifier on line 5 of a `fragment` is reported as `fragment.glsl:5`, on
  the student's own line, with no fix needed: the template's `#line` handling already maps it.
- **No camera.** `webcam()` prints why and binds black; the box still runs.

`ShadertoyChannelsTest` compiles the image preamble with every program the starting point ships,
and the flip and canvas passes.
