# Field Document: splat_shader

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 9 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="splat_renderer" index="0" -->

### Narrative

From `notes`

Renderer

```edn [field-box id="splat_renderer" role="properties"]
{"id" "splat_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["splat_passthrough" "splat_pulse_rings" "splat_dissolve" "splat_gravity_well" "splat_fern_veins" "splat_lichen_tangle" "splat_xray_shell" "splat_toy_car"] "properties" {"children" #{#afield/box "splat_passthrough" #afield/box "splat_pulse_rings" #afield/box "splat_dissolve" #afield/box "splat_gravity_well" #afield/box "splat_fern_veins" #afield/box "splat_lichen_tangle" #afield/box "splat_xray_shell" #afield/box "splat_toy_car"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "splat_renderer_notes_.md" "lines" 4 "characters" 192 "sha256" "e845fb29508e0cc057359af579a6edf1d77b9cb9a515d1cb587dcadefafb9026"} {"name" "code" "language" "kotlin" "path" "splat_renderer_code_.kt" "lines" 13 "characters" 439 "sha256" "5ec1e50d2c42076d2e020f1ffd64c062192c7c22d8aa270c7c0fa0c9793fedc3"}]}
```

```markdown [field-property box="splat_renderer" name="notes" path="splat_renderer_notes_.md"]
# Renderer

Opens the splat window and runs its frame loop. Launch this first; every other box draws
into it. Stopping this box stops rendering. Re-running it keeps the window it already has.
```

```kotlin [field-property box="splat_renderer" name="code" path="splat_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
// once: re-running this box keeps the existing window and renderer
once.renderer {
    window = Window("Splat shader", initialDimensions = Rect(80.0, 80.0, 900.0, 900.0))
    Renderer(window)
}
// the frame loop runs as this box's coroutine; stopping the box stops rendering
start(renderer::run)
```

## 2. fern veins

<!-- field-box id="splat_fern_veins" index="1" -->

### Narrative

From `notes`

Fern veins

```edn [field-box id="splat_fern_veins" role="properties"]
{"id" "splat_fern_veins" "index" 1 "name" "fern veins" "parents" ["root" "splat_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "splat_renderer"} "name" "fern veins" "_tombstone" false "frame" #afield/rect [40.0 1240.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "splat_fern_veins_notes_.md" "lines" 4 "characters" 173 "sha256" "aa25db6961df480572d38b7a62691571913dc4fc1462695b557b61b52ef05d47"} {"name" "code" "language" "kotlin" "path" "splat_fern_veins_code_.kt" "lines" 12 "characters" 433 "sha256" "31e7a088395d2336dcb0beed7d0a8c45f5cb4e8ad07eab8640ab0d6e933f3a49"} {"name" "compute" "language" "glsl" "path" "splat_fern_veins_compute_.glsl" "lines" 14 "characters" 433 "sha256" "29d25a380e5487e891bd5327a48dd08dd6eacf04bada704ff84e476ec10ec4e6"}]}
```

```markdown [field-property box="splat_fern_veins" name="notes" path="splat_fern_veins_notes_.md"]
# Fern veins

Splats along the frond's midrib glow green and sway; away from it they darken and thin. Shown on
the **fern plume**, a Barnsley fern played as the chaos game.
```

```kotlin [field-property box="splat_fern_veins" name="code" path="splat_fern_veins_code_.kt"]
import afield.graphics.scene.fabricated.ProceduralSplats
import afield.graphics.scene.fabricated.SplatShader
import afield.graphics.scene.fabricated.showControls

/*
Fern veins. The splats along the frond's midrib glow green and sway; away from it they
darken and thin, so the frond reads as veins in the dark.
*/
var module = computeModuleHere {}

val splats = SplatShader(module, ProceduralSplats.fernPlume())
showControls(splats)
```

```glsl [field-property box="splat_fern_veins" name="compute" path="splat_fern_veins_compute_.glsl"]
#version 450
#include "splat/insert"

float rib = exp(-7.5 * abs(pos.x));
float leaf = sat(1.0 - abs(pos.y) * 0.62);
float flutter = sin(pos.y * 8.0 + pos.z * 5.0 + t * 1.1);

pos.x += 0.18 * rib * sin(pos.y * 6.5 + t * 0.9);
pos.z += 0.06 * flutter * leaf;
rgba.rgb = mix(vec3(0.02, 0.07, 0.03), vec3(0.46, 1.0, 0.38), rib * leaf);
rgba.a *= 0.16 + 0.84 * rib * leaf;
scales.xy *= mix(0.48, 1.95, rib * leaf);

#include "splat/end"
```

## 3. pulse rings

<!-- field-box id="splat_pulse_rings" index="2" -->

### Narrative

From `notes`

Pulse rings

```edn [field-box id="splat_pulse_rings" role="properties"]
{"id" "splat_pulse_rings" "index" 2 "name" "pulse rings" "parents" ["root" "splat_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "splat_renderer"} "name" "pulse rings" "_tombstone" false "frame" #afield/rect [40.0 720.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "splat_pulse_rings_notes_.md" "lines" 6 "characters" 269 "sha256" "5c191cd6e3d2fdadc915fb467e70ca7fc90d62f80211b63cb981cef7a353b996"} {"name" "code" "language" "kotlin" "path" "splat_pulse_rings_code_.kt" "lines" 12 "characters" 461 "sha256" "1852c032dc391ca07bd69fdd2a8e55076508096aba217dab9f4ac05a55464f91"} {"name" "compute" "language" "glsl" "path" "splat_pulse_rings_compute_.glsl" "lines" 17 "characters" 452 "sha256" "efa652df6e3e4ee1326ffc5f9f5d6a3af904e3f7e2dc7f80c69a3713aef59988"}]}
```

```markdown [field-property box="splat_pulse_rings" name="notes" path="splat_pulse_rings_notes_.md"]
# Pulse rings

A wave runs out from the vertical axis. Each splat rides up and down on it, swings round a
little, and turns from cyan to ember as a crest passes. Shown on the **phyllotaxis bloom**.

Try changing `18.0` (how many rings) or `3.5` (how fast they travel).
```

```kotlin [field-property box="splat_pulse_rings" name="code" path="splat_pulse_rings_code_.kt"]
import afield.graphics.scene.fabricated.ProceduralSplats
import afield.graphics.scene.fabricated.SplatShader
import afield.graphics.scene.fabricated.showControls

/*
Pulse rings. A wave travels out from the cloud's axis: each splat rides up and down on
it, swings round a little, and lights up from cyan to ember as a crest passes.
*/
var module = computeModuleHere {}

val splats = SplatShader(module, ProceduralSplats.phyllotaxisBloom())
showControls(splats)
```

```glsl [field-property box="splat_pulse_rings" name="compute" path="splat_pulse_rings_compute_.glsl"]
#version 450
#include "splat/insert"

float r = length(pos.xz);
float wave = sin(r * 18.0 - t * 3.5);
float ring = smoothstep(0.92, 1.0, wave);

pos.y += wave * 0.045;
pos.xz *= rot2(0.12 * sin(t + r * 4.0));

vec3 cyan = vec3(0.08, 0.85, 1.0);
vec3 ember = vec3(1.0, 0.54, 0.16);
rgba.rgb = mix(rgba.rgb, mix(cyan, ember, sat(pos.y * 0.55 + 0.5)), 0.55 + ring * 0.35);
rgba.a *= 0.52 + ring * 0.48;
scales *= 0.82 + ring * 1.45;

#include "splat/end"
```

## 4. xray shell

<!-- field-box id="splat_xray_shell" index="3" -->

### Narrative

From `notes`

Xray shell

```edn [field-box id="splat_xray_shell" role="properties"]
{"id" "splat_xray_shell" "index" 3 "name" "xray shell" "parents" ["root" "splat_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "splat_renderer"} "name" "xray shell" "_tombstone" false "frame" #afield/rect [1240.0 1240.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "splat_xray_shell_notes_.md" "lines" 4 "characters" 194 "sha256" "8357530a72911bb5c1a16a92890b6b683c4cde56e7bc73413a4177dc9eb2383c"} {"name" "code" "language" "kotlin" "path" "splat_xray_shell_code_.kt" "lines" 12 "characters" 459 "sha256" "6086b4240464a87e942171e488a3231a3073323e38147c893c6bde6e980e94b7"} {"name" "compute" "language" "glsl" "path" "splat_xray_shell_compute_.glsl" "lines" 13 "characters" 386 "sha256" "1e9b757b35e20151bc50e34a071ee7f5cf05fe81fa8e21042d5842baa782ee2a"}]}
```

```markdown [field-property box="splat_xray_shell" name="notes" path="splat_xray_shell_notes_.md"]
# Xray shell

Colour by distance from the centre: the core goes dark and the outer shell pale blue, and a
shimmer runs outward, nudging the splats as it passes. Shown on the **nautilus shell**.
```

```kotlin [field-property box="splat_xray_shell" name="code" path="splat_xray_shell_code_.kt"]
import afield.graphics.scene.fabricated.ProceduralSplats
import afield.graphics.scene.fabricated.SplatShader
import afield.graphics.scene.fabricated.showControls

/*
Xray shell. Colour by distance from the centre: the core goes dark, the outer shell
pale blue, and a shimmer runs outward through it, nudging the splats as it passes.
*/
var module = computeModuleHere {}

val splats = SplatShader(module, ProceduralSplats.nautilusShell())
showControls(splats)
```

```glsl [field-property box="splat_xray_shell" name="compute" path="splat_xray_shell_compute_.glsl"]
#version 450
#include "splat/insert"

float radius = length(pos);
float edge = smoothstep(0.2, 1.2, radius);
float shimmer = 0.5 + 0.5 * sin(radius * 24.0 - t * 4.0);

pos += normalize(pos + vec3(0.001)) * shimmer * 0.035;
rgba.rgb = mix(vec3(0.04, 0.08, 0.1), vec3(0.75, 0.96, 1.0), edge);
rgba.a *= 0.16 + edge * shimmer * 0.84;
scales *= vec3(0.55, 0.55, 1.8);

#include "splat/end"
```

## 5. passthrough

<!-- field-box id="splat_passthrough" index="4" -->

### Narrative

From `notes`

Splat shader

```edn [field-box id="splat_passthrough" role="properties"]
{"id" "splat_passthrough" "index" 4 "name" "passthrough" "parents" ["root" "splat_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "splat_renderer"} "name" "passthrough" "_tombstone" false "frame" #afield/rect [40.0 200.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "splat_passthrough_notes_.md" "lines" 78 "characters" 4776 "sha256" "ac61e4ff04deabfbefee6aa6d1ea4e028088831f36c7c040cc83f5218696c455"} {"name" "code" "language" "kotlin" "path" "splat_passthrough_code_.kt" "lines" 20 "characters" 989 "sha256" "999deb7c7694ca842a9582174d8c62f1d14bb6812b908ca74439773aeda253ff"} {"name" "compute" "language" "glsl" "path" "splat_passthrough_compute_.glsl" "lines" 22 "characters" 550 "sha256" "03da5912255eed50c7ec8d92fd9d982c020efe69e12c30be657dfe8572136247"}]}
```

```markdown [field-property box="splat_passthrough" name="notes" path="splat_passthrough_notes_.md"]
# Splat shader

This is a Field version of **splat.html**, the madp classroom demo. In the original you write a
few lines of GLSL in an editor beside a three.js view, and they run once for every Gaussian splat
in a cloud, every frame. Each splat is a small soft ellipsoid with a centre, three radii, a
rotation, a colour and an opacity, and your lines may change any of them. The cloud is redrawn
with your changes at once. The demo comes with six clouds built from code (an icosphere shell, a
fern plume, a nautilus shell, coral branches, a phyllotaxis bloom and a torus braid) and eleven
example inserts. You can also drop in a `.ply` capture, or a `.glb` model shown as one flat disc
per vertex.

What it teaches is shader thinking on data that is not pixels or triangles: one small program
applied to thousands of independent elements, where position, size, colour and transparency are
all just numbers you can drive with noise, distance and time.

## How to play

1. Launch **renderer**. A window opens.
2. Launch this box. The icosphere shell appears and turns slowly.
3. Open this box's **compute** tab. Your lines go between `#include "splat/insert"` and
   `#include "splat/end"`. Write some, for example `rgba.rgb = vec3(1.0, 0.3, 0.1);`, then
   reload the shader (**Reload Compute Shader**, alt-r). A GLSL error is reported against your
   own line number, and the last good shader keeps running.
4. Launch any of the other boxes. Each is one of the demo's examples on a different cloud, and it
   replaces whatever is showing.
5. Use the **controls** panel under a box: *turn* and *size* are the demo's sliders, *splat size*
   scales every splat on screen, *play* pauses `t` and the turn, *t = 0* restarts time, and
   *trail* with *fade* is the demo's trail mode.

What your lines can use:

| name | type | |
| --- | --- | --- |
| `pos` | `vec3` | the splat's centre; change it |
| `scales` | `vec3` | its three radii; change them |
| `rgba` | `vec4` | its colour and opacity; change them |
| `quat` | `vec4` | its rotation, a quaternion `(x, y, z, w)`; change it |
| `index` | `float` | which splat, 0, 1, 2, ... |
| `t` | `float` | seconds since the box ran |
| `pi` | `float` | |

The helpers are `sat(x)`, `rot2(a)`, `hash31(p)` and `noise31(p)`, the same as the demo's.
Afterwards `rgba` is clamped to 0..1 and every scale is kept above 0.0001.

To use another cloud, change `ProceduralSplats.icosphereShell()` in this box's code to
`fernPlume()`, `nautilusShell()`, `coralBranches()`, `phyllotaxisBloom()` or `torusBraid()`. For
your own data, use `SplatSet.fromPly("/path/to/capture.ply").fitted()` for a Gaussian-splat
capture, or `SplatSet.fromGltf("/path/to/model.glb").fitted()` for a mesh. `fitted()` centres the
cloud and scales it to about a unit across, which is the size the examples are written for.

## How it works in Field

The splats are drawn by Field's own Vulkan Gaussian-splat renderer, not by Spark and three.js.
Each box's compute tab is a compute shader: the two includes wrap your lines in a `main()` that
reads one splat, runs them, and writes the result into the buffer the renderer draws from.
`SplatShader` runs that pass every frame. The renderer then sorts the splats back to front and
draws each as a screen-aligned Gaussian, blended over the ones behind it. The picture goes into a
target that keeps a fading copy of the last frame, which is how trail mode works; with the trail
off that target is cleared every frame.

## What is different from the original

- **The sort sees where your lines moved the splats.** Splats blended over each other only look
  right drawn farthest first. Here your lines run before the sort, so a dissolve that throws
  splats outward is sorted where they went. Measured on the GPU (`four.splat-shader.smoke`),
  moving the splats in the vertex shader instead leaves half of all neighbouring pairs in the
  wrong order.
- **Splats are drawn a little smaller.** Spark blends in display colours and Field blends in
  linear light. At the same radius a Field splat's faint edge shows more, so the default
  *splat size* is 0.67 to match the demo's look. Set it to 1 for the splats' true size.
- **The camera is fixed.** There is no mouse orbit: the cloud turns about its vertical axis, and
  the view is the demo's starting one, from (0, 0.38, 2.65) with a 54° lens.
- **Models are handled more carefully.** Disc sizes for a `.glb` are relative to the model's size
  rather than in absolute units, and textures are not sampled, so discs start in the material
  colours.
- **Left out:** the AI completion, the chat panel, local saves and the "copy LLM intro" button.
  Field's own completion, AI chat, documents and history cover those. Spherical-harmonic colour
  is not exposed, as in the demo.
```

```kotlin [field-property box="splat_passthrough" name="code" path="splat_passthrough_code_.kt"]
import afield.graphics.scene.fabricated.ProceduralSplats
import afield.graphics.scene.fabricated.SplatShader
import afield.graphics.scene.fabricated.showControls

/*
A splat shader. Every frame, the lines in this box's compute tab run once for each of
the cloud's splats, on the GPU, and may change it: move it, recolour it, stretch it,
fade it. The renderer then sorts the splats by depth and draws them where your lines
left them. This one changes nothing, so you see the cloud as it was built.

The other boxes are the demo's examples, each on a different cloud. Launch one and it
replaces this. Try another cloud here too: ProceduralSplats.fernPlume(), nautilusShell(),
coralBranches(), phyllotaxisBloom(), torusBraid(); or your own capture,
SplatSet.fromPly("/path/to/splats.ply"), or a mesh as one disc per vertex,
SplatSet.fromGltf("/path/to/model.glb").
*/
var module = computeModuleHere {}

val splats = SplatShader(module, ProceduralSplats.icosphereShell())
showControls(splats)
```

```glsl [field-property box="splat_passthrough" name="compute" path="splat_passthrough_compute_.glsl"]
#version 450
#include "splat/insert"

/*
  Change these:
    pos     vec3   the splat's centre
    scales  vec3   its radii
    rgba    vec4   its colour and opacity
    quat    vec4   its rotation, a quaternion

  Read these:
    index   float  which splat, 0, 1, 2, ...
    t       float  seconds since the box ran
    pi

  Helpers: sat(x), rot2(a), hash31(p), noise31(p)

  Afterwards rgba is clamped to 0..1 and every scale kept above 0.0001.
  This is a passthrough. Copy lines from the other boxes, or write your own.
*/

#include "splat/end"
```

## 6. dissolve

<!-- field-box id="splat_dissolve" index="5" -->

### Narrative

From `notes`

Dissolve

```edn [field-box id="splat_dissolve" role="properties"]
{"id" "splat_dissolve" "index" 5 "name" "dissolve" "parents" ["root" "splat_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "splat_renderer"} "name" "dissolve" "_tombstone" false "frame" #afield/rect [640.0 720.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "splat_dissolve_notes_.md" "lines" 8 "characters" 327 "sha256" "ad3b4a03bbbca2902738300b193aa3795b311739c6ba5b928e7282853ba6f96e"} {"name" "code" "language" "kotlin" "path" "splat_dissolve_code_.kt" "lines" 14 "characters" 584 "sha256" "915b087b839a258bd256d6bd1104a50b9a16b1f5e2d6a66f2691f3ff47a318d2"} {"name" "compute" "language" "glsl" "path" "splat_dissolve_compute_.glsl" "lines" 14 "characters" 426 "sha256" "ed4c69f3f194f5b3996c4ec92972f2ef3e5834776c0025939082ee5492ea52c3"}]}
```

```markdown [field-property box="splat_dissolve" name="notes" path="splat_dissolve_notes_.md"]
# Dissolve

A threshold sweeps through a noise field. Where the noise is below it, splats drift outward,
shrink, turn orange and fade. The sweep repeats every eight seconds or so. Shown on the
**icosphere shell**.

This is the example where the sort matters most: the splats really move, and they are sorted
where they end up.
```

```kotlin [field-property box="splat_dissolve" name="code" path="splat_dissolve_code_.kt"]
import afield.graphics.scene.fabricated.ProceduralSplats
import afield.graphics.scene.fabricated.SplatShader
import afield.graphics.scene.fabricated.showControls

/*
Dissolve. A threshold sweeps through a noise field. Where the noise falls below it,
splats let go: they drift outward, shrink, burn orange and fade. Every few seconds the
sweep starts again and the cloud comes back. The splats really do move, so the renderer
sorts them where they drift to.
*/
var module = computeModuleHere {}

val splats = SplatShader(module, ProceduralSplats.icosphereShell())
showControls(splats)
```

```glsl [field-property box="splat_dissolve" name="compute" path="splat_dissolve_compute_.glsl"]
#version 450
#include "splat/insert"

float n = noise31(pos * 3.2 + vec3(0.0, t * 0.22, 0.0));
float sweep = fract(t * 0.12);
float keep = smoothstep(sweep - 0.08, sweep + 0.12, n);

vec3 drift = normalize(pos + hash31(pos) * 0.4);
pos += drift * (1.0 - keep) * (0.45 + 0.25 * sin(t + index * 0.01));
rgba.rgb = mix(vec3(1.0, 0.35, 0.12), rgba.rgb, keep);
rgba.a *= keep;
scales *= mix(0.18, 1.0, keep);

#include "splat/end"
```

## 7. lichen tangle

<!-- field-box id="splat_lichen_tangle" index="6" -->

### Narrative

From `notes`

Lichen tangle

```edn [field-box id="splat_lichen_tangle" role="properties"]
{"id" "splat_lichen_tangle" "index" 6 "name" "lichen tangle" "parents" ["root" "splat_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "splat_renderer"} "name" "lichen tangle" "_tombstone" false "frame" #afield/rect [640.0 1240.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "splat_lichen_tangle_notes_.md" "lines" 4 "characters" 204 "sha256" "7bbb15369c1676841506a49935c1cee958d8b8d1fe09abbd49d4c84f8741495e"} {"name" "code" "language" "kotlin" "path" "splat_lichen_tangle_code_.kt" "lines" 13 "characters" 470 "sha256" "e78147b11b559293eea69a9e277e389bf48a4afac4f604ac2f1e51d04ac47d66"} {"name" "compute" "language" "glsl" "path" "splat_lichen_tangle_compute_.glsl" "lines" 20 "characters" 542 "sha256" "8f166bc62df520ad2c609ea2ddee4d6a2a3e1d8827b633f0f87ae7c82e56a3ae"}]}
```

```markdown [field-property box="splat_lichen_tangle" name="notes" path="splat_lichen_tangle_notes_.md"]
# Lichen tangle

Five octaves of folded noise grow a canopy over the cloud. Splats under it swell, turn pale
green and move a little outward; the rest sink into the dark. Shown on the **coral branches**.
```

```kotlin [field-property box="splat_lichen_tangle" name="code" path="splat_lichen_tangle_code_.kt"]
import afield.graphics.scene.fabricated.ProceduralSplats
import afield.graphics.scene.fabricated.SplatShader
import afield.graphics.scene.fabricated.showControls

/*
Lichen tangle. Five octaves of folded noise grow a canopy over the coral: splats under
it swell and turn pale green and are pushed a little outward, the rest sink into the
dark.
*/
var module = computeModuleHere {}

val splats = SplatShader(module, ProceduralSplats.coralBranches())
showControls(splats)
```

```glsl [field-property box="splat_lichen_tangle" name="compute" path="splat_lichen_tangle_compute_.glsl"]
#version 450
#include "splat/insert"

vec3 p = pos;
float branch = 0.0;
float amp = 0.58;
for (int i = 0; i < 5; i++) {
    branch += amp * abs(noise31(p * 4.2) - 0.5);
    p = p.yzx * 1.73 + vec3(0.19, -0.07, 0.11);
    amp *= 0.6;
}

float canopy = sat(1.0 - branch * 1.7);
vec3 dir = normalize(hash31(floor(pos * 9.0)) - 0.5 + vec3(0.0, 0.35, 0.0));
pos += dir * canopy * 0.12;
rgba.rgb = mix(vec3(0.04, 0.03, 0.02), vec3(0.78, 0.96, 0.52), canopy);
rgba.a *= 0.08 + 0.92 * canopy;
scales *= mix(0.16, 1.85, canopy);

#include "splat/end"
```

## 8. gravity well

<!-- field-box id="splat_gravity_well" index="7" -->

### Narrative

From `notes`

Gravity well

```edn [field-box id="splat_gravity_well" role="properties"]
{"id" "splat_gravity_well" "index" 7 "name" "gravity well" "parents" ["root" "splat_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "splat_renderer"} "name" "gravity well" "_tombstone" false "frame" #afield/rect [1240.0 720.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "splat_gravity_well_notes_.md" "lines" 5 "characters" 259 "sha256" "851c5dfe9093ef7ed7803592365784587ca0262fac36d87f5892979e1d73373c"} {"name" "code" "language" "kotlin" "path" "splat_gravity_well_code_.kt" "lines" 16 "characters" 560 "sha256" "2455a97e949faf042f556f1c9c03205ecc98ee451272d96f562fc7696a1c16bf"} {"name" "compute" "language" "glsl" "path" "splat_gravity_well_compute_.glsl" "lines" 14 "characters" 350 "sha256" "78211dd1bd349e0d4fa8366ff5abed8830a7c7c723c55541ab050ee0bb4eb340"}]}
```

```markdown [field-property box="splat_gravity_well" name="notes" path="splat_gravity_well_notes_.md"]
# Gravity well

Splats near the vertical axis are pulled harder: they spin faster, bob, swell and glow violet,
while those far out stay faint. Shown on the **torus braid**, with the trail on, so the spin
leaves streaks. Untick *trail* to see it without them.
```

```kotlin [field-property box="splat_gravity_well" name="code" path="splat_gravity_well_code_.kt"]
import afield.graphics.scene.fabricated.ProceduralSplats
import afield.graphics.scene.fabricated.SplatShader
import afield.graphics.scene.fabricated.showControls

/*
Gravity well. Splats near the vertical axis are pulled harder: they spin faster round
it, bob, swell and glow violet, while those far out stay faint.
*/
var module = computeModuleHere {}

// trail: each frame lands on a fading copy of the last, so the spin leaves streaks
val splats = SplatShader(module, ProceduralSplats.torusBraid()) {
    trail = true
    fade = 0.12
}
showControls(splats)
```

```glsl [field-property box="splat_gravity_well" name="compute" path="splat_gravity_well_compute_.glsl"]
#version 450
#include "splat/insert"

float r = length(pos.xz) + 0.001;
float pull = 0.22 / (0.22 + r * r);
float spin = t * 0.8 + pull * 3.0;

pos.xz *= rot2(spin);
pos.y -= pull * 0.45 * sin(t * 1.2 + r * 8.0);
rgba.rgb = mix(rgba.rgb, vec3(0.88, 0.68, 1.0), pull);
rgba.a *= 0.35 + sat(r * 1.4);
scales *= 0.65 + pull * 2.4;

#include "splat/end"
```

## 9. scanlines on a model

<!-- field-box id="splat_toy_car" index="8" -->

### Narrative

From `notes`

Scanlines on a model

```edn [field-box id="splat_toy_car" role="properties"]
{"id" "splat_toy_car" "index" 8 "name" "scanlines on a model" "parents" ["root" "splat_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "splat_renderer"} "name" "scanlines on a model" "_tombstone" false "frame" #afield/rect [40.0 1760.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "splat_toy_car_notes_.md" "lines" 8 "characters" 424 "sha256" "38daf8cc7df12940576391bf27213d0f2f3b51a0d0c95901e39d6bbffc877f1b"} {"name" "code" "language" "kotlin" "path" "splat_toy_car_code_.kt" "lines" 28 "characters" 1351 "sha256" "897ed3930ddb1d5c157e3e667025e9c1b448513f7090a040d97c133f4db54f7b"} {"name" "compute" "language" "glsl" "path" "splat_toy_car_compute_.glsl" "lines" 12 "characters" 320 "sha256" "ab4146ae7edd8d5a2c75cb0c0a92af93bcba6def3ad8ae7ac099dcb9b270ac6b"}]}
```

```markdown [field-property box="splat_toy_car" name="notes" path="splat_toy_car_notes_.md"]
# Scanlines on a model

The demo's scanlines over a mesh rather than a cloud. `SplatSet.fromGltf` reads the Khronos
ToyCar sample committed in this repository and puts one flat disc on every vertex, facing along
its normal; `fitted()` brings it to about a unit across so the stripes are the right size.

The path is relative to the repository root. If Field was started somewhere else, point `path`
at a `.glb` of your own.
```

```kotlin [field-property box="splat_toy_car" name="code" path="splat_toy_car_code_.kt"]
import afield.graphics.scene.fabricated.SplatSet
import afield.graphics.scene.fabricated.SplatShader
import afield.graphics.scene.fabricated.showControls

/*
Scanlines, on a mesh instead of a cloud. SplatSet.fromGltf reads a .glb through Field's
glTF loader and puts one flat disc on every vertex, facing along its normal and sized
from how densely the vertices cover the surface. Then bright bands sweep up through it.

The model is the Khronos ToyCar sample committed in this repository; the path is
relative to the repository root, so point it at a .glb of your own if Field was started
elsewhere. Textures are not sampled, so the discs start in the material colours.
*/
var module = computeModuleHere {}

val path = "public/docs/assets/fabricate_demo/ToyCar.glb"
if (!java.io.File(path).isFile) {
    println("no model at ${java.io.File(path).absolutePath}: point `path` at a .glb of your own")
} else {
    // fitted(): centred and about a unit across, the size the insert's stripes are written for
    // the ToyCar sample sits on a draped cloth, its "Fabric" mesh; leave that out
    val car = SplatSet.fromGltf(path, meshes = { it != "Fabric" })
    // the wheels and chassis pack most of the vertices near the middle, so frame it larger
    val splats = SplatShader(module, car.fitted()) {
        size = 1.8
    }
    showControls(splats)
}
```

```glsl [field-property box="splat_toy_car" name="compute" path="splat_toy_car_compute_.glsl"]
#version 450
#include "splat/insert"

float stripe = sin((pos.y + t * 0.28) * 38.0);
float gate = smoothstep(0.35, 0.9, stripe);

pos.x += sin(pos.y * 9.0 + t * 2.0) * 0.025;
rgba.rgb = mix(rgba.rgb * 0.18, vec3(0.2, 1.0, 0.58), gate);
rgba.a *= 0.18 + gate * 0.82;
scales.xy *= 0.55 + gate * 1.7;

#include "splat/end"
```

