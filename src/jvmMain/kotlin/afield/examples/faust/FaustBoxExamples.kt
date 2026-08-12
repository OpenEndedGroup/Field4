package afield.examples.faust

/*
 * Faust inside a real box, through the backdoor.
 *
 * `synth.faust.smoke` covers the compiler and the DSP as libraries; these cover the part that only
 * exists when there is a box: `Box.faustCompile` reaching the vendored compiler from inside a
 * document, the labels the generated controls would carry, an offline render into an output area, and
 * a compile error travelling back with its line intact.
 *
 * Neither of these starts the audio engine. `Box.faust` does — it has to, it plays — and opening an
 * audio device is not something a headless catalog run should depend on, so these use the
 * compile-and-render path instead. What that leaves uncovered is the launch itself (the box staying
 * lit while sound comes out), which is the generic audio return-value machinery this reuses.
 *
 * Two constraints on how these are written, both learned by getting them wrong:
 *
 *  * The runner extracts a snippet as **raw source text**, so a `${'$'}` or a nested triple quote would
 *    arrive at the box verbatim rather than escaped. Hence the Faust programs are single-line strings
 *    with `\n`, and the output is assembled with `+` rather than interpolation.
 *  * The `FIELD_TEST` block is parsed by a YAML subset that splits a value on `": "`, so no expected
 *    substring may contain a colon followed by a space.
 */

/*
FIELD_TEST
id: faust.box.controls
runner: backdoor-box
tags: [examples, backdoor, faust, audio, wasm]
timeoutSec: 240
boxName: FaustControls
w: 460
h: 300
waitSec: 0.5
expect:
  outputContains:
    - "faust writable 2"
    - "faust labels cutoff (Hz) freq (Hz)"
    - "faust meters 1 level"
    - "faust outputs 2"
    - "faust rendered"
  eventKinds:
    - output
*/
private val CONTROLS_SNIPPET = """
import afield.four.synth.*

val src = "#faust\n" +
    "import(\"stdfaust.lib\");\n" +
    "freq   = hslider(\"freq [unit:Hz] [scale:log]\", 220, 50, 2000, 0.01);\n" +
    "cutoff = hslider(\"cutoff [unit:Hz]\", 1400, 50, 8000, 1);\n" +
    "level  = hbargraph(\"level\", 0, 1);\n" +
    "process = os.sawtooth(freq) : fi.lowpass(2, cutoff) * 0.15\n" +
    "        : (_ <: attach(_, abs : max ~ *(0.999) : level)) <: _,_;\n"

val program = faustCompile(src)

// The labels are the program's own. They must not carry the implicit group that Faust wraps every
// program in, because that group is named after the module — which here is the box id.
println("faust writable " + program.ui.writable.size)
println("faust labels " + program.ui.writable.joinToString(" ") { it.displayLabel })
println("faust meters " + program.ui.meters.size + " " + program.ui.meters.joinToString(" ") { it.label })
println("faust outputs " + program.outputs)

// The metadata Faust carries, which is what makes a generated control useful. Looked up by label:
// Faust lists controls alphabetically, so `cutoff` comes first however the source was written.
val freq = program.ui.writable.first { it.label == "freq" }
println("faust freq range " + freq.min + " to " + freq.max + " step " + freq.step)
println("faust freq scale " + freq.scale + " unit " + freq.unit)

// Renders offline, so this needs no audio device.
faustWaveform(program, seconds = 0.01)
println("faust rendered")
""".trimIndent()

/*
FIELD_TEST
id: faust.box.compile-error
runner: backdoor-box
tags: [examples, backdoor, faust, errors]
timeoutSec: 240
boxName: FaustCompileError
w: 460
h: 220
waitSec: 0.5
expect:
  outputContains:
    - "faust error at stripped line 2"
    - "sawtoothX"
    - "faust recovered 1"
    - "faust box line would be 8"
  eventKinds:
    - output
*/
private val COMPILE_ERROR_SNIPPET = """
import afield.four.synth.*

// `os.sawtoothX` does not exist.
//
// The source's lines are 1 `#faust`, 2 the import, 3 the bad line. stripFaustHeader removes the
// header, so the compiler sees the import as line 1 and the bad line as line 2 — which is what
// e.line reports. Box.faust adds the header's lines back, plus the line the literal starts on, when
// it places the marker; that part is not visible from a box's output, so what is asserted here is
// the number that arithmetic starts from.
val bad = "#faust\n" +
    "import(\"stdfaust.lib\");\n" +
    "process = os.sawtoothX(440);\n"

// FaustEngine.compile rather than Box.faustCompile on purpose. The box-level call also places an
// error marker, and this runner treats *any* error marker as a test failure — so the marker cannot
// be asserted here. What is asserted is everything the marker is computed from.
try {
    FaustEngine.compile("errbox", stripFaustHeader(bad).first)
    println("faust expected a compile error and did not get one")
} catch (e: FaustCompileError) {
    println("faust error at stripped line " + e.line + " " + e.faustMessage)
    // And the arithmetic Box.faust does with it. The header is 1 line, and if this snippet's `bad`
    // literal began on box line 6 the marker would land here. Computed, not hardcoded, so a change
    // to stripFaustHeader shows up as a different number.
    val headerLines = 1
    val literalStartsOn = 6
    println("faust box line would be " + (literalStartsOn + headerLines + ((e.line ?: 1) - 1)))
}

// The compiler holds state across a failure and has to be cleaned up, or every later compile in the
// session fails too. This is the half of the error path that is easy to get wrong and never notice.
val ok = faustCompile("#faust\nimport(\"stdfaust.lib\");\nprocess = os.osc(440) * 0.1;\n")
println("faust recovered " + ok.outputs)
""".trimIndent()
