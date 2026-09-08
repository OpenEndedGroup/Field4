import afield.four.Renderer
import afield.four.modules.*
import afield.four.targets.BinocularGazeIntersection
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Quaternionf
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay

val renderer: Renderer = fromContext()
val xr = renderer.openXRHmdTarget
if (xr != null) {
    check(xr.hasEyeViews) { "OpenXR views are not ready; check the headset/runtime, then re-launch this box" }
}
val xrFormats = if (xr == null) emptyList<Int>() else renderer.runOnQueueThread {
    listOf(xr.sideBySideRTTParameters().format)
}

// Change this and re-launch this box to exercise a different world origin.
// It is deliberately non-identity: +0.35m X, +0.10m Y, -0.20m Z, +30 degrees yaw.
val worldFromLocal = Matrix4f().translate(0.35f, 0.10f, -0.20f)
    .rotate(Quaternionf(0f, 0.25881904f, 0f, 0.9659258f))
val localFromWorld = Matrix4f(worldFromLocal).invert()
val solve = BinocularGazeIntersection(
    minimumAngleDegrees = 0.25, maximumSeparation = 0.03,
    minimumDistance = 0.05, maximumDistance = 8.0
)
val holdMarker = AtomicBoolean(false)
val report = AtomicReference("Waiting for the first render callback")
val markerWorld = Vec3()
var hasPlacement = false
var held = false

val previewView = Matrix4f().translate(0f, -0.15f, -0.5f)
val previewProjection = Matrix4f().setPerspective(1.15f, 1.5f, 0.01f, 100f, true)
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
            pos.add(v.x, v.y, v.z); col.add(color.x, color.y, color.z, color.w); tri.add(i)
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
    val corners = (0..7).map { i -> Vec3(
        if (i and 1 == 0) -radius else radius,
        if (i and 2 == 0) -radius else radius,
        if (i and 4 == 0) -radius else radius) }
    val result = mutableListOf<Vec3>()
    for (i in 0..7) for (bit in listOf(1, 2, 4)) if (i and bit == 0) {
        result.add(corners[i]); result.add(corners[i or bit])
    }
    return result
}

val grid = mutableListOf<Vec3>()
for (i in -4..4) {
    val x = i * 0.5f
    grid.add(Vec3(x, -0.5f, 0f)); grid.add(Vec3(x, -0.5f, -5f))
}
for (i in 0..10) {
    val z = -i * 0.5f
    grid.add(Vec3(-2f, -0.5f, z)); grid.add(Vec3(2f, -0.5f, z))
}
lineMesh("world grid", grid, vec(0.25, 0.25, 0.25, 1)) { Matrix4f() }
for ((name, end, color) in listOf(
    Triple("world +X", vec(0.5, 0, 0), vec(1, 0.3, 0.3, 1)),
    Triple("world +Y", vec(0, 0.5, 0), vec(0.3, 1, 0.3, 1)),
    Triple("world -Z", vec(0, 0, -0.5), vec(0.3, 0.5, 1, 1)))) {
    lineMesh(name, listOf(Vec3(), end), color) { Matrix4f() }
}
for (distance in listOf(1f, 2f, 4f)) {
    lineMesh("reference at $distance m", cubeLines(0.08f), vec(0.45, 0.45, 0.45, 1)) {
        Matrix4f().translate(0f, 0f, -distance)
    }
}

val leftModel = Matrix4f()
val rightModel = Matrix4f()
val combinedModel = Matrix4f()
val gapModel = Matrix4f()
val unitRay = listOf(Vec3(), Vec3(0f, 0f, -1f))
val leftRay = lineMesh("HTC left ray", unitRay, vec(0.3, 0.85, 1, 1)) { leftModel }
val rightRay = lineMesh("HTC right ray", unitRay, vec(1, 0.55, 0.2, 1)) { rightModel }
val dashes = (0..19).flatMap { i -> listOf(Vec3(0f, 0f, -i / 20f), Vec3(0f, 0f, -(i + 0.5f) / 20f)) }
val combinedRay = lineMesh("EXT combined ray (no depth)", dashes, vec(0.6, 0.6, 0.6, 1)) { combinedModel }
val gap = lineMesh("closest-point gap", listOf(Vec3(), Vec3(1f, 1f, 1f)), vec(1, 0.2, 0.5, 1)) { gapModel }
val marker = lineMesh("binocular world marker", cubeLines(0.025f), vec(1, 1, 1, 1)) {
    Matrix4f().translate(markerWorld)
}
leftRay.instanceCount = 0; rightRay.instanceCount = 0
combinedRay.instanceCount = 0; gap.instanceCount = 0; marker.instanceCount = 0

fun point(v: Vec3) = "(%.3f, %.3f, %.3f)".format(v.x, v.y, v.z)
module += "sample eyes once before left-eye drawing" to before { _, _ ->
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
            leftModel.set(worldFromLocal).translate(eyes.left.position).rotate(eyes.left.orientation).scale(1f, 1f, ld)
            rightModel.set(worldFromLocal).translate(eyes.right.position).rotate(eyes.right.orientation).scale(1f, 1f, rd)
        }
        combinedRay.instanceCount = if (combined?.poseValid == true) 1 else 0
        if (combined?.poseValid == true) {
            combinedModel.set(worldFromLocal).translate(combined.position).rotate(combined.orientation).scale(1f, 1f, 2f)
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
            report.set("""
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
            """.trimIndent().format(solve.separation, solve.angleDegrees))
        }
    }
}
if (xr != null) xr += "eye intersection scene" to module
else renderer += "eye intersection scene" to module
module.enable()

checkbox("Hold the last valid marker in world space", false, "hold") { holdMarker.set(it) }
println("Eye intersection scene ready; worldFromLocal is translation + 30-degree yaw")
loop {
    display("eye rays", report.get())
    delay(250)
    true
}.end {
    module.disable()
}
