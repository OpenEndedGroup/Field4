# Field Document: lattice_boltzmann_cylinder

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="lbm_renderer" index="0" -->

```edn [field-box id="lbm_renderer" role="properties"]
{"id" "lbm_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["lbm_fluid"] "properties" {"children" #{#afield/box "lbm_fluid"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lbm_renderer_code_.kt" "lines" 15 "characters" 509 "sha256" "e055a811e3d0713b2db3405794bd18ee91bea2d517361c8a0bfe9b9471332afa"}]}
```

```kotlin [field-property box="lbm_renderer" name="code" path="lbm_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
// once: re-running this box keeps the existing window and renderer
once.renderer {
    window =
        Window("Lattice Boltzmann - a cylinder in a wind tunnel", initialDimensions = Rect(80.0, 80.0, 1000.0, 600.0))
    Renderer(window)
}
println("renderer ready")
// the frame loop runs as this box's coroutine; stopping the box stops rendering
start(renderer::run)
```

## 2. fluid

<!-- field-box id="lbm_fluid" index="1" -->

### Narrative

From `code`

A lattice Boltzmann fluid in a wind tunnel: a wind blows left to right and the
lattice wraps, so what leaves on the right comes back on the left.
On its own it is uniform wind and shows nothing; the box beneath this one puts
something in its way.

```edn [field-box id="lbm_fluid" role="properties"]
{"id" "lbm_fluid" "index" 1 "name" "fluid" "parents" ["root" "lbm_renderer"] "children" ["lbm_cylinder"] "properties" {"children" #{#afield/box "lbm_cylinder"} "parents" #{#afield/box "root" #afield/box "lbm_renderer"} "name" "fluid" "_tombstone" false "frame" #afield/rect [340.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lbm_fluid_code_.kt" "lines" 23 "characters" 949 "sha256" "5331d4ad169f31f0ef6bbaf106a697f9aca7969b5c957fe7a0cf74aa2510404e"}]}
```

```kotlin [field-property box="lbm_fluid" name="code" path="lbm_fluid_code_.kt"]
/*
A lattice Boltzmann fluid in a wind tunnel: a wind blows left to right and the
lattice wraps, so what leaves on the right comes back on the left.
On its own it is uniform wind and shows nothing; the box beneath this one puts
something in its way.
*/
prop<ShaderLatticeBoltzmannSystem>(lbm) = ShaderLatticeBoltzmannSystem() {
    width = 800
    height = 480
    baseVelocity = Vector2f(0.08f, 0f)
    // a thin fluid (viscosity about 0.014) so the wake sheds vortices
    relaxation = 1.85f
    // nothing pulls the fluid back toward the wind, so the wake travels;
    // the inlet that 10b draws at the left edge keeps the wind blowing
    driveBlend = 0f
    stepsPerFrame = 8
    dyeDecayPerFrame = 0.001f
    // vorticity: orange turns one way, blue the other. Try COMPOSITE, DYE or SPEED
    previewMode = FluidVisualizationMode.CURL
}

// this installs the editable collision-and-streaming rule in this box's fragment tab
lbm.installInto()
```

## 3. cylinder

<!-- field-box id="lbm_cylinder" index="2" -->

### Narrative

From `code`

Draws into the fluid's control texture: a solid disc, walls top and bottom, and
stripes of dye released at the left edge. The controls persist from frame to
frame, so clear them first or a re-run leaves the old drawing behind.

```edn [field-box id="lbm_cylinder" role="properties"]
{"id" "lbm_cylinder" "index" 2 "name" "cylinder" "parents" ["root" "lbm_fluid"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "lbm_fluid"} "name" "cylinder" "_tombstone" false "frame" #afield/rect [340.0 160.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lbm_cylinder_code_.kt" "lines" 16 "characters" 494 "sha256" "79266ffd359cdd12a94b22aa274fccc154d9ef43d0657cefed497e023cca0fd9"} {"name" "vertex" "language" "glsl" "path" "lbm_cylinder_vertex_.glsl" "lines" 12 "characters" 345 "sha256" "c0ac9cc065ddd6b8287a1f4e4fa5deda869d6ebda87289989aa92f59ebdc6e54"} {"name" "fragment" "language" "glsl" "path" "lbm_cylinder_fragment_.glsl" "lines" 21 "characters" 582 "sha256" "3b24a103fe4c9a276e882e311da89cdc3d0dd74c3d2731b88fef79964e8c5646"}]}
```

```kotlin [field-property box="lbm_cylinder" name="code" path="lbm_cylinder_code_.kt"]
/*
Draws into the fluid's control texture: a solid disc, walls top and bottom, and
stripes of dye released at the left edge. The controls persist from frame to
frame, so clear them first or a re-run leaves the old drawing behind.
*/
lbm.clearControls()

var module = moduleHere(lbm.controlsParameters("cylinder"))
lbm.addControls("cylinder", module)

val quad = fullscreenTriangle(module)
module += "quad" to quad

quad.center = { vec(160f, 236f) }
quad.radius = { 24f }
quad.height = { 480f }
```

```glsl [field-property box="lbm_cylinder" name="vertex" path="lbm_cylinder_vertex_.glsl"]
#version 450

// one triangle that covers the whole target; positions come from the vertex index
vec2 fullscreenVertex(uint index) {
    if (index == 0u) return vec2(-1.0, -1.0);
    if (index == 1u) return vec2(3.0, -1.0);
    return vec2(-1.0, 3.0);
}

void main() {
    gl_Position = vec4(fullscreenVertex(uint(gl_VertexIndex)), 0.0, 1.0);
}
```

```glsl [field-property box="lbm_cylinder" name="fragment" path="lbm_cylinder_fragment_.glsl"]
#version 450

layout(push_constant) uniform Push {
    vec2 center;
    float radius;
    float height;
} push;

layout(location = 0) out vec4 outColor;

// red: solid; green and blue: a push; alpha: a dye source.
// The blend is additive and the controls carry over, so this is a drawing, not a frame.
void main() {
    vec2 p = gl_FragCoord.xy;
    outColor = vec4(0.0);

    if (distance(p, push.center) < push.radius) outColor.r = 1.0;
    if (p.y < 3.0 || p.y > push.height - 3.0) outColor.r = 1.0;

    if (p.x < 4.0 && mod(floor(p.y / 12.0), 2.0) == 0.0) outColor.a = 1.0;
}
```

