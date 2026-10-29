@file:afield.runtime.completion.CompletionCategory("Animation")

package afield.animation.mocap

import afield.geometry.path.FLine
import afield.math.linalg.Quat
import afield.math.linalg.Vec3
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Await an optional worker without blocking Field's frame thread. Cancellation cancels opening too. */
suspend fun <T> CompletableFuture<T>.awaitMotion(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { value, error ->
        if (error != null) continuation.resumeWithException(error) else continuation.resume(value)
    }
    continuation.invokeOnCancellation { cancel(false) }
}

/** First uncommitted session time. A currently running advance may move it further before an edit applies. */
val MotionSession.editableTime: Double get() = latest?.let {
    it.time + if (capabilities.backend == "ardy") 1.0 / capabilities.details.getDouble("fps") else 0.0
} ?: capabilities.details.optDouble("startTime", 0.0)

/** Change intention at the next editable time, or explicitly schedule [at] in session seconds. */
fun MotionSession.prompt(text: String, at: Double = editableTime) = setPrompt(text, at)

/** Sample Field's arc-length cursor, with geometry, duration, heading and speed envelope independent. */
fun MotionSession.route(path: FLine, duration: Duration, at: Double = editableTime,
    headings: Boolean = true, distanceFraction: (Double) -> Double = { it }): CompletableFuture<MotionApplied?> {
    val seconds = duration.inWholeNanoseconds / 1e9
    require(duration.isFinite() && seconds > 0)
    val fps = capabilities.details.optDouble("fps", 20.0)
    return setRootPlan(path.motionRoute(at, seconds, ceil(seconds * fps).toInt().coerceIn(1, 9999) + 1,
        headings, distanceFraction), at)
}

/** A runtime physical body; resolving it checks the explicit body mapping, never a display-joint index. */
fun MotionSession.body(name: String): MotionBodyHandle {
    capabilities.require(MotionOperation.ATTACH)
    return MotionBodyHandle(this, skeleton.body(name))
}

/** An animation joint. Holding it is kinematic conditioning, distinct from a physical tether. */
fun MotionSession.joint(name: String) = MotionJointHandle(this, skeleton.joint(name))

private fun Vec3.components() = listOf(x.toDouble(), y.toDouble(), z.toDouble())
private fun List<Double>.vector() = Vec3(this[0].toFloat(), this[1].toFloat(), this[2].toFloat())
private fun MotionSession.globalRotation(index: Int): Quat {
    val pose = latest?.executed ?: error("Advance once before reading a body or joint")
    val chain = ArrayList<Int>(); var i = index
    while (i >= 0) { chain.add(i); i = skeleton.parents[i] }
    val result = Quat()
    chain.asReversed().forEach { result.mul(pose.rotation(it)) }
    return result.normalize()
}

/** Mutable only while authoring a command; vectors are copied into the immutable wire payload. SI units. */
class TetherSettings internal constructor(previous: MotionAttachment? = null) {
    var point = previous?.localPoint?.vector() ?: Vec3()
    var anchor: Vec3? = previous?.anchor?.vector()
    var velocity = previous?.anchorVelocity?.vector() ?: Vec3()
    var stiffness = previous?.stiffness ?: 150.0
    var damping = previous?.damping ?: 15.0
    var maxForce = previous?.maximumForce ?: 150.0
    var ramp: Duration = ((previous?.engagementSeconds ?: .2) * 1000).milliseconds
    var breakForce = previous?.breakForce
    var breakDistance = previous?.breakError
    var breakDuration: Duration = ((previous?.breakDuration ?: .1) * 1000).milliseconds

    /** Break on sustained pre-cap demand, not merely because the spring hits its force cap. */
    fun breakAbove(force: Double, forAtLeast: Duration = 100.milliseconds) {
        breakForce = force; breakDuration = forAtLeast
    }
    /** Keep the connection when its applied force saturates. */
    fun unbreakable() { breakForce = null; breakDistance = null }

    internal fun build(id: String, body: MotionBodyHandle): MotionAttachment {
        require(ramp.isFinite() && breakDuration.isFinite())
        return MotionAttachment(id, body.name, point.components(), (anchor ?: body.position(point)).components(),
            stiffness, damping, maxForce, ramp.inWholeNanoseconds / 1e9,
            breakForce, breakDistance, breakDuration.inWholeNanoseconds / 1e9, velocity.components())
    }
}

private val tetherIds = AtomicLong()

class MotionBodyHandle internal constructor(private val session: MotionSession, val mapping: MotionBody) {
    val name get() = mapping.name
    /** Current executed body-local point in world metres; [localPoint] is not a joint offset. */
    fun position(localPoint: Vec3 = Vec3()): Vec3 {
        val index = session.skeleton.joint(mapping.joint)
        val pose = session.latest?.executed ?: error("Advance once before attaching a body")
        return session.globalRotation(index).transform(Vec3(localPoint)).add(pose.position(index))
    }
    /** Capture the current point as anchor unless the block supplies [TetherSettings.anchor]. */
    fun tether(id: String = "tether-${tetherIds.incrementAndGet()}", at: Double = session.editableTime,
        block: TetherSettings.() -> Unit = {}): MotionTether {
        val parameters = TetherSettings().apply(block).build(id, this)
        return MotionTether(session, this, parameters, session.attach(parameters, at))
    }
    /** World impulse in N s, applied once at a body-local point. */
    fun impulse(impulse: Vec3, point: Vec3 = Vec3(), at: Double = session.editableTime) =
        session.applyImpulse(name, point.components(), impulse.components(), at)
}

/** A named connection. [accepted] acknowledges scheduling; [observation] reports actual physics/reset events. */
class MotionTether internal constructor(private val session: MotionSession, private val body: MotionBodyHandle,
    parameters: MotionAttachment, val accepted: CompletableFuture<MotionApplied?>) {
    @Volatile var parameters = parameters; private set
    val id get() = parameters.id
    private val previousObservation = session.telemetry.tether(id)
    val observation get() = session.telemetry.tether(id)?.takeUnless { it === previousObservation }
    @Synchronized fun tune(at: Double = session.editableTime, block: TetherSettings.() -> Unit): CompletableFuture<MotionApplied?> {
        check(observation?.active != false) { "Tether $id was ${observation?.reason}; attach it again before tuning" }
        val next = TetherSettings(parameters).apply {
            // Moving anchors continue from their current location when coefficients change.
            observation?.let { o -> if (o.anchor.size == 3) {
                val dt = (at - o.time).coerceAtLeast(0.0)
                anchor = Vec3(o.anchor[0].toFloat(), o.anchor[1].toFloat(), o.anchor[2].toFloat())
                    .add(Vec3(velocity).mul(dt.toFloat()))
            } }
        }.apply(block).build(id, body)
        val future = session.updateAttachment(next, at)
        parameters = next
        return future
    }
    fun moveTo(anchor: Vec3, velocity: Vec3 = Vec3(), at: Double = session.editableTime) =
        tune(at) { this.anchor = Vec3(anchor); this.velocity = Vec3(velocity) }
    fun release(at: Double = session.editableTime): CompletableFuture<MotionApplied?> =
        if (observation?.active == false) CompletableFuture.completedFuture(null) else session.detach(id, at)
}

class MotionJointHandle internal constructor(private val session: MotionSession, val index: Int) {
    val name get() = session.skeleton.names[index]
    fun position(): Vec3 = session.latest?.executed?.position(index) ?: error("Advance once before holding a joint")
    /** Repeated future targets; ARDY requires an explicit root plan covering the same interval. */
    fun hold(duration: Duration, orientation: Boolean = false, at: Double = session.editableTime): CompletableFuture<MotionApplied?> {
        session.capabilities.require(MotionOperation.JOINT_TARGETS)
        require(duration.isFinite() && duration > Duration.ZERO)
        val seconds = duration.inWholeNanoseconds / 1e9
        val fps = session.capabilities.details.getDouble("fps")
        val count = ceil(seconds * fps).toInt(); require(count in 1..10000)
        val target = position().components()
        val rotation = if (orientation) session.globalRotation(index).let { listOf(it.x.toDouble(), it.y.toDouble(), it.z.toDouble(), it.w.toDouble()) } else null
        return session.setJointTargets(List(count) { JointTarget(name, at + it / fps, target, rotation) }, at)
    }
    fun release(at: Double = session.editableTime) = session.setJointTargets(emptyList(), at)
}

/** Actual submitted point force and raw anchor residual, not inferred muscular effort. */
data class TetherObservation(val id: String, val body: String, val active: Boolean, val reason: String,
    val time: Double, val forceN: Double = 0.0, val rawForceN: Double = 0.0, val stretchMetres: Double = 0.0,
    val powerWatts: Double = 0.0, val maxForce: Double = 0.0,
    val point: List<Double> = emptyList(), val anchor: List<Double> = emptyList(), val force: List<Double> = emptyList()) {
    val saturated get() = maxForce > 0 && rawForceN >= maxForce
}

/** Bounded observations of actual backend events. Reset and break invalidate every affected connection. */
class MotionTelemetry internal constructor() {
    private val state = LinkedHashMap<String, TetherObservation>()
    @Volatile var lastEvent = "Ready"; private set
    @Synchronized fun tether(id: String) = state[id]
    @Synchronized fun tethers(): List<TetherObservation> = state.values.toList()
    @Synchronized internal fun accept(update: MotionUpdate) {
        for (e in update.events) {
            val time = e.optDouble("physicsTime", e.optDouble("time", e.optDouble("appliedTime", 0.0)))
            when (e.optString("type")) {
                "applied" -> {
                    val p = e.getJSONObject("payload"); val id = p.optString("id")
                    when (e.getString("operation")) {
                        "ATTACH", "UPDATE_ATTACHMENT" -> state[id] = TetherObservation(id, p.getString("body"), true, "attached", time, maxForce=p.getDouble("maximumForce"))
                        "DETACH" -> state[id]?.let { state[id] = it.copy(active=false, reason="released", time=time) }
                    }
                }
                "tetherPhysics" -> {
                    val id = e.getString("id"); val previous = state[id] ?: continue
                    state[id] = previous.copy(time=time, forceN=e.getDouble("forceN"), rawForceN=e.getDouble("rawForceN"),
                        stretchMetres=e.getDouble("errorM"), powerWatts=e.getDouble("powerWatts"),
                        point=e.getJSONArray("point").doubles(), anchor=e.getJSONArray("anchor").doubles(), force=e.getJSONArray("force").doubles())
                }
                "tetherBreak" -> {
                    val id = e.getString("id"); state[id]?.let { state[id] = it.copy(active=false, reason="broken", time=time) }
                    lastEvent = "Tether $id broke"
                }
                "reset" -> {
                    lastEvent = "Reset: ${e.optString("reason", "backend reset")} — attachments released"
                    state.replaceAll { _, value -> value.copy(active=false, reason="reset", time=time) }
                }
                "termination" -> lastEvent = "Controller terminated — reset pending"
                "episodeEnd" -> lastEvent = "Episode ended — reset pending"
            }
        }
        while (state.size > 128) state.remove(state.keys.first())
    }
}

/**
 * Present a bounded, wall-paced stretch of motion from a Frames coroutine. Inference stays on the
 * session worker; [draw] sees interpolated snapshots and explicit lag. Duration rounds up to the
 * backend's next complete token/control step. Late output holds; it never extrapolates a body.
 */
suspend fun afield.runtime.lifecycle.Frames.playMotion(session: MotionSession, duration: Duration,
    draw: (MotionSnapshot) -> Unit) {
    require(duration.isFinite() && duration > Duration.ZERO)
    val quantum = session.capabilities.details.optDouble("minimumAdvanceSeconds",
        session.capabilities.details.optDouble("fixedControlStep", .05))
    val batch = quantum * ceil(.1 / quantum).coerceAtLeast(1.0)
    val start = session.editableTime
    val end = minOf(start + ceil((duration.inWholeNanoseconds / 1e9) / quantum) * quantum,
        session.capabilities.details.optDouble("endTime", Double.POSITIVE_INFINITY))
    val wall = afield.runtime.lifecycle.AnimationClock.nowSeconds()
    var pending: CompletableFuture<MotionUpdate>? = null
    while (true) {
        session.failure?.let { throw it }
        if (pending?.isDone == true) { pending.get(); pending = null }
        val clock = (start + afield.runtime.lifecycle.AnimationClock.nowSeconds() - wall).coerceAtMost(end)
        val frontier = session.editableTime
        if (pending == null && (session.latest == null || frontier < end - quantum / 2 && frontier < clock + batch)) {
            pending = session.advance(if (session.latest == null) batch else minOf(batch, end - frontier))
        }
        session.buffer.sample(clock)?.let(draw)
        if (clock >= end && pending == null && session.editableTime >= end - quantum / 2) break
        delay(16)
    }
}
