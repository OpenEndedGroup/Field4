package drowning

import afield.stdlib.KeyframeScope
import afield.stdlib.KeyframeContributor
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
    val videoGain = DrowningEnvelope(1f)

    internal var cadenceShutter = 1f
    internal var cadenceVideoGain = 1f

    /** Resources remain resident while a retirement fade is in flight. */
    var resident: Boolean = true
        private set
    var resourceWeight: Float = 1f
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
        resourceWeight = 1f
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
            particleInfluence.isSettledAt(0f) && projectedLight.isSettledAt(0f)) {
            resident = false
            resourceWeight = 0f
        }
    }

    internal fun setResourceWeight(value: Float) {
        resourceWeight = value.coerceIn(0f, 1f)
        wantsResident = resourceWeight > 0f
        resident = wantsResident
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

/** One discrete step in a render-frame cadence; [rotateDegrees] fires on the step's first frame. */
data class DrowningCadenceFrame(
    val frames: Int = 1,
    val videoGain: Float = 1f,
    val shutter: Float = 1f,
    val rotateDegrees: Float = 0f
) {
    init {
        require(frames > 0) { "cadence frames must be positive" }
        require(videoGain.isFinite() && videoGain >= 0f) {
            "cadence video gain must be finite and non-negative"
        }
        require(shutter.isFinite() && shutter in 0f..1f) { "cadence shutter must be between zero and one" }
        require(rotateDegrees.isFinite()) { "cadence rotation must be finite" }
    }
}

fun cadenceFrame(
    frames: Int = 1,
    videoGain: Float = 1f,
    shutter: Float = 1f,
    rotateDegrees: Float = 0f
) = DrowningCadenceFrame(frames, videoGain, shutter, rotateDegrees)

data class DrowningCadenceSample(
    val videoGain: Float,
    val shutter: Float,
    val rotationDegrees: Double,
    val stepIndex: Int,
    val frameInStep: Int
)

/**
 * A periodic device-frame score. Rotation steps accumulate analytically across cycles, so every
 * rendered frame has an absolute result even if the asset transport holds, skips or seeks.
 */
class DrowningFrameCadence(vararg frames: DrowningCadenceFrame) {
    val steps = frames.toList()
    val frameCount: Int
    val cycleRotationDegrees: Double

    init {
        require(steps.isNotEmpty()) { "a frame cadence needs at least one step" }
        frameCount = steps.sumOf { it.frames }
        cycleRotationDegrees = steps.sumOf { it.rotateDegrees.toDouble() }
    }

    /** A positive [phaseFrames] delays this instance relative to render frame zero. */
    fun sample(renderFrame: Long, phaseFrames: Int = 0): DrowningCadenceSample {
        val localFrame = renderFrame - phaseFrames.toLong()
        val cycle = Math.floorDiv(localFrame, frameCount.toLong())
        val within = Math.floorMod(localFrame, frameCount.toLong()).toInt()
        var accumulatedRotation = cycle * cycleRotationDegrees
        var cursor = 0
        steps.forEachIndexed { index, step ->
            if (within >= cursor) accumulatedRotation += step.rotateDegrees
            if (within < cursor + step.frames) {
                return DrowningCadenceSample(
                    step.videoGain,
                    step.shutter,
                    accumulatedRotation,
                    index,
                    within - cursor
                )
            }
            cursor += step.frames
        }
        error("cadence frame $within escaped $frameCount frames")
    }
}

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

    /** Smooth base gain for projected RGB. Frame-cadence gain multiplies this value. */
    fun video(to: Float, seconds: Double = 0.0) = apply {
        score.submitGesture { live -> indices.forEach { live[it].videoGain.to(to, seconds, live.time) } }
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

    /**
     * Apply one absolute device-render cadence. Put this in [DrowningScore.script] so it is evaluated
     * once per render independently of asset playback. Successive members receive phase delays.
     */
    fun cadence(
        pattern: DrowningFrameCadence,
        staggerFrames: Int = 0,
        rotationScale: Float = 1f,
        reference: DrowningPose = score.home,
        pivot: Vector3f? = null,
        axis: Vector3f = Vector3f(0f, 1f, 0f)
    ) = apply {
        score.applyCadence(indices, pattern, staggerFrames, rotationScale, reference, pivot, axis)
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
) : KeyframeContributor {
    val cells = cue.cells.map { DrowningScoreCell(it.index, it.modelFromCamera) }
    val poses = linkedMapOf<String, DrowningPose>()
    val home: DrowningPose
    /** One camera-ray depth curve shared by MXG, all splat shades and video/depth interaction. */
    val depthRemap = DrowningDepthRemap()
    var time: Double = 0.0
        private set
    /** Absolute device-render frame supplied by the owner, independent of source time. */
    var renderFrame: Long = 0L
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
    val sourceFrame: Long get() = floor(time * cue.fps + 1e-6).toLong()

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

    /** Change the shared depth curve without touching the 15 screen placements. */
    fun remapDepth(
        pivot: Float = live.depthRemap.pivot,
        range: Float = live.depthRemap.range,
        power: Float = live.depthRemap.power,
        amount: Float = live.depthRemap.amount
    ): DrowningScore = apply {
        submitGesture { it.depthRemap.set(pivot, range, power, amount) }
    }

    fun clearDepthRemap(): DrowningScore = apply {
        submitGesture { it.depthRemap.clear() }
    }

    /**
     * A complete, persistent keyframer view of all 15 score cells and the shared depth curve. Each
     * cell contributes position, rotation and scale plus opacity, particle influence, projected
     * light, resource residency and every flicker control. The values are all ordinary
     * [KeyframeScope] persistence types, so two captures can be interpolated, saved with version
     * history, loaded and applied without a second score-file format.
     *
     * Transform components are separate deliberately: positions and scales lerp while rotations
     * slerp. The `resource` float remains above zero throughout an ordinary 1-to-0 blend, keeping
     * sparse decoders alive until the fully retired endpoint is applied.
     */
    fun keyframes(schemaVersion: Int = 4, prefix: String = "cell"): KeyframeScope =
        KeyframeScope(schemaVersion).also { scope ->
            registerKeyframeTargets(scope, cellPrefix = prefix, globalPrefix = "")
        }

    /** Contribute this score to a larger scope; [prefix] qualifies both `cell.*` and `depth.*`. */
    override fun registerKeyframes(scope: KeyframeScope, prefix: String) {
        registerKeyframeTargets(
            scope,
            cellPrefix = qualifyKeyframe(prefix, "cell"),
            globalPrefix = prefix
        )
    }

    private fun registerKeyframeTargets(
        scope: KeyframeScope,
        cellPrefix: String,
        globalPrefix: String
    ) {
            cells.indices.forEach { index ->
                val stem = "$cellPrefix.${index.toString().padStart(2, '0')}"
                scope.vector3Target("$stem.position",
                    get = { live[index].modelFromCamera.getTranslation(Vector3f()) },
                    set = { position ->
                        submitGesture { it.setTransform(index, position = position) }
                    })
                scope.target("$stem.rotation",
                    get = { live[index].modelFromCamera.getUnnormalizedRotation(Quaternionf()).normalize() },
                    set = { value ->
                        val rotation = Quaternionf(value.requireKeyType<Quaternionf>("$stem.rotation")).normalize()
                        submitGesture { it.setTransform(index, rotation = rotation) }
                    })
                scope.vector3Target("$stem.scale",
                    get = { live[index].modelFromCamera.getScale(Vector3f()) },
                    set = { scale ->
                        submitGesture { it.setTransform(index, scale = scale) }
                    })
                scope.floatTarget("$stem.opacity",
                    get = { live[index].opacity.value },
                    set = { value -> submitGesture { it[index].opacity.set(value) } })
                scope.floatTarget("$stem.effects",
                    get = { live[index].particleInfluence.value },
                    set = { value -> submitGesture { it[index].particleInfluence.set(value) } })
                scope.floatTarget("$stem.light",
                    get = { live[index].projectedLight.value },
                    set = { value -> submitGesture { it[index].projectedLight.set(value) } })
                scope.floatTarget("$stem.videoGain",
                    get = { live[index].videoGain.value },
                    set = { value -> submitGesture { it[index].videoGain.set(value) } })
                scope.floatTarget("$stem.flicker.depth",
                    get = { live[index].flickerDepth },
                    set = { value -> submitGesture { it[index].flickerDepth = value } })
                scope.floatTarget("$stem.flicker.hz",
                    get = { live[index].flickerHz },
                    set = { value -> submitGesture { it[index].flickerHz = value } })
                scope.floatTarget("$stem.flicker.duty",
                    get = { live[index].flickerDuty },
                    set = { value -> submitGesture { it[index].flickerDuty = value } })
                scope.floatTarget("$stem.flicker.phase",
                    get = { live[index].flickerPhase },
                    set = { value -> submitGesture { it[index].flickerPhase = value } })
                scope.floatTarget("$stem.flicker.softness",
                    get = { live[index].flickerSoftness },
                    set = { value -> submitGesture { it[index].flickerSoftness = value } })
                scope.floatTarget("$stem.resource",
                    get = { live[index].resourceWeight },
                    set = { value -> submitGesture { it[index].setResourceWeight(value) } })
            }
            val depth = qualifyKeyframe(globalPrefix, "depth")
            scope.floatTarget("$depth.pivot",
                get = { live.depthRemap.pivot },
                set = { value -> submitGesture { it.depthRemap.set(pivot = value) } })
            scope.floatTarget("$depth.range",
                get = { live.depthRemap.range },
                set = { value -> submitGesture { it.depthRemap.set(range = value) } })
            scope.floatTarget("$depth.power",
                get = { live.depthRemap.power },
                set = { value -> submitGesture { it.depthRemap.set(power = value) } })
            scope.floatTarget("$depth.amount",
                get = { live.depthRemap.amount },
                set = { value -> submitGesture { it.depthRemap.set(amount = value) } })
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

    internal fun applyCadence(
        indices: IntArray,
        pattern: DrowningFrameCadence,
        staggerFrames: Int,
        rotationScale: Float,
        reference: DrowningPose,
        pivot: Vector3f?,
        axis: Vector3f
    ) {
        val rotationPivot = pivot?.let(::Vector3f) ?: centre(reference)
        val rotationAxis = Vector3f(axis)
        require(rotationAxis.lengthSquared() > 1e-8f) { "cadence rotation axis must be non-zero" }
        rotationAxis.normalize()
        indices.forEachIndexed { order, index ->
            val sample = pattern.sample(renderFrame, order * staggerFrames)
            // A cadence can run for hours; reduce the equivalent orientation before converting to
            // Float so a monotonically increasing renderer frame never costs rotational precision.
            val degrees = Math.IEEEremainder(sample.rotationDegrees * rotationScale, 360.0)
            val radians = (degrees * PI / 180.0).toFloat()
            cells[index].modelFromCamera
                .set(Matrix4f().translate(rotationPivot).rotate(radians, rotationAxis)
                    .translate(-rotationPivot.x, -rotationPivot.y, -rotationPivot.z))
                .mul(reference.matrices[index])
            cells[index].cadenceShutter = sample.shutter
            cells[index].cadenceVideoGain = sample.videoGain
        }
    }

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

    fun update(time: Double, renderFrame: Long) {
        liveScores[sessionKey] = WeakReference(this)
        this.time = time
        this.renderFrame = renderFrame
        updating = true
        try {
            cells.forEach {
                it.cadenceShutter = 1f
                it.cadenceVideoGain = 1f
            }
            gestureQueues[sessionKey]?.let { queue ->
                while (true) (queue.poll() ?: break)(this)
            }
            script?.invoke(this, time)
            cue.depthRemap.set(depthRemap)
            cells.indices.forEach { i ->
                val scored = cells[i]
                val cell = cue.cells[i]
                cell.modelFromCamera.set(scored.modelFromCamera)
                cell.cameraFromModel.set(scored.modelFromCamera).invert()
                cell.opacity = scored.opacity.sample(time)
                cell.particleInfluence = scored.particleInfluence.sample(time)
                cell.projectedLight = scored.projectedLight.sample(time)
                cell.flicker = scored.shutter(time) * scored.cadenceShutter
                cell.videoGain = scored.videoGain.sample(time) * scored.cadenceVideoGain
                scored.finishLifecycle()
            }
        } finally {
            updating = false
        }
    }

    /** Compatibility tick for non-renderer tests and old snippets; live renderers should pass theirs. */
    @Deprecated("Pass the owning renderer's frameCounter so cadence follows the device")
    fun update(time: Double) = update(time, renderFrame + 1L)

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

    private fun setTransform(
        index: Int,
        position: Vector3f? = null,
        rotation: Quaternionf? = null,
        scale: Vector3f? = null
    ) {
        val matrix = cells[index].modelFromCamera
        val p = position ?: matrix.getTranslation(Vector3f())
        val r = rotation ?: matrix.getUnnormalizedRotation(Quaternionf()).normalize()
        val s = scale ?: matrix.getScale(Vector3f())
        matrix.identity().translate(p).rotate(r).scale(s)
    }

    companion object {
        private val liveScores = ConcurrentHashMap<String, WeakReference<DrowningScore>>()
        private val gestureQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<(DrowningScore) -> Unit>>()
    }
}

private inline fun <reified T : Any> Any?.requireKeyType(name: String): T =
    this as? T ?: error("score key '$name' needs ${T::class.simpleName}, got ${this?.let { it::class.simpleName } ?: "null"}")

private fun qualifyKeyframe(prefix: String, name: String): String =
    prefix.trim('.').takeIf { it.isNotEmpty() }?.let { "$it.$name" } ?: name
