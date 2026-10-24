# Field Document: demo_document

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 20 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. gyroid

<!-- field-box id="demo_gyroid" index="0" -->

### Narrative

From `code`

A marched gyroid and its open rims, as one three.js scene in the output panel.

```edn [field-box id="demo_gyroid" role="properties"]
{"id" "demo_gyroid" "index" 0 "name" "gyroid" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "gyroid" "_tombstone" false "frame" #afield/rect [1300.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "demo_gyroid_code_.kt" "lines" 13 "characters" 487 "sha256" "3a097f5fc6f4f51e9c6987339db7a9384ac9f10b54afce4b03700b749490434b"}]}
```

```kotlin [field-property box="demo_gyroid" name="code" path="demo_gyroid_code_.kt"]
// A marched gyroid and its open rims, as one three.js scene in the output panel.
val surface = marchingCubes(sdfGyroid(4.0, 0.3), boundsAround(radius = 1.0), 56)
val rims =
    surface.toFLines(MeshLines.BOUNDARY).map { ring ->
        ring.color = vec(1.0, 0.45, 0.12, 1.0)
        ring.thickness = 2.6
        ring
    }
three(channel = "gyroid", height = 420, width = 640) {
    mesh(surface, wireframe = false)
    lines(rims)
    camera(yaw = 32.0, pitch = 20.0, padding = 1.02)
}
```

## 2. cue 1

<!-- field-box id="demo_cue_1" index="1" -->

### Narrative

From `code`

Runs while the time slider is over it.

```edn [field-box id="demo_cue_1" role="properties"]
{"id" "demo_cue_1" "index" 1 "name" "cue 1" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "cue 1" "_tombstone" false "frame" #afield/rect [4420.0 80.0 260.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "demo_cue_1_code_.kt" "lines" 4 "characters" 202 "sha256" "e7e51625e49ced129a5356b51eaa1cf172ddaf03842f5455c85dbe97899d2ab9"}]}
```

```kotlin [field-property box="demo_cue_1" name="code" path="demo_cue_1_code_.kt"]
// Runs while the time slider is over it.
display("main", "<div style='width:300px;height:26px;border-radius:4px;background:linear-gradient(90deg,#1971c2,#4dabf7)'></div>")
displayOpen("main")
loop { }
```

## 3. palette

<!-- field-box id="demo_palette" index="2" -->

### Narrative

From `code`

Everything connected beneath this box sees `petals`, and reruns when it changes.

```edn [field-box id="demo_palette" role="properties"]
{"id" "demo_palette" "index" 2 "name" "palette" "parents" ["root"] "children" ["demo_swatch" "demo_rosette" "demo_sketch"] "properties" {"children" #{#afield/box "demo_swatch" #afield/box "demo_rosette" #afield/box "demo_sketch"} "parents" #{#afield/box "root"} "name" "palette" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "demo_palette_code_.kt" "lines" 4 "characters" 222 "sha256" "fed6c499e3255171c2028d8ce1700de66d51216d9dfe842f87aa230fb3d225c0"}]}
```

```kotlin [field-property box="demo_palette" name="code" path="demo_palette_code_.kt"]
// Everything connected beneath this box sees `petals`, and reruns when it changes.
propDown<Int>(petals) = 7
markdown("Pushing **" + petals + " petals** down to every box connected beneath this one.")
displayOpen("main")
```

## 4. sketch

<!-- field-box id="demo_sketch" index="3" -->

### Narrative

From `code`

Two helpers are nested inside this box: open its "nested" panel to see them.

```edn [field-box id="demo_sketch" role="properties"]
{"id" "demo_sketch" "index" 3 "name" "sketch" "parents" ["root" "demo_palette"] "children" ["demo_leaf" "demo_bud"] "properties" {"children" #{#afield/box "demo_leaf" #afield/box "demo_bud"} "parents" #{#afield/box "root" #afield/box "demo_palette"} "name" "sketch" "_tombstone" false "frame" #afield/rect [40.0 760.0 300.0 100.0] "nested" #{#afield/box "demo_leaf" #afield/box "demo_bud"}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "demo_sketch_code_.kt" "lines" 4 "characters" 220 "sha256" "3521731378bc0f4929a66fbed3840f1ba65096aad6b2cc069721ba3c662bcaa7"}]}
```

```kotlin [field-property box="demo_sketch" name="code" path="demo_sketch_code_.kt"]
// Two helpers are nested inside this box: open its "nested" panel to see them.
markdown("sketch holds " + nested.size + " nested helpers, and " + petals + " petals from above")
displayOpen("main")
displayOpen("nested")
```

## 5. bud

<!-- field-box id="demo_bud" index="4" -->

```edn [field-box id="demo_bud" role="properties"]
{"id" "demo_bud" "index" 4 "name" "bud" "parents" ["root" "demo_sketch"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "demo_sketch"} "name" "bud" "_tombstone" false "frame" #afield/rect [20.0 110.0 200.0 60.0] "nestedIn" #afield/box "demo_sketch"} "blockProperties" [{"name" "code" "language" "kotlin" "path" "demo_bud_code_.kt" "lines" 1 "characters" 24 "sha256" "f821517d52d0fa62475ae48adcfeb86830e08e2dfefcd092ddd0a82eb522c404"}]}
```

```kotlin [field-property box="demo_bud" name="code" path="demo_bud_code_.kt"]
println("a nested bud")
```

## 6. renderer

<!-- field-box id="demo_renderer" index="5" -->

```edn [field-box id="demo_renderer" role="properties"]
{"id" "demo_renderer" "index" 5 "name" "renderer" "parents" ["root"] "children" ["demo_spiral" "demo_particles"] "properties" {"children" #{#afield/box "demo_spiral" #afield/box "demo_particles"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [2300.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "demo_renderer_code_.kt" "lines" 12 "characters" 271 "sha256" "d14a71263ee70760260f0379085fa500294540a2495cf161c1e5f0ab474ce313"}]}
```

```kotlin [field-property box="demo_renderer" name="code" path="demo_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Field demo document")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 7. probed particles

<!-- field-box id="demo_particles" index="6" -->

### Narrative

From `code`

A compute shader moves 100,000 particles; the probe plots how far each one moved.

```edn [field-box id="demo_particles" role="properties"]
{"id" "demo_particles" "index" 6 "name" "probed particles" "parents" ["root" "demo_renderer"] "children" ["demo_draw"] "properties" {"children" #{#afield/box "demo_draw"} "parents" #{#afield/box "root" #afield/box "demo_renderer"} "name" "probed particles" "_tombstone" false "frame" #afield/rect [3300.0 40.0 300.0 100.0]} "blockProperties" [{"name" "compute" "language" "glsl" "path" "demo_particles_compute_.glsl" "lines" 29 "characters" 813 "sha256" "d9175777709da5fbec6625b4a9a6cc0312dcd644212dfc9b290aa725c8d145c7"} {"name" "code" "language" "kotlin" "path" "demo_particles_code_.kt" "lines" 25 "characters" 831 "sha256" "6f7b141ed382e14890614c9acba46aca6504dfd6d486725c5644f5b6a0786e20"}]}
```

```glsl [field-property box="demo_particles" name="compute" path="demo_particles_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;
layout(std430, buffer_reference) buffer inData {
    vec4 a[];
};
layout(std430, buffer_reference) buffer outData {
    vec4 a[];
};
layout(push_constant) uniform dat {
    inData inData;
    outData outData;
    float something;
    uint particleCount;
} pc;
{% property "fieldProbe" %}
void main() {
    uint idx = gl_GlobalInvocationID.x;
    if (idx >= pc.particleCount) return;
    vec4 seed = pc.inData.a[idx];
    float phaseZ = pc.something * seed.z;
    float phaseW = pc.something * seed.w;
    vec2 moved = vec2(
    seed.x * cos(phaseZ) + seed.y * sin(phaseW),
    -seed.x * sin(phaseW) + seed.y * cos(phaseZ * 0.03)
    );
    pc.outData.a[idx] = vec4(moved, seed.zw);
    fieldProbe(length(moved - seed.xy));
}
```

```kotlin [field-property box="demo_particles" name="code" path="demo_particles_code_.kt"]
// A compute shader moves 100,000 particles; the probe plots how far each one moved.
var module = computeModuleHere {}
var m = ComputeMesh()
module += "step" to m
renderer += "compute" to module
val N = 100000
var inData = defaultBuffer { size = N.vec4s }.buffer
var outData = defaultBuffer { size = N.vec4s }.buffer
m.inData = { inData }
m.outData = { outData }
m.particleCount = { N }
m.something = { (AnimationClock.nowSeconds() * 0.6).f }
fieldProbeHere(samples = 100000).display("probe")
displayOpen("probe")
prop<FrameBufferGroup>(outputPositions) = outData
m.x = (N + 63) / 64
m.y = 1
m.z = 1
val seeded = kotlin.random.Random(0x4f1e1d)
inData.write {
    for (i in 0 until N) {
        put(vec(seeded.nextDouble(), seeded.nextDouble(), seeded.nextDouble(), seeded.nextDouble()))
    }
}
println("compute launched, N=" + N)
```

## 8. cue 3

<!-- field-box id="demo_cue_3" index="7" -->

### Narrative

From `code`

Runs while the time slider is over it.

```edn [field-box id="demo_cue_3" role="properties"]
{"id" "demo_cue_3" "index" 7 "name" "cue 3" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "cue 3" "_tombstone" false "frame" #afield/rect [4860.0 520.0 260.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "demo_cue_3_code_.kt" "lines" 4 "characters" 202 "sha256" "3d94128ffcaf4f14a89b73c355dfcc964cefc125edbb634c983dfebbd190b4f9"}]}
```

```kotlin [field-property box="demo_cue_3" name="code" path="demo_cue_3_code_.kt"]
// Runs while the time slider is over it.
display("main", "<div style='width:300px;height:26px;border-radius:4px;background:linear-gradient(90deg,#e8590c,#ffc078)'></div>")
displayOpen("main")
loop { }
```

## 9. cue 2

<!-- field-box id="demo_cue_2" index="8" -->

### Narrative

From `code`

Runs while the time slider is over it.

```edn [field-box id="demo_cue_2" role="properties"]
{"id" "demo_cue_2" "index" 8 "name" "cue 2" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "cue 2" "_tombstone" false "frame" #afield/rect [4640.0 300.0 260.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "demo_cue_2_code_.kt" "lines" 4 "characters" 202 "sha256" "ab2d2e2c3783b3557ac37cc6436a0dbb58704694da178dc1f18044fa624dd9aa"}]}
```

```kotlin [field-property box="demo_cue_2" name="code" path="demo_cue_2_code_.kt"]
// Runs while the time slider is over it.
display("main", "<div style='width:300px;height:26px;border-radius:4px;background:linear-gradient(90deg,#2f9e44,#8ce99a)'></div>")
displayOpen("main")
loop { }
```

## 10. swatch

<!-- field-box id="demo_swatch" index="9" -->

```edn [field-box id="demo_swatch" role="properties"]
{"id" "demo_swatch" "index" 9 "name" "swatch" "parents" ["root" "demo_palette"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "demo_palette"} "name" "swatch" "_tombstone" false "frame" #afield/rect [40.0 330.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "demo_swatch_code_.kt" "lines" 2 "characters" 91 "sha256" "1cea38c879b8d437e5d0ed0373efcbb1a7228b483d2d77d35970e140b3768591"}]}
```

```kotlin [field-property box="demo_swatch" name="code" path="demo_swatch_code_.kt"]
markdown("A flower with " + petals + " petals: " + "✿".repeat(petals))
displayOpen("main")
```

## 11. cue 4

<!-- field-box id="demo_cue_4" index="10" -->

### Narrative

From `code`

Runs while the time slider is over it.

```edn [field-box id="demo_cue_4" role="properties"]
{"id" "demo_cue_4" "index" 10 "name" "cue 4" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "cue 4" "_tombstone" false "frame" #afield/rect [5080.0 740.0 260.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "demo_cue_4_code_.kt" "lines" 4 "characters" 202 "sha256" "c5c74a74a0a0ef1a125eae4d5e917428c27df11d7426770b806f40363bed07e8"}]}
```

```kotlin [field-property box="demo_cue_4" name="code" path="demo_cue_4_code_.kt"]
// Runs while the time slider is over it.
display("main", "<div style='width:300px;height:26px;border-radius:4px;background:linear-gradient(90deg,#ae3ec9,#e599f7)'></div>")
displayOpen("main")
loop { }
```

## 12. t

<!-- field-box id="demo_timeline" index="11" -->

### Narrative

From `code`

A time slider: drag it sideways and it starts each box it enters and stops each box it leaves.

```edn [field-box id="demo_timeline" role="properties"]
{"id" "demo_timeline" "index" 11 "name" "t" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "t" "_tombstone" false "frame" #afield/rect [4300.0 -80.0 40.0 1400.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "demo_timeline_code_.kt" "lines" 45 "characters" 1519 "sha256" "761a34a2486bb74a215e61ec7ac3283449f59edd88be25b3a7ece5798da05f48"}]}
```

```kotlin [field-property box="demo_timeline" name="code" path="demo_timeline_code_.kt"]
// A time slider: drag it sideways and it starts each box it enters and stops each box it leaves.
rootProp<Box>(execution) = thisBox

onViewportChange +=
    "lock_x" to
        { inside, a, b ->
            frame = frame.copy(y = -b.y - 100, h = b.h * 2 - 50, w = 40.0)
        }

onFrameChange +=
    "lock_x" to
        { inside, a, b ->
            // The boxes under the line before the move, swept by it, and under it after.
            val all = root.children - thisBox
            val before = all.filter { it.frame.intersects(a.x) }
            val swept = all.filter { it.frame.intersects(a.x, b.x) }
            val after = all.filter { it.frame.intersects(b.x) }
            (after - before).forEach(Box::begin)
            (before - after).forEach(Box::end)
            // Crossed in one move: start it and stop it again.
            (swept - (before + after)).forEach {
                inMain {
                    it.begin()
                    yield()
                    it.end()
                }
            }
            b.copy(y = -inside.viewport.y - 100, w = 40.0)
        }

frame = frame.copy(w = 40.0)

customization =
    """
    this.style.border = "0px solid transparent"
    this.style.borderLeft = "3px solid red"
    this.style.background = "transparent"
    this.style.marginTop = "0px"
    this.style.paddingTop = "0px"
    this.style.setProperty("--box-border", "0")
    this.style.fontStyle = "italic"
    this.style.color = "white"
    this.lock_w = true
    this.lock_h = true
"""
```

## 13. notes

<!-- field-box id="demo_notes" index="12" -->

### Narrative

From `code`

Marks on the drawing layer (alt/option-D), owned by this box and redrawn when it reruns.

```edn [field-box id="demo_notes" role="properties"]
{"id" "demo_notes" "index" 12 "name" "notes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "notes" "_tombstone" false "frame" #afield/rect [1300.0 1500.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "demo_notes_code_.kt" "lines" 15 "characters" 837 "sha256" "5a3c3ed55995564fd14f77ede3d91526a7d36a4bc3c76c8dd064d99269350a30"}]}
```

```kotlin [field-property box="demo_notes" name="code" path="demo_notes_code_.kt"]
// Marks on the drawing layer (alt/option-D), owned by this box and redrawn when it reruns.
val byName = inside.boxes.values.associateBy { it.name }
notation {
    stroke = "#1971c2"
    ellipse(10, 10, 360, 160)
    text("one value, pushed down", 40, -30)
    arrow(from = byName.getValue("palette"), to = byName.getValue("gyroid"), label = "and beside it, 3D")
    stroke = "#e8590c"
    rect(2270, 10, 740, 700, label = "Vulkan, streamed into the page")
    arrow(from = byName.getValue("probed particles"), to = byName.getValue("streamed spiral"), label = "probed")
    stroke = "#2f9e44"
    rect(4380, 40, 1000, 860, label = "a timeline to scrub")
    stroke((0..80).map { k -> vec(1300 + k * 12.0, 1440 + 30 * sin(k / 5.0)) }, stroke = "#2f9e44")
}
markdown("marks", "" + notation.mine.size + " marks drawn on the drawing layer")
```

## 14. leaf

<!-- field-box id="demo_leaf" index="13" -->

```edn [field-box id="demo_leaf" role="properties"]
{"id" "demo_leaf" "index" 13 "name" "leaf" "parents" ["root" "demo_sketch"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "demo_sketch"} "name" "leaf" "_tombstone" false "frame" #afield/rect [20.0 20.0 200.0 60.0] "nestedIn" #afield/box "demo_sketch"} "blockProperties" [{"name" "code" "language" "kotlin" "path" "demo_leaf_code_.kt" "lines" 1 "characters" 57 "sha256" "a749219cb26aa3144acc44a95c1e30532bf8799b0bb6737c9776b48261e33651"}]}
```

```kotlin [field-property box="demo_leaf" name="code" path="demo_leaf_code_.kt"]
println("a nested leaf, " + petals + " petals above it")
```

## 15. brightness

<!-- field-box id="demo_brightness" index="14" -->

### Narrative

From `code`

A keyframe curve with its playhead running, and a meter it drives.

```edn [field-box id="demo_brightness" role="properties"]
{"id" "demo_brightness" "index" 14 "name" "brightness" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "brightness" "_tombstone" false "frame" #afield/rect [2300.0 860.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "demo_brightness_code_.kt" "lines" 13 "characters" 464 "sha256" "a0690cd3ecf1e6428adae1a1f4d716edd254d273318b8f9dd324a6633921eeb9"}]}
```

```kotlin [field-property box="demo_brightness" name="code" path="demo_brightness_code_.kt"]
// A keyframe curve with its playhead running, and a meter it drives.
val brightness = keyframes(name = "brightness", defaults = [vec(0.0, 0.1), vec(1.0, 0.9), vec(2.5, 0.4), vec(4.0, 0.1)])

lateinit var level: MeterHandle
controlGroup("level") { level = meter("brightness") }
displayOpen("level")

val start = System.nanoTime()
loop {
    val t = ((System.nanoTime() - start) / 1e9) % 4.0
    brightness.setTime(t)
    level.set(brightness.evaluateValueAt(t))
}
```

## 16. draw particles

<!-- field-box id="demo_draw" index="15" -->

### Narrative

From `code`

Draws the particles the compute box publishes as `outputPositions`, in the renderer's window.

```edn [field-box id="demo_draw" role="properties"]
{"id" "demo_draw" "index" 15 "name" "draw particles" "parents" ["root" "demo_particles"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "demo_particles"} "name" "draw particles" "_tombstone" false "frame" #afield/rect [3300.0 680.0 300.0 100.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "demo_draw_vertex_.glsl" "lines" 19 "characters" 647 "sha256" "2c8e11634cdf55e1666a96f3bd1411949e77074c7a19dbac24f48c5380aa07f1"} {"name" "fragment" "language" "glsl" "path" "demo_draw_fragment_.glsl" "lines" 10 "characters" 312 "sha256" "739c6e5b6ad0ac8fd506d6926fe7af6eac86d734807a1a8c121ca6d00585c25e"} {"name" "code" "language" "kotlin" "path" "demo_draw_code_.kt" "lines" 10 "characters" 370 "sha256" "ef5d245e8063e6542b4e4bd0d9311adffc8e7b87fed3cccfeb889912806b0848"}]}
```

```glsl [field-property box="demo_draw" name="vertex" path="demo_draw_vertex_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(std430, buffer_reference) buffer inData {
    vec4 pos[];
};
layout(location = 0) out vec2 tc;
layout(push_constant) uniform push {
    inData inData;
    float aspectRatio;
} pc;
const vec3[6] part = vec3[6](vec3(0,0,0),vec3(1,0,0),vec3(1,1,0),vec3(0,0,0),vec3(1,1,0),vec3(0,1,0));
void main() {
    float scale = 1.0 * pc.inData.pos[gl_VertexIndex/6].w;
    vec3 p = (part[gl_VertexIndex%6]-vec3(0.5,0.5,0))*scale;
    p.xy += pc.inData.pos[gl_VertexIndex/6].xy*10.0;
    p.xy *= vec2(1/pc.aspectRatio, 1);
    gl_Position = vec4(p*0.04, 1);
    tc = part[gl_VertexIndex%6].xy;
}
```

```glsl [field-property box="demo_draw" name="fragment" path="demo_draw_fragment_.glsl"]
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 0) in vec2 tc;
void main() {
    float d = max(0.0, 0.5-length(tc-vec2(0.5)));
    float w = fwidth(d);
    d = smoothstep(0.1, 0.1+w, d)*smoothstep(0.14+w, 0.14, d);
    uFragColor = vec4(1.0, 0.8, 0.4, d*0.2);
    if (d<0.01) discard;
}
```

```kotlin [field-property box="demo_draw" name="code" path="demo_draw_code_.kt"]
// Draws the particles the compute box publishes as `outputPositions`, in the renderer's window.
var module = moduleHere { primitiveType = triangles }
var m = Mesh()
module += "mesh" to m
renderer += "draw" to module
val N = 100000
m.inData = { outputPositions }
m.aspectRatio = { window.width / window.height.toFloat() }
m.set(N * 2 * 3, 0) {}
println("draw launched")
```

## 17. rosette

<!-- field-box id="demo_rosette" index="16" -->

### Narrative

From `code`

The same `petals`, drawn as FLines.

```edn [field-box id="demo_rosette" role="properties"]
{"id" "demo_rosette" "index" 16 "name" "rosette" "parents" ["root" "demo_palette"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "demo_palette"} "name" "rosette" "_tombstone" false "frame" #afield/rect [640.0 330.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "demo_rosette_code_.kt" "lines" 16 "characters" 557 "sha256" "ccbb092dad6edaed6d07dcbf4baf9dc9337689870ff15ea92ffeae62c1a84e28"}]}
```

```kotlin [field-property box="demo_rosette" name="code" path="demo_rosette_code_.kt"]
// The same `petals`, drawn as FLines.
val rings =
    (0 until petals).map { i ->
        val a = i * 2 * PI / petals
        fline {
            color = vec(0.35 + 0.6 * i / petals, 0.7, 1.0 - 0.5 * i / petals, 0.8)
            thickness = 1.5
            moveTo(vec(0.0, 0.0, 0.0))
            cubicTo(
                vec(120 * cos(a - 0.5), 120 * sin(a - 0.5), 0.0),
                vec(160 * cos(a + 0.5), 160 * sin(a + 0.5), 0.0),
                vec(0.0, 0.0, 0.0),
            )
        }
    }
lines(rings, w = 300.0, h = 300.0, fitToSize = true)
```

## 18. editable flower

<!-- field-box id="demo_flower" index="17" -->

### Narrative

From `code`

Click a point and drag it: the edit changes these FLines.

```edn [field-box id="demo_flower" role="properties"]
{"id" "demo_flower" "index" 17 "name" "editable flower" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "editable flower" "_tombstone" false "frame" #afield/rect [1300.0 760.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "demo_flower_code_.kt" "lines" 19 "characters" 529 "sha256" "85b3eb8cb6e807db4fd7913a3b2925ff5b8ce87c5fefa2ffe720566bc4aa2a29"}]}
```

```kotlin [field-property box="demo_flower" name="code" path="demo_flower_code_.kt"]
// Click a point and drag it: the edit changes these FLines.
val petal = fline {
    moveTo(0, -120)
    cubicTo(90, -100, 140, -20, 0, 120)
    cubicTo(-140, -20, -90, -100, 0, -120)
    close()
    filled = true
    stroked = true
    thickness = 1.0
    color = vec(0.95, 0.42, 0.6, 0.34)
}
val stem = fline {
    moveTo(0, 120)
    cubicTo(10, 180, -20, 230, 0, 300)
    thickness = 1.0
    color = vec(0.45, 0.95, 0.62, 1.0)
}
lines(petal, stem, w = 420.0, h = 420.0, fitToSize = true, editable = true)
displayOpen("lines")
```

## 19. streamed spiral

<!-- field-box id="demo_spiral" index="18" -->

```edn [field-box id="demo_spiral" role="properties"]
{"id" "demo_spiral" "index" 18 "name" "streamed spiral" "parents" ["root" "demo_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "demo_renderer"} "name" "streamed spiral" "_tombstone" false "frame" #afield/rect [2300.0 240.0 300.0 100.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "demo_spiral_vertex_.glsl" "lines" 11 "characters" 182 "sha256" "9d436fbe6ce920d07c12fe0a650bcc9b94bf95c322ada019712f8575ac389fa7"} {"name" "fragment" "language" "glsl" "path" "demo_spiral_fragment_.glsl" "lines" 16 "characters" 417 "sha256" "683d125eba81059cab8d2a0711eb55a928d359bce34e3fd05be19546485a276c"} {"name" "code" "language" "kotlin" "path" "demo_spiral_code_.kt" "lines" 21 "characters" 555 "sha256" "2f3672b816363c06bb50e8c265c81d038078cf353cc47475fc5bad58b5a70edc"}]}
```

```glsl [field-property box="demo_spiral" name="vertex" path="demo_spiral_vertex_.glsl"]
#version 450
layout (location = 0) in vec3 pos;
layout (location = 1) in vec2 tc;

layout (location = 1) out vec2 ftc;

void main()
{
    gl_Position = vec4(pos, 1);
    ftc = tc;
}
```

```glsl [field-property box="demo_spiral" name="fragment" path="demo_spiral_fragment_.glsl"]
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 1) in vec2 ftc;

layout(push_constant) uniform constants {
    float time;
} pc;

void main()
{
    vec2 p = ftc * 2.0 - 1.0;
    float r = length(p);
    float rings = 0.5 + 0.5 * sin(24.0 * r - 3.0 * pc.time + 5.0 * atan(p.y, p.x));
    vec3 col = mix(vec3(0.05, 0.10, 0.30), vec3(1.0, 0.65, 0.25), rings);
    uFragColor = vec4(col, 1.0);
}
```

```kotlin [field-property box="demo_spiral" name="code" path="demo_spiral_code_.kt"]
import afield.media.stream.ElectronRTTView

// Drawn by Vulkan, streamed into this box's output panel.
val view = ElectronRTTView(640, 360)
val started = AnimationClock.nowSeconds()

view += "content" to moduleHere {
    +view
    +Mesh("square") {
        set(4, 2) {
            pos.add(-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0)
            tc.add(0, 0, 1, 0, 1, 1, 0, 1)
            tri.add(0, 1, 2)
            tri.add(0, 2, 3)
        }
        time = { AnimationClock.nowSeconds() - started }
    }
}

display("main", view.html())
displayOpen("main")
```

## 20. metrics

<!-- field-box id="demo_metrics" index="19" -->

### Narrative

From `code`

Open this box's editor, then launch it: the three calls draw beside their own lines.

```edn [field-box id="demo_metrics" role="properties"]
{"id" "demo_metrics" "index" 19 "name" "metrics" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "metrics" "_tombstone" false "frame" #afield/rect [3300.0 860.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "demo_metrics_code_.kt" "lines" 19 "characters" 494 "sha256" "d78765a133c516f82d2350d0c93d1abc9f9ea43064d083b398efc062f0bd452d"}]}
```

```kotlin [field-property box="demo_metrics" name="code" path="demo_metrics_code_.kt"]
// Open this box's editor, then launch it: the three calls draw beside their own lines.
var tick = 0.0
var visualizerFrame = 0

println("inline metrics ready")

loop {
    tick += 0.4
    visualizerFrame += 1

    if (visualizerFrame % 2 == 0) {
        val value = sin(tick * 0.22) * 0.7 + cos(tick * 0.11) * 0.2 + tick * 0.01

        __m("histogram").histogram(value, dur = 120)
        __m("trail").history(value, dur = 48)
        __m("summary").stats(value, dur = 48)
    }
    frame()
}
```

