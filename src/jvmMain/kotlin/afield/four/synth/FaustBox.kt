@file:afield.CompletionCategory("Synthesis")
package afield.four.synth

import afield.Box
import afield.ReturnValueRegistry
import afield.RunsAt
import afield.controlGroup
import afield.inMain
import afield.registerLaunchJob
import afield.returnValueRegistration
import afield.showSignals
import kotlinx.coroutines.Job
import kotlinx.coroutines.yield
import java.io.Closeable

/**
 * A box that is a signal processor.
 *
 * ```kotlin
 * faust("""
 * #faust
 * import("stdfaust.lib");
 * freq = hslider("freq [unit:Hz]", 220, 50, 1000, 0.01);
 * process = os.sawtooth(freq) : fi.lowpass(2, 1200) * 0.2 <: _,_;
 * """)
 * ```
 *
 * Returning the result as the box's last expression is what ties the sound to the box: it stays lit
 * while the processor runs, and stopping the box stops the sound and releases the DSP. That is the
 * same path any other Field audio launch takes.
 *
 * Three things happen that are worth knowing about.
 *
 * **The `#faust` marker is removed before compiling.** `#` is not a comment in Faust — unlike Ruby,
 * where `#sonicpi` can stay in the program Sonic Pi is handed — so it would be a syntax error. Every
 * line number the compiler reports is therefore shifted back onto the box's own lines before a marker
 * is placed; see [stripFaustHeader] and [inferFaustLineOffset].
 *
 * **The controls are generated.** A Faust program declares its own interface (`hslider`, `button`,
 * `hgroup`), and [faustControls] turns that declaration into a real Field control group — which is
 * also, for free, a remote control surface, because a control group is what the controller websocket
 * serves to a phone or an iPad. Nobody writes the sliders.
 *
 * **The first compile in a session is slow.** libfaust runs as WebAssembly under GraalVM, which needs
 * 13–22s of warm-up before it settles at roughly 600ms a compile. Call
 * [FaustEngine.warmInBackground] from a workspace that is going to use Faust and that cost is paid
 * while you are still typing.
 *
 * @param source Faust source, normally a `"""` literal beginning with a `#faust` line
 * @param gain applied to the DSP's output on the way into the audio engine
 * @param controls generate a control group from the program's own UI declaration
 * @param diagram also show Faust's block diagram for the program
 * @param input feeds the DSP's inputs, for a program that declares any (an effect rather than a
 *   synth); a program with inputs and no `input` hears silence
 * @param channel output disclosure that receives the controls and diagram
 * @param lineOffset box line on which the source starts; inferred when negative
 * @throws FaustCompileError if the program does not compile — the message is also placed on the
 *   offending line of the box
 */
fun Box.faust(
    source: String,
    gain: Float = 1f,
    controls: Boolean = true,
    diagram: Boolean = false,
    input: StereoRenderable? = null,
    channel: String = "controls",
    lineOffset: Int = -1
): FaustLaunch {
    val (body, headerLines) = stripFaustHeader(source)
    val offset = if (lineOffset >= 0) lineOffset else inferFaustLineOffset(this, source)

    val program = try {
        FaustEngine.compile(faustModuleName(this), body)
    } catch (e: FaustCompileError) {
        // Both channels, for the same reason Sonic Pi uses both: the marker puts the failure on the
        // line, and the text makes it readable. Faust's own message is terse but accurate.
        val boxLine = offset + headerLines + ((e.line ?: 1) - 1)
        runCatching { error(e.faustMessage, "code", boxLine) }
        runCatching { println("Faust: ${e.raw.trim()}") }
        throw e
    }

    val engine = FieldAudioEngine.shared()
    val dsp = FaustDsp(program, engine.sampleRate)
    val launch = FaustLaunch(program, dsp, FaustNode(dsp, input), gain)

    if (controls && program.ui.writable.isNotEmpty()) {
        faustControls(launch, channel)
    }
    if (diagram) {
        runCatching { faustDiagram(program, channel) }
            .onFailure { println("Faust: could not draw the block diagram: ${it.message}") }
    }

    return launch
}

/** Compile `source` without running it — for inspecting a program's interface or its diagram. */
fun Box.faustCompile(source: String): FaustProgram {
    val (body, headerLines) = stripFaustHeader(source)
    val offset = inferFaustLineOffset(this, source)
    return try {
        FaustEngine.compile(faustModuleName(this), body)
    } catch (e: FaustCompileError) {
        runCatching { error(e.faustMessage, "code", offset + headerLines + ((e.line ?: 1) - 1)) }
        throw e
    }
}

/**
 * A running Faust program, and the handle a box holds it by.
 *
 * *Not* a [StereoRenderable]: if it were, the generic audio return-value handler would
 * claim it and close only the audio patch, leaving this object's GraalVM context alive for the life
 * of the process. One leaked context per box run is exactly the kind of slow leak that only shows up
 * in a long session, so [registerFaustLaunchReturnValue] handles it instead and closes both.
 */
class FaustLaunch internal constructor(
    val program: FaustProgram,
    val dsp: FaustDsp,
    internal val node: FaustNode,
    val gain: Float
) : Closeable {

    private var closed = false

    /**
     * The controls this program declares.
     *
     * Alphabetical within each group, not in declaration order — that is how Faust lists them. Use
     * [set] with a label rather than indexing into this.
     */
    val controls: List<FaustControl> get() = program.ui.writable

    /** The meters (`hbargraph`/`vbargraph`) this program declares. */
    val meters: List<FaustControl> get() = program.ui.meters

    private fun find(label: String): FaustControl? =
        controls.firstOrNull { it.label == label || it.qualifiedLabel == label || it.address == label } ?:
            meters.firstOrNull { it.label == label || it.qualifiedLabel == label || it.address == label }

    /** Set a control by label, address or qualified name. Silently ignores an unknown name. */
    operator fun set(label: String, value: Double) {
        find(label)?.let { dsp.setParam(it, value) }
    }

    /** Set a control. */
    fun set(control: FaustControl, value: Double) = dsp.setParam(control, value)

    /** Read a meter's most recent value, or 0 for an unknown name. */
    fun meter(label: String): Float = find(label)?.let { dsp.meterValue(it.index) } ?: 0f

    /** Every meter's most recent value, by label. */
    fun meterValues(): Map<String, Float> = meters.associate { it.label to dsp.meterValue(it.index) }

    /**
     * Press and release a `button`, `holdMs` apart.
     *
     * Faust's `button` is momentary — 1 while held — and Field's generated control for it is a
     * checkbox, which latches. This is the momentary version, for triggering a gate from code:
     * `launch.press("gate")`. The release is queued behind the press, and [FaustDsp] applies queued
     * writes in order, so a press and release inside one audio block still both happen.
     */
    fun press(label: String, holdMs: Long = 0L) {
        val control = find(label) ?: return
        dsp.setParam(control, 1.0)
        if (holdMs <= 0L) {
            dsp.setParam(control, 0.0)
        } else {
            // A daemon thread rather than a coroutine: this is allowed to be missed at shutdown.
            kotlin.concurrent.thread(isDaemon = true, name = "faust-press") {
                runCatching { Thread.sleep(holdMs) }
                if (!closed) dsp.setParam(control, 0.0)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        dsp.close()
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "program" to program.name,
        "controls" to controls.map { it.label },
        "meters" to meters.map { it.label },
        "gain" to gain
    )
}

/**
 * Turn a Faust program's own UI declaration into a Field control group.
 *
 * The mapping is closer than it has any right to be: Faust's
 * `hslider(label, init, min, max, step)` and Field's `slider(label, min, max, step, initialValue)`
 * carry the same five values, and [afield.ControlGroupBuilder] already reuses a control's value
 * across box reruns — which is exactly what live-coding a DSP needs, since recompiling must not snap
 * every slider back to its default.
 *
 * Three declarations do not map cleanly, and are handled rather than hidden:
 *
 *  * `button` is momentary and Field's checkbox latches, so it becomes a checkbox labelled `(hold)`.
 *    [FaustLaunch.press] is the momentary path for code.
 *  * `hbargraph`/`vbargraph` are outputs, not inputs, so they are not controls at all — read them
 *    with [FaustLaunch.meter].
 *  * `[scale:log]` is not something a Field slider can do yet, so the slider stays linear over the
 *    declared range. The declared numbers remain correct, which is the half worth keeping; see
 *    [FaustControl.fromNormalised] for the law itself.
 */
fun Box.faustControls(launch: FaustLaunch, channel: String = "controls") {
    controlGroup(channel) {
        for (control in launch.controls) {
            when {
                control.isToggle -> checkbox(control.displayLabel, control.init != 0.0) { on ->
                    launch.set(control, if (on) 1.0 else 0.0)
                }

                control.isMomentary -> checkbox("${control.displayLabel} (hold)", false) { on ->
                    launch.set(control, if (on) 1.0 else 0.0)
                }

                else -> slider(
                    label = control.displayLabel,
                    min = control.min,
                    max = control.max,
                    // A step of 0 means continuous in Faust and would divide by zero in a UI.
                    step = if (control.step > 0.0) control.step else (control.max - control.min) / 200.0,
                    initialValue = control.init
                ) { value ->
                    launch.set(control, value)
                }
            }
        }
    }
}

/**
 * Show Faust's block diagram for a program.
 *
 * This is the canonical way to read a Faust program — the language is about signal flow, and the
 * diagram *is* the signal flow. Faust emits a set of linked SVGs, one per sub-expression; only the
 * top-level `process.svg` is shown here, because the links between them are file references that
 * mean nothing to a browser that never had the files.
 */
fun Box.faustDiagram(program: FaustProgram, channel: String = "diagram") {
    val svgs = FaustEngine.blockDiagram(program.name, program.source)
    val svg = svgs["process.svg"] ?: return
    display(channel, """<div class="faust-diagram">$svg</div>""")
}

/**
 * Render a program offline and plot the result.
 *
 * Nothing here touches the audio engine, so this works headless — which makes it the reliable way to
 * put a Faust figure in the documentation, and a quick way to see what a program does without
 * listening to it. The DSP is instantiated, rendered and closed; a program that needs an input hears
 * silence.
 *
 * @param seconds how much audio to render
 * @param sampleRate a plain default rather than the audio engine's rate: asking
 *   [FieldAudioEngine] would open an audio device, and this path is meant to work where there is none
 * @param maxPoints decimate to at most this many points, since a second at 48kHz is far more than a
 *   plot can show or a websocket should carry
 */
fun Box.faustWaveform(
    program: FaustProgram,
    seconds: Double = 0.02,
    sampleRate: Int = 48_000,
    channel: String = "output",
    maxPoints: Int = 2000
) {
    val frames = (seconds * sampleRate).toInt().coerceIn(1, sampleRate * 10)
    val left = FloatArray(frames)
    val right = FloatArray(frames)
    FaustDsp(program, sampleRate).use { dsp -> dsp.render(left, right, frames) }

    val stride = (frames / maxPoints).coerceAtLeast(1)
    val indices = 0 until frames step stride
    val time = indices.map { it.toDouble() / sampleRate }
    val l = indices.map { left[it] }
    val r = indices.map { right[it] }

    // One trace when the two channels are identical — a mono program fanned out would otherwise draw
    // the same line twice and look like a rendering bug.
    val series = if (left.contentEquals(right)) {
        arrayOf("out" to l)
    } else {
        arrayOf("left" to l, "right" to r)
    }
    showSignals(
        *series,
        x = time,
        channel = channel,
        title = "${program.name} — ${"%.0f".format(seconds * 1000)}ms",
        xTitle = "seconds",
        yTitle = "amplitude"
    )
}

/**
 * Which line of `box` the Faust source starts on.
 *
 * The same technique as [inferSonicPiLineOffset], and for the same reason: the compiler counts lines
 * from the start of what it was handed, which is the inside of a Kotlin string, and a marker has to
 * land on the box's line instead. Anchors on the literal where it appears verbatim and falls back to
 * its first non-blank line when `trimIndent()` has reshaped it.
 */
internal fun inferFaustLineOffset(box: Box, source: String): Int {
    val code = runCatching { box.getPropertyRaw("code") as? String }.getOrNull() ?: return 0
    if (code.isEmpty() || source.isEmpty()) return 0
    val at = code.indexOf(source)
    if (at >= 0) return code.take(at).count { it == '\n' }

    val anchor = source.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return 0
    if (anchor.isEmpty()) return 0
    val anchorAt = code.indexOf(anchor)
    if (anchorAt < 0) return 0
    val blankLeading = source.lineSequence().takeWhile { it.isBlank() }.count()
    return (code.take(anchorAt).count { it == '\n' } - blankLeading).coerceAtLeast(0)
}

/** Faust uses the module name as an identifier and as its diagnostic prefix; keep it legal and stable. */
private fun faustModuleName(box: Box): String {
    val id = box.id.filter { it.isLetterOrDigit() || it == '_' }
    return if (id.isEmpty()) "faustbox" else "box_${id.take(40)}"
}

/**
 * Teach Field that a [FaustLaunch] is something a box can be running.
 *
 * Follows [registerAudioReturnValue]'s shape — register the patch, hold the box open while the run
 * generation is current, close in a `finally` — with the one addition that makes it worth having its
 * own handler: the DSP's GraalVM context is closed too.
 */
@RunsAt(returnValueRegistration, priority = 10)
fun registerFaustLaunchReturnValue() {
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, launchEpoch, notify ->
        val launch = returnValue as? FaustLaunch ?: return@handler false

        val handle = FieldAudioEngine.shared().register(audio(launch.node, gain = launch.gain))

        inMain {
            val job = kotlin.coroutines.coroutineContext[Job]!!
            if (!inside.registerLaunchJob(job, launchEpoch)) {
                // Superseded before this even started; the newer launch owns the sound now.
                handle.close()
                launch.close()
                notify(false, strand, generation)
                return@inMain
            }
            notify(true, strand, generation)
            try {
                while (inside.runGeneration[strand] == generation) {
                    yield()
                }
            } finally {
                handle.close()
                launch.close()
                inside.jobs.remove(job)
                notify(false, strand, generation)
            }
        }
        true
    }
}
