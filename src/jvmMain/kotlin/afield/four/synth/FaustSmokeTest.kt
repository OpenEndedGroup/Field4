@file:afield.CompletionCategory("Internal")
package afield.four.synth

import kotlin.math.abs
import kotlin.math.max

/*
FIELD_TEST
id: synth.faust.smoke
runner: jvm-main
entrypoint: afield.four.synth.FaustSmokeTest
tags: [smoke, synth, faust, audio, wasm, cpu]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "Faust smoke tests passed"
*/
/**
 * The Faust binding, end to end, without audio hardware.
 *
 * What this is really guarding is the *contract with a vendored third-party compiler*: nothing here
 * is Field's own code being clever, it is a 3MB WebAssembly build of libfaust behaving the way
 * `FaustEngine`'s KDoc claims it does. Every assertion below corresponds to something that was
 * discovered the hard way and would break silently on a version bump:
 *
 *  * the Emscripten environment shim still boots (a `libfaust-wasm.js` that detects Node would call
 *    `require('fs')` and die),
 *  * `createDSPFactory` still returns its wasm in `data` as an embind vector rather than `code`,
 *  * a bad program still reports `name:line : ERROR : ...`, which is the whole diagnostics story,
 *  * `generateAuxFiles(-svg)` still throws *after* writing the diagram,
 *  * and the UI JSON still carries `index`, which is the only reason a slider can move a parameter.
 *
 * The timeout is generous on purpose: the first compile in a process pays 13–22s of Truffle warm-up.
 */
object FaustSmokeTest {

    private const val SR = 48_000

    @JvmStatic
    fun main(args: Array<String>) {
        val ok = try {
            runTests()
        } finally {
            // Leave nothing holding the JVM open; see FaustEngine.shutdown.
            FaustEngine.shutdown()
        }
        check(ok) { "Faust smoke tests failed" }
        println("Faust smoke tests passed")
    }

    fun runTests(): Boolean {
        val report = Reporter()

        println("header stripping")
        checkHeader(report)

        println("compiler")
        val program = checkCompile(report)

        println("ui metadata")
        if (program != null) checkUi(report, program)

        println("diagnostics")
        checkDiagnostics(report)

        println("rendering")
        if (program != null) checkRendering(report, program)

        println("mono fan-out")
        checkMono(report)

        println("effects (inputs)")
        checkEffect(report)

        println("block diagram")
        checkDiagram(report)

        report.summarise()
        return report.failures == 0
    }

    // ── header ─────────────────────────────────────────────────────────────────────────────

    private fun checkHeader(report: Reporter) {
        val (body, offset) = stripFaustHeader("\n#faust\nprocess = _;\n")
        report.check("header removed", !body.contains("#faust")) { body }
        // Two lines went: the leading blank from the Kotlin """ and the marker itself.
        report.check("offset counts the blank line", offset == 2) { "offset=$offset" }
        report.check("body survives", body.trim() == "process = _;") { body }

        val (plain, zero) = stripFaustHeader("process = _;")
        report.check("no header is left alone", plain == "process = _;" && zero == 0) { "offset=$zero" }
    }

    // ── compiling ──────────────────────────────────────────────────────────────────────────

    private val VOICE = """
        import("stdfaust.lib");
        freq = hslider("freq [unit:Hz] [scale:log]", 440, 50, 2000, 0.01);
        cutoff = hslider("cutoff [unit:Hz]", 1200, 50, 8000, 1);
        gain = hslider("gain", 0.3, 0, 1, 0.001);
        level = hbargraph("level", 0, 1);
        process = os.sawtooth(freq) : fi.lowpass(2, cutoff) * gain
                : (_ <: attach(_, abs : max ~ *(0.999) : level)) <: _,_;
    """.trimIndent()

    private fun checkCompile(report: Reporter): FaustProgram? {
        val version = FaustEngine.libfaustVersion()
        report.check(
            "libfaust is the vendored build",
            version == FaustEngine.EXPECTED_LIBFAUST_VERSION
        ) { "loaded $version, PROVENANCE.md says ${FaustEngine.EXPECTED_LIBFAUST_VERSION}" }

        val firstMs = System.currentTimeMillis()
        val program = try {
            FaustEngine.compile("smoke", VOICE)
        } catch (e: FaustCompileError) {
            report.check("voice compiles", false) { "${e.faustMessage} (line ${e.line})" }
            return null
        }
        val elapsed = System.currentTimeMillis() - firstMs
        println("  first compile ${elapsed}ms, ${program.wasm.size} bytes of wasm")

        report.check("wasm was produced", program.wasm.size > 512) { "${program.wasm.size} bytes" }
        report.check(
            "wasm is a WebAssembly module",
            program.wasm.size > 4 &&
                program.wasm[0] == 0x00.toByte() && program.wasm[1] == 0x61.toByte() &&
                program.wasm[2] == 0x73.toByte() && program.wasm[3] == 0x6D.toByte()
        ) { program.wasm.take(4).joinToString(" ") { "%02x".format(it) } }
        report.check("stereo out", program.outputs == 2) { "outputs=${program.outputs}" }
        report.check("no inputs", program.inputs == 0) { "inputs=${program.inputs}" }

        // The cache is what makes a re-run of an unchanged box free.
        val cachedStart = System.nanoTime()
        val again = FaustEngine.compile("smoke", VOICE)
        val cachedMs = (System.nanoTime() - cachedStart) / 1_000_000
        report.check("second compile is cached", cachedMs < 50) { "${cachedMs}ms" }
        report.check("cached program is the same", again.wasm.contentEquals(program.wasm)) { "differs" }

        return program
    }

    // ── ui ─────────────────────────────────────────────────────────────────────────────────

    private fun checkUi(report: Reporter, program: FaustProgram) {
        val ui = program.ui
        val byLabel = ui.controls.associateBy { it.label }

        report.check("three sliders and a meter", ui.controls.size == 4) {
            ui.controls.joinToString { "${it.type}:${it.label}" }
        }
        report.check("writable excludes the meter", ui.writable.size == 3) {
            ui.writable.joinToString { it.label }
        }
        report.check("meter is read-only", ui.meters.singleOrNull()?.label == "level") {
            ui.meters.joinToString { it.label }
        }

        val freq = byLabel["freq"]
        report.check("freq exists", freq != null) { byLabel.keys.toString() }
        if (freq != null) {
            report.check("freq range", freq.min == 50.0 && freq.max == 2000.0) { "${freq.min}..${freq.max}" }
            report.check("freq default", freq.init == 440.0) { "${freq.init}" }
            report.check("freq step", freq.step == 0.01) { "${freq.step}" }
            report.check("freq unit parsed", freq.unit == "Hz") { "${freq.unit}" }
            report.check("freq scale parsed", freq.scale == FaustScale.LOG) { "${freq.scale}" }
            report.check("freq display label", freq.displayLabel == "freq (Hz)") { freq.displayLabel }
            // Faust wraps every program in an implicit group named after the module. Field's module
            // name is the box id, so if that wrapper leaked into the label every slider in the UI
            // would read `box_a1b2c3/freq`.
            report.check("implicit outer group is dropped", freq.groupPath.isEmpty()) {
                freq.groupPath.toString()
            }
            report.check("qualified label is bare", freq.qualifiedLabel == "freq") { freq.qualifiedLabel }
            // Without an index there is no way to move the parameter at all.
            report.check("freq has a memory index", freq.index >= 0) { "${freq.index}" }
        }

        val cutoff = byLabel["cutoff"]
        report.check("cutoff is linear by default", cutoff?.scale == FaustScale.LINEAR) { "${cutoff?.scale}" }

        // Every writable control must have a distinct index, or two sliders would fight.
        val indices = ui.writable.map { it.index }
        report.check("indices are distinct", indices.toSet().size == indices.size) { indices.toString() }
    }

    // ── diagnostics ────────────────────────────────────────────────────────────────────────

    private fun checkDiagnostics(report: Reporter) {
        val bad = "import(\"stdfaust.lib\");\nprocess = os.sawtoothX(440);\n"
        try {
            FaustEngine.compile("badprog", bad)
            report.check("a bad program fails", false) { "it compiled" }
        } catch (e: FaustCompileError) {
            report.check("error names the line", e.line == 2) { "line=${e.line} raw=${e.raw}" }
            report.check(
                "error names the symbol",
                e.faustMessage.contains("sawtoothX")
            ) { e.faustMessage }
            report.check(
                "error message is not the Emscripten wrapper",
                !e.faustMessage.contains("Exception catching")
            ) { e.faustMessage }
        }

        // The compiler must still work after an error — it holds state that needs cleaning up.
        val after = try {
            FaustEngine.compile("recovered", "process = 0.0;"); true
        } catch (e: FaustCompileError) {
            false
        }
        report.check("compiler recovers after an error", after) { "second compile failed" }

        // A syntax error, which reports differently from an unbound symbol.
        try {
            FaustEngine.compile("syntaxerr", "process = (((;\n")
            report.check("a syntax error fails", false) { "it compiled" }
        } catch (e: FaustCompileError) {
            report.check("syntax error has a message", e.faustMessage.isNotBlank()) { e.raw }
        }
    }

    // ── rendering ──────────────────────────────────────────────────────────────────────────

    private fun checkRendering(report: Reporter, program: FaustProgram) {
        val dsp = FaustDsp(program, SR)
        try {
            report.check("dsp reports stereo", dsp.numOutputs == 2) { "${dsp.numOutputs}" }

            val block = 128
            val l = FloatArray(block)
            val r = FloatArray(block)
            dsp.render(l, r, block)

            val peak = l.maxOf { abs(it) }
            report.check("it makes a sound", peak > 1e-3) { "peak=$peak" }
            report.check("nothing is NaN", l.none { it.isNaN() } && r.none { it.isNaN() }) { "NaN present" }
            report.check("it stays in range", l.all { abs(it) <= 1.5f }) { "peak=$peak" }

            // A gain of zero must actually silence it: proof the parameter reached the DSP.
            val gain = program.ui.writable.first { it.label == "gain" }
            dsp.setParam(gain, 0.0)
            dsp.render(l, r, block)   // this block applies the queued write
            dsp.render(l, r, block)   // and this one is fully at the new value
            val silenced = l.maxOf { abs(it) }
            report.check("gain=0 silences it", silenced < 1e-6) { "peak=$silenced" }

            dsp.setParam(gain, 0.3)
            dsp.render(l, r, block)
            dsp.render(l, r, block)
            report.check("gain restores", l.maxOf { abs(it) } > 1e-3) { "peak=${l.maxOf { abs(it) }}" }

            // The meter should have seen the signal.
            val meter = program.ui.meters.first()
            report.check("meter reads back", dsp.meterValue(meter.index) > 0f) {
                "level=${dsp.meterValue(meter.index)}"
            }

            // A block larger than one compute call, to exercise the chunking loop.
            val big = FaustDsp.MAX_FRAMES + 37
            val bl = FloatArray(big)
            val br = FloatArray(big)
            dsp.render(bl, br, big)
            report.check("a long block renders throughout", abs(bl[big - 1]) > 0f || abs(bl[big - 2]) > 0f) {
                "tail is silent"
            }
            report.check("no NaN in a long block", bl.none { it.isNaN() }) { "NaN present" }

            // The measurement that justifies doing this in the JVM at all.
            val blocks = 4000
            val warm = FloatArray(block)
            repeat(500) { dsp.render(warm, warm, block) }
            val start = System.nanoTime()
            repeat(blocks) { dsp.render(l, r, block) }
            val wallMs = (System.nanoTime() - start) / 1e6
            val audioMs = blocks.toDouble() * block / SR * 1000.0
            val factor = audioMs / wallMs
            println("  render: ${"%.1f".format(factor)}x realtime (${"%.1f".format(wallMs * 1000 / blocks)}us per $block-frame block)")
            // Deliberately loose: this is a smoke test on unknown hardware, and the measured figure
            // was 353x. Anything below 10x would mean something is structurally wrong.
            report.check("renders comfortably faster than realtime", factor > 10.0) {
                "${"%.1f".format(factor)}x"
            }
        } finally {
            dsp.close()
        }
    }

    private fun checkMono(report: Reporter) {
        val program = FaustEngine.compile("monoprog", "import(\"stdfaust.lib\");\nprocess = os.osc(220) * 0.2;")
        report.check("mono program has one output", program.outputs == 1) { "${program.outputs}" }
        val dsp = FaustDsp(program, SR)
        try {
            val l = FloatArray(64)
            val r = FloatArray(64)
            dsp.render(l, r, 64)
            report.check("mono is audible", l.maxOf { abs(it) } > 1e-3) { "peak=${l.maxOf { abs(it) }}" }
            report.check("mono is copied to both channels", l.contentEquals(r)) { "channels differ" }
        } finally {
            dsp.close()
        }
    }

    /**
     * A program with inputs — an effect rather than a synth.
     *
     * This is the only check on the *write* half of the WebAssembly boundary. Outputs come out through
     * one bulk `readBuffer` per channel, but the polyglot API has no bulk write, so inputs go in a
     * float at a time through `writeBufferFloat` — a completely separate path, and one that a synth
     * program never touches. A gain of exactly 0.5 makes the assertion arithmetic rather than
     * approximate: every output sample must be half its input, and the two channels must not be
     * swapped or aliased.
     */
    private fun checkEffect(report: Reporter) {
        val program = FaustEngine.compile(
            "effectprog",
            "process = _,_ : *(0.5), *(0.25);"
        )
        report.check("effect declares two inputs", program.inputs == 2) { "${program.inputs}" }
        report.check("effect declares two outputs", program.outputs == 2) { "${program.outputs}" }

        val dsp = FaustDsp(program, SR)
        try {
            report.check("dsp reports the inputs", dsp.numInputs == 2) { "${dsp.numInputs}" }

            val n = 64
            // Distinct, non-constant, and different per channel, so a silent buffer, a swap or one
            // channel aliased onto the other all show up as a failure rather than as a pass.
            val inL = FloatArray(n) { 0.8f * kotlin.math.sin(it * 0.11f) }
            val inR = FloatArray(n) { -0.6f * kotlin.math.cos(it * 0.07f) }
            val outL = FloatArray(n)
            val outR = FloatArray(n)
            dsp.render(outL, outR, n, inL, inR)

            var worstL = 0f
            var worstR = 0f
            for (i in 0 until n) {
                worstL = max(worstL, abs(outL[i] - inL[i] * 0.5f))
                worstR = max(worstR, abs(outR[i] - inR[i] * 0.25f))
            }
            report.check("left channel is the input halved", worstL < 1e-6f) { "worst error $worstL" }
            report.check("right channel is the input quartered", worstR < 1e-6f) { "worst error $worstR" }
            report.check("it is not silent", outL.maxOf { abs(it) } > 0.1f) { "peak ${outL.maxOf { abs(it) }}" }

            // No input given: an effect must go quiet rather than reuse the last block or read rubbish
            // out of whatever happened to be in wasm memory.
            dsp.render(outL, outR, n)
            report.check("no input means silence", outL.all { it == 0f } && outR.all { it == 0f }) {
                "peak ${outL.maxOf { abs(it) }}"
            }
        } finally {
            dsp.close()
        }
    }

    // ── diagram ────────────────────────────────────────────────────────────────────────────

    private fun checkDiagram(report: Reporter) {
        val svgs = FaustEngine.blockDiagram("diagprog", VOICE)
        report.check("a diagram was produced", svgs.isNotEmpty()) { "none" }
        val process = svgs["process.svg"]
        report.check("process.svg is there", process != null) { svgs.keys.joinToString() }
        if (process != null) {
            report.check("it is really svg", process.contains("<svg")) { process.take(60) }
            report.check("it is not empty", process.length > 1000) { "${process.length} chars" }
        }
        println("  diagram: ${svgs.size} files, process.svg ${process?.length ?: 0} chars")
    }

    // ── reporting ──────────────────────────────────────────────────────────────────────────

    private class Reporter {
        var failures = 0
        private var checks = 0

        fun check(what: String, ok: Boolean, detail: () -> String = { "" }) {
            checks++
            if (ok) {
                println("  ok   $what")
            } else {
                failures++
                println("  FAIL $what — ${detail()}")
            }
        }

        fun summarise() {
            println("$checks checks, $failures failed")
        }
    }
}
