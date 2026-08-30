package drowning

import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Matrix4fc
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector3f
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** A smooth, imperative score channel. Calling [to] once starts a timeline-relative fade. */
class DrowningEnvelope(initial: Float = 1f) {
    var value: Float = initial
        private set

    private var from = initial
    private var target = initial
    private var startedAt = 0.0
    private var duration = 0.0

    fun set(value: Float): DrowningEnvelope {
        this.value = value
        from = value
        target = value
        duration = 0.0
        return this
    }

    fun to(value: Float, seconds: Double, now: Double): DrowningEnvelope {
        sample(now)
        from = this.value
        target = value
        startedAt = now
        duration = seconds.coerceAtLeast(0.0)
        if (duration == 0.0) set(value)
        return this
    }

    internal fun sample(now: Double): Float {
        if (duration <= 0.0) return value
        val linear = ((now - startedAt) / duration).coerceIn(0.0, 1.0)
        val smooth = linear * linear * (3.0 - 2.0 * linear)
        value = (from + (target - from) * smooth.toFloat())
        if (linear >= 1.0) duration = 0.0
        return value
    }

    internal fun isTarget(value: Float): Boolean = target == value
    internal fun isSettledAt(value: Float): Boolean = duration <= 0.0 && this.value == value
}

/** One of the 15 independently scoreable camera/frustum elements. */
class DrowningScoreCell internal constructor(val index: Int, model: Matrix4fc) {
    val modelFromCamera = Matrix4f(model)
    val opacity = DrowningEnvelope(1f)
    val particleInfluence = DrowningEnvelope(1f)
    val projectedLight = DrowningEnvelope(1f)

    /** Resources remain resident while a retirement fade is in flight. */
    var resident: Boolean = true
        private set
    private var wantsResident: Boolean = true

    var flickerDepth = 0f
    var flickerHz = 0f
    var flickerDuty = 0.5f
    var flickerPhase = index / 15f
    var flickerSoftness = 0.04f

    internal fun shutter(time: Double): Float {
        if (flickerDepth <= 0f || flickerHz == 0f) return 1f
        val cycle = time * flickerHz + flickerPhase
        val phase = (cycle - floor(cycle)).toFloat()
        val edge = flickerSoftness.coerceIn(0.0001f, 0.49f)
        val duty = flickerDuty.coerceIn(edge, 1f - edge)
        val open = smoothstep(0f, edge, phase) * (1f - smoothstep(duty - edge, duty, phase))
        return 1f + (open - 1f) * flickerDepth.coerceIn(0f, 1f)
    }

    internal fun materialize(seconds: Double, now: Double) {
        resident = true
        wantsResident = true
        listOf(opacity, particleInfluence, projectedLight).forEach {
            if (!it.isTarget(1f)) it.to(1f, seconds, now)
        }
    }

    internal fun retire(seconds: Double, now: Double) {
        wantsResident = false
        listOf(opacity, particleInfluence, projectedLight).forEach {
            if (!it.isTarget(0f)) it.to(0f, seconds, now)
        }
    }

    internal fun finishLifecycle() {
        if (!wantsResident && opacity.isSettledAt(0f) &&
            particleInfluence.isSettledAt(0f) && projectedLight.isSettledAt(0f)) resident = false
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / max(b - a, 1e-6f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

class DrowningPose internal constructor(
    val name: String,
    internal val matrices: List<Matrix4f>
)

class DrowningScoreGroup internal constructor(
    private val score: DrowningScore,
    val indices: IntArray
) {
    fun fade(to: Float, seconds: Double = 0.0) = apply {
        score.submitGesture { live -> indices.forEach { live[it].opacity.to(to, seconds, live.time) } }
    }

    fun effects(to: Float, seconds: Double = 0.0) = apply {
        score.submitGesture { live -> indices.forEach { live[it].particleInfluence.to(to, seconds, live.time) } }
    }

    fun light(to: Float, seconds: Double = 0.0) = apply {
        score.submitGesture { live -> indices.forEach { live[it].projectedLight.to(to, seconds, live.time) } }
    }

    /** Fade geometry, particle effects, and projected light as one musical gesture. */
    fun presence(to: Float, seconds: Double = 0.0) = apply {
        fade(to, seconds)
        effects(to, seconds)
        light(to, seconds)
    }

    /** Make the cells resident first, then fade all of their musical channels in. */
    fun materialize(seconds: Double = 0.0) = apply {
        score.submitGesture { live -> indices.forEach { live[it].materialize(seconds, live.time) } }
    }

    /** Fade all musical channels out; decoding/upload stops only after the fade completes. */
    fun retire(seconds: Double = 0.0) = apply {
        score.submitGesture { live -> indices.forEach { live[it].retire(seconds, live.time) } }
    }

    fun flicker(hz: Float, depth: Float = 1f, duty: Float = 0.5f, softness: Float = 0.04f) = apply {
        score.submitGesture { live ->
            indices.forEach {
                live[it].flickerHz = hz
                live[it].flickerDepth = depth
                live[it].flickerDuty = duty
                live[it].flickerSoftness = softness
            }
        }
    }

    fun clearFlicker() = apply {
        score.submitGesture { live -> indices.forEach { live[it].flickerDepth = 0f } }
    }
}

/**
 * Mutable score for the 15 transforms and their appearance/effect channels.
 *
 * [home] is captured after the document has applied its VR-tuned roll and radius. Layout methods
 * always accept a reference pose, so absolute-time animation never accumulates matrix error.
 */
class DrowningScore(
    private val cue: DrowningCue,
    private val sessionKey: String = cue.directory.absolutePath
) {
    val cells = cue.cells.map { DrowningScoreCell(it.index, it.modelFromCamera) }
    val poses = linkedMapOf<String, DrowningPose>()
    val home: DrowningPose
    var time: Double = 0.0
        private set
    private var updating = false

    /** Optional absolute timeline callback, replaced freely from a live-coded child box. */
    var script: (DrowningScore.(Double) -> Unit)? = null

    val all: DrowningScoreGroup get() = group(0 until cells.size)
    /**
     * The score currently driving this session's render target. Field child preambles can construct
     * a sibling instance, so gesture boxes bind `_cueScore.live` to reach the launched owner.
     */
    val live: DrowningScore get() = liveScores[sessionKey]?.get() ?: this
    val activeMask: Int get() = cells.fold(0) { mask, cell ->
        if (cell.resident) mask or (1 shl cell.index) else mask
    }

    init {
        home = capture("home")
    }

    operator fun get(index: Int): DrowningScoreCell = cells[index]

    fun group(vararg indices: Int): DrowningScoreGroup = groupArray(indices)

    fun group(indices: IntRange): DrowningScoreGroup = groupArray(indices.toList().toIntArray())

    /** Materialize exactly [indices], retiring every other cell with the same scored duration. */
    fun only(vararg indices: Int, seconds: Double = 0.0): DrowningScore {
        val selected = indices.distinct().toSet()
        require(selected.all { it in cells.indices })
        submitGesture { live ->
            live.cells.forEach { cell ->
                if (cell.index in selected) cell.materialize(seconds, live.time)
                else cell.retire(seconds, live.time)
            }
        }
        return this
    }

    private fun groupArray(indices: IntArray): DrowningScoreGroup {
        require(indices.all { it in cells.indices })
        return DrowningScoreGroup(this, indices.distinct().toIntArray())
    }

    fun capture(name: String): DrowningPose {
        val pose = DrowningPose(name, cells.map { Matrix4f(it.modelFromCamera) })
        poses[name] = pose
        return pose
    }

    fun recall(pose: DrowningPose = home, mix: Float = 1f): DrowningScore {
        val t = mix.coerceIn(0f, 1f)
        cells.indices.forEach { i -> mixMatrix(cells[i].modelFromCamera, pose.matrices[i], t) }
        return this
    }

    fun recall(name: String, mix: Float = 1f): DrowningScore =
        recall(poses[name] ?: error("unknown drowning pose '$name'"), mix)

    /** Rotate an immutable reference pose around a world-space pivot; ideal for wagon-wheel rates. */
    fun spin(
        radians: Float,
        reference: DrowningPose = home,
        pivot: Vector3f = centre(reference)
    ): DrowningScore {
        val around = Matrix4f()
            .translate(pivot.x, pivot.y, pivot.z)
            .rotateY(radians)
            .translate(-pivot.x, -pivot.y, -pivot.z)
        cells.indices.forEach { i -> cells[i].modelFromCamera.set(around).mul(reference.matrices[i]) }
        return this
    }

    fun wagonWheel(turnsPerSecond: Float, time: Double = this.time, reference: DrowningPose = home): DrowningScore =
        spin((time * turnsPerSecond * 2.0 * PI).toFloat(), reference)

    /**
     * Stack the reference pose vertically. radiusScale=0 makes one spine; 1 retains its radial spread.
     * Existing per-cell orientation is preserved, including the tuned image-plane roll.
     */
    fun tower(
        spacing: Float = 0.65f,
        radiusScale: Float = 0f,
        twistPerCell: Float = 0f,
        reference: DrowningPose = home
    ): DrowningScore {
        val centre = centre(reference)
        val middle = (cells.size - 1) * 0.5f
        cells.indices.forEach { i ->
            val source = reference.matrices[i]
            val translation = source.getTranslation()
            cells[i].modelFromCamera.set(source)
                .setTranslation(
                    centre.x + (translation.x - centre.x) * radiusScale,
                    centre.y + (i - middle) * spacing,
                    centre.z + (translation.z - centre.z) * radiusScale
                )
                .rotateY(i * twistPerCell)
        }
        return this
    }

    /**
     * Place the 15 elements in a grid in an arbitrary camera/world anchor. The anchor is the complete
     * model-from-camera transform for the grid centre, so a VR head/controller pose can be supplied.
     */
    fun front(
        anchor: Matrix4fc,
        columns: Int = 5,
        spacingX: Float = 1.1f,
        spacingY: Float = 0.75f,
        depthStagger: Float = 0f
    ): DrowningScore {
        require(columns > 0)
        val rows = (cells.size + columns - 1) / columns
        cells.indices.forEach { i ->
            val column = i % columns
            val row = i / columns
            cells[i].modelFromCamera.set(anchor).translate(
                (column - (columns - 1) * 0.5f) * spacingX,
                ((rows - 1) * 0.5f - row) * spacingY,
                i * depthStagger
            )
        }
        return this
    }

    fun update(time: Double) {
        liveScores[sessionKey] = WeakReference(this)
        this.time = time
        updating = true
        try {
            gestureQueues[sessionKey]?.let { queue ->
                while (true) (queue.poll() ?: break)(this)
            }
            script?.invoke(this, time)
            cells.indices.forEach { i ->
                val scored = cells[i]
                val cell = cue.cells[i]
                cell.modelFromCamera.set(scored.modelFromCamera)
                cell.cameraFromModel.set(scored.modelFromCamera).invert()
                cell.opacity = scored.opacity.sample(time)
                cell.particleInfluence = scored.particleInfluence.sample(time)
                cell.projectedLight = scored.projectedLight.sample(time)
                cell.flicker = scored.shutter(time)
                scored.finishLifecycle()
            }
        } finally {
            updating = false
        }
    }

    internal fun submitGesture(gesture: (DrowningScore) -> Unit) {
        if (updating) gesture(this)
        else gestureQueues.computeIfAbsent(sessionKey) { ConcurrentLinkedQueue() }.add(gesture)
    }

    private fun centre(pose: DrowningPose): Vector3f {
        val result = Vector3f()
        pose.matrices.forEach { result.add(it.getTranslation()) }
        return result.mul(1f / pose.matrices.size)
    }

    private fun mixMatrix(destination: Matrix4f, target: Matrix4fc, t: Float) {
        if (t >= 1f) {
            destination.set(target)
            return
        }
        val fromTranslation = destination.getTranslation()
        val toTranslation = target.getTranslation(Vector3f())
        val translation = fromTranslation.mul(1f - t).add(Vector3f(toTranslation).mul(t))
        val fromScale = destination.getScale()
        val toScale = target.getScale(Vector3f())
        val scale = fromScale.mul(1f - t).add(Vector3f(toScale).mul(t))
        val rotation = destination.getUnnormalizedRotation(Quaternionf())
            .slerp((target as? Matrix4f ?: Matrix4f(target)).getUnnormalizedRotation(Quaternionf()), t)
        destination.identity().translate(translation).rotate(rotation).scale(scale)
    }

    companion object {
        private val liveScores = ConcurrentHashMap<String, WeakReference<DrowningScore>>()
        private val gestureQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<(DrowningScore) -> Unit>>()
    }
}
