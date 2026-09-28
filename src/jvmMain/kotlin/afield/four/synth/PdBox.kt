@file:afield.CompletionCategory("Synthesis")
package afield.four.synth

import afield.Box
import afield.ReturnValueRegistry
import afield.RunsAt
import afield.controlGroup
import afield.inMain
import afield.pd.PdInstance
import afield.pd.PdLibrary
import afield.pd.PdPatch
import afield.registerLaunchJob
import afield.returnValueRegistration
import afield.showSignals
import afield.four.audio.WavFloatArrayWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.yield
import java.io.Closeable
import java.io.File

/**
 * The box-facing side of Pure Data: a patch as a sound source, with messages both ways.
 *
 * ```kotlin
 * val patch = pd("""
 * #X obj 20 20 r freq;
 * #X obj 20 60 osc~ 220;
 * #X obj 20 100 *~ 0.1;
 * #X obj 20 140 dac~;
 * #X connect 0 0 1 0;
 * #X connect 1 0 2 0;
 * #X connect 2 0 3 0;
 * #X connect 2 0 3 1;
 * """)
 * controlGroup { slider("freq", 100.0, 1000.0, initialValue = 220.0) { patch.send("freq", it) } }
 * patch
 * ```
 *
 * Returning the [PdLaunch] as the box's last expression is what makes it sound: it joins
 * [FieldAudioEngine] as a [afield.pd.PdNode] — the same seam Faust and `patSynth` use — and stopping
 * the box stops the sound and frees the Pd. Everything Pd prints (`[print]`, errors) goes to the
 * box's output, and an object Pd could not create is marked on the box's own line.
 *
 * Every launch is its own Pd, so two boxes can both use `[r freq]` without hearing each other.
 * libpd is an optional native library; see [PdLibrary].
 */

/**
 * Open a Pure Data patch written as text, ready to play.
 *
 * @param patch the contents of a `.pd` file; a `#N canvas` header is supplied when missing, and the
 *   text may be indented
 * @param gain applied on the way into the audio engine
 * @param printToBox send what Pd prints to this box's output
 * @param lineOffset box line on which the patch text starts; inferred when negative
 * @param input feeds the patch's `[adc~]`, left and right, which makes the patch an effect on a
 *   Field signal rather than a source; without it `[adc~]` hears silence
 * @throws PdUnavailable when libpd has not been built
 */
fun Box.pd(
    patch: String,
    gain: Float = 1f,
    printToBox: Boolean = true,
    lineOffset: Int = -1,
    input: StereoRenderable? = null,
): PdLaunch {
    requirePd()
    val engine = FieldAudioEngine.shared()
    val instance = PdInstance(sampleRate = engine.sampleRate, inputs = if (input != null) 2 else 0)
    val opened = try {
        instance.openPatchText(patch, "box_${id.filter { it.isLetterOrDigit() }.take(40)}")
    } catch (t: Throwable) {
        instance.close()
        throw t
    }
    val offset = if (lineOffset >= 0) lineOffset else inferFaustLineOffset(this, patch)
    reportPdProblems(opened, offset)
    return PdLaunch(instance, opened, gain, if (printToBox) this else null, input)
}

/**
 * Open a `.pd` file — one drawn in Pd itself — ready to play. Abstractions next to it are found.
 *
 * Problems Pd reports while opening go to the box's output; they are not placed on a line, because
 * the lines are the file's, not the box's.
 */
fun Box.pd(file: File, gain: Float = 1f, printToBox: Boolean = true): PdLaunch {
    requirePd()
    val engine = FieldAudioEngine.shared()
    val instance = PdInstance(sampleRate = engine.sampleRate)
    val opened = try {
        instance.openPatch(file)
    } catch (t: Throwable) {
        instance.close()
        throw t
    }
    for (p in opened.problems) runCatching { println("Pd: ${p.message}") }
    return PdLaunch(instance, opened, gain, if (printToBox) this else null)
}

/** libpd is not built; the message says how to build it. */
class PdUnavailable(message: String) : IllegalStateException(message)

private fun requirePd() {
    if (!PdLibrary.isAvailable) throw PdUnavailable(PdLibrary.describe())
}

private fun Box.reportPdProblems(patch: PdPatch, offset: Int) {
    for (p in patch.problems) {
        if (p.line >= 0) runCatching { error(p.message, "code", offset + p.line) }
        runCatching { println("Pd: ${p.message}") }
    }
}

/**
 * A running Pd patch, and the handle a box holds it by.
 *
 * *Not* a [StereoRenderable], for the reason [FaustLaunch] is not: the generic audio handler would
 * close only the audio patch and leave the Pd instance alive. [registerPdLaunchReturnValue] closes
 * both.
 *
 * Sends are queued and reach Pd at the start of the next audio block; receives are delivered on the
 * box's main-thread loop, never on the audio thread. [pd] is the whole [PdInstance] when you need
 * more than this surface.
 */
class PdLaunch internal constructor(
    val pd: PdInstance,
    val patch: PdPatch,
    val gain: Float,
    private val printBox: Box?,
    private val input: StereoRenderable? = null,
) : Closeable {

    /** What joins the audio engine: this launch's Pd, fed by its `input` when it has one. */
    val node: afield.pd.PdNode by lazy { if (input == null) pd.node else afield.pd.PdNode(pd, input) }

    private val printing: Closeable? = printBox?.let { box -> pd.onPrint { line -> runCatching { box.println("Pd: $line") } } }
    private val receipts = ArrayList<Closeable>()

    /** This patch's `$0`: `send("${launch.dollarZero}-freq", 440)` reaches `[r $0-freq]`. */
    val dollarZero: Int get() = patch.dollarZero

    /** Send a number to `[r receiver]`. */
    fun send(receiver: String, value: Number) = pd.send(receiver, value)

    /** Send a bang to `[r receiver]`. */
    fun bang(receiver: String) = pd.bang(receiver)

    /** Send a symbol to `[r receiver]`. */
    fun symbol(receiver: String, symbol: String) = pd.symbol(receiver, symbol)

    /** Send a list to `[r receiver]`. */
    fun list(receiver: String, vararg items: Any) = pd.list(receiver, *items)

    /** Send `selector args…` to `[r receiver]`. */
    fun message(receiver: String, selector: String, vararg args: Any) = pd.message(receiver, selector, *args)

    /** Call [callback] with what the patch sends to `[s name]`, on the box's main-thread loop. */
    fun receive(name: String, callback: (PdInstance.Message) -> Unit): Closeable =
        pd.receive(name, callback).also { receipts += it }

    /** The last message seen on a [receive]d name. */
    fun lastValue(name: String): PdInstance.Message? = pd.lastValue(name)

    /** Read Pd array `name`. */
    fun readArray(name: String, offset: Int = 0, count: Int = -1): FloatArray = pd.readArray(name, offset, count)

    /** Write into Pd array `name`. */
    fun writeArray(name: String, values: FloatArray, offset: Int = 0) = pd.writeArray(name, values, offset)

    /** Length of Pd array `name`, or -1. */
    fun arraySize(name: String): Int = pd.arraySize(name)

    /** Deliver what Pd has sent since the last call. The launch loop does this every frame. */
    fun poll(): Int = pd.poll()

    override fun close() {
        runCatching { printing?.close() }
        receipts.forEach { runCatching { it.close() } }
        pd.close()
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "patch" to patch.file.name,
        "\$0" to dollarZero,
        "problems" to patch.problems.map { it.message },
        "gain" to gain,
    )
}

/**
 * A slider for every horizontal or vertical Pd slider (`hsl`/`vsl`) in the patch that has a receive
 * name, a checkbox for every toggle (`tgl`), and a number slider for every `nbx`.
 *
 * The range and initial value are Pd's own, read from the patch text, and the control sends to the
 * object's receive name — so the Pd slider moves with it and its outlet fires as though it had been
 * dragged. Nobody writes the sliders twice; this is [faustControls] for Pd.
 *
 * @return how many controls were made
 */
fun Box.pdControls(launch: PdLaunch, channel: String = "controls"): Int {
    val guis = pdGuiControls(launch.patch.source)
    if (guis.isEmpty()) return 0
    controlGroup(channel) {
        for (g in guis) {
            if (g.toggle) {
                checkbox(g.label, g.init != 0.0) { on -> launch.send(g.receive, if (on) g.max else 0.0) }
            } else {
                slider(g.label, g.min, g.max, step = (g.max - g.min) / 200.0, initialValue = g.init) { v ->
                    launch.send(g.receive, v)
                }
            }
        }
    }
    return guis.size
}

/** One Pd GUI object with a receive name, as [pdControls] reads it. */
data class PdGuiControl(
    val kind: String,
    val receive: String,
    val label: String,
    val min: Double,
    val max: Double,
    val init: Double,
) {
    val toggle: Boolean get() = kind == "tgl"
}

/**
 * The `hsl`, `vsl`, `tgl` and `nbx` objects in [patchText] that have a receive name.
 *
 * Pd's file format for these is positional, and `empty` means "no name". Only the fields that set
 * range, receive name, label and initial value are read.
 */
fun pdGuiControls(patchText: String): List<PdGuiControl> {
    val out = ArrayList<PdGuiControl>()
    for (raw in patchText.lines()) {
        val t = raw.trim().removeSuffix(";")
        if (!t.startsWith("#X obj ")) continue
        val f = t.split(Regex("\\s+"))
        if (f.size < 5) continue
        val kind = f[4]
        val a = f.drop(5)
        fun num(i: Int, default: Double) = a.getOrNull(i)?.toDoubleOrNull() ?: default
        fun name(i: Int) = a.getOrNull(i)?.takeIf { it != "empty" && it != "-" }
        when (kind) {
            "hsl", "vsl" -> {
                // 0 width, 1 height, 2 bottom, 3 top, 4 log, 5 init, 6 send, 7 receive, 8 label,
                // 9-15 label placement and colours, 16 stored value, 17 steady-on-click
                val receive = name(7) ?: continue
                val min = num(2, 0.0)
                val max = num(3, 127.0)
                // `value` is stored ×100 of the slider's pixel travel; only meaningful when init == 1.
                val init = if (num(5, 0.0) != 0.0) {
                    val width = num(if (kind == "hsl") 0 else 1, 128.0)
                    val stored = num(16, 0.0) / 100.0
                    min + (max - min) * (stored / (width - 1).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                } else min
                out += PdGuiControl(kind, receive, name(8) ?: receive, min, max, init)
            }
            "tgl" -> {
                // size init send receive label x y font size bg fg lbl init_value nonzero
                val receive = name(3) ?: continue
                val nonzero = num(13, 1.0)
                val init = if (num(1, 0.0) != 0.0) num(12, 0.0) else 0.0
                out += PdGuiControl(kind, receive, name(4) ?: receive, 0.0, nonzero, init)
            }
            "nbx" -> {
                // width height min max log init send receive label ...
                val receive = name(7) ?: continue
                val min = num(2, -1e37).coerceAtLeast(-1e6)
                val max = num(3, 1e37).coerceAtMost(1e6)
                out += PdGuiControl(kind, receive, name(8) ?: receive, min, max, min.coerceAtLeast(0.0).coerceAtMost(max))
            }
        }
    }
    return out
}

/**
 * Teach Field that a [PdLaunch] is something a box can be running.
 *
 * The shape of [registerFaustLaunchReturnValue], with one addition: the loop that holds the box open
 * also [PdLaunch.poll]s every frame, which is where `[s]` messages and `[print]` lines reach Kotlin —
 * on the main thread, where a callback may touch the box.
 */
@RunsAt(returnValueRegistration, priority = 10)
fun registerPdLaunchReturnValue() {
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, launchEpoch, notify ->
        val launch = returnValue as? PdLaunch ?: return@handler false

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
 * Render a patch offline and plot it — no audio device, no audio engine.
 *
 * Messages in [before] are sent after the patch opens and before the first sample, so a patch whose
 * sound depends on a `[r]` can still be drawn. Prints go to the box's output.
 *
 * @return the rendered left and right channels, for measuring
 */
fun Box.pdWaveform(
    patch: String,
    seconds: Double = 0.02,
    sampleRate: Int = 48_000,
    channel: String = "output",
    maxPoints: Int = 2000,
    before: (PdInstance) -> Unit = {},
): Pair<FloatArray, FloatArray> {
    requirePd()
    val (left, right) = PdInstance(sampleRate).use { instance ->
        instance.onPrint { line -> runCatching { println("Pd: $line") } }
        val opened = instance.openPatchText(patch)
        reportPdProblems(opened, inferFaustLineOffset(this, patch))
        before(instance)
        instance.renderOffline(seconds)
    }
    val stride = (left.size / maxPoints).coerceAtLeast(1)
    val indices = left.indices step stride
    val time = indices.map { it.toDouble() / sampleRate }
    val series = if (left.contentEquals(right)) arrayOf("out" to indices.map { left[it] })
    else arrayOf("left" to indices.map { left[it] }, "right" to indices.map { right[it] })
    showSignals(*series, x = time, channel = channel,
        title = "Pd — ${"%.0f".format(seconds * 1000)}ms", xTitle = "seconds", yTitle = "amplitude")
    return left to right
}

/**
 * Render a patch offline to a stereo 16-bit `.wav`. No audio device is opened.
 *
 * @param before messages to send once the patch is open, before the first sample
 */
fun renderPdWav(
    path: File,
    patch: String,
    seconds: Double,
    sampleRate: Int = 48_000,
    before: (PdInstance) -> Unit = {},
): File {
    requirePd()
    val (left, right) = PdInstance(sampleRate).use { instance ->
        instance.openPatchText(patch)
        before(instance)
        instance.renderOffline(seconds)
    }
    path.absoluteFile.parentFile?.mkdirs()
    WavFloatArrayWriter.writeStereo16(path, left, right, sampleRate, null)
    return path
}
