import afield.graphics.scene.video.VideoAutoExposure
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

// The controls remain usable before the renderer exists. Bind to a new carousel on access/relaunch.
prop<String>(videoExposureSettings) = (thisBox.getPropertyRaw("videoExposureSettings") as? String) ?: ""
val exposureJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
var exposureControlError = ""
fun currentExposureMeter(): VideoAutoExposure? = runCatching { carousel.autoExposure }.getOrNull()
var attachedExposureMeter = currentExposureMeter()
var pendingExposureSettings = attachedExposureMeter?.settings ?: VideoAutoExposure.Settings()
var resetExposureOnAttach = false
if (videoExposureSettings.isNotBlank()) runCatching {
    pendingExposureSettings = exposureJson.decodeFromString<VideoAutoExposure.Settings>(videoExposureSettings)
    attachedExposureMeter?.settings = pendingExposureSettings
}.onFailure { exposureControlError = "Could not restore exposure settings: ${it.message}" }
fun exposureSettings() = attachedExposureMeter?.settings ?: pendingExposureSettings
fun changeExposure(change: (VideoAutoExposure.Settings) -> VideoAutoExposure.Settings) {
    runCatching {
        val next = change(exposureSettings())
        pendingExposureSettings = next
        attachedExposureMeter?.settings = next
        videoExposureSettings = exposureJson.encodeToString(next)
        exposureControlError = ""
    }.onFailure { exposureControlError = it.message ?: it.javaClass.simpleName }
}
val exposureMirrors = mutableListOf<() -> Unit>()
fun afield.ui.output.ControlGroupBuilder.exposureSlider(label: String, min: Double, max: Double, step: Double,
    get: (VideoAutoExposure.Settings) -> Double, help: String,
    set: (VideoAutoExposure.Settings, Double) -> VideoAutoExposure.Settings) {
    val value = get(exposureSettings())
    val handle = slider(label, min = minOf(min, value), max = maxOf(max, value), step = step,
        initialValue = value, hoverText = help) { v -> changeExposure { set(it, v) } }
    exposureMirrors += { handle.set(get(exposureSettings())) }
}
controlGroup("meter and exposure mode") {
    exposureSlider("Global pre-exposure gamma", .05, 8.0, .001, { it.preExposureGamma.toDouble() },
        "Global power curve before black/white exposure, applied equally to every accumulated screen and the GPU meter: RGB = max(RGB, 0)^(1/gamma). One is neutral; above one lifts midtones. This also works in manual mode. Changing it remeasures held buffers and resets the trackers in the new domain. Does not modify accumulation history or sharp video; per-screen Presentation gamma follows exposure.") { s, v -> s.copy(preExposureGamma = v.toFloat()) }
    checkbox("Update GPU meter", initialValue = exposureSettings().enabled,
        hoverText = "Measure accumulation buffers after Global pre-exposure gamma, before black/white exposure, blur cadence, flashing and opacity. Off freezes existing statistics and tracker state; an enabled auto mode can still use those frozen endpoints. With no valid buffer, auto exposure falls back to manual levels.") { v -> changeExposure { it.copy(enabled = v) } }
        .also { h -> exposureMirrors += { h.set(exposureSettings().enabled) } }
    button("Manual levels", hoverText = "Use each screen's manual black and white levels. Meter updates remain independently controlled by the checkbox.") { down ->
        if (down) changeExposure { it.copy(mode = VideoAutoExposure.Mode.OFF) }
    }
    button("Auto: per screen", hoverText = "Enable metering and derive separate black/white points from each buffer's tracked luminance extrema. Each screen's Auto exposure mix blends those with its manual endpoints; gamma stays manual.") { down ->
        if (down) changeExposure { it.copy(enabled = true, mode = VideoAutoExposure.Mode.PER_SCREEN) }
    }
    button("Auto: linked screens", hoverText = "Enable metering and share one exposure derived from pooled raw min/max and pixel-weighted mean over every valid accumulation buffer, including hidden or frozen buffers. The linked alpha-beta tracker is independent of the per-screen trackers.") { down ->
        if (down) changeExposure { it.copy(enabled = true, mode = VideoAutoExposure.Mode.LINKED) }
    }
    exposureSlider("Global auto mix", 0.0, 1.0, .01, { it.mix.toDouble() },
        "Blend automatic endpoints with manual black/white points, multiplied by each screen's local auto mix. Zero keeps manual levels while metering may continue.") { s, v -> s.copy(mix = v.toFloat()) }
    exposureSlider("Meter samples / second", .5, 60.0, .5, { it.meterHz },
        "Alpha-beta measurement cadence, independent of headset/video rate. Only changed accumulation images are scanned; cached measurements still correct the trackers at this cadence. Between samples the GPU predicts from retained velocities. Changing cadence changes the response of the per-sample gains.") { s, v -> s.copy(meterHz = v) }
    button("Reset exposure trackers", hoverText = "Seed min, mean and max from the next measurement and zero their velocities. Does not clear or modify the accumulation images.") { down -> if (down) { attachedExposureMeter?.reset(); resetExposureOnAttach = attachedExposureMeter == null } }
}
controlGroup("directional alpha-beta") {
    exposureSlider("Rising alpha", 0.0, 1.0, .001, { it.risingAlpha.toDouble() },
        "Level correction gain for rising measurements, applied independently to min, mean and max in RGB and luminance. The update matches Field's AlphaBetaFilter. On flat measurements, the prediction residual selects the pair so velocity can settle.") { s, v -> s.copy(risingAlpha = v.toFloat()) }
    exposureSlider("Rising beta", 0.0, 1.0, .001, { it.risingBeta.toDouble() },
        "Velocity correction gain for rising measurements: velocity += beta * residual / sample dt. The tracker predicts level += velocity * dt before correcting. This is applied per meter sample, not per headset frame.") { s, v -> s.copy(risingBeta = v.toFloat()) }
    exposureSlider("Falling alpha", 0.0, 1.0, .001, { it.fallingAlpha.toDouble() },
        "Level correction gain for falling measurements. Tune independently of rising alpha for asymmetric attack/release behaviour; every statistic retains its own level and velocity.") { s, v -> s.copy(fallingAlpha = v.toFloat()) }
    exposureSlider("Falling beta", 0.0, 1.0, .001, { it.fallingBeta.toDouble() },
        "Velocity correction gain for falling measurements. A measurement step chooses rising/falling; on a constant signal the residual chooses the correction direction.") { s, v -> s.copy(fallingBeta = v.toFloat()) }
    exposureSlider("Velocity cap (0 = free)", 0.0, 16.0, .01, { it.maximumVelocity.toDouble() },
        "Optional magnitude limit on every tracker velocity, in linear-light units/second. Zero leaves alpha-beta prediction unconstrained. Endpoint bounds and maximum gain remain active either way.") { s, v -> s.copy(maximumVelocity = v.toFloat()) }
}
controlGroup("auto endpoint margins and bounds") {
    exposureSlider("Black padding", -2.0, 2.0, .001, { it.blackPadding.toDouble() },
        "Black candidate = tracked minimum luminance minus this padding. Positive reserves footroom below the darkest measurement; negative crushes shadows. The black-point bounds then limit it.") { s, v -> s.copy(blackPadding = v.toFloat()) }
    exposureSlider("White padding", -2.0, 2.0, .001, { it.whitePadding.toDouble() },
        "White candidate = tracked maximum luminance plus this padding. Positive reserves highlight headroom; negative increases contrast by clipping more highlights. White bounds and maximum gain then limit it.") { s, v -> s.copy(whitePadding = v.toFloat()) }
    exposureSlider("Black point minimum", -2.0, 4.0, .001, { it.blackMinimum.toDouble() },
        "Lowest allowed automatic black point. Must not exceed black maximum, and must leave the minimum endpoint span below white maximum. Invalid edits retain the prior settings and report the reason.") { s, v -> s.copy(blackMinimum = v.toFloat()) }
    exposureSlider("Black point maximum", -2.0, 4.0, .001, { it.blackMaximum.toDouble() },
        "Highest allowed automatic black point, limiting how aggressively bright/foggy images are pulled down. The minimum span may impose a lower upper bound.") { s, v -> s.copy(blackMaximum = v.toFloat()) }
    exposureSlider("White point minimum", -2.0, 8.0, .001, { it.whiteMinimum.toDouble() },
        "Lowest allowed automatic white point. Raising this avoids excessive brightening of dark or nearly uniform buffers.") { s, v -> s.copy(whiteMinimum = v.toFloat()) }
    exposureSlider("White point maximum", -2.0, 8.0, .001, { it.whiteMaximum.toDouble() },
        "Highest allowed automatic white point. Must remain above black minimum by at least the minimum span. Bounds apply after prediction, even when the alpha-beta state overshoots.") { s, v -> s.copy(whiteMaximum = v.toFloat()) }
    exposureSlider("Maximum contrast gain", .25, 100.0, .01, { 1.0 / it.minimumSpan },
        "Caps reciprocal endpoint span: white - black >= 1 / this value. Prevents a flat buffer or crossed predictions from amplifying tiny differences without limit. Gamma and flash brightness are separate.") { s, v -> s.copy(minimumSpan = (1.0 / v).toFloat()) }
}
displayOpen("meter and exposure mode")
displayOpen("directional alpha-beta")
displayOpen("auto endpoint margins and bounds")
displayOpen("meter status")
var lastExposureUI = 0L
var lastExposureStatus = ""
loop {
    val now = System.nanoTime()
    if (now - lastExposureUI > 250_000_000L) {
        lastExposureUI = now
        val meter = currentExposureMeter()
        if (meter !== attachedExposureMeter) {
            if (meter != null && videoExposureSettings.isNotBlank()) meter.settings = pendingExposureSettings
            if (meter != null && resetExposureOnAttach) { meter.reset(); resetExposureOnAttach = false }
            attachedExposureMeter = meter
        }
        val settings = exposureSettings()
        pendingExposureSettings = settings
        exposureMirrors.forEach { it() }
        val snapshot = meter?.latest
        val rows = snapshot?.let { snap ->
            (snap.screens.mapIndexed { i, v -> "Screen ${i + 1}" to v } + ("Linked" to snap.linked)).joinToString("\n") { (name, v) ->
                if (!v.valid) "| $name | — | — | — | — | — |"
                else "| $name | ${v.minimum[3].f3} | ${v.mean[3].f3} | ${v.maximum[3].f3} | ${v.black.f3} | ${v.white.f3} |"
            }
        } ?: "| No completed measurements | — | — | — | — | — |"
        val exposureStatus = """
### ${if (meter == null) "Renderer stopped · settings ready" else settings.mode.toString()} · meter ${if (settings.enabled) "updating" else "frozen / off"}

${exposureControlError.ifEmpty { meter?.error ?: "" }}

**${meter?.measurements ?: 0}** measurement batches · **${meter?.tileDispatches ?: 0}** image scans · ${settings.meterHz.f2} samples/s.

| Source | Filtered min | Filtered mean | Filtered max | Auto black | Auto white |
| --- | --- | --- | --- | --- | --- |
$rows

Settings can be edited while the renderer is stopped and apply when the carousel starts.
Luminance shown; RGB statistics and velocities are also available in `carousel.autoExposure.latest`.
Automatic endpoints include inter-sample prediction, margins and bounds, before global/local mix.
No accumulation or a zero accumulation mix in a fresh screen means there is no buffer to meter yet.
        """.trimIndent()
        // Replacing unchanged DOM repeatedly retriggers content autosizing. Keep this card's width stable.
        if (exposureStatus != lastExposureStatus) {
            markdown("meter status", exposureStatus, width = 680, open = false)
            lastExposureStatus = exposureStatus
        }
    }
    frame()
}
