# Chain: a RectSL scene on a light rig — design, measurements, traps

Added 2026-09-28 (nightly branch `nightly/2026-09-28/3-chain-scene-to-light`). The public page is
`public/docs/chain_scene_to_light.md`; the loadable demo is
`public/docs/assets/chain_scene_to_light_demo/`.

## Files

| file | what |
| --- | --- |
| `src/jvmMain/kotlin/afield/rectsl/RectSLImg.kt` | `RectSLImager` / `rectSLImage`: a scene at time t as an `Img` (new bridge) |
| `src/jvmMain/kotlin/afield/dmx/Patch.kt` | `Fixture.decode` — slots back to the look, inverse of `writeInto` (new) |
| `src/jvmMain/kotlin/afield/dmx/DmxVisual.kt` | `Patch.receivedToFLines`, `Box.showReceived` — a received universe in the rig's layout (new) |
| `src/jvmMain/kotlin/afield/dmx/SceneOnARig.kt` | the worked chain and its three measurements (`roundTrip`, `aliasing`, `timing`, `decodeErrors`) |
| `src/jvmTest/kotlin/afield/dmx/SceneOnARigTest.kt` | pins the measurements; one test decodes E1.31 datagrams by hand from a bare socket |
| `src/jvmTest/kotlin/afield/dmx/SceneOnARigDemoBundleTest.kt` | the demo bundle parses; inline fallbacks match; the sender's scene is `SceneOnARig.SCENE` |

## Decisions

- **The bridge renders with `CpuRectSLRasterizer`**, the study tools' CPU reference, via
  `RectSLStudy.drawable(source)` (which also compiles `{{ }}` escapes). No GPU path: lights must run
  headless, and the docs captures must work with the display asleep.
- **As-typed by default, `linear = true` on request.** The rasterizer hands back linear light decoded
  from 8-bit bytes; the default re-encodes to sRGB so a frame equals what the RectSL pictures show.
  Averaging (the aliasing fix) is only right on linear values.
- **`decode` returns `color × dimmer`**, because an RGB profile without a dimmer slot bakes the dimmer
  into its colour bytes and nothing on the wire separates them.
- **The aliasing fix is `img.resize(cols, rows)` before `mapImage`, not a new `mapImage` mode.**
  `mapImage` maps the fixture bounding box onto pixel *centres* (`u × (width − 1)`), so an image of
  exactly cols × rows lands each grid fixture on its own pixel. For non-grid rigs there is no such
  size; the page says so.

## Measurements (2026-09-28, this machine; the test holds bounds, the page's captures hold the digits)

- Round trip, 9 frames × 132 fixtures (128 RGB + 4 RGBWA), sACN unicast 127.0.0.1: **0 of 9216** slot
  bytes changed. Worst grid colour error 0.500 levels; worst wash 0.500 levels *in this scene* (its
  colours make white/amber small). Over 2000 random colours: RGB/GRB 0.4998, DRGB 0.86, RGBW 0.99,
  RGBWA 1.28, RGB16 0.0019 levels.
- Aliasing, the 0.25-unit line over 121 frames of the middle 3 s: point sampling is dark in **91 of
  121** frames and lights a whole column (8.0 fixtures' worth) at its peaks; its time-average is 1.98,
  about the truth (2.0). Area averaging in linear light: exactly 2.000 in every frame.
- Timing: the 44 Hz sender kept up; see the page's readout for the frame cost and arrival gaps.

## Traps found

- **A two-key `'( 0=a 4=b )` track is TCB, i.e. eased**, not linear. The first sweep was eased, and at
  four instants the line's edges landed exactly on pixel centres; `CpuRectSLRasterizer` counts a
  centre on an edge as inside at *both* edges, so the 4-px line drew 5 px and the "exact" area route
  showed +25% spikes. The scenes now use `linear ->x`; with 121 frames a linear sweep never puts an
  edge on a centre (edge = 30 + 1.6k px). The rasterizer's inclusive rule is documented in its KDoc
  ("the GPU's edge rule differs") and was not changed.
- **`Vec3` components are `Float`.** `decode` round-trips to float precision; a 1e-9 tolerance fails.
- **`loop { if (frameNumber % 4 == 0) … }` does not throttle.** `Frames.frame()` is a `yield()`, so the
  loop spins and redraws many times per Runloop tick (≈140 `set-output`s a second observed). The demo
  uses `delay(100)`. `public/docs/assets/dmx_lighting_demo/` still uses the `% 4` idiom (not fixed
  here).

## Verification record

- `./gradlew jvmTest --tests 'afield.dmx.*'`: DmxServicesTest 17/17, DmxProtocolTest 11/11,
  SceneOnARigTest 6/6, DmxLightingDemoBundleTest 2/2; SceneOnARigDemoBundleTest 3/3.
- Demo: a fresh copy of the bundle opened through the backdoor (`open … --must-exist`, 3 boxes) on
  port 9550; `scene-sender` launched, `rig-receiver` launched, window screenshot shows sent and
  received rigs agreeing and universe 1 in wire order; both stopped; `rig-measure` run, printing
  `0 of 9216 slots differ` and the aliasing counts above.
