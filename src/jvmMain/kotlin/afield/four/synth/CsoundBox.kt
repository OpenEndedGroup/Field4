@file:afield.CompletionCategory("Synthesis")
package afield.four.synth

import afield.Box
import afield.ReturnValueRegistry
import afield.RunsAt
import afield.controlGroup
import afield.csound.CsoundCompileError
import afield.csound.CsoundInstance
import afield.csound.CsoundLibrary
import afield.displayOpen
import afield.inMain
import afield.registerLaunchJob
import afield.returnValueRegistration
import afield.showSignals
import kotlinx.coroutines.Job
import kotlinx.coroutines.yield
import java.io.Closeable
import java.io.File

/**
 * The box-facing side of Csound: an orchestra and a score as a sound source, with control channels
 * both ways.
 *
 * ```kotlin
 * val cs = csound("""
 * chn_k "freq", 1, 2, 220, 55, 880
 * instr 1
 *   kf chnget "freq"
 *   a1 vco2 0.1, kf
 *   outs a1, a1
 * endin
 * """, "i1 0 z")
 * csoundControls(cs)
 * cs
 * ```
 *
 * Returning the [CsoundLaunch] as the box's last expression is what makes it sound: it joins
 * [FieldAudioEngine] as a [afield.csound.CsoundNode], so Csound's perform loop runs on the audio
 * thread and nowhere else, and stopping the box stops the sound and frees the Csound. What Csound
 * prints goes to the box's output; a compile error is marked on the box's own line.
 *
 * Every launch is its own Csound, so two boxes can both use `chnget "freq"` without hearing each
 * other. libcsound is an optional native library; see [CsoundLibrary].
 */

/** libcsound has not been built; the message says how to build it. */
class CsoundUnavailable(message: String) : IllegalStateException(message)

private fun requireCsound() {
    if (!CsoundLibrary.isAvailable) throw CsoundUnavailable(CsoundLibrary.describe())
}

/**
 * Compile an orchestra and score, ready to play.
 *
 * @param orchestra Csound orchestra code — `instr … endin`, `chn_k` declarations, `opcode`s. The
 *   rate header is Field's (`sr` = the audio engine's rate, `0dbfs = 1`), so leave it out
 * @param score Csound score. A score that ends goes silent and stops taking [CsoundLaunch.event]s,
 *   so for a box that should sound until it is stopped give an instrument the duration `z`
 *   (`i1 0 z`). The default, `f0 z`, plays nothing and keeps Csound running forever — for an
 *   orchestra that is played only by [CsoundLaunch.event]
 * @param gain applied on the way into the audio engine
 * @param printToBox send what Csound prints to this box's output
 * @param input feeds `ins`/`inch`, which makes the orchestra an effect on a Field signal
 * @param audioIn Field signals fed into a-rate channels: `mapOf("in" to SawOsc(Param(110f)))` is
 *   read in the orchestra with `ain chnget "in"`. Delayed by one control period (`ksmps` frames)
 * @throws CsoundCompileError when the code does not compile; the errors are also marked on the box
 */
fun Box.csound(
    orchestra: String,
    score: String = "f0 z",
    gain: Float = 1f,
    printToBox: Boolean = true,
    input: StereoRenderable? = null,
    ksmps: Int = 32,
    audioIn: Map<String, Signal> = emptyMap(),
): CsoundLaunch {
    requireCsound()
    val engine = FieldAudioEngine.shared()
    val instance = compileForBox(orchestra) {
        CsoundInstance(orchestra, score, sampleRate = engine.sampleRate, ksmps = ksmps, inputs = if (input != null) 2 else 0)
    }
    return CsoundLaunch(instance, orchestra, gain, if (printToBox) this else null, input, audioIn)
}

/** Build a [CsoundInstance], marking any compile error on the box line it belongs to. */
private fun Box.compileForBox(orchestra: String, make: () -> CsoundInstance): CsoundInstance {
    val offset = inferFaustLineOffset(this, orchestra)
    return try {
        make().also { cs -> cs.poll() }
    } catch (e: CsoundCompileError) {
        // Printed first, marked last: the editor clears a box's marks whenever the box prints.
        for (p in e.problems) if (p.line <= 0) runCatching { println("Csound: ${p.message}") }
        for (p in e.problems) if (p.line > 0) runCatching { error(p.message, "code", offset + p.line) }
        throw e
    }
}

/**
 * Render an orchestra and score with no audio device and put the result in this box's output: a
 * player on `"$channel-audio"` and a waveform on [channel]. Optionally writes a `.wav` too.
 *
 * @param seconds how much to render; null renders until the score ends
 * @param before called once the Csound exists and before the first sample — set channels here
 * @return the left and right channels, for measuring
 */
fun Box.csoundOffline(
    orchestra: String,
    score: String,
    seconds: Double? = null,
    sampleRate: Int = 48_000,
    wav: File? = null,
    channel: String = "main",
    maxPoints: Int = 2400,
    before: (CsoundInstance) -> Unit = {},
): Pair<FloatArray, FloatArray> {
    requireCsound()
    val (left, right) = compileForBox(orchestra) { CsoundInstance(orchestra, score, sampleRate = sampleRate) }.use { cs ->
        cs.onMessage { line -> runCatching { println("Csound: $line") } }
        before(cs)
        cs.renderOffline(seconds)
    }
    if (wav != null) {
        wav.absoluteFile.parentFile?.mkdirs()
        afield.four.audio.WavFloatArrayWriter.writeStereo16(wav, left, right, sampleRate)
    }
    val audioChannel = "$channel-audio"
    displayOpen(audioChannel)
    display(audioChannel, afield.four.audio.WavFloatArrayWriter.encodeStereo16(left, right, sampleRate, normalizePeak = 0.9f))
    val stride = (left.size / maxPoints).coerceAtLeast(1)
    val indices = left.indices step stride
    val series = if (left.contentEquals(right)) arrayOf("out" to indices.map { left[it] })
    else arrayOf("left" to indices.map { left[it] }, "right" to indices.map { right[it] })
    showSignals(*series, x = indices.map { it.toDouble() / sampleRate }, channel = channel,
        title = "Csound — ${"%.2f".format(left.size.toDouble() / sampleRate)} s", xTitle = "seconds", yTitle = "amplitude")
    return left to right
}

/**
 * A running Csound, and the handle a box holds it by.
 *
 * *Not* a [StereoRenderable], for the reason [PdLaunch] is not: the generic audio handler would close
 * only the audio patch and leave Csound alive. [registerCsoundLaunchReturnValue] closes both.
 */
class CsoundLaunch internal constructor(
    val csound: CsoundInstance,
    val orchestra: String,
    val gain: Float,
    private val printBox: Box?,
    private val input: StereoRenderable? = null,
    private val audioIn: Map<String, Signal> = emptyMap(),
) : Closeable {

    /** What joins the audio engine. */
    val node: afield.csound.CsoundNode by lazy { csound.node(input, audioIn) }

    /** The a-rate channel the orchestra `chnset`s into [name], as a Field signal. */
    fun audioOut(name: String): Signal = node.audioOut(name)

    /** Csound's left and right `outs` as Field signals. */
    val outL: Signal get() = node.outL
    val outR: Signal get() = node.outR

    /** Write string channel [name]. */
    fun setString(name: String, value: String) = csound.setStringChannel(name, value)

    /** Read string channel [name]. */
    fun getString(name: String): String = csound.stringChannel(name)

    /** Call [callback] on the main thread with what `outvalue "name", …` sends (a Double or a String). */
    fun onOutvalue(name: String, callback: (Any) -> Unit): Closeable = csound.onOutvalue(name, callback)

    /** Set what `invalue "name"` reads. */
    fun setInvalue(name: String, value: Number) = csound.setInvalue(name, value)

    /** Function table [number]'s values. */
    fun table(number: Int): DoubleArray? = csound.table(number)

    /** Overwrite function table [number]; the next block reads it. */
    fun writeTable(number: Int, values: FloatArray) = csound.writeTable(number, values)

    /** A note on to `massign`ed instruments, 0-based channel. */
    fun noteOn(channel: Int, pitch: Int, velocity: Int) = csound.noteOn(channel, pitch, velocity)

    /** A note off, 0-based channel. */
    fun noteOff(channel: Int, pitch: Int) = csound.noteOff(channel, pitch)

    private val printing: Closeable? = printBox?.let { box -> csound.onMessage { line -> runCatching { box.println("Csound: $line") } } }

    /** Write control channel [name]; `chnget "name"` reads it on the next k-cycle. */
    fun set(name: String, value: Number) = csound.setChannel(name, value)

    /** Read control channel [name], as `chnset` last left it. */
    fun get(name: String): Double = csound.channel(name)

    /** A score line, `i1 0 1 440`, played at the next audio block. */
    fun event(line: String) = csound.event(line)

    /** A score line from numbers: `event('i', 1, 0, 0.5, 440)`. */
    fun event(type: Char, vararg fields: Number) = csound.event(type, *fields)

    /** Compile more instruments into the running Csound. */
    fun compile(orchestra: String) = csound.compile(orchestra)

    private class Ticker(val periodNanos: Long, var next: Long, val block: CsoundLaunch.() -> Unit)
    private val tickers = java.util.concurrent.CopyOnWriteArrayList<Ticker>()

    /**
     * Call [block] every [seconds] while the launch plays, on the box's main-thread loop — the place to
     * write a score from Kotlin: `cs.every(0.25) { event('i', 1, 0, 0.4, 60) }`. The first call is
     * on the first frame. Timing is the main loop's (a frame or so of jitter); for sample-accurate
     * rhythm, put the timing in the score and let Kotlin choose the notes.
     */
    fun every(seconds: Double, block: CsoundLaunch.() -> Unit): Closeable {
        require(seconds > 0.0) { "seconds must be positive" }
        val t = Ticker((seconds * 1e9).toLong(), 0L, block)
        tickers += t
        return Closeable { tickers -= t }
    }

    /**
     * Deliver what Csound has printed since the last call and run any [every] blocks that are due.
     * The launch loop does this every frame.
     */
    fun poll(): Int {
        val now = System.nanoTime()
        for (t in tickers) {
            if (t.next == 0L) t.next = now
            if (now >= t.next) {
                runCatching { t.block(this) }.onFailure { e -> printBox?.let { b -> runCatching { b.println("every: ${e.message}") } } }
                t.next += t.periodNanos
                if (t.next < now) t.next = now + t.periodNanos
            }
        }
        return csound.poll()
    }

    override fun close() {
        runCatching { printing?.close() }
        csound.close()
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "channels" to csoundChannels(orchestra).map { it.name },
        "finished" to csound.finished,
        "gain" to gain,
    )
}

/** One `chn_k` declaration, as [csoundChannels] reads it. */
data class CsoundChannel(val name: String, val mode: Int, val default: Double, val min: Double, val max: Double) {
    /** `chn_k` mode 1 or 3: Csound reads it, so Field may write it. */
    val isInput: Boolean get() = mode and 1 != 0
}

/**
 * The `chn_k "name", imode, itype, idflt, imin, imax` declarations in [orchestra]. A channel declared
 * without a range gets 0..1.
 */
fun csoundChannels(orchestra: String): List<CsoundChannel> {
    val re = Regex("""^\s*chn_k\s+"([^"]+)"\s*,\s*(\d+)(?:\s*,\s*(-?[\d.]+))?(?:\s*,\s*(-?[\d.eE+-]+))?(?:\s*,\s*(-?[\d.eE+-]+))?(?:\s*,\s*(-?[\d.eE+-]+))?""")
    return orchestra.lines().mapNotNull { line ->
        val m = re.find(line.substringBefore(';')) ?: return@mapNotNull null
        val g = m.groupValues
        val min = g[5].toDoubleOrNull() ?: 0.0
        val max = g[6].toDoubleOrNull() ?: 1.0
        CsoundChannel(g[1], g[2].toInt(), (g[4].toDoubleOrNull() ?: min).coerceIn(min, max), min, max)
    }
}

/**
 * A slider for every input `chn_k` channel the orchestra declares, with Csound's own default and
 * range, writing that channel as it moves. The orchestra is the one place the range is written;
 * this is [pdControls] for Csound. Each channel starts at its declared default.
 *
 * @return how many sliders were made
 */
fun Box.csoundControls(launch: CsoundLaunch, channel: String = "controls"): Int {
    // The range comes from Csound's own record of the declaration (csoundGetControlChannelHints);
    // the text is only read for which channels there are and in what order.
    val channels = csoundChannels(launch.orchestra).filter { it.isInput }.map { c ->
        launch.csound.hints(c.name)?.let { h -> c.copy(default = h.default, min = h.min, max = h.max) } ?: c
    }
    if (channels.isEmpty()) return 0
    for (c in channels) launch.set(c.name, c.default)
    controlGroup(channel) {
        for (c in channels) {
            slider(c.name, c.min, c.max, step = (c.max - c.min) / 200.0, initialValue = c.default) { v -> launch.set(c.name, v) }
        }
    }
    return channels.size
}

/**
 * Teach Field that a [CsoundLaunch] is something a box can be running: it joins the audio engine,
 * the loop that holds the box open polls Csound's messages every frame on the main thread, and
 * stopping the box removes it from the engine and frees the Csound.
 */
@RunsAt(returnValueRegistration, priority = 10)
fun registerCsoundLaunchReturnValue() {
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, launchEpoch, notify ->
        val launch = returnValue as? CsoundLaunch ?: return@handler false

        val handle = FieldAudioEngine.shared().register(audio(launch.node, gain = launch.gain))

        inMain {
            val job = kotlin.coroutines.coroutineContext[Job]!!
            if (!inside.registerLaunchJob(job, launchEpoch)) {
                handle.close()
                launch.close()
                notify(false, strand, generation)
                return@inMain
            }
            notify(true, strand, generation)
            try {
                while (inside.runGeneration[strand] == generation) {
                    launch.poll()
                    yield()
                }
            } finally {
                handle.close()
                launch.poll()
                launch.close()
                inside.jobs.remove(job)
                notify(false, strand, generation)
            }
        }
        true
    }
}

/**
 * The playing Csound of the Csound box named [name] in this box's document, so Kotlin can talk to
 * it: `csoundOf("drone").csound.setChannel("cutoff", 900)`.
 *
 * @throws IllegalStateException when no box (or more than one) has that name, it is not a Csound
 *   box, or it is not playing
 */
fun Box.csoundOf(name: String): afield.csound.CsoundExecution.Running {
    val seen = HashSet<Box>()
    val fringe = ArrayDeque(listOf(root))
    val found = ArrayList<Box>()
    while (fringe.isNotEmpty()) {
        val next = fringe.removeFirst()
        if (!seen.add(next)) continue
        if (next.name == name && next.getPropertyRaw("_tombstone") != true) found += next
        fringe.addAll(next.children)
    }
    val box = when (found.size) {
        1 -> found[0]
        0 -> throw IllegalStateException("no box is named '$name'")
        else -> throw IllegalStateException("${found.size} boxes are named '$name'; rename one")
    }
    val execution = box.ke as? afield.csound.CsoundExecution
        ?: throw IllegalStateException("'$name' is not a Csound box: put it under a box whose Main Language is Csound")
    return execution.running ?: throw IllegalStateException("'$name' is not playing: launch it, then talk to it")
}


/**
 * Render [signals] side by side, offline, in [block]-frame audio blocks — what the audio thread
 * would hear, without a device. All of them advance through the same cycles, so a graph they share
 * (a [afield.csound.CsoundNode] and the signals taken from it) is rendered once per block, as live.
 *
 * ```kotlin
 * val (dry, wet) = renderSignalsOffline(1.0, saw, node.audioOut("wet"))
 * ```
 *
 * @return one array per signal, `seconds * sampleRate` frames each
 */
fun renderSignalsOffline(seconds: Double, vararg signals: Signal, sampleRate: Int = 48_000, block: Int = 256): List<FloatArray> {
    val frames = (seconds * sampleRate).toInt().coerceAtLeast(1)
    val ctx = SynthContext(sampleRate.toFloat(), block)
    val outs = signals.map { FloatArray(frames) }
    val buf = FloatArray(block)
    var at = 0
    while (at < frames) {
        val n = minOf(block, frames - at)
        ctx.advanceCycle()
        for ((k, s) in signals.withIndex()) {
            s.render(buf, n, ctx)
            System.arraycopy(buf, 0, outs[k], at, n)
        }
        at += n
    }
    return outs
}
