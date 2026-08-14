@file:afield.CompletionCategory("Synthesis")
package afield.four.synth

import afield.graphquery.LiveGraphEdge
import afield.graphquery.LiveGraphInspectable
import afield.Box
import afield.RunsAt
import afield.newRoot
import afield.registerValuePrinter
import kotlinx.coroutines.*
import java.util.*
import kotlin.math.*

private const val SYNTH_BLUE = "#0af"
private const val FIELD_ORANGE = "#f80"
private const val DEFAULT_BACKGROUND = "#000000"
private const val DEFAULT_FONT = "\"Iosevka Web\", \"IBM Plex Mono\", monospace"

internal class ScalarTrace(
    private val capacity: Int = 4096,
    private val stride: Int = 1
) {
    private val values = FloatArray(capacity.coerceAtLeast(1))
    @Volatile private var writeIndex = 0
    @Volatile private var size = 0
    private var strideCounter = 0

    fun record(value: Float) {
        strideCounter++
        if (strideCounter < stride) return
        strideCounter = 0

        values[writeIndex] = value
        writeIndex = (writeIndex + 1) % values.size
        if (size < values.size) size++
    }

    fun snapshot(): FloatArray {
        val count = size
        if (count <= 0) return FloatArray(0)

        val out = FloatArray(count)
        val start = (writeIndex - count + values.size) % values.size
        val first = min(count, values.size - start)
        System.arraycopy(values, start, out, 0, first)
        if (first < count) {
            System.arraycopy(values, 0, out, first, count - first)
        }
        return out
    }

    fun clear() {
        writeIndex = 0
        size = 0
        strideCounter = 0
        values.fill(0f)
    }
}

internal class StereoTrace(
    private val capacity: Int = 4096,
    private val stride: Int = 1
) {
    private val left = FloatArray(capacity.coerceAtLeast(1))
    private val right = FloatArray(capacity.coerceAtLeast(1))
    @Volatile private var writeIndex = 0
    @Volatile private var size = 0
    private var strideCounter = 0

    fun recordMono(values: FloatArray, numFrames: Int) {
        for (i in 0 until numFrames) {
            strideCounter++
            if (strideCounter < stride) continue
            strideCounter = 0
            left[writeIndex] = values[i]
            right[writeIndex] = values[i]
            advance()
        }
    }

    fun recordStereo(leftValues: FloatArray, rightValues: FloatArray, numFrames: Int) {
        for (i in 0 until numFrames) {
            strideCounter++
            if (strideCounter < stride) continue
            strideCounter = 0
            left[writeIndex] = leftValues[i]
            right[writeIndex] = rightValues[i]
            advance()
        }
    }

    private fun advance() {
        writeIndex = (writeIndex + 1) % left.size
        if (size < left.size) size++
    }

    fun snapshot(sampleRate: Int): AudioSnapshot {
        val count = size
        if (count <= 0) return AudioSnapshot(FloatArray(0), null, sampleRate)

        val leftOut = FloatArray(count)
        val rightOut = FloatArray(count)
        val start = (writeIndex - count + left.size) % left.size
        val first = min(count, left.size - start)
        System.arraycopy(left, start, leftOut, 0, first)
        System.arraycopy(right, start, rightOut, 0, first)
        if (first < count) {
            System.arraycopy(left, 0, leftOut, first, count - first)
            System.arraycopy(right, 0, rightOut, first, count - first)
        }
        return AudioSnapshot(leftOut, rightOut, sampleRate)
    }

    fun clear() {
        writeIndex = 0
        size = 0
        strideCounter = 0
        left.fill(0f)
        right.fill(0f)
    }
}

data class AudioSnapshot(
    val left: FloatArray,
    val right: FloatArray? = null,
    val sampleRate: Int = 48_000
) {
    val frameCount: Int
        get() = if (right == null) left.size else min(left.size, right.size)

    val isStereo: Boolean
        get() = right != null

    fun waveformSvg(
        widthPx: Int = 480,
        heightPx: Int = 96,
        title: String = if (isStereo) "Waveform (Stereo)" else "Waveform",
        background: String = DEFAULT_BACKGROUND,
        primaryColor: String = SYNTH_BLUE,
        secondaryColor: String = FIELD_ORANGE,
        fontFamily: String = DEFAULT_FONT
    ): String = renderWaveformSvg(
        snapshot = this,
        widthPx = widthPx,
        heightPx = heightPx,
        title = title,
        background = background,
        primaryColor = primaryColor,
        secondaryColor = secondaryColor,
        fontFamily = fontFamily
    )

    fun spectrumSvg(
        widthPx: Int = 480,
        heightPx: Int = 96,
        title: String = if (isStereo) "Spectrum (Stereo)" else "Spectrum",
        background: String = DEFAULT_BACKGROUND,
        primaryColor: String = SYNTH_BLUE,
        secondaryColor: String = FIELD_ORANGE,
        fontFamily: String = DEFAULT_FONT
    ): String = renderSpectrumSvg(
        snapshot = this,
        widthPx = widthPx,
        heightPx = heightPx,
        title = title,
        background = background,
        primaryColor = primaryColor,
        secondaryColor = secondaryColor,
        fontFamily = fontFamily
    )

    fun summaryHtml(
        widthPx: Int = 640,
        waveformHeightPx: Int = 96,
        spectrumHeightPx: Int = 96,
        background: String = DEFAULT_BACKGROUND,
        primaryColor: String = SYNTH_BLUE,
        secondaryColor: String = FIELD_ORANGE,
        fontFamily: String = DEFAULT_FONT
    ): String {
        val gap = 8
        val panelWidth = ((widthPx - gap) / 2).coerceAtLeast(180)
        return renderPanelGridHtml(
            listOf(
                waveformSvg(
                    widthPx = panelWidth,
                    heightPx = waveformHeightPx,
                    background = background,
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor,
                    fontFamily = fontFamily
                ),
                spectrumSvg(
                    widthPx = panelWidth,
                    heightPx = spectrumHeightPx,
                    background = background,
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor,
                    fontFamily = fontFamily
                )
            ),
            widthPx = widthPx,
            gapPx = gap
        )
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "frameCount" to frameCount,
        "isStereo" to isStereo,
        "sampleRate" to sampleRate
    )
}

class Scope(
    historySize: Int = 4096
) : SignalProcessor() {
    internal val trace = StereoTrace(capacity = historySize, stride = 1)
    @Volatile internal var sampleRateHint: Int = 48_000

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        input.render(output, numFrames, ctx)
        sampleRateHint = ctx.sampleRate.roundToInt()
        trace.recordMono(output, numFrames)
    }
}

class StereoScope(
    private val source: StereoRenderable,
    historySize: Int = 4096
) : StereoNode(), LiveGraphInspectable {
    internal val trace = StereoTrace(capacity = historySize, stride = 1)
    @Volatile internal var sampleRateHint: Int = 48_000

    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        source.render(outL, outR, numFrames, ctx)
        sampleRateHint = ctx.sampleRate.roundToInt()
        trace.recordStereo(outL, outR, numFrames)
    }

    override fun liveGraphChildren(): Iterable<LiveGraphEdge> = listOf(
        LiveGraphEdge("source") { source }
    )
}

fun Signal.scope(historySize: Int = 4096): Scope = Scope(historySize).also { it.input = this }

fun StereoRenderable.scope(historySize: Int = 4096): StereoScope = StereoScope(this.hold(), historySize)

fun AudioPatchSpec.snapshot(): AudioSnapshot = visualTrace.snapshot(visualSampleRate)

fun AudioPatchSpec.waveformSvg(
    widthPx: Int = 480,
    heightPx: Int = 96,
    title: String = "Waveform (Patch)",
    background: String = DEFAULT_BACKGROUND,
    primaryColor: String = SYNTH_BLUE,
    secondaryColor: String = FIELD_ORANGE,
    fontFamily: String = DEFAULT_FONT
): String = snapshot().waveformSvg(
    widthPx = widthPx,
    heightPx = heightPx,
    title = title,
    background = background,
    primaryColor = primaryColor,
    secondaryColor = secondaryColor,
    fontFamily = fontFamily
)

fun AudioPatchSpec.spectrumSvg(
    widthPx: Int = 480,
    heightPx: Int = 96,
    title: String = "Spectrum (Patch)",
    background: String = DEFAULT_BACKGROUND,
    primaryColor: String = SYNTH_BLUE,
    secondaryColor: String = FIELD_ORANGE,
    fontFamily: String = DEFAULT_FONT
): String = snapshot().spectrumSvg(
    widthPx = widthPx,
    heightPx = heightPx,
    title = title,
    background = background,
    primaryColor = primaryColor,
    secondaryColor = secondaryColor,
    fontFamily = fontFamily
)

fun AudioPatchSpec.summaryHtml(
    widthPx: Int = 640,
    waveformHeightPx: Int = 96,
    spectrumHeightPx: Int = 96,
    background: String = DEFAULT_BACKGROUND,
    primaryColor: String = SYNTH_BLUE,
    secondaryColor: String = FIELD_ORANGE,
    fontFamily: String = DEFAULT_FONT
): String = snapshot().summaryHtml(
    widthPx = widthPx,
    waveformHeightPx = waveformHeightPx,
    spectrumHeightPx = spectrumHeightPx,
    background = background,
    primaryColor = primaryColor,
    secondaryColor = secondaryColor,
    fontFamily = fontFamily
)

private val liveSynthVisualizerJobs =
    java.util.Collections.synchronizedMap(WeakHashMap<Box, MutableMap<String, Job>>())

@OptIn(DelicateCoroutinesApi::class)
private fun installLiveSynthVisualizer(
    key: String,
    channel: String,
    refreshHz: Double,
    render: () -> String
) {
    val box = Box.context.get()
        ?: throw IllegalStateException("Live synth visualizers require an active Box context")
    val intervalMs = (1000.0 / refreshHz.coerceIn(1.0, 60.0)).toLong().coerceAtLeast(16L)

    val job = GlobalScope.launch(Box.boxContextElement(box)) {
        while (isActive) {
            box.display(channel, render())
            delay(intervalMs)
        }
    }

    liveSynthVisualizerJobs.computeIfAbsent(box) { mutableMapOf() }
        .put(key, job)
        ?.cancel()

    box.jobs += job
}

fun AudioPatchSpec.liveSummary(
    channel: String = "audio",
    refreshHz: Double = 12.0,
    widthPx: Int = 640,
    waveformHeightPx: Int = 96,
    spectrumHeightPx: Int = 96
): AudioPatchSpec = apply {
    installLiveSynthVisualizer("audio-summary:$channel", channel, refreshHz) {
        summaryHtml(widthPx = widthPx, waveformHeightPx = waveformHeightPx, spectrumHeightPx = spectrumHeightPx)
    }
}

fun Scope.snapshot(sampleRate: Int = sampleRateHint): AudioSnapshot = trace.snapshot(sampleRate)

fun Scope.waveformSvg(
    widthPx: Int = 480,
    heightPx: Int = 96,
    sampleRate: Int = 48_000
): String = snapshot(sampleRate).waveformSvg(widthPx = widthPx, heightPx = heightPx, title = "Waveform (Scope)")

fun Scope.spectrumSvg(
    widthPx: Int = 480,
    heightPx: Int = 96,
    sampleRate: Int = 48_000
): String = snapshot(sampleRate).spectrumSvg(widthPx = widthPx, heightPx = heightPx, title = "Spectrum (Scope)")

fun Scope.summaryHtml(
    widthPx: Int = 640,
    waveformHeightPx: Int = 96,
    spectrumHeightPx: Int = 96,
    sampleRate: Int = 48_000
): String = snapshot(sampleRate).summaryHtml(
    widthPx = widthPx,
    waveformHeightPx = waveformHeightPx,
    spectrumHeightPx = spectrumHeightPx
)

fun Scope.liveWaveform(
    channel: String = "scope",
    refreshHz: Double = 30.0,
    widthPx: Int = 480,
    heightPx: Int = 96,
    sampleRate: Int = 48_000
): Scope = apply {
    installLiveSynthVisualizer("scope-waveform:$channel", channel, refreshHz) {
        waveformSvg(widthPx = widthPx, heightPx = heightPx, sampleRate = sampleRate)
    }
}

fun Scope.liveSpectrum(
    channel: String = "scope",
    refreshHz: Double = 30.0,
    widthPx: Int = 480,
    heightPx: Int = 96,
    sampleRate: Int = 48_000
): Scope = apply {
    installLiveSynthVisualizer("scope-spectrum:$channel", channel, refreshHz) {
        spectrumSvg(widthPx = widthPx, heightPx = heightPx, sampleRate = sampleRate)
    }
}

fun Scope.liveSummary(
    channel: String = "scope",
    refreshHz: Double = 12.0,
    widthPx: Int = 640,
    waveformHeightPx: Int = 96,
    spectrumHeightPx: Int = 96,
    sampleRate: Int = 48_000
): Scope = apply {
    installLiveSynthVisualizer("scope-summary:$channel", channel, refreshHz) {
        summaryHtml(
            widthPx = widthPx,
            waveformHeightPx = waveformHeightPx,
            spectrumHeightPx = spectrumHeightPx,
            sampleRate = sampleRate
        )
    }
}

fun StereoScope.snapshot(sampleRate: Int = sampleRateHint): AudioSnapshot = trace.snapshot(sampleRate)

fun StereoScope.waveformSvg(
    widthPx: Int = 480,
    heightPx: Int = 96,
    sampleRate: Int = 48_000
): String = snapshot(sampleRate).waveformSvg(widthPx = widthPx, heightPx = heightPx, title = "Waveform (Stereo Scope)")

fun StereoScope.spectrumSvg(
    widthPx: Int = 480,
    heightPx: Int = 96,
    sampleRate: Int = 48_000
): String = snapshot(sampleRate).spectrumSvg(widthPx = widthPx, heightPx = heightPx, title = "Spectrum (Stereo Scope)")

fun StereoScope.summaryHtml(
    widthPx: Int = 640,
    waveformHeightPx: Int = 96,
    spectrumHeightPx: Int = 96,
    sampleRate: Int = 48_000
): String = snapshot(sampleRate).summaryHtml(
    widthPx = widthPx,
    waveformHeightPx = waveformHeightPx,
    spectrumHeightPx = spectrumHeightPx
)

fun StereoScope.liveWaveform(
    channel: String = "stereo",
    refreshHz: Double = 12.0,
    widthPx: Int = 480,
    heightPx: Int = 96,
    sampleRate: Int = 48_000
): StereoScope = apply {
    installLiveSynthVisualizer("stereo-waveform:$channel", channel, refreshHz) {
        waveformSvg(widthPx = widthPx, heightPx = heightPx, sampleRate = sampleRate)
    }
}

fun StereoScope.liveSpectrum(
    channel: String = "stereo",
    refreshHz: Double = 12.0,
    widthPx: Int = 480,
    heightPx: Int = 96,
    sampleRate: Int = 48_000
): StereoScope = apply {
    installLiveSynthVisualizer("stereo-spectrum:$channel", channel, refreshHz) {
        spectrumSvg(widthPx = widthPx, heightPx = heightPx, sampleRate = sampleRate)
    }
}

fun StereoScope.liveSummary(
    channel: String = "stereo",
    refreshHz: Double = 12.0,
    widthPx: Int = 640,
    waveformHeightPx: Int = 96,
    spectrumHeightPx: Int = 96,
    sampleRate: Int = 48_000
): StereoScope = apply {
    installLiveSynthVisualizer("stereo-summary:$channel", channel, refreshHz) {
        summaryHtml(
            widthPx = widthPx,
            waveformHeightPx = waveformHeightPx,
            spectrumHeightPx = spectrumHeightPx,
            sampleRate = sampleRate
        )
    }
}

fun SpectralWavetable.waveformSvg(
    position: Float = lastAccessPosition,
    harmonicLimit: Int = frameSize / 2,
    widthPx: Int = 480,
    heightPx: Int = 96
): String = snapshot(position = position, harmonicLimit = harmonicLimit).waveformSvg(
    widthPx = widthPx,
    heightPx = heightPx,
    title = "Wavetable Waveform"
)

fun SpectralWavetable.spectrumSvg(
    position: Float = lastAccessPosition,
    harmonicLimit: Int = frameSize / 2,
    widthPx: Int = 480,
    heightPx: Int = 96
): String = snapshot(position = position, harmonicLimit = harmonicLimit).spectrumSvg(
    widthPx = widthPx,
    heightPx = heightPx,
    title = "Wavetable Spectrum"
)

fun SpectralWavetable.summaryHtml(
    position: Float = lastAccessPosition,
    harmonicLimit: Int = frameSize / 2,
    widthPx: Int = 640,
    waveformHeightPx: Int = 96,
    spectrumHeightPx: Int = 96
): String = snapshot(position = position, harmonicLimit = harmonicLimit).summaryHtml(
    widthPx = widthPx,
    waveformHeightPx = waveformHeightPx,
    spectrumHeightPx = spectrumHeightPx
)

fun ADSR.stateSvg(
    widthPx: Int = 480,
    heightPx: Int = 84,
    background: String = DEFAULT_BACKGROUND,
    primaryColor: String = SYNTH_BLUE,
    secondaryColor: String = FIELD_ORANGE,
    fontFamily: String = DEFAULT_FONT
): String = renderCompactTraceSvg(
    title = "ADSR",
    widthPx = widthPx,
    heightPx = heightPx,
    background = background,
    textColor = primaryColor,
    fontFamily = fontFamily,
    series = listOf(
        TraceSeries("Level", vizLevelTrace.snapshot(), primaryColor, 0.95, 1.7),
        TraceSeries("Gate", vizGateTrace.snapshot(), secondaryColor, 0.55, 1.3)
    ),
    fixedMin = 0f,
    fixedMax = 1.05f,
    referenceValues = listOf(0f, 1f),
    badges = listOf(
        SvgBadge(vizStageLabel),
        SvgBadge("lvl ${formatFloat(vizCurrentLevel)}")
    )
)

fun AR.stateSvg(
    widthPx: Int = 480,
    heightPx: Int = 84,
    background: String = DEFAULT_BACKGROUND,
    primaryColor: String = SYNTH_BLUE,
    secondaryColor: String = FIELD_ORANGE,
    fontFamily: String = DEFAULT_FONT
): String = renderCompactTraceSvg(
    title = "AR",
    widthPx = widthPx,
    heightPx = heightPx,
    background = background,
    textColor = primaryColor,
    fontFamily = fontFamily,
    series = listOf(
        TraceSeries("Level", vizLevelTrace.snapshot(), primaryColor, 0.95, 1.7),
        TraceSeries("Gate", vizGateTrace.snapshot(), secondaryColor, 0.55, 1.3)
    ),
    fixedMin = 0f,
    fixedMax = 1.05f,
    referenceValues = listOf(0f, 1f),
    badges = listOf(
        SvgBadge(vizStageLabel),
        SvgBadge("lvl ${formatFloat(vizCurrentLevel)}")
    )
)

fun BreakpointEnvelope.stateSvg(
    widthPx: Int = 480,
    heightPx: Int = 84,
    background: String = DEFAULT_BACKGROUND,
    primaryColor: String = SYNTH_BLUE,
    fontFamily: String = DEFAULT_FONT
): String = renderCompactTraceSvg(
    title = "Breakpoint Envelope",
    widthPx = widthPx,
    heightPx = heightPx,
    background = background,
    textColor = primaryColor,
    fontFamily = fontFamily,
    series = listOf(
        TraceSeries("Value", vizLevelTrace.snapshot(), primaryColor, 0.95, 1.7)
    ),
    fixedMin = 0f,
    fixedMax = 1.05f,
    referenceValues = listOf(0f, 1f),
    badges = listOf(
        SvgBadge(if (vizRunning) "RUNNING" else "HOLD"),
        SvgBadge("${formatFloat(vizCurrentTimeSec * 1000f)} ms")
    )
)

fun EnvelopeFollower.stateSvg(
    widthPx: Int = 480,
    heightPx: Int = 84,
    background: String = DEFAULT_BACKGROUND,
    primaryColor: String = SYNTH_BLUE,
    fontFamily: String = DEFAULT_FONT
): String = renderCompactTraceSvg(
    title = "Envelope Follower",
    widthPx = widthPx,
    heightPx = heightPx,
    background = background,
    textColor = primaryColor,
    fontFamily = fontFamily,
    series = listOf(
        TraceSeries("Envelope", vizEnvelopeTrace.snapshot(), primaryColor, 0.95, 1.7)
    ),
    fixedMin = 0f,
    fixedMax = max(1.0f, vizEnvelopeTrace.snapshot().maxOrNull() ?: 1f),
    referenceValues = listOf(0f),
    badges = listOf(
        SvgBadge(vizModeLabel),
        SvgBadge("lvl ${formatFloat(vizCurrentLevel)}")
    )
)

fun Compressor.stateSvg(
    widthPx: Int = 480,
    heightPx: Int = 84,
    background: String = DEFAULT_BACKGROUND,
    primaryColor: String = SYNTH_BLUE,
    secondaryColor: String = FIELD_ORANGE,
    fontFamily: String = DEFAULT_FONT
): String {
    val levelTrace = vizOutputDbTrace.snapshot()
    val reductionTrace = vizReductionDbTrace.snapshot()
    val minDb = min(-60f, min(levelTrace.minOrNull() ?: 0f, reductionTrace.minOrNull() ?: 0f) - 3f)
    return renderCompactTraceSvg(
        title = "Compressor",
        widthPx = widthPx,
        heightPx = heightPx,
        background = background,
        textColor = primaryColor,
        fontFamily = fontFamily,
        series = listOf(
            TraceSeries("Output dB", levelTrace, primaryColor, 0.95, 1.7),
            TraceSeries("Gain Reduction", reductionTrace, secondaryColor, 0.8, 1.5)
        ),
        fixedMin = minDb,
        fixedMax = 3f,
        referenceValues = listOf(0f, vizCurrentThresholdDb),
        badges = listOf(
            SvgBadge("GR ${formatFloat(-vizCurrentGainReductionDb)} dB", secondaryColor),
            SvgBadge("th ${formatFloat(vizCurrentThresholdDb)} dB"),
            SvgBadge("${formatFloat(vizCurrentRatio)}:1")
        )
    )
}

fun Limiter.stateSvg(
    widthPx: Int = 480,
    heightPx: Int = 84,
    background: String = DEFAULT_BACKGROUND,
    primaryColor: String = SYNTH_BLUE,
    secondaryColor: String = FIELD_ORANGE,
    fontFamily: String = DEFAULT_FONT
): String {
    val levelTrace = vizOutputDbTrace.snapshot()
    val reductionTrace = vizReductionDbTrace.snapshot()
    val minDb = min(-48f, min(levelTrace.minOrNull() ?: 0f, reductionTrace.minOrNull() ?: 0f) - 3f)
    return renderCompactTraceSvg(
        title = "Limiter",
        widthPx = widthPx,
        heightPx = heightPx,
        background = background,
        textColor = primaryColor,
        fontFamily = fontFamily,
        series = listOf(
            TraceSeries("Output dB", levelTrace, primaryColor, 0.95, 1.7),
            TraceSeries("Gain Reduction", reductionTrace, secondaryColor, 0.8, 1.5)
        ),
        fixedMin = minDb,
        fixedMax = 3f,
        referenceValues = listOf(0f, vizCurrentCeilingDb),
        badges = listOf(
            SvgBadge("GR ${formatFloat(-vizCurrentGainReductionDb)} dB", secondaryColor),
            SvgBadge("ceiling ${formatFloat(vizCurrentCeilingDb)} dB")
        )
    )
}

fun ADSR.stateHtml(widthPx: Int = 480, heightPx: Int = 84): String = stateSvg(widthPx, heightPx)
fun AR.stateHtml(widthPx: Int = 480, heightPx: Int = 84): String = stateSvg(widthPx, heightPx)
fun BreakpointEnvelope.stateHtml(widthPx: Int = 480, heightPx: Int = 84): String = stateSvg(widthPx, heightPx)
fun EnvelopeFollower.stateHtml(widthPx: Int = 480, heightPx: Int = 84): String = stateSvg(widthPx, heightPx)
fun Compressor.stateHtml(widthPx: Int = 480, heightPx: Int = 84): String = stateSvg(widthPx, heightPx)
fun Limiter.stateHtml(widthPx: Int = 480, heightPx: Int = 84): String = stateSvg(widthPx, heightPx)

private data class SvgBadge(
    val text: String,
    val color: String = SYNTH_BLUE
)

private data class TraceSeries(
    val label: String,
    val values: FloatArray,
    val color: String,
    val opacity: Double,
    val strokeWidth: Double
)

private data class WaveStats(
    val durationMs: Float,
    val peakLeft: Float,
    val peakRight: Float?,
    val rmsLeft: Float,
    val rmsRight: Float?
)

private data class SpectrumCurve(
    val frequencies: FloatArray,
    val dbs: FloatArray
)

private fun renderWaveformSvg(
    snapshot: AudioSnapshot,
    widthPx: Int,
    heightPx: Int,
    title: String,
    background: String,
    primaryColor: String,
    secondaryColor: String,
    fontFamily: String
): String {
    val frames = snapshot.frameCount
    if (frames <= 1) return emptySvg(widthPx, heightPx, background, primaryColor, fontFamily, "Waiting for audio")

    val leftValues = snapshot.left.copyOf(frames)
    val rightValues = snapshot.right?.copyOf(frames)
    val stats = computeWaveStats(leftValues, rightValues, snapshot.sampleRate)

    val w = widthPx.toDouble()
    val h = heightPx.toDouble()
    val padLeft = 8.0
    val padRight = 8.0
    val padTop = 18.0
    val padBottom = 12.0
    val innerW = (w - padLeft - padRight).coerceAtLeast(1.0)
    val innerH = (h - padTop - padBottom).coerceAtLeast(1.0)
    val x0 = padLeft
    val y0 = padTop
    val centerY = y0 + innerH / 2.0

    fun yFor(sample: Float): Double {
        val normalized = (sample.coerceIn(-1.05f, 1.05f) + 1.05f) / 2.10f
        return y0 + innerH - normalized * innerH
    }

    val bucketCount = min(max(32, innerW.roundToInt()), frames)
    val leftBuckets = buildWaveBuckets(leftValues, bucketCount)
    val rightBuckets = rightValues?.let { buildWaveBuckets(it, bucketCount) }

    fun drawEnvelope(buckets: List<WaveBucket>, color: String, fillOpacity: Double, strokeOpacity: Double): String {
        val envelope = buildWaveEnvelopePath(
            buckets = buckets,
            x0 = x0,
            innerW = innerW,
            yFor = ::yFor
        )
        if (envelope.isEmpty()) return ""
        return """
            <path d="$envelope" fill="$color" fill-opacity="$fillOpacity" stroke="$color" stroke-opacity="$strokeOpacity" stroke-width="1.0" stroke-linejoin="round"/>
        """.trimIndent()
    }

    val infoBadges = buildBadges(
        listOfNotNull(
            SvgBadge("${formatFloat(stats.durationMs)} ms"),
            SvgBadge("pk ${formatFloat(stats.peakLeft)}"),
            if (stats.peakRight != null) SvgBadge("R ${formatFloat(stats.peakRight)}", secondaryColor) else null,
            SvgBadge("rms ${formatFloat(stats.rmsLeft)}")
        ),
        widthPx = widthPx,
        startX = widthPx - 8,
        y = 13,
        fontFamily = fontFamily
    )

    val legend = if (snapshot.isStereo) """
        <text x="${(x0 + 2).f2()}" y="${(y0 + 11).f2()}" font-family="$fontFamily" font-size="10" fill="$primaryColor">L</text>
        <text x="${(x0 + 14).f2()}" y="${(y0 + 11).f2()}" font-family="$fontFamily" font-size="10" fill="$secondaryColor">R</text>
    """.trimIndent() else ""

    return """
        <svg xmlns="http://www.w3.org/2000/svg" width="$widthPx" height="$heightPx" viewBox="0 0 $widthPx $heightPx">
          <rect x="0" y="0" width="$widthPx" height="$heightPx" fill="$background"/>
          <text x="${x0.f2()}" y="13" font-family="$fontFamily" font-size="10" fill="$primaryColor">$title</text>
          $infoBadges
          <line x1="${x0.f2()}" y1="${centerY.f2()}" x2="${(x0 + innerW).f2()}" y2="${centerY.f2()}" stroke="$primaryColor" opacity="0.25" stroke-width="1"/>
          ${drawEnvelope(leftBuckets, primaryColor, fillOpacity = 0.24, strokeOpacity = 0.92)}
          ${if (rightBuckets != null) drawEnvelope(rightBuckets, secondaryColor, fillOpacity = 0.14, strokeOpacity = 0.72) else ""}
          $legend
        </svg>
    """.trimIndent()
}

private fun renderSpectrumSvg(
    snapshot: AudioSnapshot,
    widthPx: Int,
    heightPx: Int,
    title: String,
    background: String,
    primaryColor: String,
    secondaryColor: String,
    fontFamily: String
): String {
    val frames = snapshot.frameCount
    if (frames < 64) return emptySvg(widthPx, heightPx, background, primaryColor, fontFamily, "Waiting for audio")

    val leftCurve = computeSpectrum(snapshot.left.copyOf(frames), snapshot.sampleRate) ?: return emptySvg(
        widthPx, heightPx, background, primaryColor, fontFamily, "No spectrum"
    )
    val rightCurve = snapshot.right?.copyOf(frames)?.let { computeSpectrum(it, snapshot.sampleRate) }

    val w = widthPx.toDouble()
    val h = heightPx.toDouble()
    val padLeft = 8.0
    val padRight = 8.0
    val padTop = 18.0
    val padBottom = 14.0
    val innerW = (w - padLeft - padRight).coerceAtLeast(1.0)
    val innerH = (h - padTop - padBottom).coerceAtLeast(1.0)
    val x0 = padLeft
    val y0 = padTop
    val floorDb = -72f
    val maxFreq = (snapshot.sampleRate / 2f).coerceAtLeast(40f)
    val minFreq = 20f.coerceAtMost(maxFreq)

    fun xFor(freq: Float): Double {
        val clamped = freq.coerceIn(minFreq, maxFreq)
        val minLog = log10(minFreq.toDouble())
        val maxLog = log10(maxFreq.toDouble())
        val t = (log10(clamped.toDouble()) - minLog) / (maxLog - minLog)
        return x0 + t * innerW
    }

    fun yFor(db: Float): Double {
        val t = ((db.coerceIn(floorDb, 0f) - floorDb) / -floorDb).toDouble()
        return y0 + (1.0 - t) * innerH
    }

    fun pathFor(curve: SpectrumCurve): String {
        if (curve.frequencies.isEmpty()) return ""
        return buildString {
            curve.frequencies.indices.forEach { index ->
                val x = xFor(curve.frequencies[index])
                val y = yFor(curve.dbs[index])
                if (index == 0) append("M ${x.f2()} ${y.f2()}") else append(" L ${x.f2()} ${y.f2()}")
            }
        }
    }

    val markerFreqs = listOf(50f, 100f, 500f, 1_000f, 5_000f, 10_000f).filter { it < maxFreq }
    val markers = buildString {
        markerFreqs.forEach { freq ->
            val x = xFor(freq)
            append(
                """<line x1="${x.f2()}" y1="${y0.f2()}" x2="${x.f2()}" y2="${(y0 + innerH).f2()}" stroke="$primaryColor" opacity="0.16" stroke-width="1" stroke-dasharray="2,3"/>"""
            )
            append('\n')
            append(
                """<text x="${x.f2()}" y="${(y0 + innerH + 10).f2()}" text-anchor="middle" font-family="$fontFamily" font-size="9" fill="$primaryColor" opacity="0.8">${formatFrequency(freq)}</text>"""
            )
            append('\n')
        }
    }

    val badges = buildBadges(
        listOfNotNull(
            SvgBadge("0 dB"),
            SvgBadge("${formatFrequency(findPeakFrequency(leftCurve))} peak"),
            if (rightCurve != null) SvgBadge("R", secondaryColor) else null
        ),
        widthPx = widthPx,
        startX = widthPx - 8,
        y = 13,
        fontFamily = fontFamily
    )

    return """
        <svg xmlns="http://www.w3.org/2000/svg" width="$widthPx" height="$heightPx" viewBox="0 0 $widthPx $heightPx">
          <rect x="0" y="0" width="$widthPx" height="$heightPx" fill="$background"/>
          <text x="${x0.f2()}" y="13" font-family="$fontFamily" font-size="10" fill="$primaryColor">$title</text>
          $badges
          <line x1="${x0.f2()}" y1="${(y0 + innerH).f2()}" x2="${(x0 + innerW).f2()}" y2="${(y0 + innerH).f2()}" stroke="$primaryColor" opacity="0.25" stroke-width="1"/>
          <line x1="${x0.f2()}" y1="${y0.f2()}" x2="${(x0 + innerW).f2()}" y2="${y0.f2()}" stroke="$primaryColor" opacity="0.15" stroke-width="1" stroke-dasharray="2,2"/>
          <line x1="${x0.f2()}" y1="${(yFor(-36f)).f2()}" x2="${(x0 + innerW).f2()}" y2="${(yFor(-36f)).f2()}" stroke="$primaryColor" opacity="0.12" stroke-width="1" stroke-dasharray="2,2"/>
          $markers
          <path d="${pathFor(leftCurve)}" fill="none" stroke="$primaryColor" stroke-width="1.6" stroke-linejoin="round" stroke-linecap="round" opacity="0.95"/>
          ${if (rightCurve != null) """<path d="${pathFor(rightCurve)}" fill="none" stroke="$secondaryColor" stroke-width="1.4" stroke-linejoin="round" stroke-linecap="round" opacity="0.8"/>""" else ""}
        </svg>
    """.trimIndent()
}

private fun renderCompactTraceSvg(
    title: String,
    widthPx: Int,
    heightPx: Int,
    background: String,
    textColor: String,
    fontFamily: String,
    series: List<TraceSeries>,
    fixedMin: Float? = null,
    fixedMax: Float? = null,
    referenceValues: List<Float> = emptyList(),
    badges: List<SvgBadge> = emptyList()
): String {
    val values = series.flatMap { s -> s.values.asList().filter { it.isFinite() } }
    if (values.isEmpty()) return emptySvg(widthPx, heightPx, background, textColor, fontFamily, "Waiting for state")

    val rawMin = fixedMin ?: values.minOrNull() ?: 0f
    val rawMax = fixedMax ?: values.maxOrNull() ?: 1f
    val minValue = if (rawMin == rawMax) rawMin - 1f else rawMin
    val maxValue = if (rawMin == rawMax) rawMax + 1f else rawMax

    val w = widthPx.toDouble()
    val h = heightPx.toDouble()
    val padLeft = 8.0
    val padRight = 8.0
    val padTop = 18.0
    val padBottom = 12.0
    val innerW = (w - padLeft - padRight).coerceAtLeast(1.0)
    val innerH = (h - padTop - padBottom).coerceAtLeast(1.0)
    val x0 = padLeft
    val y0 = padTop

    fun yFor(value: Float): Double {
        val t = ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
        return y0 + (1.0 - t) * innerH
    }

    fun pathFor(values: FloatArray): String {
        if (values.isEmpty()) return ""
        val count = values.size
        return buildString {
            values.forEachIndexed { index, value ->
                val x = if (count <= 1) x0 else x0 + innerW * index / (count - 1)
                val y = yFor(value)
                if (index == 0) append("M ${x.f2()} ${y.f2()}") else append(" L ${x.f2()} ${y.f2()}")
            }
        }
    }

    val refs = buildString {
        referenceValues.forEach { value ->
            if (value in minValue..maxValue) {
                val y = yFor(value)
                append(
                    """<line x1="${x0.f2()}" y1="${y.f2()}" x2="${(x0 + innerW).f2()}" y2="${y.f2()}" stroke="$textColor" opacity="0.18" stroke-width="1" stroke-dasharray="2,2"/>"""
                )
                append('\n')
            }
        }
    }

    val badgeMarkup = buildBadges(badges, widthPx = widthPx, startX = widthPx - 8, y = 13, fontFamily = fontFamily)

    return """
        <svg xmlns="http://www.w3.org/2000/svg" width="$widthPx" height="$heightPx" viewBox="0 0 $widthPx $heightPx">
          <rect x="0" y="0" width="$widthPx" height="$heightPx" fill="$background"/>
          <text x="${x0.f2()}" y="13" font-family="$fontFamily" font-size="10" fill="$textColor">$title</text>
          $badgeMarkup
          $refs
          ${series.joinToString("\n") { trace ->
              """<path d="${pathFor(trace.values)}" fill="none" stroke="${trace.color}" stroke-width="${trace.strokeWidth}" stroke-linejoin="round" stroke-linecap="round" opacity="${trace.opacity}"/>"""
          }}
          <text x="${(x0 + innerW).f2()}" y="${(y0 + 9).f2()}" text-anchor="end" font-family="$fontFamily" font-size="9" fill="$textColor" opacity="0.75">${formatFloat(maxValue)}</text>
          <text x="${(x0 + innerW).f2()}" y="${(y0 + innerH).f2()}" text-anchor="end" font-family="$fontFamily" font-size="9" fill="$textColor" opacity="0.55">${formatFloat(minValue)}</text>
        </svg>
    """.trimIndent()
}

private fun renderPanelGridHtml(panels: List<String>, widthPx: Int, gapPx: Int): String = """
    <div style="display:grid;grid-template-columns:repeat(auto-fit, minmax(180px, 1fr));gap:${gapPx}px;max-width:${widthPx}px;">
      ${panels.joinToString("\n") { """<div style="line-height:0;">$it</div>""" }}
    </div>
""".trimIndent()

private fun emptySvg(
    widthPx: Int,
    heightPx: Int,
    background: String,
    textColor: String,
    fontFamily: String,
    message: String
): String = """
    <svg xmlns="http://www.w3.org/2000/svg" width="$widthPx" height="$heightPx" viewBox="0 0 $widthPx $heightPx">
      <rect x="0" y="0" width="$widthPx" height="$heightPx" fill="$background"/>
      <text x="${widthPx / 2}" y="${heightPx / 2}" text-anchor="middle" dominant-baseline="middle"
            font-family="$fontFamily" font-size="11" fill="$textColor" opacity="0.75">$message</text>
    </svg>
""".trimIndent()

private data class WaveBucket(val min: Float, val max: Float)

private fun buildWaveEnvelopePath(
    buckets: List<WaveBucket>,
    x0: Double,
    innerW: Double,
    yFor: (Float) -> Double
): String {
    if (buckets.isEmpty()) return ""
    val count = buckets.size
    val step = if (count <= 1) 0.0 else innerW / (count - 1)

    return buildString {
        buckets.forEachIndexed { index, bucket ->
            val x = x0 + step * index
            val y = yFor(bucket.max)
            if (index == 0) append("M ${x.f2()} ${y.f2()}") else append(" L ${x.f2()} ${y.f2()}")
        }
        for (index in buckets.indices.reversed()) {
            val bucket = buckets[index]
            val x = x0 + step * index
            append(" L ${x.f2()} ${yFor(bucket.min).f2()}")
        }
        append(" Z")
    }
}

private fun buildWaveBuckets(values: FloatArray, bucketCount: Int): List<WaveBucket> {
    if (values.isEmpty()) return emptyList()
    val safeCount = bucketCount.coerceAtLeast(1).coerceAtMost(values.size)
    return List(safeCount) { bucketIndex ->
        val start = (bucketIndex * values.size) / safeCount
        val endExclusive = max(start + 1, ((bucketIndex + 1) * values.size) / safeCount).coerceAtMost(values.size)
        var minValue = Float.POSITIVE_INFINITY
        var maxValue = Float.NEGATIVE_INFINITY
        for (i in start until endExclusive) {
            minValue = min(minValue, values[i])
            maxValue = max(maxValue, values[i])
        }
        WaveBucket(minValue, maxValue)
    }
}

private fun computeWaveStats(left: FloatArray, right: FloatArray?, sampleRate: Int): WaveStats {
    fun peak(values: FloatArray): Float = values.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
    fun rms(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        var sum = 0.0
        values.forEach { sum += it * it }
        return sqrt(sum / values.size).toFloat()
    }
    return WaveStats(
        durationMs = left.size * 1000f / sampleRate.toFloat(),
        peakLeft = peak(left),
        peakRight = right?.let(::peak),
        rmsLeft = rms(left),
        rmsRight = right?.let(::rms)
    )
}

private fun computeSpectrum(values: FloatArray, sampleRate: Int, preferredFftSize: Int = 2048): SpectrumCurve? {
    val fftSize = selectFftSize(values.size, preferredFftSize)
    if (fftSize < 64) return null

    val start = values.size - fftSize
    val complex = FloatArray(fftSize * 2)
    for (i in 0 until fftSize) {
        val window = (0.5 - 0.5 * cos((2.0 * PI * i) / (fftSize - 1))).toFloat()
        complex[i * 2] = values[start + i] * window
        complex[i * 2 + 1] = 0f
    }

    FFT(fftSize).forward(complex)

    val frequencies = FloatArray((fftSize / 2) - 1)
    val dbs = FloatArray((fftSize / 2) - 1)
    var peak = 0f
    for (bin in 1 until fftSize / 2) {
        val re = complex[bin * 2]
        val im = complex[bin * 2 + 1]
        val mag = sqrt(re * re + im * im)
        peak = max(peak, mag)
    }
    if (peak <= 1e-7f) return null

    for (bin in 1 until fftSize / 2) {
        val re = complex[bin * 2]
        val im = complex[bin * 2 + 1]
        val mag = sqrt(re * re + im * im)
        frequencies[bin - 1] = bin * sampleRate.toFloat() / fftSize.toFloat()
        dbs[bin - 1] = max(-72f, (20.0 * log10((mag / peak).coerceAtLeast(1e-7f).toDouble())).toFloat())
    }

    return SpectrumCurve(frequencies, dbs)
}

private fun selectFftSize(length: Int, preferred: Int): Int {
    val maxSize = min(length, preferred).coerceAtLeast(0)
    if (maxSize < 2) return 0
    var size = 1
    while (size * 2 <= maxSize) size *= 2
    return size
}

private fun buildBadges(
    badges: List<SvgBadge>,
    widthPx: Int,
    startX: Int,
    y: Int,
    fontFamily: String
): String {
    if (badges.isEmpty()) return ""

    var cursor = startX.toDouble()
    val parts = mutableListOf<String>()
    badges.asReversed().forEach { badge ->
        val estimatedWidth = badge.text.length * 6.2 + 10.0
        cursor -= estimatedWidth
        parts += """
            <text x="${cursor.f2()}" y="$y" font-family="$fontFamily" font-size="10" fill="${badge.color}" opacity="0.92">${escapeXml(badge.text)}</text>
        """.trimIndent()
        cursor -= 8.0
        if (cursor < widthPx * 0.35) return@forEach
    }
    return parts.joinToString("\n")
}

private fun findPeakFrequency(curve: SpectrumCurve): Float {
    if (curve.frequencies.isEmpty()) return 0f
    var bestIndex = 0
    var bestValue = Float.NEGATIVE_INFINITY
    curve.dbs.indices.forEach { index ->
        if (curve.dbs[index] > bestValue) {
            bestValue = curve.dbs[index]
            bestIndex = index
        }
    }
    return curve.frequencies[bestIndex]
}

private fun formatFloat(value: Float): String {
    val abs = kotlin.math.abs(value)
    val decimals = when {
        abs >= 100f -> 0
        abs >= 10f -> 1
        abs >= 1f -> 2
        else -> 3
    }
    return String.format(Locale.US, "%.${decimals}f", value)
}

private fun formatFrequency(freqHz: Float): String {
    if (!freqHz.isFinite() || freqHz <= 0f) return "--"
    return if (freqHz >= 1_000f) {
        String.format(Locale.US, "%.1fk", freqHz / 1_000f)
    } else {
        String.format(Locale.US, "%.0f", freqHz)
    }
}

private fun escapeXml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

private fun Double.f2(): String = String.format(Locale.US, "%.2f", this)

@RunsAt(newRoot)
fun registerSynthVisualizers() {
    registerValuePrinter<AudioPatchSpec> { it.summaryHtml() }
    registerValuePrinter<Scope> { it.summaryHtml() }
    registerValuePrinter<StereoScope> { it.summaryHtml() }
    registerValuePrinter<SpectralWavetable> { it.summaryHtml() }
    registerValuePrinter<ADSR> { it.stateHtml() }
    registerValuePrinter<AR> { it.stateHtml() }
    registerValuePrinter<BreakpointEnvelope> { it.stateHtml() }
    registerValuePrinter<EnvelopeFollower> { it.stateHtml() }
    registerValuePrinter<Compressor> { it.stateHtml() }
    registerValuePrinter<Limiter> { it.stateHtml() }
}
