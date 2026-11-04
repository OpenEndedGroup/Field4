# Field Document: openxr_eye_intersection

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 · OpenXR renderer

<!-- field-box id="eye_ray_renderer" index="0" -->

```edn [field-box id="eye_ray_renderer" role="properties"]
{"id" "eye_ray_renderer" "index" 0 "name" "1 · OpenXR renderer" "parents" ["root"] "children" ["eye_ray_scene"] "properties" {"children" #{#afield/box "eye_ray_scene"} "parents" #{#afield/box "root"} "name" "1 · OpenXR renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 340.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "eye_ray_renderer_code_.kt" "lines" 18 "characters" 610 "sha256" "e6623728ad396dcd1716c8df39fc4866e1cbd603b2ce6849e38031384891704b"}]}
```

```kotlin [field-property box="eye_ray_renderer" name="code" path="eye_ray_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.enableVR = !Options.isMac
once.renderer {
    window = Window("Eye rays · LOCAL to world", initialDimensions = Rect(80.0, 80.0, 720.0, 480.0))
    val created = Renderer(window)
    if (Options.enableVR) {
        created.openXRHmdTarget = OpenXRHmdTarget(created)
        created.openXRHmdTarget!!.setClearColor(0f, 0f, 0f)
    }
    created
}
println(if (Options.enableVR) "OpenXR renderer ready" else "Desktop reference preview — no eye tracking is simulated")
start(renderer::run)
```

## 2. 2 · eye rays + world marker

<!-- field-box id="eye_ray_scene" index="1" -->

```edn [field-box id="eye_ray_scene" role="properties"]
{"id" "eye_ray_scene" "index" 1 "name" "2 · eye rays + world marker" "parents" ["root" "eye_ray_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "eye_ray_renderer"} "name" "2 · eye rays + world marker" "_tombstone" false "frame" #afield/rect [340.0 150.0 390.0 120.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "eye_ray_scene_vertex_.glsl" "lines" 13 "characters" 316 "sha256" "3ed65e56090d366813d8ebf73081fd80067692f4086282c4263593eee1af03c6"} {"name" "fragment" "language" "glsl" "path" "eye_ray_scene_fragment_.glsl" "lines" 6 "characters" 121 "sha256" "09f2d517b9f20511becdbad44a079ab2444e197c15df7f90141a8a45c10d42de"} {"name" "code" "language" "kotlin" "path" "eye_ray_scene_code_.kt" "lines" 206 "characters" 8897 "sha256" "6d90755cdbe370769df4ba45d8d22b32bef7baef707aef3d3dd986f5cfaab2b1"}]}
```

```glsl [field-property box="eye_ray_scene" name="vertex" path="eye_ray_scene_vertex_.glsl"]
#version 450
layout(location=0) in vec3 pos;
layout(location=1) in vec4 col;
layout(location=0) out vec4 fcol;
layout(push_constant) uniform Matrices {
    mat4 p;
    mat4 m;
} pc;
void main() {
    // m = eyeViewLocal * localFromWorld * modelWorld
    gl_Position = pc.p * pc.m * vec4(pos, 1.0);
    fcol = col;
}
```

```glsl [field-property box="eye_ray_scene" name="fragment" path="eye_ray_scene_fragment_.glsl"]
#version 450
layout(location=0) in vec4 fcol;
layout(location=0) out vec4 outColor;
void main() {
    outColor = fcol;
}
```

```kotlin [field-property box="eye_ray_scene" name="code" path="eye_ray_scene_code_.kt"]
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay

val xr = renderer.openXRHmdTarget
if (xr != null) {
    check(xr.hasEyeViews) { "OpenXR views are not ready; check the headset/runtime, then re-launch this box" }
}
val xrFormats =
    if (xr == null) emptyList<Int>()
    else
        renderer.runOnQueueThread {
            [xr.sideBySideRTTParameters().format]
        }

// Change this and re-launch this box to exercise a different world origin.
// It is deliberately non-identity: +0.35m X, +0.10m Y, -0.20m Z, +30 degrees yaw.
val worldFromLocal = Matrix4f().translate(0.35f, 0.10f, -0.20f).rotate(Quaternionf(0f, 0.25881904f, 0f, 0.9659258f))
val localFromWorld = Matrix4f(worldFromLocal).invert()
val solve = BinocularGazeIntersection(
    minimumAngleDegrees = 0.25,
    maximumSeparation = 0.03,
    minimumDistance = 0.05,
    maximumDistance = 8.0,
)
val holdMarker = AtomicBoolean(false)
val report = AtomicReference("Waiting for the first render callback")
val markerWorld = Vec3()
var hasPlacement = false
var held = false

// The desktop preview stands 60 cm up, looking down the floor at the reference cubes.
val previewView = Matrix4f().rotateX(0.4f).rotateY(0.2f).translate(0f, -0.6f, -0.2f)
val previewProjection = Matrix4f().setPerspective(0.7f, 1.5f, 0.01f, 100f, true)
val module = moduleHere {
    primitiveType = lines
    depthTest = true
    depthWrite = true
    blend = false
    colorFormats = xrFormats
}

// Static vertex buffers, dynamic model matrices. No uploads after the render pass starts.
fun lineMesh(name: String, vertices: List<Vec3>, color: Vec4, modelWorld: () -> Matrix4f): Mesh {
    val m = Mesh(renderer, module)
    module += name to m
    m.set(vertices.size, vertices.size / 2) {
        for ((i, v) in vertices.withIndex()) {
            pos.add(v.x, v.y, v.z)
            col.add(color.x, color.y, color.z, color.w)
            tri.add(i)
        }
    }
    m.p = { xr?.currentEyeProjectionMatrix() ?: previewProjection }
    m.m = {
        val eyeViewLocal = xr?.currentEyeViewMatrix() ?: Matrix4f(previewView)
        eyeViewLocal.mul(localFromWorld).mul(modelWorld())
    }
    return m
}
fun cubeLines(radius: Float): List<Vec3> {
    val corners =
        (0..7).map { i ->
            Vec3(
                if (i and 1 == 0) -radius else radius,
                if (i and 2 == 0) -radius else radius,
                if (i and 4 == 0) -radius else radius,
            )
        }
    val result = mutableListOf<Vec3>()
    for (i in 0..7) for (bit in [1, 2, 4]) if (i and bit == 0) {
        result.add(corners[i])
        result.add(corners[i or bit])
    }
    return result
}

val grid = mutableListOf<Vec3>()
for (i in -4..4) {
    val x = i * 0.5f
    grid.add(Vec3(x, -0.5f, 0f))
    grid.add(Vec3(x, -0.5f, -5f))
}
for (i in 0..10) {
    val z = -i * 0.5f
    grid.add(Vec3(-2f, -0.5f, z))
    grid.add(Vec3(2f, -0.5f, z))
}
lineMesh("world grid", grid, vec(0.25, 0.25, 0.25, 1)) { Matrix4f() }
for ((name, end, color) in
    [
        Triple("world +X", vec(0.5, 0, 0), vec(1, 0.3, 0.3, 1)),
        Triple("world +Y", vec(0, 0.5, 0), vec(0.3, 1, 0.3, 1)),
        Triple("world -Z", vec(0, 0, -0.5), vec(0.3, 0.5, 1, 1)),
    ]) {
    lineMesh(name, [Vec3(), end], color) { Matrix4f() }
}
for (distance in [1f, 2f, 4f]) {
    lineMesh("reference at $distance m", cubeLines(0.08f), vec(0.45, 0.45, 0.45, 1)) {
        Matrix4f().translate(0f, 0f, -distance)
    }
}

val leftModel = Matrix4f()
val rightModel = Matrix4f()
val combinedModel = Matrix4f()
val gapModel = Matrix4f()
val unitRay = [Vec3(), Vec3(0f, 0f, -1f)]
val leftRay = lineMesh("HTC left ray", unitRay, vec(0.3, 0.85, 1, 1)) { leftModel }
val rightRay = lineMesh("HTC right ray", unitRay, vec(1, 0.55, 0.2, 1)) { rightModel }
val dashes = (0..19).flatMap { i -> [Vec3(0f, 0f, -i / 20f), Vec3(0f, 0f, -(i + 0.5f) / 20f)] }
val combinedRay = lineMesh("EXT combined ray (no depth)", dashes, vec(0.6, 0.6, 0.6, 1)) { combinedModel }
val gap = lineMesh("closest-point gap", [Vec3(), Vec3(1f, 1f, 1f)], vec(1, 0.2, 0.5, 1)) { gapModel }
val marker = lineMesh("binocular world marker", cubeLines(0.025f), vec(1, 1, 1, 1)) {
    Matrix4f().translate(markerWorld)
}
leftRay.instanceCount = 0
rightRay.instanceCount = 0
combinedRay.instanceCount = 0
gap.instanceCount = 0
marker.instanceCount = 0

fun point(v: Vec3) = "(%.3f, %.3f, %.3f)".format(v.x, v.y, v.z)
module +=
    "sample eyes once before left-eye drawing" to
        before { _, _ ->
            // The XR target updates tracking before this callback. Both rendered eyes use one solve.
            if (xr == null || xr.currentEyeIndex == 0) {
                val eyes = xr?.htcEyeState()
                val combined = xr?.eyeGazeState()
                solve.update(eyes)
                held = holdMarker.get() && hasPlacement
                if (!held && solve.valid) {
                    worldFromLocal.transformPosition(solve.position, markerWorld)
                    hasPlacement = true
                }
                held = holdMarker.get() && hasPlacement
                marker.instanceCount = if ((held && hasPlacement) || solve.valid) 1 else 0
                leftRay.instanceCount = if (eyes?.left?.gazeValid == true) 1 else 0
                rightRay.instanceCount = if (eyes?.right?.gazeValid == true) 1 else 0
                if (eyes != null) {
                    val ld = if (solve.valid) solve.leftDistance.toFloat() else 2f
                    val rd = if (solve.valid) solve.rightDistance.toFloat() else 2f
                    leftModel
                        .set(worldFromLocal)
                        .translate(eyes.left.position)
                        .rotate(eyes.left.orientation)
                        .scale(1f, 1f, ld)
                    rightModel
                        .set(worldFromLocal)
                        .translate(eyes.right.position)
                        .rotate(eyes.right.orientation)
                        .scale(1f, 1f, rd)
                }
                combinedRay.instanceCount = if (combined?.poseValid == true) 1 else 0
                if (combined?.poseValid == true) {
                    combinedModel
                        .set(worldFromLocal)
                        .translate(combined.position)
                        .rotate(combined.orientation)
                        .scale(1f, 1f, 2f)
                }
                gap.instanceCount = if (solve.valid) 1 else 0
                if (solve.valid) {
                    val a = worldFromLocal.transformPosition(Vec3(solve.leftPoint))
                    val b = worldFromLocal.transformPosition(Vec3(solve.rightPoint))
                    gapModel.identity().translate(a).scale(b.sub(a))
                }
                if (renderer.frameCounter % 30L == 0L) {
                    val mode = if (xr == null) "DESKTOP REFERENCE PREVIEW — no simulated tracking" else "LIVE OPENXR"
                    val state = if (held) "HELD WORLD POSITION" else solve.status.toString()
                    report.set(
                        """
                <div style="font-family:Iosevka,monospace;background:#080808;color:#ddd;padding:20px;min-width:520px">
                <b>$mode</b><p>$state</p>
                <pre>HTC stereo: ${eyes != null}    EXT combined: ${combined != null}
                HTC gaze valid: L=${eyes?.left?.gazeValid} R=${eyes?.right?.gazeValid}
                LOCAL midpoint: ${if (solve.valid) point(solve.position) else "unavailable"}
                WORLD marker:   ${if (marker.instanceCount > 0) point(markerWorld) else "hidden"}
                closest gap: %.4f m    angle: %.3f degrees
                HTC sample time: ${eyes?.gazeTime ?: 0L} ns</pre>
                <p>Cyan = left · orange = right · dashed grey = EXT combined<br>
                White cube = binocular midpoint · grey cubes = world Z -1, -2, -4 m</p>
                <p>world = worldFromLocal * LOCAL<br>clip = eyeProjection * eyeViewLocal * inverse(worldFromLocal) * world</p>
                <p>Hold a valid placement, then move your head: the cube must stay fixed.<br>
                Edit worldFromLocal above and relaunch to test another world origin.</p></div>
            """
                            .trimIndent()
                            .format(solve.separation, solve.angleDegrees),
                    )
                }
            }
        }
if (xr != null) xr += "eye intersection scene" to module else renderer += "eye intersection scene" to module
module.enable()

checkbox("Hold the last valid marker in world space", false, "hold") { holdMarker.set(it) }
println("Eye intersection scene ready; worldFromLocal is translation + 30-degree yaw")
loop {
    display("eye rays", report.get())
    delay(250)
    true
}
    .end {
        module.disable()
    }
```

