package afield.audio.synth

import afield.audio.engine.WavFloatArrayWriter
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.log10

/**
 * The sounds and response plots for the evieve-family filters ([TptOnePole], [TiltEQ], [Korg35],
 * [Resonator]) on `spectral_examples.md`. Every clip is one signal graph rendered offline by
 * [renderSignalsOffline] at [SR] with no engine or device, each filter's cutoff driven by another
 * Signal through `Param.mod`, so it moves per sample. Every plot is the filter's measured
 * [frequencyResponseDb]: an impulse rendered through a fresh instance, then a DFT.
 *
 * `main(dir)` writes the four `.wav`s and the response `.png`s into `dir`.
 */
object TptFilterDocs {
    const val SR: Int = 48_000
    const val PEAK: Float = 0.7f
    val SCENES: Map<String, String> = linkedMapOf(
        "korg35" to "a 55 Hz saw through Korg35, cutoff swept by a 0.5 Hz sine and wobbled at 110 Hz, resonance 0.9",
        "phaser" to "a three-saw chord through four TptOnePole allpasses summed with the dry signal, allpass cutoff swept by a 0.3 Hz sine",
        "tilt" to "white noise through TiltEQ, its tilt swept from −12 dB to +12 dB and back",
        "resonator" to "white noise through three Resonators whose frequencies glide on 0.2–0.7 Hz sines",
    )

    private fun mod(base: Float, m: Signal) = Param(base).also { it.mod = m }

    fun scene(name: String): Signal = when (name) {
        "korg35" -> SawOsc(Param(55f)) / Korg35(
            mod(1200f, SineOsc(Param(0.5f)) * 900f + SineOsc(Param(110f)) * 250f),
            Param(0.9f), drive = Param(2f))
        "phaser" -> {
            val dry = SawOsc(Param(110f)) + SawOsc(Param(165.5f)) + SawOsc(Param(220.7f))
            var wet: Signal = dry
            repeat(4) { wet = wet / TptOnePole(mod(900f, SineOsc(Param(0.3f)) * 750f), TptOnePoleMode.AP) }
            dry + wet
        }
        "tilt" -> WhiteNoise(7L) / TiltEQ(Param(800f), mod(0f, SineOsc(Param(0.25f)) * 12f))
        "resonator" -> {
            val n = WhiteNoise(11L) * 0.3f
            (n / Resonator(mod(300f, SineOsc(Param(0.2f)) * 80f), Param(0.6f))) +
                (n / Resonator(mod(750f, SineOsc(Param(0.45f)) * 150f), Param(0.4f))) +
                (n / Resonator(mod(2100f, SineOsc(Param(0.7f)) * 300f), Param(0.3f)))
        }
        else -> error("unknown TptFilterDocs scene '$name'; known: ${SCENES.keys}")
    }

    fun render(name: String, seconds: Double = 4.0): FloatArray = renderSignalsOffline(seconds, scene(name), sampleRate = SR)[0]

    fun renderWav(name: String, outPath: String) {
        val samples = render(name)
        require(samples.all { it.isFinite() }) { "scene '$name' produced a non-finite sample" }
        require(samples.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeMono16(outPath, samples, SR, normalizePeak = PEAK)
    }

    /** The response curves each plot carries: label to a fresh filter. */
    fun plots(): Map<String, List<Pair<String, () -> SignalProcessor>>> = linkedMapOf(
        "korg35" to listOf(0.1, 0.5, 0.8, 0.95).map { r -> "resonance $r" to { Korg35(Param(1000f), Param(r.toFloat())) } } +
            listOf("highpass, resonance 0.8" to { Korg35(Param(1000f), Param(0.8f), Korg35Mode.HP) }),
        "onepole" to listOf(
            "TptOnePole LP" to { TptOnePole(Param(1000f), TptOnePoleMode.LP) },
            "TptOnePole HP" to { TptOnePole(Param(1000f), TptOnePoleMode.HP) },
            "OnePole LP (impulse-invariant)" to { OnePole(Param(1000f)) },
        ) + listOf(-12, -6, 6, 12).map { t -> "TiltEQ $t dB" to { TiltEQ(Param(800f), Param(t.toFloat())) } },
        "resonator" to listOf(0.02, 0.1, 0.5).map { d -> "Resonator 1 kHz, decay $d s" to { Resonator(Param(1000f), Param(d.toFloat())) } },
    )

    fun writePlot(name: String, outPath: String, w: Int = 900, h: Int = 420) {
        // 1 kHz itself is on the grid, so a narrow resonant peak is sampled at its top
        val freqs = (logFrequencies(20.0, 20_000.0, 400) + 1000.0).sorted()
        val curves = plots().getValue(name).map { (label, make) ->
            // small-signal response: keeps Korg35's tanh in its linear range
            label to frequencyResponseDb(make(), freqs, SR, frames = SR, amplitude = 1e-4f)
        }
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x16, 0x18, 0x1d); g.fillRect(0, 0, w, h)
        val l = 60; val r = w - 20; val t = 20; val b = h - 40
        val dbLo = -48.0; val dbHi = 24.0
        fun xd(f: Double) = l + (r - l) * log10(f / 20.0) / log10(1000.0)
        fun yd(db: Double) = b - (b - t) * (db.coerceIn(dbLo, dbHi) - dbLo) / (dbHi - dbLo)
        fun x(f: Double) = xd(f).toInt()
        fun y(db: Double) = (b - (b - t) * (db.coerceIn(dbLo, dbHi) - dbLo) / (dbHi - dbLo)).toInt()
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        for (f in listOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)) {
            g.color = Color(0x2c, 0x30, 0x38); g.drawLine(x(f), t, x(f), b)
            g.color = Color(0x9a, 0xa0, 0xaa); g.drawString(if (f >= 1000) "${(f / 1000).toInt()}k" else "${f.toInt()}", x(f) - 8, b + 16)
        }
        var db = dbLo
        while (db <= dbHi) {
            g.color = Color(0x2c, 0x30, 0x38); g.drawLine(l, y(db), r, y(db))
            g.color = Color(0x9a, 0xa0, 0xaa); g.drawString("${db.toInt()} dB", 6, y(db) + 4)
            db += 12.0
        }
        g.drawString("Hz", r - 20, h - 8)
        val palette = listOf(0x4fc3f7, 0xffa726, 0x66bb6a, 0xef5350, 0xab47bc, 0xd4e157, 0x26c6da)
        curves.forEachIndexed { k, (label, dbs) ->
            g.color = Color(palette[k % palette.size]); g.stroke = BasicStroke(2f)
            for (i in 1 until freqs.size) g.draw(Line2D.Double(xd(freqs[i - 1]), yd(dbs[i - 1].toDouble()), xd(freqs[i]), yd(dbs[i].toDouble())))
        }
        g.color = Color(0x16, 0x18, 0x1d, 0xe0); g.fillRect(r - 240, t + 2, 236, 8 + 16 * curves.size)
        curves.forEachIndexed { k, (label, _) ->
            g.color = Color(palette[k % palette.size]); g.drawString(label, r - 230, t + 16 + 16 * k)
        }
        g.dispose()
        ImageIO.write(img, "png", File(outPath))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.getOrNull(0) ?: File(System.getProperty("java.io.tmpdir"), "tpt-filters").path)
        dir.mkdirs()
        SCENES.keys.forEach { renderWav(it, File(dir, "$it.wav").path); println("rendered $it.wav") }
        plots().keys.forEach { writePlot(it, File(dir, "${it}_response.png").path); println("plotted ${it}_response.png") }
    }
}
