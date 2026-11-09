# Field Document: atmospheric_sky

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["atmospheric_sky"] "properties" {"children" #{#afield/box "atmospheric_sky"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 15 "characters" 275 "sha256" "e90393b044932ad7e022f5505bec768466f0d819aaa553c877bfe061d981c6d2"}]}
```

```kotlin [field-property box="agent_graphics_context" name="code" path="agent_graphics_context_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.profiling = false

once.renderer {
    window = Window("Field")
    Renderer(window)
}

println("agent graphics renderer ready")
start(renderer::run)
```

## 2. atmospheric sky

<!-- field-box id="atmospheric_sky" index="1" -->

### Narrative

From `code` tags: `#include`

A sky you can live-code: single scattering through Earth's atmosphere (Nishita 1993, with Hillaire 2020's
coefficients), one fullscreen triangle beneath the renderer. The physics is `#include "sky/atmosphere.glsl"`;
the camera and the tone mapping are in this box's fragment property. Drag the sliders, or edit the numbers
below and re-run.

```edn [field-box id="atmospheric_sky" role="properties"]
{"id" "atmospheric_sky" "index" 1 "name" "atmospheric sky" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "atmospheric sky" "_tombstone" false "frame" #afield/rect [400.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "atmospheric_sky_code_.kt" "lines" 36 "characters" 1709 "sha256" "ef9e14acf85b6e4108c0a5a251847ed5185ea4dc60b7fec21dffd77cf67662c7"} {"name" "vertex" "language" "glsl" "path" "atmospheric_sky_vertex_.glsl" "lines" 6 "characters" 227 "sha256" "8f3504d8801a4398f2518ca9de09a3f9f4b72e4eb43044567049caf473214b5a"} {"name" "fragment" "language" "glsl" "path" "atmospheric_sky_fragment_.glsl" "lines" 31 "characters" 1163 "sha256" "4199d44357e6911d8dc0ce582557ebbfcd5385edb86285176144091a9bbae88a"}]}
```

```kotlin [field-property box="atmospheric_sky" name="code" path="atmospheric_sky_code_.kt"]
/*
A sky you can live-code: single scattering through Earth's atmosphere (Nishita 1993, with Hillaire 2020's
coefficients), one fullscreen triangle beneath the renderer. The physics is `#include "sky/atmosphere.glsl"`;
the camera and the tone mapping are in this box's fragment property. Drag the sliders, or edit the numbers
below and re-run.
*/
var sunElevation = 30.0 // degrees above the horizon
var sunAzimuth = 0.0 // degrees, 0 is straight ahead
var altitude = 3.5 // km above the ground
var pitch = 10.0 // degrees the camera looks up (-90 looks straight down)
var exposure = 2.0
var fieldOfView = 90.0 // degrees, vertical

val sky = moduleHere {
    depthTest = false
    depthWrite = false
    blend = false
}
val quad = fullscreenTriangle(sky)
sky += "quad" to quad
renderer += "atmospheric-sky" to sky

quad.viewport = { vec(window.width.toFloat(), window.height.toFloat(), altitude, Math.toRadians(sunElevation)) }
quad.view = { vec(Math.toRadians(sunAzimuth), Math.toRadians(pitch), exposure, Math.toRadians(fieldOfView)) }

controlGroup("Sky") {
    slider("Sun elevation (degrees)", min = -12.0, max = 90.0, initialValue = sunElevation) { sunElevation = it }
    slider("Sun azimuth (degrees)", min = -180.0, max = 180.0, initialValue = sunAzimuth) { sunAzimuth = it }
    slider("Altitude (log10 km)", min = -3.0, max = 4.5, initialValue = Math.log10(altitude)) {
        altitude = Math.pow(10.0, it)
    }
    slider("Camera pitch (degrees)", min = -90.0, max = 90.0, initialValue = pitch) { pitch = it }
    slider("Exposure", min = 0.5, max = 40.0, initialValue = exposure) { exposure = it }
}
displayOpen("Sky")
println("atmospheric sky: sun ${sunElevation}°, altitude ${altitude} km")
```

```glsl [field-property box="atmospheric_sky" name="vertex" path="atmospheric_sky_vertex_.glsl"]
#version 450
// one triangle that covers the whole window; positions come from the vertex index
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
```

```glsl [field-property box="atmospheric_sky" name="fragment" path="atmospheric_sky_fragment_.glsl"]
#version 450
#include "sky/atmosphere.glsl"

layout(location = 0) out vec4 color;

layout(push_constant) uniform pcs {
    vec4 viewport; // width, height, altitude (km), sun elevation (radians)
    vec4 view; // sun azimuth, camera pitch, exposure, vertical field of view (radians)
} pc;

void main() {
    Atmosphere a = earthAtmosphere();

    // A pinhole camera at the given altitude, looking along -z and pitched up by view.y.
    vec2 uv = (gl_FragCoord.xy - 0.5 * pc.viewport.xy) / pc.viewport.y;
    uv.y = -uv.y;
    float pitch = pc.view.y;
    vec3 forward = vec3(0.0, sin(pitch), -cos(pitch));
    vec3 right = vec3(1.0, 0.0, 0.0);
    vec3 up = cross(right, forward);
    vec3 rd = normalize(forward + (uv.x * right + uv.y * up) * 2.0 * tan(0.5 * pc.view.w));
    vec3 ro = vec3(0.0, a.planetRadius + pc.viewport.z, 0.0);

    float e = pc.viewport.w, az = pc.view.x;
    vec3 sun = vec3(cos(e) * sin(az), sin(e), -cos(e) * cos(az));

    vec3 L = skyRadiance(a, ro, rd, sun, vec3(20.0), 0.00465, 32, 8);

    // Exposure, then a soft shoulder so the sun's disc saturates instead of clipping hue.
    color = vec4(1.0 - exp(-L * pc.view.z), 1.0);
}
```

