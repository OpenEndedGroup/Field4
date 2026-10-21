# Field Document: splat_shader

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 8 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="splat_renderer" index="0" -->

```edn [field-box id="splat_renderer" role="properties"]
{"id" "splat_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["splat_passthrough" "splat_pulse_rings" "splat_dissolve" "splat_gravity_well" "splat_fern_veins" "splat_lichen_tangle" "splat_xray_shell"] "properties" {"children" #{#afield/box "splat_passthrough" #afield/box "splat_pulse_rings" #afield/box "splat_dissolve" #afield/box "splat_gravity_well" #afield/box "splat_fern_veins" #afield/box "splat_lichen_tangle" #afield/box "splat_xray_shell"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "splat_renderer_code_.kt" "lines" 13 "characters" 439 "sha256" "5ec1e50d2c42076d2e020f1ffd64c062192c7c22d8aa270c7c0fa0c9793fedc3"}]}
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

```edn [field-box id="splat_fern_veins" role="properties"]
{"id" "splat_fern_veins" "index" 1 "name" "fern veins" "parents" ["root" "splat_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "splat_renderer"} "name" "fern veins" "_tombstone" false "frame" #afield/rect [40.0 1120.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "splat_fern_veins_code_.kt" "lines" 12 "characters" 433 "sha256" "31e7a088395d2336dcb0beed7d0a8c45f5cb4e8ad07eab8640ab0d6e933f3a49"} {"name" "compute" "language" "glsl" "path" "splat_fern_veins_compute_.glsl" "lines" 14 "characters" 433 "sha256" "29d25a380e5487e891bd5327a48dd08dd6eacf04bada704ff84e476ec10ec4e6"}]}
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

```edn [field-box id="splat_pulse_rings" role="properties"]
{"id" "splat_pulse_rings" "index" 2 "name" "pulse rings" "parents" ["root" "splat_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "splat_renderer"} "name" "pulse rings" "_tombstone" false "frame" #afield/rect [40.0 660.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "splat_pulse_rings_code_.kt" "lines" 12 "characters" 461 "sha256" "1852c032dc391ca07bd69fdd2a8e55076508096aba217dab9f4ac05a55464f91"} {"name" "compute" "language" "glsl" "path" "splat_pulse_rings_compute_.glsl" "lines" 17 "characters" 452 "sha256" "efa652df6e3e4ee1326ffc5f9f5d6a3af904e3f7e2dc7f80c69a3713aef59988"}]}
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

```edn [field-box id="splat_xray_shell" role="properties"]
{"id" "splat_xray_shell" "index" 3 "name" "xray shell" "parents" ["root" "splat_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "splat_renderer"} "name" "xray shell" "_tombstone" false "frame" #afield/rect [1240.0 1120.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "splat_xray_shell_code_.kt" "lines" 12 "characters" 459 "sha256" "6086b4240464a87e942171e488a3231a3073323e38147c893c6bde6e980e94b7"} {"name" "compute" "language" "glsl" "path" "splat_xray_shell_compute_.glsl" "lines" 13 "characters" 386 "sha256" "1e9b757b35e20151bc50e34a071ee7f5cf05fe81fa8e21042d5842baa782ee2a"}]}
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

```edn [field-box id="splat_passthrough" role="properties"]
{"id" "splat_passthrough" "index" 4 "name" "passthrough" "parents" ["root" "splat_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "splat_renderer"} "name" "passthrough" "_tombstone" false "frame" #afield/rect [40.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "splat_passthrough_code_.kt" "lines" 21 "characters" 1038 "sha256" "adcdd2b924bf9ed232484dd7b321aa2ce83a5e9c73e37b4acfae23d4bf3c0c65"} {"name" "compute" "language" "glsl" "path" "splat_passthrough_compute_.glsl" "lines" 22 "characters" 550 "sha256" "03da5912255eed50c7ec8d92fd9d982c020efe69e12c30be657dfe8572136247"}]}
```

```kotlin [field-property box="splat_passthrough" name="code" path="splat_passthrough_code_.kt"]
import afield.graphics.scene.fabricated.ProceduralSplats
import afield.graphics.scene.fabricated.SplatSet
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

```edn [field-box id="splat_dissolve" role="properties"]
{"id" "splat_dissolve" "index" 5 "name" "dissolve" "parents" ["root" "splat_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "splat_renderer"} "name" "dissolve" "_tombstone" false "frame" #afield/rect [640.0 660.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "splat_dissolve_code_.kt" "lines" 14 "characters" 584 "sha256" "915b087b839a258bd256d6bd1104a50b9a16b1f5e2d6a66f2691f3ff47a318d2"} {"name" "compute" "language" "glsl" "path" "splat_dissolve_compute_.glsl" "lines" 14 "characters" 426 "sha256" "ed4c69f3f194f5b3996c4ec92972f2ef3e5834776c0025939082ee5492ea52c3"}]}
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

```edn [field-box id="splat_lichen_tangle" role="properties"]
{"id" "splat_lichen_tangle" "index" 6 "name" "lichen tangle" "parents" ["root" "splat_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "splat_renderer"} "name" "lichen tangle" "_tombstone" false "frame" #afield/rect [640.0 1120.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "splat_lichen_tangle_code_.kt" "lines" 13 "characters" 470 "sha256" "e78147b11b559293eea69a9e277e389bf48a4afac4f604ac2f1e51d04ac47d66"} {"name" "compute" "language" "glsl" "path" "splat_lichen_tangle_compute_.glsl" "lines" 20 "characters" 536 "sha256" "ef90ae20f4f11e796d616e9a7ca67864caf9eb9782c32aefdf6d4ec9439a8299"}]}
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

```edn [field-box id="splat_gravity_well" role="properties"]
{"id" "splat_gravity_well" "index" 7 "name" "gravity well" "parents" ["root" "splat_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "splat_renderer"} "name" "gravity well" "_tombstone" false "frame" #afield/rect [1240.0 660.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "splat_gravity_well_code_.kt" "lines" 12 "characters" 439 "sha256" "2d4ebdada47bbdac36805fc478a1446133aa1d4d1458dff1993515ab2ec0f7a8"} {"name" "compute" "language" "glsl" "path" "splat_gravity_well_compute_.glsl" "lines" 14 "characters" 350 "sha256" "78211dd1bd349e0d4fa8366ff5abed8830a7c7c723c55541ab050ee0bb4eb340"}]}
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

val splats = SplatShader(module, ProceduralSplats.torusBraid())
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

