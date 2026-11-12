import afield.graphics.scene.body.*
import afield.graphics.vulkan.Target
import kotlin.math.*

// These are persistent, inherited Field properties. Re-running this box keeps the controls' values.
prop<Boolean>(readingBodyEnabled, true)
prop<Boolean>(readingBodyHands, true)
prop<Boolean>(readingBodyHead, true)
prop<Boolean>(readingBodyWaft, true)
prop<Float>(readingBodyWaftStrength, 4f) // outward acceleration at the capsule surface, m/s²
prop<Float>(readingBodyPull, 0f) // gathering gain in the optional classic standoff mode
prop<Float>(readingBodyPush, 14f) // additional acceleration per metre of penetration
prop<Float>(readingBodyReach, .4f) // outside influence band, metres
prop<Float>(readingBodyInsideReach, 0f) // classic mode only; zero covers the whole interior
prop<Float>(readingBodyGap, .018f) // extra clearance beyond each capsule radius, metres
prop<Float>(readingBodyFalloff, 1f)
prop<Float>(readingBodyMaximum, 4.5f) // acceleration cap, m/s²; zero disables the cap
prop<Float>(readingBodyRadiusScale, 1f)
rootProp<Boolean>(showSkeleton, false)
rootProp<Boolean>(showPhysicsNodes, true)
rootProp<Boolean>(showBodyForces, true)
prop<Map<String, Float>>(bodyInteractionStats) = emptyMap()

val readingBodyDrawing = drawing
val readingBodyExamples = poem.examples.toList()
val previousReadingUpdate = drawing.beforeUpdate
val readingBodyRadii = mutableMapOf<String, Float>() // optional bone name -> radius in world metres
var readingBodySnapshot = BodySegments.from(null)
val readingBodyInverse = Matrix4f()
var readingBodyModelFrame = -1L
val readingBodyWorld = Vector3f()
val nodeCapacity = readingBodyExamples.maxOf { it.player.state.size }
val contactNodes = Array(nodeCapacity) { Vector3f() } // exact world point tested by the solver
val contactBones = Array(nodeCapacity) { Vector3f() } // closest point on the selected bone's centreline
val contactForces = FloatArray(nodeCapacity) // actual applied world acceleration magnitude
val contactFrames = LongArray(nodeCapacity) { -100L }
var contactPlayer = poem.current

// Input: signed capsule-surface distance and outward normal, in world metres.
// Output: world acceleration. Wafting never attracts: its smooth outside band fades to zero.
fun readingBodyForce(hit: BodySegmentHit): Vector3f {
    if (!readingBodyWaft) return hit.force(readingBodyPull, readingBodyPush, readingBodyReach,
        readingBodyFalloff, readingBodyMaximum, readingBodyInsideReach)
    val outside = max(hit.distance, 0f)
    val t = if (outside == 0f) 1f else if (readingBodyReach > 0f)
        (1f - outside / readingBodyReach).coerceIn(0f, 1f) else 0f
    val smooth = t * t * (3f - 2f * t)
    val envelope = if (smooth == 0f) 0f else smooth.pow(readingBodyFalloff)
    var strength = readingBodyWaftStrength * envelope + readingBodyPush * max(-hit.distance, 0f)
    if (readingBodyMaximum > 0f) strength = min(strength, readingBodyMaximum)
    return Vector3f(hit.normal).mul(strength)
}

// Also available to small CPU probes without substituting a different force implementation.
prop<(Float) -> Float>(bodyForceAtDistance) = { distance ->
    readingBodyForce(BodySegmentHit(0, Vector3f(), Vector3f(1f, 0f, 0f), distance)).x
}

// These records come from the REAL force callback, rather than a second diagnostic distance test.
val readingBodyAcceleration: (Int, Vector3f, Vector3f) -> Unit = { index, local, output ->
    contactForces[index] = 0f
    contactFrames[index] = renderer.frameCounter
    if (readingBodyEnabled) {
        if (readingBodyModelFrame != renderer.frameCounter) {
            readingBodyInverse.set(drawing.model).invert()
            readingBodyModelFrame = renderer.frameCounter
        }
        drawing.model.transformPosition(local, readingBodyWorld)
        val hit = readingBodySnapshot.closest(readingBodyWorld, readingBodyGap)
        if (hit != null) {
            output.set(readingBodyForce(hit))
            contactForces[index] = output.length()
            contactNodes[index].set(readingBodyWorld)
            val radius = readingBodySnapshot[hit.segment]!!.radius + readingBodyGap
            contactBones[index].set(hit.point).fma(-max(radius, 0f), hit.normal)
            readingBodyInverse.transformDirection(output)
        }
    }
}

// One small debug module for skeleton lines, SIMD points and active force connections.
// Expansion uses the current stereo eyes; green stays zero so the debug ink remains sharp.
val readingBodyTarget = drawing.compositor.sharp
val readingBodyModule = moduleHere {
    stereo.configure(this)
    +readingBodyTarget
    depthTest = false
    depthWrite = false
    blend = true
    srcColor = org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_ALPHA
    dstColor = org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
    srcAlpha = org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
    dstAlpha = org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
}
val readingBodyMarks = List(BodySegments.CAPACITY + nodeCapacity * 2) {
    arrayOf(Vector4f(), Vector4f(), Vector4f(1f, 0f, 1f, 0f))
}
for ((id, mark) in readingBodyMarks.withIndex()) {
    val geometry = Mesh(renderer, readingBodyModule)
    geometry.resize(6, 0)
    geometry.push.bind("a") { mark[0] } // world start/centre, radius or half-width in metres
    geometry.push.bind("b") { mark[1] } // world end, 1 = circular marker / 0 = line
    geometry.push.bind("tint") { mark[2] } // red/blue ink; alpha controls diagnostic opacity
    readingBodyModule += "body diagnostic $id" to geometry
}
readingBodyTarget += "tracked-body" to readingBodyModule

val readingBodyUpdate: () -> Unit = {
    previousReadingUpdate()
    readingBodyModelFrame = -1L
    if (contactPlayer !== poem.current) {
        contactPlayer = poem.current
        contactForces.fill(0f)
        contactFrames.fill(-100L)
    }
    readingBodySnapshot = if (readingBodyEnabled || root.showSkeleton)
        renderer.openXRHmdTarget?.bodySegments(readingBodyHands, readingBodyHead) { name, radius ->
            (readingBodyRadii[name] ?: radius) * readingBodyRadiusScale
        } ?: BodySegments.from(null)
    else BodySegments.from(null)
    for (example in readingBodyExamples) {
        if (readingBodyEnabled && readingBodySnapshot.active.isNotEmpty())
            example.player.externalAccelerations["tracked-body"] = readingBodyAcceleration
        else example.player.externalAccelerations.remove("tracked-body", readingBodyAcceleration)
    }
    readingBodyModule.current.enabled = root.showSkeleton || root.showPhysicsNodes || root.showBodyForces
}
drawing.beforeUpdate = readingBodyUpdate

// This runs AFTER the reading renderer has updated model and advanced the fixed-step solver.
// Node dots use current SIMD state; force lines retain the exact point sampled by that step.
readingBodyModule.current.before["body-debug"] = Target { _, _ ->
    readingBodyMarks.forEach { it[2].w = 0f }
    if (root.showSkeleton) for (segment in readingBodySnapshot.active) {
        val mark = readingBodyMarks[segment.id]
        mark[0].set(segment.start.x, segment.start.y, segment.start.z, if (segment.id == 8) .015f else .002f)
        mark[1].set(segment.end.x, segment.end.y, segment.end.z, if (segment.id == 8) 1f else 0f)
        mark[2].set(.5f, 0f, .5f, .35f)
    }
    val player = poem.current
    var active = 0
    var maximum = 0f
    for ((index, node) in player.diagram.particles.withIndex()) {
        val pushing = readingBodyEnabled && readingBodySnapshot.active.isNotEmpty() &&
            renderer.frameCounter - contactFrames[index] <= 3L && contactForces[index] > .01f
        if (pushing) {
            active++
            maximum = max(maximum, contactForces[index])
        }
        if (root.showPhysicsNodes) {
            val p = drawing.model.transformPosition(vec(player.state.x[index], player.state.y[index], player.state.z[index]))
            val mark = readingBodyMarks[BodySegments.CAPACITY + index]
            mark[0].set(p.x, p.y, p.z, if (pushing) .010f else .006f)
            mark[1].w = 1f
            mark[2].set(1f, 0f, 1f, if (node.pinned) .18f else if (pushing) 1f else .55f)
        }
        if (root.showBodyForces && pushing) {
            val mark = readingBodyMarks[BodySegments.CAPACITY + nodeCapacity + index]
            val from = contactBones[index]
            val to = contactNodes[index]
            mark[0].set(from.x, from.y, from.z, .0015f)
            mark[1].set(to.x, to.y, to.z, 0f)
            mark[2].set(1f, 0f, 1f, .25f + .6f * min(contactForces[index] / 4f, 1f))
        }
    }
    bodyInteractionStats = mapOf("nodes" to player.state.size.toFloat(), "pushing" to active.toFloat(),
        "maxAcceleration" to maximum, "bones" to readingBodySnapshot.active.size.toFloat())
}

// Apply this preset without moving the scene, resetting particles or changing text orientations.
prop<() -> Unit>(bodyWaftPreset) = {
    readingBodyEnabled = true
    readingBodyWaft = true
    readingBodyWaftStrength = 4f
    readingBodyReach = .4f
    readingBodyMaximum = 4.5f
    poem.current.spring = 3f
    poem.current.dampingRate = 3f
    poem.current.gravity = 0f
    poem.current.wind = 0f
}
prop<() -> Unit>(bodyInteractionDemo) = {
    poem.select(6, restart = true)
    bodyWaftPreset()
    poem.current.playback.seek(6.0)
    poem.current.playback.playing = false
    drawing.recenter()
}

var lastBodyStats = 0L
loop {
    val now = System.nanoTime()
    if (now - lastBodyStats > 1_000_000_000L) {
        lastBodyStats = now
        val stats = bodyInteractionStats
        display("body diagnostics", "<div style='padding:12px;width:440px'>${stats["bones"]?.toInt() ?: 0} tracked bones · ${stats["nodes"]?.toInt() ?: 0} SIMD nodes · ${stats["pushing"]?.toInt() ?: 0} pushing. Bright dots and bone-to-node lines mark applied force. Dim dots are pinned. Contact uses these nodes; thread interior nodes and glyph outlines are not tested.</div>")
    }
    frame()
}.end {
    if (drawing.beforeUpdate === readingBodyUpdate) drawing.beforeUpdate = previousReadingUpdate
    for (example in readingBodyExamples) example.player.externalAccelerations.remove("tracked-body", readingBodyAcceleration)
    readingBodyModule.disable()
    readingBodyTarget.modules.remove("tracked-body", readingBodyModule)
}
