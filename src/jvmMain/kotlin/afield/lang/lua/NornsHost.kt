package afield.lang.lua

import afield.audio.engine.WavFloatArrayWriter
import afield.audio.synth.FieldAudioEngine
import afield.audio.synth.StereoNode
import afield.audio.synth.SynthContext
import afield.audio.synth.audio
import afield.document.graph.Box
import afield.runtime.lifecycle.ReleasePolicy
import afield.runtime.lifecycle.RunScope
import afield.ui.output.controlGroup
import afield.ui.visualization.AudioVisualizer
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The Kotlin half of the norns host a Lua box gets from `require "norns"`: the 128×64 screen and the sound.
 *
 * The norns API itself (`screen`, `params`, `clock`, `metro`, `util`, `musicutil`, `grid`, `arc`, the
 * script lifecycle) is Lua, in `src/jvmMain/resources/afield/lua/norns.lua`. It reaches Kotlin through
 * the raw handlers [nornsHandlersFor] makes, which the prelude wraps: a frame's screen operations, an
 * engine command, a softcut command, an `audio.*` level. The design and the coverage list are in
 * `public/docs/lua.md` ("A norns host").
 */

/**
 * A norns screen: 128×64, drawn with Java2D the way norns draws with cairo, shown as 16 grey levels.
 *
 * The surface keeps full 8-bit grey, as norns' cairo surface keeps ARGB; [levels] quantises to 0–15 the
 * way norns does when it sends a frame to the OLED. A level is grey `level × 17`. Paths follow cairo:
 * `move`/`line`/`curve`/`arc`/`rect` add to a current path that `stroke` or `fill` draws and clears,
 * `arc` and `circle` join the current point with a line, and `text` draws at the current point and
 * moves it on by the text's width.
 */
internal class NornsScreen {
    val image = BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY)
    private val g = image.createGraphics()
    private var path = Path2D.Double()
    private var cx = 0.0
    private var cy = 0.0
    private var hasPoint = false
    private var level = 15
    private var lineWidth = 1.0
    private var aa = true
    private var fontSize = 8
    private var fontFace = 1
    private var lastShown: String? = null

    init { clear() }

    private fun clear() {
        g.color = Color.BLACK
        g.fillRect(0, 0, W, H)
        path = Path2D.Double(); hasPoint = false
    }

    private fun font(): Font = Font(if (fontFace == 2) "Monospaced" else "SansSerif", Font.PLAIN, fontSize)

    /** The width norns' `screen.text_extents` reports for [s] at the current font, in pixels. */
    fun textWidth(s: String, size: Int = fontSize): Double =
        g.getFontMetrics(Font(if (fontFace == 2) "Monospaced" else "SansSerif", Font.PLAIN, size)).stringWidth(s).toDouble()

    private fun moveTo(x: Double, y: Double) { path.moveTo(x, y); cx = x; cy = y; hasPoint = true }
    private fun lineTo(x: Double, y: Double) { if (!hasPoint) moveTo(x, y) else { path.lineTo(x, y); cx = x; cy = y } }

    private fun prepare() {
        val grey = (level.coerceIn(0, 15) * 17)
        g.color = Color(grey, grey, grey)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, if (aa) RenderingHints.VALUE_ANTIALIAS_ON else RenderingHints.VALUE_ANTIALIAS_OFF)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, if (aa) RenderingHints.VALUE_TEXT_ANTIALIAS_ON else RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    private fun arc(x: Double, y: Double, r: Double, a1: Double, a2: Double) {
        // cairo measures clockwise from +x in a y-down space; Arc2D counter-clockwise, in degrees.
        val a = Arc2D.Double(x - r, y - r, 2 * r, 2 * r, -Math.toDegrees(a1), -Math.toDegrees(a2 - a1), Arc2D.OPEN)
        path.append(a, hasPoint)
        cx = x + r * kotlin.math.cos(a2); cy = y + r * kotlin.math.sin(a2); hasPoint = true
    }

    private fun text(s: String, align: Double) {
        prepare()
        g.font = font()
        val w = g.fontMetrics.stringWidth(s)
        val x = cx - w * align
        g.drawString(s, x.toFloat(), cy.toFloat())
        cx = x + w
        hasPoint = true
    }

    private fun png(file: String, x: Double, y: Double) {
        val img = runCatching { ImageIO.read(File(file)) }.getOrNull() ?: throw IllegalArgumentException("no PNG at $file")
        for (j in 0 until img.height) for (i in 0 until img.width) {
            val px = (x + i).toInt(); val py = (y + j).toInt()
            if (px !in 0 until W || py !in 0 until H) continue
            val argb = img.getRGB(i, j)
            val alpha = (argb ushr 24) and 0xff
            if (alpha < 128) continue
            val luma = (0.299 * ((argb shr 16) and 0xff) + 0.587 * ((argb shr 8) and 0xff) + 0.114 * (argb and 0xff)).roundToInt()
            image.raster.setSample(px, py, 0, luma)
        }
    }

    /** Apply one frame's operations, `{name, args…}` each, in order. Unknown names are returned, not thrown. */
    fun apply(ops: List<*>): Set<String> {
        val unknown = LinkedHashSet<String>()
        for (o in ops) {
            val op = o as? List<*> ?: continue
            fun d(i: Int, default: Double = 0.0) = (op.getOrNull(i) as? Number)?.toDouble() ?: default
            when (val name = op.getOrNull(0)?.toString() ?: continue) {
                "clear" -> clear()
                "level" -> level = d(1, 15.0).toInt()
                "aa" -> aa = d(1, 1.0) != 0.0
                "line_width" -> lineWidth = d(1, 1.0)
                "font_size" -> fontSize = d(1, 8.0).toInt().coerceAtLeast(1)
                "font_face" -> fontFace = d(1, 1.0).toInt()
                "move" -> moveTo(d(1), d(2))
                "move_rel" -> moveTo(cx + d(1), cy + d(2))
                "line" -> lineTo(d(1), d(2))
                "line_rel" -> lineTo(cx + d(1), cy + d(2))
                "curve" -> { if (!hasPoint) moveTo(d(1), d(2)); path.curveTo(d(1), d(2), d(3), d(4), d(5), d(6)); cx = d(5); cy = d(6) }
                "curve_rel" -> { val x0 = cx; val y0 = cy; path.curveTo(x0 + d(1), y0 + d(2), x0 + d(3), y0 + d(4), x0 + d(5), y0 + d(6)); cx = x0 + d(5); cy = y0 + d(6) }
                "arc" -> arc(d(1), d(2), d(3), d(4), d(5))
                "circle" -> arc(d(1), d(2), d(3), 0.0, 2 * PI)
                "rect" -> { path.append(Rectangle2D.Double(d(1), d(2), d(3), d(4)), false); cx = d(1); cy = d(2); hasPoint = true }
                "pixel" -> { path.append(Rectangle2D.Double(d(1), d(2), 1.0, 1.0), false); cx = d(1); cy = d(2); hasPoint = true }
                "close" -> if (hasPoint) path.closePath()
                "stroke" -> { prepare(); g.stroke = BasicStroke(lineWidth.toFloat()); g.draw(path); path = Path2D.Double(); hasPoint = false }
                "fill" -> { prepare(); g.fill(path); path = Path2D.Double(); hasPoint = false }
                "text" -> text(op.getOrNull(1)?.toString() ?: "", 0.0)
                "text_right" -> text(op.getOrNull(1)?.toString() ?: "", 1.0)
                "text_center" -> text(op.getOrNull(1)?.toString() ?: "", 0.5)
                "display_png" -> png(op.getOrNull(1)?.toString() ?: "", d(2), d(3))
                else -> unknown += name
            }
        }
        return unknown
    }

    /** The screen as norns' OLED shows it: one level, 0–15, per pixel, row by row. */
    fun levels(): IntArray = IntArray(W * H) { i -> ((image.raster.getSample(i % W, i / W, 0) + 8) / 17).coerceIn(0, 15) }

    /** A level image, scaled [scale]× with nearest-neighbour, as a PNG: levels shown white-on-black as norns' OLED does. */
    fun png(scale: Int): ByteArray {
        val lv = levels()
        val out = BufferedImage(W * scale, H * scale, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until H * scale) for (x in 0 until W * scale) {
            val v = lv[(y / scale) * W + x / scale] * 17
            out.setRGB(x, y, (v shl 16) or (v shl 8) or v)
        }
        return ByteArrayOutputStream().also { ImageIO.write(out, "png", it) }.toByteArray()
    }

    /** `screen.peek(x, y, w, h)`: the levels of a region as a string of bytes 0–15, as norns returns it. */
    fun peek(x: Int, y: Int, w: Int, h: Int): String {
        val lv = levels()
        val sb = StringBuilder()
        for (j in y until y + h) for (i in x until x + w) sb.append(if (i in 0 until W && j in 0 until H) lv[j * W + i].toChar() else 0.toChar())
        return sb.toString()
    }

    /** The `<img>` for the box's output, or null when it is the same as the last one shown. */
    fun htmlIfChanged(scale: Int): String? {
        val b64 = Base64.getEncoder().encodeToString(png(1))
        if (b64 == lastShown) return null
        lastShown = b64
        return "<img src=\"data:image/png;base64,$b64\" width=\"${W * scale}\" height=\"${H * scale}\" " +
            "style=\"image-rendering:pixelated; display:block; background:#000; border-radius:3px\" alt=\"norns screen\">"
    }

    companion object {
        /** norns' screen size. */
        const val W = 128
        const val H = 64
    }
}

/**
 * The sound of a norns script: PolyPerc's voices and a subset of softcut, mixed as norns' `audio.level_*`
 * mix them.
 *
 * Commands come from the box's Lua through [command], into a queue the renderer drains at the start
 * of each block, so a live box can send them from the main thread while [FieldAudioEngine]'s thread
 * renders. Offline (`norns.render`), the box's own thread renders through [renderOffline] between the
 * script's clock events, so a note lands on the sample its clock asked for.
 *
 * PolyPerc is SuperCollider's `Pulse` through `MoogFF` under `Env.perc`, panned: here a PolyBLEP pulse, a
 * four-pole ladder with `gain` as its resonance, an attack of 10 ms and a release whose curve is
 * `Env.perc`'s −4. Its commands are `hz` (play a note), `amp`, `pw`, `release`, `cutoff`, `gain`, `pan`.
 */
internal class NornsSound(val sampleRate: Int) : StereoNode() {
    private val queue = ConcurrentLinkedQueue<Pair<String, List<Any?>>>()

    // ── PolyPerc ─────────────────────────────────────────────────────────────────────────────────
    private var amp = 0.5; private var pw = 0.5; private var release = 0.5
    private var cutoff = 1000.0; private var gain = 2.0; private var pan = 0.0

    private class Voice(val hz: Double, val amp: Double, val pw: Double, val release: Double, val cutoff: Double, val gain: Double, val pan: Double) {
        var phase = 0.0; var t = 0.0; val s = DoubleArray(4); var done = false
    }
    private val voices = ArrayList<Voice>()

    // ── softcut ──────────────────────────────────────────────────────────────────────────────────
    private val bufferSeconds = 60
    private val buffers by lazy { Array(2) { FloatArray(bufferSeconds * sampleRate) } }
    private inner class Cut {
        var enable = false; var buffer = 1; var level = 0.0; var levelNow = 0.0; var levelSlew = 0.0
        var pan = 0.0; var rate = 1.0; var rateNow = 1.0; var rateSlew = 0.0; var loop = true
        var loopStart = 0.0; var loopEnd = 1.0; var pos = 0.0; var play = false; var rec = false
        var recLevel = 1.0; var preLevel = 0.0; var inputL = 0.0; var inputR = 0.0
    }
    private val cuts = Array(6) { Cut() }

    // ── the mix ──────────────────────────────────────────────────────────────────────────────────
    private var levelEng = 1.0; private var levelCut = 1.0; private var levelEngCut = 0.0

    /** Every command name that arrived and is not handled, for the coverage report. */
    val unhandled: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Queue a command from Lua: `engine`, `softcut` or `audio`, its name and arguments. */
    fun command(kind: String, name: String, args: List<Any?>) { queue += "$kind.$name" to args }

    private fun d(a: List<Any?>, i: Int, default: Double = 0.0) = (a.getOrNull(i) as? Number)?.toDouble() ?: default

    private fun drain() {
        while (true) {
            val (name, a) = queue.poll() ?: return
            when (name) {
                "engine.hz" -> { if (voices.size >= 24) voices.removeAt(0); voices += Voice(d(a, 0, 440.0), amp, pw, release, cutoff, gain, pan) }
                "engine.amp" -> amp = d(a, 0, amp)
                "engine.pw" -> pw = d(a, 0, pw)
                "engine.release" -> release = max(0.001, d(a, 0, release))
                "engine.cutoff" -> cutoff = d(a, 0, cutoff)
                "engine.gain" -> gain = d(a, 0, gain)
                "engine.pan" -> pan = d(a, 0, pan)
                "audio.level_eng" -> levelEng = d(a, 0, 1.0)
                "audio.level_cut" -> levelCut = d(a, 0, 1.0)
                "audio.level_eng_cut" -> levelEngCut = d(a, 0, 0.0)
                "softcut.buffer_clear" -> buffers.forEach { it.fill(0f) }
                "softcut.buffer_clear_channel" -> buffers.getOrNull(d(a, 0, 1.0).toInt() - 1)?.fill(0f)
                "softcut.buffer_clear_region" -> clearRegion(d(a, 0), d(a, 1), null)
                "softcut.buffer_clear_region_channel" -> clearRegion(d(a, 1), d(a, 2), d(a, 0, 1.0).toInt())
                "softcut.reset" -> { cuts.indices.forEach { cuts[it] = Cut() }; buffers.forEach { it.fill(0f) } }
                "softcut.buffer_read_mono" -> readFile(a, mono = true)
                "softcut.buffer_read_stereo" -> readFile(a, mono = false)
                else -> if (!(name.startsWith("softcut.") && voiceCommand(name.removePrefix("softcut."), a))) unhandled += name
            }
        }
    }

    private fun clearRegion(start: Double, dur: Double, ch: Int?) {
        val a = (start * sampleRate).toInt().coerceIn(0, bufferSeconds * sampleRate)
        val b = ((start + dur) * sampleRate).toInt().coerceIn(a, bufferSeconds * sampleRate)
        buffers.forEachIndexed { i, buf -> if (ch == null || ch == i + 1) buf.fill(0f, a, b) }
    }

    private fun readFile(a: List<Any?>, mono: Boolean) {
        val path = a.getOrNull(0)?.toString() ?: return
        val startSrc = d(a, 1); val startDst = d(a, 2); val dur = d(a, 3, -1.0)
        val chSrc = d(a, 4, 1.0).toInt(); val chDst = d(a, 5, 1.0).toInt()
        val pcm = readWav(path) ?: return
        val n = if (dur < 0) pcm.frames else min(pcm.frames, (dur * sampleRate).toInt())
        val from = (startSrc * pcm.rate).toInt(); val to = (startDst * sampleRate).toInt()
        for (i in 0 until n) {
            val si = from + i; val di = to + i
            if (si >= pcm.frames || di >= bufferSeconds * sampleRate) break
            if (mono) buffers[chDst - 1][di] = pcm.channel(chSrc - 1)[si]
            else for (c in 0..1) buffers[c][di] = pcm.channel(c)[si]
        }
    }

    private fun voiceCommand(name: String, a: List<Any?>): Boolean {
        val v = cuts.getOrNull(d(a, 0, 1.0).toInt() - 1) ?: return true
        when (name) {
            "enable" -> v.enable = d(a, 1) != 0.0
            "buffer" -> v.buffer = d(a, 1, 1.0).toInt().coerceIn(1, 2)
            "level" -> v.level = d(a, 1)
            "pan" -> v.pan = d(a, 1)
            "rate" -> v.rate = d(a, 1, 1.0)
            "loop" -> v.loop = d(a, 1, 1.0) != 0.0
            "loop_start" -> v.loopStart = d(a, 1)
            "loop_end" -> v.loopEnd = d(a, 1, 1.0)
            "position" -> v.pos = d(a, 1)
            "play" -> v.play = d(a, 1) != 0.0
            "rec" -> v.rec = d(a, 1) != 0.0
            "rec_level" -> v.recLevel = d(a, 1, 1.0)
            "pre_level" -> v.preLevel = d(a, 1)
            "level_slew_time" -> v.levelSlew = d(a, 1)
            "rate_slew_time" -> v.rateSlew = d(a, 1)
            "level_input_cut" -> if (d(a, 0, 1.0).toInt() == 1) v.inputL = d(a, 2) else v.inputR = d(a, 2)
            else -> return false
        }
        return true
    }

    private fun slewCoefficient(seconds: Double) = if (seconds <= 0) 1.0 else 1 - exp(-1.0 / (seconds * sampleRate))

    private fun voiceSample(v: Voice): Double {
        val dt = v.hz / sampleRate
        v.phase += dt; if (v.phase >= 1) v.phase -= 1
        // PolyBLEP pulse: a saw minus a saw shifted by the pulse width.
        fun blep(t: Double): Double = when {
            t < dt -> { val x = t / dt; x + x - x * x - 1 }
            t > 1 - dt -> { val x = (t - 1) / dt; x * x + x + x + 1 }
            else -> 0.0
        }
        val p2 = (v.phase + 1 - v.pw).let { it - floor(it) }
        val saw1 = 2 * v.phase - 1 - blep(v.phase)
        val saw2 = 2 * p2 - 1 - blep(p2)
        val x = (saw1 - saw2) * 0.5
        // A four-pole ladder: `gain` feeds back as resonance, tanh at its input as MoogFF's drive.
        val g = 1 - exp(-2 * PI * min(v.cutoff, sampleRate * 0.45) / sampleRate)
        val k = v.gain.coerceIn(0.0, 3.9)
        var u = tanh(x - k * v.s[3])
        for (i in 0..3) { v.s[i] += g * (u - v.s[i]); u = v.s[i] }
        // Env.perc: 10 ms linear attack, then a −4 curve to zero over `release`.
        val attack = 0.01
        val env = if (v.t < attack) v.t / attack else {
            val r = (v.t - attack) / v.release
            if (r >= 1) { v.done = true; 0.0 } else 1 - (1 - exp(-4 * r)) / (1 - exp(-4.0))
        }
        v.t += 1.0 / sampleRate
        return v.s[3] * env * v.amp * (1 + k * 0.25)
    }

    private fun cutRead(buf: FloatArray, pos: Double): Double {
        val i = floor(pos * sampleRate).toInt(); val f = pos * sampleRate - i
        val a = buf.getOrElse(i) { 0f }; val b = buf.getOrElse(i + 1) { 0f }
        return a + (b - a) * f
    }

    /** Render [n] frames into [outL]/[outR] after applying every queued command. */
    fun process(outL: FloatArray, outR: FloatArray, n: Int, offset: Int = 0) {
        drain()
        for (f in 0 until n) {
            var engL = 0.0; var engR = 0.0
            val it = voices.iterator()
            while (it.hasNext()) {
                val v = it.next()
                val s = voiceSample(v)
                if (v.done) { it.remove(); continue }
                engL += s * sqrt((1 - v.pan.coerceIn(-1.0, 1.0)) / 2); engR += s * sqrt((1 + v.pan.coerceIn(-1.0, 1.0)) / 2)
            }
            engL *= levelEng; engR *= levelEng
            var cutL = 0.0; var cutR = 0.0
            for (v in cuts) {
                if (!v.enable) continue
                v.levelNow += (v.level - v.levelNow) * slewCoefficient(v.levelSlew)
                v.rateNow += (v.rate - v.rateNow) * slewCoefficient(v.rateSlew)
                val buf = buffers[v.buffer - 1]
                if (v.play || v.rec) {
                    if (v.play) {
                        val s = cutRead(buf, v.pos) * v.levelNow
                        cutL += s * sqrt((1 - v.pan.coerceIn(-1.0, 1.0)) / 2); cutR += s * sqrt((1 + v.pan.coerceIn(-1.0, 1.0)) / 2)
                    }
                    if (v.rec) {
                        val i = (v.pos * sampleRate).toInt()
                        if (i in buf.indices) buf[i] = (buf[i] * v.preLevel + (engL + engR) * 0.5 * levelEngCut * v.recLevel).toFloat()
                    }
                    v.pos += v.rateNow / sampleRate
                    val lo = min(v.loopStart, v.loopEnd); val hi = max(v.loopStart, v.loopEnd)
                    if (v.pos >= hi) { if (v.loop) v.pos = lo + (v.pos - hi) % max(1e-4, hi - lo) else { v.pos = hi; v.play = false } }
                    if (v.pos < lo) { if (v.loop) v.pos = hi - (lo - v.pos) % max(1e-4, hi - lo) else { v.pos = lo; v.play = false } }
                }
            }
            outL[offset + f] = (engL + cutL * levelCut).toFloat()
            outR[offset + f] = (engR + cutR * levelCut).toFloat()
        }
    }

    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) = process(outL, outR, numFrames)

    // ── offline ──────────────────────────────────────────────────────────────────────────────────
    private var recL = FloatArray(0); private var recR = FloatArray(0)

    /** Frames rendered offline so far. */
    var offlineFrames = 0
        private set

    /** Render offline up to [seconds] since the render began. */
    fun renderOffline(seconds: Double) {
        val target = (seconds * sampleRate).roundToInt()
        if (target <= offlineFrames) { drain(); return }
        if (target > recL.size) {
            val size = max(target, recL.size * 2)
            recL = recL.copyOf(size); recR = recR.copyOf(size)
        }
        process(recL, recR, target - offlineFrames, offlineFrames)
        offlineFrames = target
    }

    /** What the offline render made, left and right. */
    fun recorded(): Pair<FloatArray, FloatArray> = recL.copyOf(offlineFrames) to recR.copyOf(offlineFrames)
}

private class Pcm(val rate: Int, val frames: Int, private val data: Array<FloatArray>) {
    val channels get() = data.size
    fun channel(c: Int) = data[c.coerceIn(0, data.size - 1)]
}

private fun readWav(path: String): Pcm? = runCatching {
    AudioSystem.getAudioInputStream(File(path)).use { raw ->
        val fmt = AudioFormat(raw.format.sampleRate, 16, raw.format.channels, true, false)
        AudioSystem.getAudioInputStream(fmt, raw).use { s ->
            val bytes = s.readAllBytes()
            val ch = fmt.channels; val frames = bytes.size / (2 * ch)
            val data = Array(ch) { FloatArray(frames) }
            for (i in 0 until frames) for (c in 0 until ch) {
                val j = (i * ch + c) * 2
                data[c][i] = ((bytes[j].toInt() and 0xff) or (bytes[j + 1].toInt() shl 8)).toShort() / 32768f
            }
            Pcm(fmt.sampleRate.toInt(), frames, data)
        }
    }
}.getOrNull()

/** A box's norns: its screen, and the sound its current run (live or offline) makes. */
internal class NornsBox {
    val screen = NornsScreen()
    var sound: NornsSound? = null
    var live: AutoCloseable? = null
    fun stopLive() { live?.let { runCatching { it.close() } }; live = null }
}

private val nornsBoxes = WeakHashMap<Box, NornsBox>()
internal fun nornsOf(box: Box): NornsBox = synchronized(nornsBoxes) { nornsBoxes.getOrPut(box) { NornsBox() } }

private fun num(a: List<Any?>, i: Int, default: Double) = (a.getOrNull(i) as? Number)?.toDouble() ?: default

/**
 * The raw handlers `norns.lua` calls through the prelude, under `__field_raw.norns_*`.
 *
 * - `norns_begin(mode)`: a new sound for the box; `"live"` registers it with [FieldAudioEngine] for
 *   the running launch, `"offline"` keeps it for `norns_advance`/`norns_finish`. Returns the sample rate.
 * - `norns_screen(ops, {scale=, png=, show=})`: draw one `screen.update()`'s operations; shows the
 *   screen in the `norns` panel when it changed, writes a PNG when asked. Returns the unknown ops.
 * - `norns_peek(x, y, w, h)`, `norns_text_width(s, size)`: what `screen.peek` and `screen.text_extents` need.
 * - `norns_cmd(kind, name, args)`: an engine, softcut or audio command.
 * - `norns_advance(seconds)`, `norns_finish({wav=, show=, channel=, label=})`: the offline render.
 * - `norns_controls(fn)`: K1–K3 and E1–E3 as a row of momentary keys; fn("k2", true) on the way down.
 * - `norns_file_info(path)`: channels, frames and sample rate of a sound file, as `audio.file_info`.
 */
internal fun nornsHandlersFor(box: Box): Map<String, LuaState.Handler> = mapOf(
    "norns_begin" to LuaState.Handler { _, args ->
        val n = nornsOf(box)
        n.stopLive()
        val live = args.getOrNull(0)?.toString() == "live"
        val rate = if (live) runCatching { FieldAudioEngine.shared().sampleRate }.getOrDefault(48_000) else 48_000
        val sound = NornsSound(rate)
        n.sound = sound
        if (live) {
            val handle = FieldAudioEngine.shared().register(audio(sound, gain = 1f))
            val closer = AutoCloseable { runCatching { handle.close() } }
            n.live = closer
            RunScope.current()?.own(closer, ReleasePolicy.RUNNING)
        }
        listOf(rate.toDouble())
    },
    "norns_screen" to LuaState.Handler { _, args ->
        val n = nornsOf(box)
        val unknown = n.screen.apply(args.getOrNull(0) as? List<*> ?: emptyList<Any?>())
        val options = args.getOrNull(1) as? Map<*, *> ?: emptyMap<Any?, Any?>()
        options["png"]?.toString()?.let { File(it).apply { parentFile?.mkdirs() }.writeBytes(n.screen.png(num(listOf(options["png_scale"]), 0, 4.0).toInt())) }
        if (options["show"] != false) {
            n.screen.htmlIfChanged(num(listOf(options["scale"]), 0, 4.0).toInt())?.let { box.display(options["channel"]?.toString() ?: "norns", it) }
        }
        listOf(unknown.toList())
    },
    "norns_peek" to LuaState.Handler { _, a ->
        listOf(nornsOf(box).screen.peek(num(a, 0, 0.0).toInt(), num(a, 1, 0.0).toInt(), num(a, 2, 128.0).toInt(), num(a, 3, 64.0).toInt()))
    },
    "norns_text_width" to LuaState.Handler { _, a -> listOf(nornsOf(box).screen.textWidth(a.getOrNull(0)?.toString() ?: "", num(a, 1, 8.0).toInt())) },
    "norns_cmd" to LuaState.Handler { _, a ->
        val sound = nornsOf(box).sound
        if (sound != null) sound.command(a.getOrNull(0)?.toString() ?: "", a.getOrNull(1)?.toString() ?: "", a.getOrNull(2) as? List<Any?> ?: emptyList())
        emptyList()
    },
    "norns_unhandled" to LuaState.Handler { _, _ -> listOf(nornsOf(box).sound?.unhandled?.sorted() ?: emptyList<String>()) },
    "norns_advance" to LuaState.Handler { _, a ->
        val sound = nornsOf(box).sound ?: throw IllegalStateException("norns.render has not begun")
        sound.renderOffline(num(a, 0, 0.0))
        listOf(sound.offlineFrames.toDouble())
    },
    "norns_finish" to LuaState.Handler { _, a ->
        val sound = nornsOf(box).sound ?: throw IllegalStateException("norns.render has not begun")
        val options = a.getOrNull(0) as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val (l, r) = sound.recorded()
        options["wav"]?.toString()?.let { File(it).parentFile?.mkdirs(); WavFloatArrayWriter.writeStereo16(it, l, r, sound.sampleRate) }
        val mono = FloatArray(l.size) { (l[it] + r[it]) * 0.5f }
        if (options["show"] != false) {
            box.display(options["channel"]?.toString() ?: "sound",
                AudioVisualizer.renderSamplesHtml(mono, sound.sampleRate, label = options["label"]?.toString() ?: "norns"))
        }
        val peak = max(l.maxOfOrNull { abs(it) } ?: 0f, r.maxOfOrNull { abs(it) } ?: 0f).toDouble()
        val rms = if (mono.isEmpty()) 0.0 else sqrt(mono.sumOf { it.toDouble() * it } / mono.size)
        var onsets = 0
        run {
            // Onsets: 10 ms windows whose energy jumps above twice the last window's and a floor.
            val w = sound.sampleRate / 100; var prev = 0.0
            var i = 0
            while (i + w <= mono.size) {
                var e = 0.0; for (j in i until i + w) e += mono[j].toDouble() * mono[j]
                e /= w
                if (e > 2 * prev && e > 1e-5) onsets++
                prev = e; i += w
            }
        }
        listOf(linkedMapOf("frames" to l.size, "seconds" to l.size.toDouble() / sound.sampleRate, "sampleRate" to sound.sampleRate,
            "peak" to peak, "rms" to rms, "onsets" to onsets))
    },
    "norns_controls" to LuaState.Handler { _, a ->
        val fn = a.getOrNull(0) as? LuaState.LuaFunction ?: throw IllegalArgumentException("norns_controls(fn)")
        val names = listOf("k1", "k2", "k3", "e1-", "e1+", "e2-", "e2+", "e3-", "e3+")
        box.controlGroup(a.getOrNull(1)?.toString() ?: "controls") {
            keys("K1", "K2", "K3", "E1 −", "E1 +", "E2 −", "E2 +", "E3 −", "E3 +") { i, down -> nornsCallBack(box, fn, names[i], down) }
        }
        emptyList()
    },
    "norns_file_info" to LuaState.Handler { _, a ->
        val pcm = readWav(a.getOrNull(0)?.toString() ?: "")
        if (pcm == null) listOf(0.0, 0.0, 0.0) else listOf(pcm.channels.toDouble(), pcm.frames.toDouble(), pcm.rate.toDouble())
    },
)

private fun nornsCallBack(box: Box, fn: LuaState.LuaFunction, name: String, down: Boolean) {
    afield.runtime.lifecycle.inMain {
        if (fn.state.isClosed) return@inMain
        val r = RunScope.withCurrent(box.runScopes.ambient()) { fn.state.callGuarded(fn, name, down) }
        if (r is LuaState.Result.Err) box.displayError(r.message, "code", r.line ?: -1)
    }
}
