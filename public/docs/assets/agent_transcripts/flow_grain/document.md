# Field Document: flow_grain

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 15 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1. Renderer — launch / stop

<!-- field-box id="flow_renderer" index="0" -->

```edn [field-box id="flow_renderer" role="properties"]
{"id" "flow_renderer" "index" 0 "name" "1. Renderer — launch / stop" "parents" ["root"] "children" ["flow_resources"] "properties" {"children" #{#afield/box "flow_resources"} "parents" #{#afield/box "root"} "name" "1. Renderer — launch / stop" "_tombstone" false "frame" #afield/rect [80.0 80.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_renderer_code_.kt" "lines" 11 "characters" 360 "sha256" "1f395aa1aca9c7a033ae80d758c5b920291cd385750233ddafba72e96c4c9c59"}]}
```

```kotlin [field-property box="flow_renderer" name="code" path="flow_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false // GPU timings remain in Field's existing renderer profiler.
once.renderer {
    window = Window("Flow grain playground", initialDimensions = Rect(80.0, 80.0, 960.0, 720.0))
    Renderer(window)
}
start(renderer::run)
```

## 2. 2. Resident tiles and resources

<!-- field-box id="flow_resources" index="1" -->

```edn [field-box id="flow_resources" role="properties"]
{"id" "flow_resources" "index" 1 "name" "2. Resident tiles and resources" "parents" ["root" "flow_renderer"] "children" ["flow_inputs" "flow_helpers" "flow_advect" "flow_divergence" "flow_pressure" "flow_project" "flow_particles" "flow_grain" "flow_resolve" "flow_strokes" "flow_controls" "flow_reset" "flow_profile"] "properties" {"children" #{#afield/box "flow_inputs" #afield/box "flow_helpers" #afield/box "flow_advect" #afield/box "flow_divergence" #afield/box "flow_pressure" #afield/box "flow_project" #afield/box "flow_particles" #afield/box "flow_grain" #afield/box "flow_resolve" #afield/box "flow_strokes" #afield/box "flow_controls" #afield/box "flow_reset" #afield/box "flow_profile"} "parents" #{#afield/box "root" #afield/box "flow_renderer"} "name" "2. Resident tiles and resources" "_tombstone" false "frame" #afield/rect [80.0 230.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_resources_code_.kt" "lines" 97 "characters" 3363 "sha256" "2b0d5cee5405b22a5c7cdddab25fc6ac98373160ed2b2f3000b575da01688cd5"}]}
```

```kotlin [field-property box="flow_resources" name="code" path="flow_resources_code_.kt"]
import afield.simulation.flow.*

fun grid() = defaultBuffer {
    size = 14400.vec4s
    zeroInit()
}
    .buffer
propDown<FrameBufferGroup>(flowA)
flowA = grid()
propDown<FrameBufferGroup>(flowB)
flowB = grid()
propDown<FrameBufferGroup>(advected)
advected = grid()
propDown<FrameBufferGroup>(divergence)
divergence = grid()
propDown<FrameBufferGroup>(pressureA)
pressureA = grid()
propDown<FrameBufferGroup>(pressureB)
pressureB = grid()
propDown<FrameBufferGroup>(particleA)
particleA = defaultBuffer {
    size = 32000.vec4s
    zeroInit()
}
        .buffer
propDown<FrameBufferGroup>(particleB)
particleB = defaultBuffer {
    size = 32000.vec4s
    zeroInit()
}
        .buffer
propDown<FrameBufferGroup>(tileHeaders)
tileHeaders = defaultBuffer {
    perFrame = true
    size = 25.vec4s
}
        .buffer
propDown<FrameBufferGroup>(cameraHistory)
cameraHistory = defaultBuffer {
    perFrame = true
    size = 17.vec4s
}
        .buffer
propDown<FrameBufferGroup>(itemHistory)
itemHistory = defaultBuffer {
    perFrame = true
    size = 3.vec4s
}
        .buffer
propDown<SimulationReset>(resetFlow)
resetFlow = SimulationReset()
propDown<EffectiveParameter>(force)
force = EffectiveParameter("Force", 2.5, 0.0, 12.0, "world/s²", "Vortex impulse")
propDown<EffectiveParameter>(grainCell)
grainCell = EffectiveParameter("Grain cell", .018, .006, .12, "world", "Fixed world noise scale")
propDown<EffectiveParameter>(strokeTime)
strokeTime = EffectiveParameter("Stroke time", .15, .01, .6, "s", "Noise integration interval")
propDown<afield.math.linalg.Vec4>(flowLook)
flowLook = vec(.018, .15, .45, 0) // Grain cell, integration seconds, gain, debug view.
propDown<afield.math.linalg.Vec4>(flowClock)
flowClock = vec(0, 1.0 / 60, 0, 0) // Time, fixed step, reset generation, reserved.
propDown<afield.math.linalg.Vec4>(flowEmitter)
flowEmitter = vec(0, 0, 2.5, 0) // World position and force strength.
propDown<ComputeTarget>(flowSimulation)
flowSimulation = ComputeTarget(renderer)
propDown<java.util.concurrent.atomic.AtomicLong>(flowStep)
flowStep = java.util.concurrent.atomic.AtomicLong()
propDown<java.util.concurrent.atomic.AtomicBoolean>(travel)
travel = java.util.concurrent.atomic.AtomicBoolean(false) // Move across tile boundaries and exercise zoom history.
propDown<EffectiveParameter>(flowWidth)
flowWidth =
    EffectiveParameter("View half-height", 8.0, 3.0, 10.0, "world") // Visible vertical half-height in world units.

// A color surface and a correspondence attachment. The latter stores previous UV,
// validity, and coverage; it is a sampling coordinate, not a velocity in pixels/s.
propDown<RenderToTexture>(flowSurface)
flowSurface = RenderToTexture {
    width = 960
    height = 720
    colorAttachments = 2
    colorFormats =
        [org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT, org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT]
    useDepth = false
    clearFirst = true
    clearColor = vec(0, 0, 0, 0)
    debugName = "flow-correspondence"
}
val simulation = flowSimulation
val surface = flowSurface
val targets = renderer.renderList.preTargets
targets["flow-simulation"] = simulation
targets["flow-surface"] = surface
loop {}
    .end {
        if (targets["flow-simulation"] === simulation) targets.remove("flow-simulation")
        if (targets["flow-surface"] === surface) targets.remove("flow-surface")
    }
```

## 3. 5. Divergence

<!-- field-box id="flow_divergence" index="2" -->

### Narrative

From `code`

Dependencies are explicit graph exports; resources stay in their parent box.

```edn [field-box id="flow_divergence" role="properties"]
{"id" "flow_divergence" "index" 2 "name" "5. Divergence" "parents" ["root" "flow_resources"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flow_resources"} "name" "5. Divergence" "_tombstone" false "frame" #afield/rect [80.0 830.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_divergence_code_.kt" "lines" 20 "characters" 629 "sha256" "3f7ff658a34ebdf6c4326efc2294ef35ea979b565d7e2bed80acdea3f7bb5aaa"} {"name" "compute" "language" "glsl" "path" "flow_divergence_compute_.glsl" "lines" 12 "characters" 475 "sha256" "81418045a7bc1e9f960a4a7654dc74fc293df9bd260bf29eb3e33750e9a89f54"}]}
```

```kotlin [field-property box="flow_divergence" name="code" path="flow_divergence_code_.kt"]
// Dependencies are explicit graph exports; resources stay in their parent box.

var module = computeModuleHere {}

val mesh = ComputeMesh()
module += "dispatch" to mesh
mesh.x = 225 // 14400 cells / 64 threads.
mesh.y = 1
mesh.source = { advected }
mesh.target = { divergence }
mesh.auxiliary = { pressureA }
mesh.tiles = { tileHeaders }
mesh.clock = { flowClock }
mesh.emitter = { flowEmitter }
module += "barrier" to computeBarrier(graphics = true)
flowSimulation += "divergence" to module
loop { true }
    .end {
        if (flowSimulation.modules["divergence"] === module) flowSimulation.modules.remove("divergence")
    }
```

```glsl [field-property box="flow_divergence" name="compute" path="flow_divergence_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
#include "flow_helpers.compute"
layout(local_size_x = 64) in;
void main() {
    int i = int(gl_GlobalInvocationID.x);
    if (i >= CELLS) return;
    ivec2 c = worldCell(i);
    float divergence = (readCell(pc.source, c + ivec2(1,0)).x - readCell(pc.source, c - ivec2(1,0)).x
    + readCell(pc.source, c + ivec2(0,1)).y - readCell(pc.source, c - ivec2(0,1)).y) / (2. * H);
    storeCell(i, vec3(divergence, 0, 0));
}
```

## 4. 7. Project

<!-- field-box id="flow_project" index="3" -->

### Narrative

From `code`

Dependencies are explicit graph exports; resources stay in their parent box.

```edn [field-box id="flow_project" role="properties"]
{"id" "flow_project" "index" 3 "name" "7. Project" "parents" ["root" "flow_resources"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flow_resources"} "name" "7. Project" "_tombstone" false "frame" #afield/rect [510.0 230.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_project_code_.kt" "lines" 20 "characters" 657 "sha256" "c45b45e1d46849167ede1f24e651814314cb0f03ba44660608f86b35171a5245"} {"name" "compute" "language" "glsl" "path" "flow_project_compute_.glsl" "lines" 19 "characters" 1016 "sha256" "60776a6ac32761d04c4f861ea50f2a83901b6f337fb28c231e73365a40cec89d"}]}
```

```kotlin [field-property box="flow_project" name="code" path="flow_project_code_.kt"]
// Dependencies are explicit graph exports; resources stay in their parent box.

var module = computeModuleHere {}

val mesh = ComputeMesh()
module += "dispatch" to mesh
mesh.x = 225 // 14400 cells / 64 threads.
mesh.y = 1
mesh.source = { advected }
mesh.target = { if (flowStep.get() % 2L == 0L) flowB else flowA }
mesh.auxiliary = { pressureA }
mesh.tiles = { tileHeaders }
mesh.clock = { flowClock }
mesh.emitter = { flowEmitter }
module += "barrier" to computeBarrier(graphics = true)
flowSimulation += "project" to module
loop { true }
    .end {
        if (flowSimulation.modules["project"] === module) flowSimulation.modules.remove("project")
    }
```

```glsl [field-property box="flow_project" name="compute" path="flow_project_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
#include "flow_helpers.compute"
layout(local_size_x = 64) in;
void main() {
    int i = int(gl_GlobalInvocationID.x);
    if (i >= CELLS) return;
    ivec2 c = worldCell(i);
    vec2 gradient = vec2(readCell(pc.auxiliary, c + ivec2(1,0)).x - readCell(pc.auxiliary, c - ivec2(1,0)).x,
    readCell(pc.auxiliary, c + ivec2(0,1)).x - readCell(pc.auxiliary, c - ivec2(0,1)).x) / (2. * H);
    vec2 velocity = readCell(pc.source, c).xy - gradient;
    if (rock(worldPosition(i))) velocity = vec2(0);
    // Enforce no-through-flow against the grid stencil. Drawing uses the exact mask below.
    if (rock((vec2(c + ivec2(1,0)) + .5) * H) && velocity.x > 0.) velocity.x = 0.;
    if (rock((vec2(c - ivec2(1,0)) + .5) * H) && velocity.x < 0.) velocity.x = 0.;
    if (rock((vec2(c + ivec2(0,1)) + .5) * H) && velocity.y > 0.) velocity.y = 0.;
    if (rock((vec2(c - ivec2(0,1)) + .5) * H) && velocity.y < 0.) velocity.y = 0.;
    storeCell(i, vec3(velocity, 0));
}
```

## 5. Reset — execute

<!-- field-box id="flow_reset" index="4" -->

### Narrative

From `code`

Dependencies are explicit graph exports; resources stay in their parent box.

Ordinary execution queues a reset; the existing renderer consumes it once at its boundary.

```edn [field-box id="flow_reset" role="properties"]
{"id" "flow_reset" "index" 4 "name" "Reset — execute" "parents" ["root" "flow_resources"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flow_resources"} "name" "Reset — execute" "_tombstone" false "frame" #afield/rect [940.0 230.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_reset_code_.kt" "lines" 4 "characters" 226 "sha256" "04fb96ef5ab4d53754f3ac6e729a541f176292ff2e7cf57d9f31ea96640d0811"}]}
```

```kotlin [field-property box="flow_reset" name="code" path="flow_reset_code_.kt"]
// Dependencies are explicit graph exports; resources stay in their parent box.

// Ordinary execution queues a reset; the existing renderer consumes it once at its boundary.
println("Reset requested: ${resetFlow.request()}")
```

## 6. 3. Frame inputs and reset boundary

<!-- field-box id="flow_inputs" index="5" -->

```edn [field-box id="flow_inputs" role="properties"]
{"id" "flow_inputs" "index" 5 "name" "3. Frame inputs and reset boundary" "parents" ["root" "flow_resources"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flow_resources"} "name" "3. Frame inputs and reset boundary" "_tombstone" false "frame" #afield/rect [80.0 380.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_inputs_code_.kt" "lines" 100 "characters" 3802 "sha256" "b30edcee754e349ee6843b409451c3da31e3d2dce06fe6feef36c8cc83c39f83"}]}
```

```kotlin [field-property box="flow_inputs" name="code" path="flow_inputs_code_.kt"]
import afield.simulation.flow.TileAtlasLayout

// Dependencies are explicit graph exports; resources stay in their parent box.

val pool = TileResidency(5, 5, 4f)
val layout = TileAtlasLayout(pool, 24)
val camera = FrameHistory()
val items = MotionItems()
val pose = MotionPose()
val itemData = FloatArray(12)
val matrix = afield.math.linalg.Matrix4f()
val key = "flow-frame-inputs"
var ticks = 0L
var resetEpoch = 1
var lastRendered = Long.MIN_VALUE
var report = 0L

// This hook runs once per rendered frame, before compute/draw. No timer or
// swapchain-image index advances the simulation or camera history.
frames {
    val budget = frameProfile("Flow inputs", targetFps = 60.0, allocationKiB = 128.0)
    val upload: (Renderer.GeometryPhaseScope) -> Unit = {
        val serial = renderer.frameCounter
        if (serial != lastRendered) {
            lastRendered = serial
            budget.beginFrame()
            budget.measure("Reset and residency") {
                resetFlow.atFrame(serial) {
                    ticks = 0L
                    resetEpoch++
                    pool.invalidate()
                    camera.invalidate()
                    items.clear()
                }
                val time = ticks / 60.0
                val pan = if (travel.get()) sin(time * .12) * 28.0 else 0.0
                pool.moveTo(pan, 0.0)
                tileHeaders.mapAndWrite_ints { put(layout.updateHeaders()) }
                val height = flowWidth.effective * if (travel.get()) 1.0 + .18 * sin(time * .25) else 1.0
                val width = height * window.width / window.height.toDouble()
                matrix.set(
                    (1.0 / width).toFloat(),
                    0f,
                    0f,
                    0f,
                    0f,
                    (1.0 / height).toFloat(),
                    0f,
                    0f,
                    0f,
                    0f,
                    -1f,
                    0f,
                    (-pan / width).toFloat(),
                    0f,
                    0f,
                    1f,
                )
                camera.capture(serial, matrix, 960, 720, resetFlow.epoch)
                cameraHistory.mapAndWrite { put(camera.packed) }
                flowClock.set(time.toFloat(), 1f / 60, resetEpoch.toFloat(), 0f)
                flowEmitter.set(
                    (pan + cos(time * .7) * 2).toFloat(),
                    sin(time * .9).toFloat(),
                    force.effective.toFloat(),
                    0f,
                )
                flowLook.x = grainCell.effective.toFloat()
                flowLook.y = strokeTime.effective.toFloat()
            }
            budget.measure("Object correspondence") {
                pose.x = flowEmitter.x
                pose.y = flowEmitter.y
                pose.angle = flowClock.x
                items.begin(serial)
                itemData[0] = pose.x
                itemData[1] = pose.y
                itemData[2] = pose.angle
                itemData[3] = 1f
                items.write("emitter", pose, itemData, 4, camera.available)
                itemHistory.mapAndWrite { put(itemData) }
            }
            flowStep.set(ticks++)
        }
    }
    renderer.geometryProducers[key] = upload
    try {
        while (frame()) {
            if (renderer.frameCounter - report >= 120) {
                report = renderer.frameCounter
                println(
                    "render=$report step=$ticks tiles=${pool.capacity} relocations=${pool.relocations} reset=${resetFlow.completed} history=${camera.available}",
                )
            }
        }
    } finally {
        if (renderer.geometryProducers[key] === upload) renderer.geometryProducers.remove(key)
        budget.close()
    }
}
```

## 7. Parameters — edit and execute

<!-- field-box id="flow_controls" index="6" -->

### Narrative

From `code`

Dependencies are explicit graph exports; resources stay in their parent box.

Edit and execute this box. The shader receives the effective values; all values
and bounds remain inspectable through Field's ordinary object viewer.

```edn [field-box id="flow_controls" role="properties"]
{"id" "flow_controls" "index" 6 "name" "Parameters — edit and execute" "parents" ["root" "flow_resources"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flow_resources"} "name" "Parameters — edit and execute" "_tombstone" false "frame" #afield/rect [940.0 80.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_controls_code_.kt" "lines" 11 "characters" 805 "sha256" "f403bb9cba7540536936a55cdd4e62cbd5517023c7fe8ff5e120137921cb3b32"}]}
```

```kotlin [field-property box="flow_controls" name="code" path="flow_controls_code_.kt"]
// Dependencies are explicit graph exports; resources stay in their parent box.

// Edit and execute this box. The shader receives the effective values; all values
// and bounds remain inspectable through Field's ordinary object viewer.
force.requested = 2.5 // Vortex acceleration, clamped to 0–12 world units / second².
grainCell.requested = .018 // World-space cell size, clamped to .006–.12.
strokeTime.requested = .15 // Integrated path length in seconds, clamped to .01–.6.
travel.set(false) // Scroll across the fixed tile pool and vary zoom.
flowWidth.requested = 8.0 // Visible vertical half-height in world units.
flowLook.w = 0f // 0 grain; 1 fluid velocity; 2 camera UV; 3 object+camera UV; 4 displacement.
[force.snapshot(), grainCell.snapshot(), strokeTime.snapshot(), flowWidth.snapshot()]
```

## 8. 9. Grain and correspondence RTT

<!-- field-box id="flow_grain" index="7" -->

### Narrative

From `code`

Dependencies are explicit graph exports; resources stay in their parent box.

```edn [field-box id="flow_grain" role="properties"]
{"id" "flow_grain" "index" 7 "name" "9. Grain and correspondence RTT" "parents" ["root" "flow_resources"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flow_resources"} "name" "9. Grain and correspondence RTT" "_tombstone" false "frame" #afield/rect [510.0 530.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_grain_code_.kt" "lines" 20 "characters" 710 "sha256" "054f91f5eb896a4b590d387b22d6798544c23a222268f62b5a93ffef10df07a1"} {"name" "vertex" "language" "glsl" "path" "flow_grain_vertex_.glsl" "lines" 7 "characters" 202 "sha256" "093ff9c35c7d0656fa9929963897d03e432d20bb26c591aafde0c25275d29caa"} {"name" "fragment" "language" "glsl" "path" "flow_grain_fragment_.glsl" "lines" 85 "characters" 3650 "sha256" "cb2b0663e2e4aac4903cbd18808c21a2a2196c4b4b9b67515c361da81a293550"}]}
```

```kotlin [field-property box="flow_grain" name="code" path="flow_grain_code_.kt"]
// Dependencies are explicit graph exports; resources stay in their parent box.

var module = moduleHere {
    flowSurface.apply()
    primitiveType = triangles
    blend = false // Correspondence is a coordinate, never alpha-blended.
    depthTest = false
    depthWrite = false
}
val mesh = Mesh()
module += "grain surface" to mesh
mesh.velocity = { if (flowStep.get() % 2L == 0L) flowB else flowA }
mesh.tiles = { tileHeaders }
mesh.history = { cameraHistory }
mesh.clock = { flowClock }
mesh.look = { flowLook }
mesh.item = { itemHistory }
mesh.resize(3)
flowSurface += "flow-grain" to module
loop { true }.end { if (flowSurface.modules["flow-grain"] === module) flowSurface.modules.remove("flow-grain") }
```

```glsl [field-property box="flow_grain" name="vertex" path="flow_grain_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 uv;
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(p * 2. - 1., 0, 1);
    uv = vec2(p.x, 1. - p.y);
}
```

```glsl [field-property box="flow_grain" name="fragment" path="flow_grain_fragment_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
#include "flow/grain.glsl"
#include "flow/motion.glsl"
#include "flow/tiles.glsl"
layout(std430, buffer_reference) readonly buffer Field {
    vec4 v[];
};
layout(std430, buffer_reference) readonly buffer Tiles {
    ivec4 v[];
};
layout(std430, buffer_reference) readonly buffer History {
    FlowFrameHistory frame;
};
layout(push_constant) uniform Push {
    Field item;
    // Current pose and MotionItems previous affine/validity.
    Field velocity;
    // Current projected fluid velocity.
    Tiles tiles;
    // Resident world coordinates and generations.
    History history;
    // FrameHistory's complete 68-float layout.
    vec4 clock;
    // x time; y dt; zw reserved.
    vec4 look;
    // x world grain cell; y stroke seconds; z gain; w debug view.
} pc;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 color;
layout(location = 1) out vec4 motion;
// Previous UV, valid, coverage. No blending on this attachment.
const float H = 4. / 24.;
bool rock(vec2 p) {
    return (abs(p.y + 2.) < .10 && abs(p.x) < 4.) || length(p - vec2(2.4, 1.2)) < .8;
}
float flowMask(vec2 p) {
    return rock(p) ? 0. : 1.;
}
vec2 atCell(ivec2 cell) {
    ivec2 local = ivec2(flowFloorMod(cell.x,24),flowFloorMod(cell.y,24));
    ivec2 tile = (cell - local) / 24;
    int slot = flowTileSlot(tile,ivec2(5));
    if (!flowTileResident(pc.tiles.v[slot], tile)) return vec2(0);
    vec4 v = pc.velocity.v[slot * 576 + local.y * 24 + local.x];
    return floatBitsToInt(v.w) == pc.tiles.v[slot].z ? v.xy : vec2(0);
}
vec2 flowVelocity(vec2 p) {
    vec2 grid = p / H - .5, f = fract(grid);
    ivec2 c = ivec2(floor(grid));
    // The same cell-centred interpolation used by the simulation pass.
    vec2 lower = mix(atCell(c), atCell(c + ivec2(1, 0)), f.x);
    vec2 upper = mix(atCell(c + ivec2(0, 1)), atCell(c + ivec2(1)), f.x);
    return mix(lower, upper, f.y);
}
void main() {
    vec2 world = flowUVToWorld(uv, pc.history.frame.inverseCurrent);
    vec4 clip = pc.history.frame.current * vec4(world,0,1);
    vec4 previousClip = pc.history.frame.reprojection * clip;
    motion = vec4(flowClipToUV(previousClip), pc.history.frame.viewport.z, 1);
    float pixelWorld = length(pc.history.frame.inverseCurrent[0].xy) * 2. / pc.history.frame.viewport.x;
    float grain = flowGrain(world, vec2(0), pixelWorld, pc.look.x, pc.look.y, .9, pc.clock.x, 1.6);
    // Light is evaluated after the noise integration, preserving a sharp geometric shadow.
    vec2 light = vec2(-3., 3.);
    vec2 ray = world - light;
    float illumination = 1. / (1. + dot(ray,ray) * .035);
    for (int i=1; i<=48; ++i) if (rock(light + ray * (float(i) / 48.))) illumination = .02;
    float ink = smoothstep(.08,.32,grain) * illumination * pc.look.z;
    color = vec4(vec3(ink),1);
    if (rock(world)) color.rgb = vec3(0);
    // The moving, rotating emitter has object motion in addition to the camera.
    vec4 pose = pc.item.v[0];
    float c = cos(pose.z), s = sin(pose.z);
    vec2 local = mat2(c,-s,s,c) * (world-pose.xy);
    if (abs(local.x)<.45 && abs(local.y)<.13) {
        color.rgb = vec3(.9);
        vec2 before = flowPreviousPosition(world,pose.xy,pc.item.v[1]);
        motion = vec4(flowClipToUV(pc.history.frame.previous*vec4(before,0,1)),pc.item.v[2].x,1);
    }
    if (pc.look.w > .5 && pc.look.w < 1.5) color.rgb = vec3(abs(flowVelocity(world)) * .2,0);
    if (pc.look.w > 1.5) {
        vec4 prior = pc.history.frame.reprojection * vec4(uv * vec2(2,-2) + vec2(-1,1),0,1);
        color.rgb = pc.history.frame.viewport.z > .5 ? vec3(flowClipToUV(prior),0) : vec3(1,0,0);
    }
}
```

## 9. 6. Pressure — 24 iterations

<!-- field-box id="flow_pressure" index="8" -->

### Narrative

From `code`

Dependencies are explicit graph exports; resources stay in their parent box.

```edn [field-box id="flow_pressure" role="properties"]
{"id" "flow_pressure" "index" 8 "name" "6. Pressure — 24 iterations" "parents" ["root" "flow_resources"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flow_resources"} "name" "6. Pressure — 24 iterations" "_tombstone" false "frame" #afield/rect [510.0 80.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_pressure_code_.kt" "lines" 23 "characters" 800 "sha256" "25af357d04343d346f71538fd7140b43308ffa90910e311954b1b0f7063f3bb0"} {"name" "compute" "language" "glsl" "path" "flow_pressure_compute_.glsl" "lines" 13 "characters" 529 "sha256" "04201d2215376ac0ab4d723f5bfbd7b56b2845044c688aa11cd055d6e7efb2d2"}]}
```

```kotlin [field-property box="flow_pressure" name="code" path="flow_pressure_code_.kt"]
// Dependencies are explicit graph exports; resources stay in their parent box.

var module = computeModuleHere {}

// The same editable shader performs each iteration. Every dependency has a barrier.
for (i in 0 until 24) {
    val mesh = ComputeMesh()
    module += "relax-$i" to mesh
    mesh.x = 225
    mesh.y = 1
    mesh.source = { if (i % 2 == 0) pressureA else pressureB }
    mesh.target = { if (i % 2 == 0) pressureB else pressureA }
    mesh.auxiliary = { divergence }
    mesh.tiles = { tileHeaders }
    mesh.clock = { flowClock }
    mesh.emitter = { flowEmitter }
    module += "barrier-$i" to computeBarrier()
}
flowSimulation += "pressure" to module
loop { true }
    .end {
        if (flowSimulation.modules["pressure"] === module) flowSimulation.modules.remove("pressure")
    }
```

```glsl [field-property box="flow_pressure" name="compute" path="flow_pressure_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
#include "flow_helpers.compute"
layout(local_size_x = 64) in;
void main() {
    int i = int(gl_GlobalInvocationID.x);
    if (i >= CELLS) return;
    ivec2 c = worldCell(i);
    float sum = readCell(pc.source, c + ivec2(1,0)).x + readCell(pc.source, c - ivec2(1,0)).x
    + readCell(pc.source, c + ivec2(0,1)).x + readCell(pc.source, c - ivec2(0,1)).x;
    float divergence = readCell(pc.auxiliary, c).x;
    storeCell(i, vec3((sum - divergence * H * H) * .25, 0, 0));
}
```

## 10. 8. Advect finite grains

<!-- field-box id="flow_particles" index="9" -->

### Narrative

From `code`

Dependencies are explicit graph exports; resources stay in their parent box.

```edn [field-box id="flow_particles" role="properties"]
{"id" "flow_particles" "index" 9 "name" "8. Advect finite grains" "parents" ["root" "flow_resources"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flow_resources"} "name" "8. Advect finite grains" "_tombstone" false "frame" #afield/rect [510.0 380.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_particles_code_.kt" "lines" 20 "characters" 735 "sha256" "b6085b92123a77a337ccf2b6aba8bc646eec773db905d9eeca9e6ac32fee5fd8"} {"name" "compute" "language" "glsl" "path" "flow_particles_compute_.glsl" "lines" 39 "characters" 1504 "sha256" "a6a38ba15fa515003f8c7c56d64e62d3f7966fdfffa5dae0c793095c3d37f1d0"}]}
```

```kotlin [field-property box="flow_particles" name="code" path="flow_particles_code_.kt"]
// Dependencies are explicit graph exports; resources stay in their parent box.

var module = computeModuleHere {}

val mesh = ComputeMesh()
module += "advect particles" to mesh
mesh.x = 250
mesh.y = 1
mesh.source = { if (flowStep.get() % 2L == 0L) particleA else particleB }
mesh.target = { if (flowStep.get() % 2L == 0L) particleB else particleA }
mesh.auxiliary = { if (flowStep.get() % 2L == 0L) flowB else flowA }
mesh.tiles = { tileHeaders }
mesh.clock = { flowClock }
mesh.emitter = { flowEmitter }
module += "barrier" to computeBarrier(graphics = true)
flowSimulation += "particles" to module
loop { true }
    .end {
        if (flowSimulation.modules["particles"] === module) flowSimulation.modules.remove("particles")
    }
```

```glsl [field-property box="flow_particles" name="compute" path="flow_particles_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
#include "flow_helpers.compute"
#include "flow/strokes.glsl"
#include "flow/grain.glsl"
layout(local_size_x = 64) in;
vec2 flowVelocity(vec2 p) {
    return sampleField(pc.auxiliary, p).xy;
}
float flowMask(vec2 p) {
    return rock(p) ? 0. : 1.;
}
void main() {
    int id = int(gl_GlobalInvocationID.x);
    if (id >= 16000) return;
    int slot = id / 640;
    vec4 old = pc.source.v[id * 2];
    vec4 smoothVelocity = pc.source.v[id * 2 + 1];
    int generation = pc.tiles.v[slot].z;
    vec2 lower = vec2(pc.tiles.v[slot].xy) * TILE;
    vec2 p = old.xy;
    bool staleGeneration = floatBitsToInt(old.w) != generation;
    bool outsideTile = any(lessThan(p, lower)) || any(greaterThanEqual(p, lower + TILE));
    // Relocation invalidates both the old position and its smoothed velocity.
    if (staleGeneration || outsideTile) {
        vec2 randomInTile = vec2(
        flowRandom(ivec2(id % 640, pc.tiles.v[slot].x), pc.tiles.v[slot].y),
        flowRandom(ivec2(id % 640 + 9923, pc.tiles.v[slot].x), pc.tiles.v[slot].y)
        );
        p = lower + randomInTile * TILE;
        smoothVelocity = vec4(0);
    }
    vec2 velocity = flowVelocity(p) + vec2(0,-.18);
    vec2 next = p + velocity * pc.clock.y;
    if (!rock(next)) p = next;
    else velocity *= 0.;
    pc.target.v[id * 2] = vec4(p, 0, intBitsToFloat(generation));
    pc.target.v[id * 2 + 1] = vec4(flowSmoothVelocity(smoothVelocity.xy, velocity, pc.clock.y, .12),0,0);
}
```

## 11. Profile — 180 rendered frames

<!-- field-box id="flow_profile" index="10" -->

### Narrative

From `code`

Dependencies are explicit graph exports; resources stay in their parent box.

```edn [field-box id="flow_profile" role="properties"]
{"id" "flow_profile" "index" 10 "name" "Profile — 180 rendered frames" "parents" ["root" "flow_resources"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flow_resources"} "name" "Profile — 180 rendered frames" "_tombstone" false "frame" #afield/rect [940.0 380.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_profile_code_.kt" "lines" 50 "characters" 2623 "sha256" "adffe7cd26a32a6fd66d23cbf9ecad7140a49d95114aeb10c4d8e00447998e59"}]}
```

```kotlin [field-property box="flow_profile" name="code" path="flow_profile_code_.kt"]
// Dependencies are explicit graph exports; resources stay in their parent box.
val resetFlow = requireNotNull(fromContextWithNameOrNull<afield.runtime.lifecycle.SimulationReset>("resetFlow")) {
    "Missing resetFlow; run Resources first"
}
val travel = requireNotNull(fromContextWithNameOrNull<java.util.concurrent.atomic.AtomicBoolean>("travel")) {
    "Missing travel; run Resources first"
}
val settings =
    ["force", "grainCell", "strokeTime", "flowWidth"].map { parameterName ->
        requireNotNull(fromContextWithNameOrNull<afield.ui.controls.EffectiveParameter>(parameterName)) {
                "Run Resources first: missing parameter $parameterName"
            }
            .snapshot()
    }

// Compare a deterministic 180-frame path with identical settings after a reset.
// Stop Box also ends this capture and restores the previous travel setting.
frames {
    val wasTravelling = travel.getAndSet(true)
    var recording = false
    try {
        val ticket = resetFlow.request()
        val resetStarted = System.nanoTime()
        val resetTimeoutNanos = 30_000_000_000L
        while (resetFlow.completed < ticket) {
            // Ignore a prior failure while this new request is still pending.
            if (!resetFlow.pending) check(resetFlow.failure == null) { "Reset failed: ${resetFlow.failure}" }
            check(System.nanoTime() - resetStarted < resetTimeoutNanos) {
                "Timed out waiting for reset $ticket: ${resetFlow.failure ?: "no frame-boundary consumer"}"
            }
            if (!frame()) return@frames
        }
        println("Profile settings: $settings; reset ticket=${resetFlow.completed}")
        val start = renderer.frameCounter
        while (frame() && renderer.frameCounter - start < 60) {} // Warm shaders and caches.
        CoroutineProfiles.request(thisBox.inside.filename, CoroutineProfileRequest("start", 30.0, stacks = true))
        recording = true
        val recordingStart = renderer.frameCounter
        while (frame() && renderer.frameCounter - recordingStart < 180) {}
        val result = CoroutineProfiles.request(thisBox.inside.filename, CoroutineProfileRequest("stop"))
        recording = false
        thisBox.inBrowser("window.showProfilingTimeline(); document.getElementById('profile-tab-frames').click()")
        println(
            "Flow profile: ${result.frameRecords.size} intervals; ${renderer.frameCounter-recordingStart} rendered frames",
        )
    } finally {
        if (recording) CoroutineProfiles.request(thisBox.inside.filename, CoroutineProfileRequest("stop"))
        travel.set(wasTravelling)
    }
}
```

## 12. 4. Advect and inject

<!-- field-box id="flow_advect" index="11" -->

### Narrative

From `code`

Dependencies are explicit graph exports; resources stay in their parent box.

```edn [field-box id="flow_advect" role="properties"]
{"id" "flow_advect" "index" 11 "name" "4. Advect and inject" "parents" ["root" "flow_resources"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flow_resources"} "name" "4. Advect and inject" "_tombstone" false "frame" #afield/rect [80.0 680.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_advect_code_.kt" "lines" 21 "characters" 668 "sha256" "3b9979cefcdb0a877df57e5faced3c9994a3a1c2dea6ed3f2554aed23ea8ca68"} {"name" "compute" "language" "glsl" "path" "flow_advect_compute_.glsl" "lines" 23 "characters" 823 "sha256" "51bb8e1f545e82db59e1f5c9847226a7f93a64662c0ecc4fd9a840b172009f29"}]}
```

```kotlin [field-property box="flow_advect" name="code" path="flow_advect_code_.kt"]
// Dependencies are explicit graph exports; resources stay in their parent box.

var module = computeModuleHere {}

val mesh = ComputeMesh()
module += "dispatch" to mesh
mesh.x = 225 // 14400 cells / 64 threads.
mesh.y = 1
mesh.source = { if (flowStep.get() % 2L == 0L) flowA else flowB }
mesh.target = { advected }
mesh.auxiliary = { pressureA }
mesh.tiles = { tileHeaders }
mesh.clock = { flowClock }
mesh.emitter = { flowEmitter }
module += "barrier" to computeBarrier(graphics = true)
val simulation = flowSimulation
simulation += "advect" to module
loop {}
    .end {
        if (simulation.modules["advect"] === module) simulation.modules.remove("advect")
    }
```

```glsl [field-property box="flow_advect" name="compute" path="flow_advect_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
#include "flow_helpers.compute"
layout(local_size_x = 64) in;
void main() {
    int i = int(gl_GlobalInvocationID.x);
    if (i >= CELLS) return;
    vec2 p = worldPosition(i);
    if (rock(p)) {
        storeCell(i, vec3(0));
        return;
    }
    vec2 velocity = readCell(pc.source, worldCell(i)).xy;
    vec2 back = p - velocity * pc.clock.y;
    if (rock(back)) back = p;
    velocity = sampleField(pc.source, back).xy * exp(-.2 * pc.clock.y);
    vec2 offset = p - pc.emitter.xy;
    float falloff = exp(-dot(offset, offset) / .8);
    vec2 force = vec2(-offset.y, offset.x) * 8. + vec2(0., 2.);
    velocity += force * pc.emitter.z * falloff * pc.clock.y;
    velocity *= min(1., 12. / max(length(velocity), 1e-6));
    storeCell(i, vec3(velocity, 0));
}
```

## 13. flow_helpers

<!-- field-box id="flow_helpers" index="12" -->

### Narrative

From `compute`

Common storage contract for the editable fluid stages.

```edn [field-box id="flow_helpers" role="properties"]
{"id" "flow_helpers" "index" 12 "name" "flow_helpers" "parents" ["root" "flow_resources"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flow_resources"} "name" "flow_helpers" "_tombstone" false "frame" #afield/rect [80.0 530.0 370.0 100.0]} "blockProperties" [{"name" "compute" "language" "glsl" "path" "flow_helpers_compute_.glsl" "lines" 69 "characters" 2553 "sha256" "2559d6ac993786d908bacf163c30c2d32d92ff8466f9a61274833dd706737c2e"}]}
```

```glsl [field-property box="flow_helpers" name="compute" path="flow_helpers_compute_.glsl"]
// Common storage contract for the editable fluid stages.
#include "flow/tiles.glsl"
layout(std430, buffer_reference) buffer Field {
    vec4 v[];
};
layout(std430, buffer_reference) readonly buffer Tiles {
    ivec4 v[];
};
layout(push_constant) uniform Push {
    Field source;
    // Stage input; xy velocity or x scalar; w generation bits.
    Field target;
    // Stage output, on the same graphics queue.
    Field auxiliary;
    // Divergence or pressure, depending on the stage.
    Tiles tiles;
    // TileResidency.pack: world x/y, generation, occupied.
    vec4 clock;
    // x seconds; y step seconds; zw reserved.
    vec4 emitter;
    // xy force origin; z strength; w reserved.
} pc;

const int N = 24;
// Cells along each tile; matches resources box.
const ivec2 SHAPE = ivec2(5, 5);
// Bounded residency; no periodic world wrapping.
const float TILE = 4.;
// World units per tile.
const float H = TILE / float(N);
// World units per fluid cell.
const int CELLS = 25 * N * N;

bool rock(vec2 p) {
    // A thin ledge and a disc, evaluated exactly for drawing and approximately for the grid.
    return (abs(p.y + 2.) < .10 && abs(p.x) < 4.) || length(p - vec2(2.4, 1.2)) < .8;
}
ivec2 worldCell(int index) {
    int slot = index / (N * N), local = index % (N * N);
    return pc.tiles.v[slot].xy * N + ivec2(local % N, local / N);
}
vec2 worldPosition(int index) {
    return (vec2(worldCell(index)) + .5) * H;
}
int cellIndex(ivec2 cell) {
    ivec2 local = ivec2(flowFloorMod(cell.x, N), flowFloorMod(cell.y, N));
    ivec2 tile = (cell - local) / N;
    int slot = flowTileSlot(tile, SHAPE);
    if (!flowTileResident(pc.tiles.v[slot], tile)) return -1;
    return slot * N * N + local.y * N + local.x;
}
vec4 readCell(Field field, ivec2 cell) {
    int index = cellIndex(cell);
    if (index < 0 || rock((vec2(cell) + .5) * H)) return vec4(0.);
    vec4 value = field.v[index];
    return floatBitsToInt(value.w) == pc.tiles.v[index / (N * N)].z ? value : vec4(0.);
}
vec4 sampleField(Field field, vec2 p) {
    vec2 grid = p / H - .5;
    ivec2 cell = ivec2(floor(grid));
    vec2 f = fract(grid);
    // Cell-centred bilinear interpolation; readCell rejects stale tile generations.
    vec4 lower = mix(readCell(field, cell), readCell(field, cell + ivec2(1, 0)), f.x);
    vec4 upper = mix(readCell(field, cell + ivec2(0, 1)), readCell(field, cell + ivec2(1)), f.x);
    return mix(lower, upper, f.y);
}
void storeCell(int index, vec3 value) {
    pc.target.v[index] = vec4(value, intBitsToFloat(pc.tiles.v[index / (N * N)].z));
}
```

## 14. 10. Present color or motion

<!-- field-box id="flow_resolve" index="13" -->

### Narrative

From `code`

Dependencies are explicit graph exports; resources stay in their parent box.

The color and motion attachment are both available to future postprocessing boxes.

```edn [field-box id="flow_resolve" role="properties"]
{"id" "flow_resolve" "index" 13 "name" "10. Present color or motion" "parents" ["root" "flow_resources"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flow_resources"} "name" "10. Present color or motion" "_tombstone" false "frame" #afield/rect [510.0 680.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_resolve_code_.kt" "lines" 18 "characters" 655 "sha256" "bfd4ccc2e7ed2c4793ec1fbe78da48c2ed2cfb64e0775861627595e638f96ba7"} {"name" "vertex" "language" "glsl" "path" "flow_resolve_vertex_.glsl" "lines" 7 "characters" 202 "sha256" "093ff9c35c7d0656fa9929963897d03e432d20bb26c591aafde0c25275d29caa"} {"name" "fragment" "language" "glsl" "path" "flow_resolve_fragment_.glsl" "lines" 15 "characters" 621 "sha256" "45f8fadc2c335b9de763a700e2dd5dc17721722816ad9c022384a53c396cf6a0"}]}
```

```kotlin [field-property box="flow_resolve" name="code" path="flow_resolve_code_.kt"]
// Dependencies are explicit graph exports; resources stay in their parent box.

// The color and motion attachment are both available to future postprocessing boxes.
var module = moduleHere {
    depthTest = false
    depthWrite = false
    primitiveType = triangles
    textures("colorTexture" to flowSurface.imageViewArray[0], "motionTexture" to flowSurface.imageViewArray[1])
}
val mesh = Mesh()
module += "present" to mesh
mesh.look = { flowLook }
mesh.resize(3)
renderer += "flow-resolve" to module
loop { true }
    .end {
        if (renderer.renderList.modules["flow-resolve"] === module) renderer.renderList.modules.remove("flow-resolve")
    }
```

```glsl [field-property box="flow_resolve" name="vertex" path="flow_resolve_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 uv;
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(p * 2. - 1., 0, 1);
    uv = vec2(p.x, 1. - p.y);
}
```

```glsl [field-property box="flow_resolve" name="fragment" path="flow_resolve_fragment_.glsl"]
#version 450
layout(set=0,binding=0) uniform sampler2D colorTexture;
layout(set=0,binding=1) uniform sampler2D motionTexture;
layout(push_constant) uniform Push {
    vec4 look;
    // w: 0 color; 3 previous UV; 4 displacement magnified.
} pc;
layout(location=0) in vec2 uv;
layout(location=0) out vec4 color;
void main() {
    color = texture(colorTexture,uv);
    vec4 motion = texture(motionTexture,uv);
    if (pc.look.w > 2.5 && pc.look.w < 3.5) color = vec4(motion.z > .5 ? vec3(motion.xy,0) : vec3(1,0,0),1);
    if (pc.look.w > 3.5) color = vec4(motion.z > .5 ? vec3(.5+(motion.xy-uv)*12.,.5) : vec3(1,0,0),1);
}
```

## 15. 11. Velocity strokes

<!-- field-box id="flow_strokes" index="14" -->

### Narrative

From `code`

Dependencies are explicit graph exports; resources stay in their parent box.

```edn [field-box id="flow_strokes" role="properties"]
{"id" "flow_strokes" "index" 14 "name" "11. Velocity strokes" "parents" ["root" "flow_resources"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flow_resources"} "name" "11. Velocity strokes" "_tombstone" false "frame" #afield/rect [510.0 830.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_strokes_code_.kt" "lines" 23 "characters" 833 "sha256" "ba943615ee132296aabbf11ea0b578c6b7084fce0594ccb8ad60126078cc940e"} {"name" "vertex" "language" "glsl" "path" "flow_strokes_vertex_.glsl" "lines" 32 "characters" 1107 "sha256" "d7164b666c2529edf702f44e836a66a9cbf25049c3cfe34b6eada57f6aa949dd"} {"name" "fragment" "language" "glsl" "path" "flow_strokes_fragment_.glsl" "lines" 11 "characters" 435 "sha256" "9752e42e92d3d9bda251e7c5daa170efc6245410dc64df857a9bfda5e7a1e7af"}]}
```

```kotlin [field-property box="flow_strokes" name="code" path="flow_strokes_code_.kt"]
// Dependencies are explicit graph exports; resources stay in their parent box.

var module = moduleHere {
    depthTest = false
    depthWrite = false
    primitiveType = triangles
}
val mesh = Mesh()
module += "velocity strokes" to mesh
mesh.particles = { if (flowStep.get() % 2L == 0L) particleB else particleA }
mesh.history = { cameraHistory }
val strokeLook = vec(.18, .6, 1, 0) // Stroke seconds, half-width pixels, gain, reserved.
mesh.look = { strokeLook }
val strokeViewport = vec(0, 0, 0, 0)
mesh.viewport = { strokeViewport.set(window.width.toFloat(), window.height.toFloat(), 0f, 0f) }
mesh.resize(16000 * 6)
renderer += "flow-strokes" to module
loop { true }
    .end {
        if (renderer.renderList.modules["flow-strokes"] === module) {
            renderer.renderList.modules.remove("flow-strokes")
        }
    }
```

```glsl [field-property box="flow_strokes" name="vertex" path="flow_strokes_vertex_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
#include "flow/strokes.glsl"
#include "flow/motion.glsl"
layout(std430, buffer_reference) readonly buffer Field {
    vec4 v[];
};
layout(std430, buffer_reference) readonly buffer History {
    FlowFrameHistory frame;
};
layout(push_constant) uniform Push {
    Field particles;
    // Pairs of position/generation and smoothed velocity.
    History history;
    // Camera matrices and target resolution.
    vec4 viewport;
    // xy current window dimensions; zw reserved.
    vec4 look;
    // x seconds of stroke; y half-width pixels; z gain; w reserved.
} pc;
layout(location=0) out vec2 edge;
layout(location=1) out vec2 world;
const vec2 corners[6] = vec2[6](vec2(0,-1),vec2(1,-1),vec2(1,1),vec2(0,-1),vec2(1,1),vec2(0,1));
void main() {
    int id = gl_VertexIndex / 6;
    vec2 p = pc.particles.v[id*2].xy;
    vec2 v = pc.particles.v[id*2+1].xy;
    edge = corners[gl_VertexIndex % 6];
    world = p;
    gl_Position = flowStrokeClip(p, v, vec2(0,-1), edge, pc.history.frame.current,
    pc.viewport.xy, pc.look.x, .035, .65, pc.look.y);
}
```

```glsl [field-property box="flow_strokes" name="fragment" path="flow_strokes_fragment_.glsl"]
#version 450
layout(location=0) in vec2 edge;
layout(location=1) in vec2 world;
layout(location=0) out vec4 color;
void main() {
    bool rock = (abs(world.y+2.)<.10 && abs(world.x)<4.) || length(world-vec2(2.4,1.2))<.8;
    if (rock) discard;
    float alpha = (1.-abs(edge.y)) * smoothstep(0.,.25,edge.x);
    float light = 1. / (1. + dot(world-vec2(-3,3), world-vec2(-3,3))*.035);
    color = vec4(vec3(.8), alpha * light * .35);
}
```

