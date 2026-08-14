@file:afield.CompletionCategory("Internal")
package afield.four.synth

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/*
FIELD_TEST
id: synth.dough-dsp.gallery
runner: jvm-main
entrypoint: afield.four.synth.DoughDspPlotGallery
tags: [smoke, synth, dsp, dough, audio, plot, capture, cpu]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "dough dsp plot gallery rendered"
*/

/**
 * Headless **response-gallery / plot-capture harness** for the dough DSP ports in [DoughDsp].
 * It is the visual companion to `DoughDspSmokeTest` (which asserts the same behaviour numerically):
 * every plotted point comes from actually *running* a primitive on the CPU — an impulse into
 * [PlateReverb], a swept sine into [Phaser] / [ChamberlinLowpass], a ramp into [SoftDistort], a
 * gated [CurvedADSR], and raw [ZawOsc] / [PulzeOsc] periods — never a synthesized or hand-drawn
 * curve.
 *
 * Rendering path mirrors `afield.examples.functions.FunctionsPlotGallery` and
 * `afield.examples.rectsl.RectSLDataVizProof`: pure-CPU AWT `Graphics2D` → `BufferedImage` →
 * `ImageIO` PNG, run under `java.awt.headless=true`. There is no browser, GPU, Vulkan or Field
 * window. Only the "draw a line between real samples" step is synthetic; the samples themselves
 * are honest.
 *
 * As a FIELD_TEST (`synth.dough-dsp.gallery`) it renders to a temp dir and asserts real
 * correctness (painted fraction, finite samples, and per-plot sanity: the reverb tail decays and
 * L≠R, the resonant filter peaks above its flat setting, the phaser notch dips, the ADSR
 * retrigger has no discontinuity, the soft-clip is monotonic). It writes NO committed assets
 * during the test. Pass an output dir as `args[0]` (or set `DOUGH_DSP_PLOT_DIR`) to regenerate the
 * committed docs captures under `dev_docs/assets/dough_dsp_gallery/`.
 *
 * The harness is **parameterized/reusable**: add a plot by appending one [Plot] to [PLOTS].
 */
object DoughDspPlotGallery {

    private const val SR = 48_000f
    private const val BLOCK = 128

    // ── plot model ──────────────────────────────────────────────────────────────

    enum class Style { LINE, THICK, DASHED, AREA, POINTS }

    class Series(
        val label: String,
        val xs: DoubleArray,
        val ys: DoubleArray,
        val color: Color,
        val style: Style = Style.LINE
    )

    class PlotSpec(
        val title: String,
        val xLabel: String,
        val yLabel: String,
        val series: List<Series>,
        val yRange: Pair<Double, Double>? = null,
        /** Log-scale the x axis — filter magnitude responses need it to read a peak. */
        val logX: Boolean = false
    )

    /** A named plot the gallery renders, plus an optional deeper correctness check on its data. */
    class Plot(
        val name: String,
        val build: () -> PlotSpec,
        val verify: (PlotSpec) -> Unit = {}
    )

    // Dark-mode palette shared with the rest of the docs figures.
    private val BG = Color(20, 22, 28)
    private val PANEL = Color(28, 30, 38)
    private val AXIS = Color(120, 126, 140)
    private val GRID = Color(48, 52, 62)
    private val TEXT = Color(206, 210, 220)
    private val CYAN = Color(72, 202, 228)
    private val ORANGE = Color(240, 156, 74)
    private val VIOLET = Color(176, 142, 240)
    private val GREEN = Color(120, 214, 140)
    private val PINK = Color(232, 120, 168)
    private val GREY = Color(150, 156, 168)

    // ── curated plots ───────────────────────────────────────────────────────────

    val PLOTS: List<Plot> = listOf(
        Plot("plate-reverb-impulse", {
            // A single-sample impulse into the plate: the abs-peak decay envelope of each channel.
            // The tail must decay, and L and R diverge (the seven-tap-per-side decorrelation).
            val (l, r) = renderStereo(
                PlateReverb(source = ImpulseSource(), decay = Param(0.85f), mix = Param(1f)),
                1.5f
            )
            val seconds = 1.5
            PlotSpec(
                title = "PlateReverb impulse response — abs-peak envelope (decay 0.85)",
                xLabel = "time (s)", yLabel = "|amplitude|",
                series = listOf(
                    envelope("left", l, seconds, CYAN, Style.LINE),
                    envelope("right", r, seconds, ORANGE, Style.LINE)
                )
            )
        }, { spec ->
            val l = spec.series[0].ys
            val r = spec.series[1].ys
            // decays: mean of the last third well below the first third.
            fun meanAbs(a: DoubleArray, from: Int, to: Int): Double {
                var s = 0.0; for (i in from until to) s += abs(a[i]); return s / (to - from)
            }
            val early = meanAbs(l, l.size / 20, l.size / 6)
            val late = meanAbs(l, l.size * 5 / 6, l.size)
            check(late < early * 0.5) { "plate-reverb tail did not decay: early=$early late=$late" }
            // decorrelated: L and R envelopes are not the same curve.
            var same = 0; var seen = 0
            for (i in l.indices) if (max(abs(l[i]), abs(r[i])) > 1e-4) { seen++; if (abs(l[i] - r[i]) < 1e-5) same++ }
            check(seen > 0 && same < seen / 2) { "plate-reverb L and R look identical (not decorrelated)" }
        }),

        Plot("phaser-swept-notch", {
            // Magnitude response with the LFO parked (rate=0) at three centre settings: the notch
            // sits at a different frequency in each, i.e. the positions its sweep moves through.
            fun response(center: Float): Series =
                magnitude("notch @ ${center.toInt()} Hz", { f ->
                    SineOsc(Param(f)).through(
                        Phaser(rate = Param(0f), depth = Param(0f), center = Param(center), sweep = Param(0f), mix = Param(1f))
                    )
                }, CYAN)
            val a = response(400f); val b = response(1200f); val c = response(3500f)
            PlotSpec(
                title = "Phaser swept notch — magnitude at three LFO positions (rate 0)",
                xLabel = "frequency (Hz)", yLabel = "gain (dB)",
                series = listOf(
                    a.recolor(CYAN), b.recolor(ORANGE), c.recolor(VIOLET)
                ),
                yRange = -26.0 to 6.0,
                logX = true
            )
        }, { spec ->
            // each curve must dip: its minimum is well below its high-frequency shelf.
            for (s in spec.series) {
                val lo = s.ys.filter { it.isFinite() }.min()
                val shelf = s.ys.takeLast(s.ys.size / 6).filter { it.isFinite() }.average()
                check(lo < shelf - 6.0) { "phaser '${s.label}' notch too shallow: min=$lo shelf=$shelf" }
            }
        }),

        Plot("chamberlin-lowpass", {
            fun response(label: String, cutoff: Float, res: Float, color: Color): Series =
                magnitude(label, { f ->
                    SineOsc(Param(f)).through(ChamberlinLowpass(Param(cutoff), Param(res)))
                }, color)
            PlotSpec(
                title = "ChamberlinLowpass magnitude — cutoff / resonance",
                xLabel = "frequency (Hz)", yLabel = "gain (dB)",
                series = listOf(
                    response("1 kHz, res 0.0", 1000f, 0.0f, GREY),
                    response("1 kHz, res 0.7", 1000f, 0.7f, CYAN),
                    response("1 kHz, res 0.95", 1000f, 0.95f, ORANGE),
                    response("4 kHz, res 0.9", 4000f, 0.9f, VIOLET)
                ),
                yRange = -42.0 to 18.0,
                logX = true
            )
        }, { spec ->
            val flat = spec.series[0].ys
            val resonant = spec.series[2].ys // 1 kHz res 0.95
            check(resonant.max() > flat.max() + 4.0) {
                "resonant setting shows no peak: flatMax=${flat.max()} resMax=${resonant.max()}"
            }
        }),

        Plot("curved-adsr", {
            // Gate on, off part-way through release, on again, off. CurvedADSR retriggers from the
            // current level (no click); plain ADSR restarts from a jump — plotted for contrast.
            val schedule = listOf(true to 0.18f, false to 0.05f, true to 0.15f, false to 0.28f)
            val a = 0.03f; val d = 0.06f; val s = 0.6f; val rel = 0.12f

            val gate = Gate()
            val curved = CurvedADSR(gate, Param(a), Param(d), Param(s), Param(rel))
            val (times, curvedLevels, gates) = runEnvelope(curved, gate, schedule)

            val plainGate = Gate()
            val plain = ADSR(plainGate, Param(a), Param(d), Param(s), Param(rel))
            val (_, plainLevels, _) = runEnvelope(plain, plainGate, schedule)

            PlotSpec(
                title = "CurvedADSR — click-free retrigger (vs plain ADSR)",
                xLabel = "time (ms)", yLabel = "level",
                series = listOf(
                    Series("gate", times, gates, GREY, Style.DASHED),
                    Series("ADSR (jumps)", times, plainLevels, PINK, Style.LINE),
                    Series("CurvedADSR", times, curvedLevels, CYAN, Style.THICK)
                ),
                yRange = -0.05 to 1.1
            )
        }, { spec ->
            val curved = spec.series[2].ys
            check(curved.max() > 0.98) { "CurvedADSR never reached full level: peak=${curved.max()}" }
            check(abs(curved.last()) < 0.02) { "CurvedADSR did not return to zero: last=${curved.last()}" }
            var maxJump = 0.0
            for (i in 1 until curved.size) maxJump = max(maxJump, abs(curved[i] - curved[i - 1]))
            // downsampled trace, so allow a larger threshold than the per-sample smoke test.
            check(maxJump < 0.08) { "CurvedADSR retrigger has a discontinuity: maxJump=$maxJump" }
        }),

        Plot("soft-distort-transfer", {
            val n = 800
            val xs = DoubleArray(n) { -3.0 + 6.0 * it / (n - 1) }
            fun curve(label: String, amount: Float, color: Color): Series {
                val ys = transfer(SoftDistort(Param(amount), Param(1f)), xs)
                return Series(label, xs, ys, color, Style.LINE)
            }
            PlotSpec(
                title = "SoftDistort transfer curve (input sweep -3..3)",
                xLabel = "input", yLabel = "output",
                series = listOf(
                    Series("identity", xs, xs.copyOf(), GREY, Style.DASHED),
                    curve("amount 0.5", 0.5f, GREEN),
                    curve("amount 1.0", 1.0f, CYAN),
                    curve("amount 2.0", 2.0f, ORANGE),
                    curve("amount 4.0", 4.0f, VIOLET)
                ),
                yRange = -2.0 to 2.0
            )
        }, { spec ->
            val shapers = spec.series.drop(1) // amounts 0.5, 1, 2, 4
            for (s in shapers) {
                var monotonic = true
                for (i in 1 until s.ys.size) if (s.ys[i] < s.ys[i - 1] - 1e-6) monotonic = false
                check(monotonic) { "SoftDistort '${s.label}' transfer curve is not monotonic" }
                check(s.ys.all { it.isFinite() && abs(it) <= 2.6 }) { "SoftDistort '${s.label}' left the expected bound" }
            }
            // More drive = more compression: the output at the top of the input sweep decreases.
            val ends = shapers.map { it.ys.last() }
            for (i in 1 until ends.size) {
                check(ends[i] < ends[i - 1]) { "SoftDistort drive did not increase compression: $ends" }
            }
        }),

        Plot("naive-oscillators", {
            // Two periods of each raw (aliasing) oscillator at 200 Hz — 240 samples/period @ 48 kHz.
            val freq = 200f
            val periods = 2
            val n = (SR / freq * periods).toInt()
            fun ms(samples: FloatArray) = DoubleArray(samples.size) { it / SR.toDouble() * 1000.0 }
            val zaw = renderMonoSamples(ZawOsc(Param(freq)), n)
            val sq = renderMonoSamples(PulzeOsc(Param(freq), Param(0.5f)), n)
            val narrow = renderMonoSamples(PulzeOsc(Param(freq), Param(0.25f)), n)
            PlotSpec(
                title = "Naive oscillators — 2 periods @ 200 Hz (raw, aliasing)",
                xLabel = "time (ms)", yLabel = "amplitude",
                series = listOf(
                    Series("zaw (saw)", ms(zaw), zaw.toDoubles(), CYAN, Style.LINE),
                    Series("pulze (duty 0.5)", ms(sq), sq.toDoubles(), ORANGE, Style.LINE),
                    Series("pulze (duty 0.25)", ms(narrow), narrow.toDoubles(), VIOLET, Style.LINE)
                ),
                yRange = -1.3 to 1.3
            )
        }, { spec ->
            val zaw = spec.series[0].ys
            check(zaw.min() < -0.9 && zaw.max() > 0.9) { "zaw does not span [-1,1]: ${zaw.min()}..${zaw.max()}" }
        })
    )

    private fun Series.recolor(c: Color) = Series(label, xs, ys, c, style)

    private fun FloatArray.toDoubles(): DoubleArray = DoubleArray(size) { this[it].toDouble() }

    // ── CPU drivers (real primitive output) ───────────────────────────────────────

    /** A single 1.0 sample followed by silence. */
    private class ImpulseSource : Signal() {
        private var fired = false
        override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
            java.util.Arrays.fill(output, 0, numFrames, 0f)
            if (!fired) { output[0] = 1f; fired = true }
        }
    }

    /** Emits a caller-supplied sample sequence, one per frame (for probing transfer curves). */
    private class SequenceSource(private val values: DoubleArray) : Signal() {
        private var i = 0
        override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
            for (k in 0 until numFrames) { output[k] = if (i < values.size) values[i].toFloat() else 0f; i++ }
        }
    }

    private fun renderMonoSamples(signal: Signal, total: Int): FloatArray {
        val ctx = SynthContext(SR, BLOCK)
        val out = FloatArray(total)
        val buf = FloatArray(BLOCK)
        var written = 0
        while (written < total) {
            signal.render(buf, BLOCK, ctx)
            ctx.advanceCycle()
            val n = minOf(BLOCK, total - written)
            System.arraycopy(buf, 0, out, written, n)
            written += n
        }
        return out
    }

    private fun renderMono(signal: Signal, seconds: Float): FloatArray =
        renderMonoSamples(signal, (seconds * SR).toInt())

    private fun renderStereo(node: StereoRenderable, seconds: Float): Pair<FloatArray, FloatArray> {
        val ctx = SynthContext(SR, BLOCK)
        val total = (seconds * SR).toInt()
        val outL = FloatArray(total); val outR = FloatArray(total)
        val bufL = FloatArray(BLOCK); val bufR = FloatArray(BLOCK)
        var written = 0
        while (written < total) {
            node.render(bufL, bufR, BLOCK, ctx)
            ctx.advanceCycle()
            val n = minOf(BLOCK, total - written)
            System.arraycopy(bufL, 0, outL, written, n)
            System.arraycopy(bufR, 0, outR, written, n)
            written += n
        }
        return outL to outR
    }

    /** Bucketed abs-peak envelope of a signal over [seconds], as `(x=time s, y=|peak|)`. */
    private fun envelope(label: String, samples: FloatArray, seconds: Double, color: Color, style: Style, buckets: Int = 360): Series {
        val n = buckets.coerceAtMost(samples.size).coerceAtLeast(1)
        val xs = DoubleArray(n); val ys = DoubleArray(n)
        for (b in 0 until n) {
            val start = (b.toLong() * samples.size / n).toInt()
            val end = ((b + 1).toLong() * samples.size / n).toInt().coerceAtLeast(start + 1).coerceAtMost(samples.size)
            var peak = 0f
            for (i in start until end) peak = max(peak, abs(samples[i]))
            xs[b] = (start + end) / 2.0 / samples.size * seconds
            ys[b] = peak.toDouble()
        }
        return Series(label, xs, ys, color, style)
    }

    /** Magnitude response in dB: for each log-spaced frequency, run a sine through the chain and
     * measure steady-state output RMS relative to the input sine's RMS. */
    private fun magnitude(
        label: String,
        chain: (Float) -> Signal,
        color: Color,
        fromHz: Float = 30f,
        toHz: Float = 18_000f,
        points: Int = 140
    ): Series {
        val xs = DoubleArray(points); val ys = DoubleArray(points)
        val logFrom = log10(fromHz.toDouble()); val logTo = log10(toHz.toDouble())
        for (i in 0 until points) {
            val f = Math.pow(10.0, logFrom + (logTo - logFrom) * i / (points - 1)).toFloat()
            val out = renderMono(chain(f), 0.35f)
            // steady state: skip the first 60%, measure the last 40%.
            val steady = out.copyOfRange((out.size * 6 / 10), out.size)
            val outRms = rms(steady)
            val inRms = 0.7071068f // unit-amplitude sine
            val db = 20.0 * log10((outRms / inRms).coerceAtLeast(1e-4f).toDouble())
            xs[i] = f.toDouble(); ys[i] = db
        }
        return Series(label, xs, ys, color, Style.LINE)
    }

    /** Push a value sequence through a memoryless processor, returning the output per input. */
    private fun transfer(processor: SignalProcessor, xs: DoubleArray): DoubleArray {
        val chain = SequenceSource(xs).through(processor)
        val out = renderMonoSamples(chain, xs.size)
        return DoubleArray(xs.size) { out[it].toDouble() }
    }

    /** Run an envelope through a gate schedule, returning `(timesMs, levels, gate01)` downsampled. */
    private fun runEnvelope(env: Signal, gate: Gate, schedule: List<Pair<Boolean, Float>>): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val ctx = SynthContext(SR, BLOCK)
        val buf = FloatArray(BLOCK)
        val levels = ArrayList<Float>(); val gates = ArrayList<Float>()
        for ((open, seconds) in schedule) {
            if (open) gate.open() else gate.close()
            val blocks = (seconds * SR / BLOCK).toInt()
            repeat(blocks) {
                env.render(buf, BLOCK, ctx)
                ctx.advanceCycle()
                for (i in 0 until BLOCK) { levels += buf[i]; gates += if (open) 1f else 0f }
            }
        }
        val stride = 16
        val m = levels.size / stride
        val times = DoubleArray(m); val lv = DoubleArray(m); val gt = DoubleArray(m)
        for (j in 0 until m) {
            val idx = j * stride
            times[j] = idx / SR.toDouble() * 1000.0
            lv[j] = levels[idx].toDouble()
            gt[j] = gates[idx].toDouble()
        }
        return Triple(times, lv, gt)
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return sqrt(sum / samples.size).toFloat()
    }

    // ── renderer (shared with FunctionsPlotGallery; + log-x support) ───────────────

    private const val W = 900
    private const val H = 540
    private const val ML = 78
    private const val MR = 24
    private const val MT = 48
    private const val MB = 54

    fun render(spec: PlotSpec): BufferedImage {
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = BG
        g.fillRect(0, 0, W, H)

        val plotX = ML; val plotY = MT
        val plotW = W - ML - MR; val plotH = H - MT - MB
        g.color = PANEL
        g.fillRect(plotX, plotY, plotW, plotH)

        var xmin = Double.POSITIVE_INFINITY; var xmax = Double.NEGATIVE_INFINITY
        for (s in spec.series) for (x in s.xs) { if (x < xmin) xmin = x; if (x > xmax) xmax = x }
        if (!xmin.isFinite() || !xmax.isFinite() || xmin == xmax) { xmin = 0.0; xmax = 1.0 }
        val (ymin, ymax) = spec.yRange ?: autoYRange(spec.series)

        val logLo = if (spec.logX) log10(xmin.coerceAtLeast(1e-9)) else 0.0
        val logHi = if (spec.logX) log10(xmax.coerceAtLeast(xmin * 1.0000001)) else 0.0
        fun xToPx(x: Double): Double =
            if (spec.logX) plotX + (log10(x.coerceAtLeast(1e-9)) - logLo) / (logHi - logLo) * plotW
            else plotX + (x - xmin) / (xmax - xmin) * plotW
        fun yToPx(y: Double) = plotY + plotH - (y - ymin) / (ymax - ymin) * plotH

        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        g.stroke = BasicStroke(1f)
        for (yt in niceTicks(ymin, ymax, 6)) {
            val py = yToPx(yt).toFloat()
            g.color = GRID
            g.draw(Line2D.Double(plotX.toDouble(), py.toDouble(), (plotX + plotW).toDouble(), py.toDouble()))
            g.color = TEXT
            val str = fmt(yt)
            g.drawString(str, (plotX - 8 - g.fontMetrics.stringWidth(str)).toFloat(), py + 4)
        }
        val xTicks = if (spec.logX) logTicks(xmin, xmax) else niceTicks(xmin, xmax, 6)
        for (xt in xTicks) {
            val px = xToPx(xt).toFloat()
            g.color = GRID
            g.draw(Line2D.Double(px.toDouble(), plotY.toDouble(), px.toDouble(), (plotY + plotH).toDouble()))
            g.color = TEXT
            val str = if (spec.logX) fmtHz(xt) else fmt(xt)
            g.drawString(str, px - g.fontMetrics.stringWidth(str) / 2f, (plotY + plotH + 18).toFloat())
        }

        if (0.0 in ymin..ymax) {
            g.color = AXIS; g.stroke = BasicStroke(1.4f)
            val py = yToPx(0.0)
            g.draw(Line2D.Double(plotX.toDouble(), py, (plotX + plotW).toDouble(), py))
        }

        g.color = AXIS; g.stroke = BasicStroke(1.2f)
        g.draw(Rectangle2D.Double(plotX.toDouble(), plotY.toDouble(), plotW.toDouble(), plotH.toDouble()))

        val solid = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val thick = BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val dashed = BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(7f, 6f), 0f)
        for (s in spec.series) {
            g.color = s.color
            when (s.style) {
                Style.POINTS -> for (i in s.xs.indices) {
                    val y = s.ys[i]; if (!y.isFinite()) continue
                    g.fill(Ellipse2D.Double(xToPx(s.xs[i]) - 2.5, yToPx(y) - 2.5, 5.0, 5.0))
                }
                Style.AREA -> {
                    g.stroke = solid
                    val path = Path2D.Double()
                    val base = yToPx(0.0.coerceIn(ymin, ymax))
                    var started = false
                    for (i in s.xs.indices) {
                        val y = s.ys[i]; if (!y.isFinite()) continue
                        val px = xToPx(s.xs[i]); val py = yToPx(y)
                        if (!started) { path.moveTo(px, base); path.lineTo(px, py); started = true } else path.lineTo(px, py)
                    }
                    path.lineTo(xToPx(s.xs.last()), base)
                    g.color = Color(s.color.red, s.color.green, s.color.blue, 70)
                    g.fill(path)
                    g.color = s.color
                    drawPolyline(g, s, ::xToPx, ::yToPx)
                }
                else -> {
                    g.stroke = when (s.style) { Style.THICK -> thick; Style.DASHED -> dashed; else -> solid }
                    drawPolyline(g, s, ::xToPx, ::yToPx)
                }
            }
        }

        drawLegend(g, spec.series, plotX + plotW - 8, plotY + 8)

        g.color = TEXT
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
        g.drawString(spec.title, ML.toFloat(), 28f)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        g.drawString(spec.xLabel, (plotX + plotW / 2).toFloat(), (H - 12).toFloat())
        val old = g.transform
        g.rotate(-Math.PI / 2, 18.0, (plotY + plotH / 2).toDouble())
        g.drawString(spec.yLabel, 18f, (plotY + plotH / 2 + 4).toFloat())
        g.transform = old

        g.dispose()
        return img
    }

    private fun drawPolyline(g: java.awt.Graphics2D, s: Series, xToPx: (Double) -> Double, yToPx: (Double) -> Double) {
        val path = Path2D.Double()
        var pen = false
        for (i in s.xs.indices) {
            val y = s.ys[i]
            if (!y.isFinite()) { pen = false; continue }
            val px = xToPx(s.xs[i]); val py = yToPx(y)
            if (!pen) { path.moveTo(px, py); pen = true } else path.lineTo(px, py)
        }
        g.draw(path)
    }

    private fun drawLegend(g: java.awt.Graphics2D, series: List<Series>, right: Int, top: Int) {
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        val fm = g.fontMetrics
        val rowH = 18; val swatch = 20; val pad = 8
        val textW = series.maxOf { fm.stringWidth(it.label) }
        val boxW = swatch + 8 + textW + pad * 2
        val boxH = series.size * rowH + pad * 2
        val x = right - boxW
        g.color = Color(16, 18, 24, 210)
        g.fill(Rectangle2D.Double(x.toDouble(), top.toDouble(), boxW.toDouble(), boxH.toDouble()))
        g.color = GRID
        g.draw(Rectangle2D.Double(x.toDouble(), top.toDouble(), boxW.toDouble(), boxH.toDouble()))
        series.forEachIndexed { i, s ->
            val cy = top + pad + i * rowH + rowH / 2
            g.color = s.color; g.stroke = BasicStroke(3f)
            g.draw(Line2D.Double((x + pad).toDouble(), cy.toDouble(), (x + pad + swatch).toDouble(), cy.toDouble()))
            g.color = TEXT
            g.drawString(s.label, (x + pad + swatch + 8).toFloat(), (cy + 4).toFloat())
        }
    }

    private fun autoYRange(series: List<Series>): Pair<Double, Double> {
        var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
        for (s in series) for (y in s.ys) if (y.isFinite()) { if (y < lo) lo = y; if (y > hi) hi = y }
        if (!lo.isFinite() || !hi.isFinite()) { lo = -1.0; hi = 1.0 }
        if (lo == hi) { lo -= 1.0; hi += 1.0 }
        val pad = (hi - lo) * 0.08
        return (lo - pad) to (hi + pad)
    }

    private fun niceTicks(lo: Double, hi: Double, target: Int): List<Double> {
        if (!lo.isFinite() || !hi.isFinite() || lo == hi) return listOf(lo)
        val span = hi - lo
        val raw = span / target
        val mag = Math.pow(10.0, Math.floor(Math.log10(raw)))
        val norm = raw / mag
        val step = when { norm < 1.5 -> 1.0; norm < 3.0 -> 2.0; norm < 7.0 -> 5.0; else -> 10.0 } * mag
        val start = Math.ceil(lo / step) * step
        val ticks = mutableListOf<Double>()
        var t = start
        while (t <= hi + step * 1e-6 && ticks.size < 40) { ticks.add(t); t += step }
        return ticks
    }

    private val LOG_TICKS = doubleArrayOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)
    private fun logTicks(lo: Double, hi: Double): List<Double> = LOG_TICKS.filter { it in lo..hi }

    private fun fmt(v: Double): String {
        val r = if (abs(v) < 1e-9) 0.0 else v
        return if (r == Math.rint(r) && abs(r) < 1e6) r.toInt().toString() else "%.2f".format(r)
    }

    private fun fmtHz(v: Double): String = if (v >= 1000.0) "${(v / 1000.0).let { if (it == Math.rint(it)) it.toInt().toString() else "%.1f".format(it) }}k" else v.toInt().toString()

    private fun paintedFraction(img: BufferedImage): Double {
        val bg = BG.rgb and 0xFFFFFF; val panel = PANEL.rgb and 0xFFFFFF
        var painted = 0; var seen = 0
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                val p = img.getRGB(x, y) and 0xFFFFFF
                if (p != bg && p != panel) painted++
                seen++; x += 2
            }
            y += 2
        }
        return painted.toDouble() / seen.coerceAtLeast(1)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val outDir = File(
            args.getOrNull(0) ?: System.getenv("DOUGH_DSP_PLOT_DIR")
                ?: File(System.getProperty("java.io.tmpdir"), "dough-dsp-plot-gallery").path
        )
        outDir.mkdirs()

        PLOTS.forEach { plot ->
            val spec = plot.build()
            for (s in spec.series) {
                check(s.ys.any { it.isFinite() }) { "${plot.name}: series '${s.label}' has no finite samples" }
            }
            plot.verify(spec)
            val img = render(spec)
            val file = File(outDir, "dough-${plot.name}.png")
            ImageIO.write(img, "png", file)
            val painted = paintedFraction(img)
            check(painted > 0.02) { "${plot.name}: rasterized near-empty (painted=$painted)" }
            println("wrote ${file.absolutePath} (${img.width}x${img.height}, painted=${"%.3f".format(painted)})")
        }

        println("dough dsp plot gallery rendered to ${outDir.absolutePath} (${PLOTS.size} plots)")
    }
}
