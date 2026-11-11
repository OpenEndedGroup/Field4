package afield.graphics.scene.video

import afield.animation.keyframes.KeyframeScope
import afield.animation.keyframes.OrderedKeyframeGroup
import java.nio.file.Path
import kotlin.math.roundToInt

/** Whole-carousel looks: one source-backed rule per lane, evaluated over stable zero-based screen order. */
class StereoVideoGroupLooks(
    val directory: Path,
    private val sourceBoxId: String,
    private val carousel: () -> StereoVideoCarousel,
    private val clock: () -> VideoCarouselClock,
) {
    /** UI bounds are suggestions; the domain checks mirror the actual surface setters. */
    data class Parameter(val name: String, val min: Double, val max: Double, val help: String,
                         internal val read: (StereoVideoSurface) -> Double,
                         internal val write: (StereoVideoSurface, Double) -> Unit,
                         internal val valid: (Double) -> Boolean = { true })

    val scope = KeyframeScope(schemaVersion = 1)
    val group = OrderedKeyframeGroup({ carousel().surfaces.size }, scope, sourceBoxId) { resolved ->
        // Validation has already covered every lane and constructed every shutter before any writes.
        val flashes = flashes(resolved)
        val screens = carousel().surfaces
        parameters.forEach { p -> screens.forEachIndexed { i, screen -> p.write(screen, resolved[p.name, i]) } }
        clock().flashes = flashes
        if (!applyingTransition) {
            running = null
            lastAction = "Whole-ring rules applied"
        }
    }
    var selectedName = "initial ring"
    var restoreSpeed = false
    var transitionSeconds = 1.0
    var staggerSeconds = 0.0
    var lastAction = "Ready"
        private set
    private var applyingTransition = false
    private var running: Recall? = null
    private data class Recall(val from: KeyframeScope.Key, val to: KeyframeScope.Key, val started: Long,
                              val duration: Double, val stagger: Double)

    init {
        parameters.forEach { p -> group.lane(p.name, sampled(carousel().surfaces.map(p.read)), p.valid) }
        val flashes = clock().flashes
        fun flash(name: String, read: (VideoCarouselClock.Flash) -> Double, validate: (Double) -> Boolean) {
            group.lane(name, sampled(flashes.map(read)), validate)
        }
        flash("flash.enabled", { if (it.enabled) 1.0 else 0.0 }) { it in 0.0..1.0 }
        flash("flash.onFrames", { it.onFrames.toDouble() }) { it in 0.0..2400.0 }
        flash("flash.offFrames", { it.offFrames.toDouble() }) { it in 0.0..2400.0 }
        flash("flash.phaseFrames", { it.phaseFrames.toDouble() }) { it in -2400.0..2400.0 }
        flash("flash.onBrightness", { it.onBrightness.toDouble() }) { it in 0.0..16.0 }
        flash("flash.offBrightness", { it.offBrightness.toDouble() }) { it in 0.0..16.0 }
        group.validate { flashes(it) }
        scope.doubleTarget("playback.speed", { carousel().source.speed }) { error("Speed applies through its batch") }
        scope.applicationBatch("video-shared-speed", setOf("playback.speed")) { incoming ->
            val speed = (incoming.getValue("playback.speed") as? Number)?.toDouble()
            require(speed != null && speed.isFinite() && speed in .1..8.0) { "Playback speed must be in 0.1..8" }
            val apply: () -> Unit = { if (restoreSpeed) carousel().source.speed = speed }
            apply
        }
    }

    private fun sampled(values: List<Double>): Any = OrderedKeyframeGroup.sampledRule(sourceBoxId, values)

    private fun flashes(r: OrderedKeyframeGroup.Resolved) = List(r.size) { i ->
        try {
            VideoCarouselClock.Flash(r["flash.enabled", i] >= .5,
                r["flash.onFrames", i].roundToInt(), r["flash.offFrames", i].roundToInt(),
                r["flash.phaseFrames", i].roundToInt(), r["flash.onBrightness", i].toFloat(), r["flash.offBrightness", i].toFloat())
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid flash at screen index $i: on/off durations must total at least one frame", failure)
        }
    }

    val names: List<String> get() = scope.savedKeyNames(directory)
    fun path(name: String): Path {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean !in setOf(".", "..") && clean.none { it == '/' || it == '\\' || it.isISOControl() }) {
            "Choose a look name without path separators or control characters"
        }
        return directory.resolve(clean)
    }

    /** Save authored rules and shared speed. Mid-transition rules are also persistent. */
    fun store(name: String = selectedName): KeyframeScope.Key = scope.capture().save(path(name)).also {
        selectedName = name.trim(); lastAction = "Stored $selectedName"
    }

    /** Apply a whole key; optional global speed changes once, independently of screen stagger. */
    fun recall(name: String = selectedName) {
        require(transitionSeconds.isFinite() && transitionSeconds >= 0 && staggerSeconds.isFinite() && staggerSeconds >= 0)
        val key = scope.loadKey(path(name))
        group.resolve(key)
        val speed = key.values["playback.speed"] as? Number
        require(speed == null || speed.toDouble().let { it.isFinite() && it in .1..8.0 }) { "Invalid saved playback speed" }
        running = Recall(group.capture(), key, System.nanoTime(), transitionSeconds, staggerSeconds)
        if (restoreSpeed && speed != null) carousel().source.speed = speed.toDouble()
        selectedName = name.trim(); lastAction = "Recalling $selectedName"
        tick()
    }

    /** Run on the control loop; this never decodes video, reads GPU pixels or waits for a frame fence. */
    fun tick(now: Long = System.nanoTime()) {
        val recall = running ?: return
        val elapsed = (now - recall.started) / 1e9
        try {
            applyingTransition = true
            group.apply(group.transition(recall.from, recall.to, elapsed, recall.duration, recall.stagger))
            if (elapsed >= recall.duration + (carousel().surfaces.size - 1) * recall.stagger) {
                group.apply(scope.Key(recall.to.values.filterKeys { it != "playback.speed" }.toMutableMap()))
                running = null; lastAction = "Applied $selectedName"
            }
        } catch (failure: Exception) {
            running = null
            lastAction = "Recall stopped: ${failure.message}"
            throw failure
        } finally {
            applyingTransition = false
        }
    }

    fun cancel() { running = null; lastAction = "Transition held" }

    /** Import deliberate manual edits from the legacy per-screen widgets, replacing rules with sampled lookups. */
    fun importCurrentScreens() {
        cancel()
        val current = StereoVideoGroupLooks(directory, sourceBoxId, carousel, clock).group.capture()
        group.apply(current)
        lastAction = "Imported current screens; identical values became constants"
    }

    companion object {
        val parameters = listOf(
            Parameter("exposure.mix", 0.0, 1.0, "Sharp to accumulated video blend; zero bypasses history unless colour transfer needs it.", { it.accumulationMix.toDouble() }, { s, v -> s.accumulationMix = v.toFloat() }, { it in 0.0..1.0 }),
            Parameter("exposure.halfLife", 0.0, 60.0, "Seconds for old accumulation to halve. Zero replaces history immediately.", { it.accumulationHalfLife }, { s, v -> s.accumulationHalfLife = v }, { it in 0.0..60.0 }),
            Parameter("exposure.inputWeight", 0.0, 10.0, "Weight of each new video contribution before accumulation.", { it.accumulationInputWeight }, { s, v -> s.accumulationInputWeight = v }, { it in 0.0..100.0 }),
            Parameter("cadence.enabled", 0.0, 1.0, "At 0.5 or above enable alternating soft/dark and clear halves.", { if (it.exposureCadenceEnabled) 1.0 else 0.0 }, { s, v -> s.exposureCadenceEnabled = v >= .5 }, { it in 0.0..1.0 }),
            Parameter("cadence.pairsPerSecond", 1.0, 30.0, "Real-time soft/clear pairs per second, independent of movie speed.", { it.exposurePairsPerSecond }, { s, v -> s.exposurePairsPerSecond = v }, { it in 1.0..30.0 }),
            Parameter("cadence.blurSigma", 0.0, 16.0, "Soft-half Gaussian radius in source pixels, separately filtered per eye.", { it.exposureBlurSigma.toDouble() }, { s, v -> s.exposureBlurSigma = v.toFloat() }, { it in 0.0..16.0 }),
            Parameter("cadence.blurMix", 0.0, 1.0, "Spatial blur blend in the soft half; the clear half is unchanged.", { it.exposureBlurMix.toDouble() }, { s, v -> s.exposureBlurMix = v.toFloat() }, { it in 0.0..1.0 }),
            Parameter("cadence.darkening", 0.0, 1.0, "Linear-light fraction removed during the soft half.", { it.exposureDarkening.toDouble() }, { s, v -> s.exposureDarkening = v.toFloat() }, { it in 0.0..1.0 }),
            Parameter("appearance.opacity", 0.0, 2.0, "Geometry coverage after the image treatment; independent of flash brightness.", { it.opacity.toDouble() }, { s, v -> s.opacity = v.toFloat() }, { it.toFloat().isFinite() }),
            Parameter("presentation.black", -1.0, 2.0, "Manual accumulated-image black endpoint, blended with the automatic endpoint.", { it.accumulationBlackLevel.toDouble() }, { s, v -> s.accumulationBlackLevel = v.toFloat() }, { it.toFloat().isFinite() }),
            Parameter("presentation.white", 0.0, 4.0, "Manual accumulated-image white endpoint; changes presentation, not stored pixels.", { it.accumulationWhiteLevel.toDouble() }, { s, v -> s.accumulationWhiteLevel = v.toFloat() }, { it.toFloat().isFinite() }),
            Parameter("presentation.gamma", .05, 4.0, "Accumulation presentation gamma; above one brightens midtones.", { it.accumulationGamma.toDouble() }, { s, v -> s.accumulationGamma = v.toFloat() }, { it.toFloat().let { f -> f.isFinite() && f > 0f } }),
            Parameter("presentation.autoMix", 0.0, 1.0, "Per-screen blend into the shared auto-exposure system.", { it.autoExposureMix.toDouble() }, { s, v -> s.autoExposureMix = v.toFloat() }, { it in 0.0..1.0 }),
            Parameter("presentation.colourTransfer", 0.0, 1.0, "Borrow accumulation hue and saturation while retaining sharp-video luminance.", { it.accumulationColourTransfer.toDouble() }, { s, v -> s.accumulationColourTransfer = v.toFloat() }, { it in 0.0..1.0 }),
            Parameter("input.shadowCooling", 0.0, 1.0, "Cool shadows before they enter temporal history; existing history remains.", { it.accumulationShadowCooling.toDouble() }, { s, v -> s.accumulationShadowCooling = v.toFloat() }, { it in 0.0..1.0 }),
            Parameter("output.shadowCooling", 0.0, 1.0, "Cool shadows after sharp/accumulated blending, preserving luminance.", { it.shadowCooling.toDouble() }, { s, v -> s.shadowCooling = v.toFloat() }, { it in 0.0..1.0 }),
        )
    }
}
