package afield.lang.forth

import afield.audio.engine.WavFloatArrayWriter
import afield.ui.visualization.h
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/*
 * ForthField's step sequencer: a grid of steps, each step a short ForthField program.
 *
 * The design is Raphaël Forment's Cagire (https://cagire.raphaelforment.fr, AGPL-3.0-or-later), a
 * Forth step sequencer in which a step is not a note but a program that decides the note each time
 * the grid comes round to it. ForthField takes the idea and a handful of its words:
 *
 * - note names are literals: `c4` pushes 60, `eb3` 51, `f#5` 78 (a defined word of the same name wins);
 * - `note ( midi -- )` sounds a note on this step with the voice set up so far;
 * - inside a step, `< a b c >` picks one item per pass: the first time the grid reaches this step `a`,
 *   then `b`, …; outside a step `<` and `>` are the comparisons;
 * - `[ a b c ]` is a list, `iter ( list -- list n )` pushes the pass count and
 *   `index ( list n -- x )` takes item `n` of the list, wrapping;
 * - `choose ( list -- x )` and `wchoose ( values weights -- x )` pick at random from a seeded generator.
 *
 * A grid is written `seq: name  program | program | … ;seq`. Each `|` ends a step; an empty step is
 * a rest. `name n run-steps` lists what the first n steps played in a `steps` panel, `name n render-steps` renders them
 * to an audio player in the box's output, and in launch mode `name tick-steps frame` steps through the grid
 * against the wall clock, printing each step as it comes due.
 */

/** One note a step program sounded. */
data class ForthStepEvent(
    /** Index of the step in time: 0, 1, 2, … across every pass of the grid. */
    val time: Long,
    /** Which cell of the grid produced it. */
    val cell: Int,
    val midi: Double,
    val wave: String,
    val gain: Double,
    /** One-pole lowpass corner in Hz, or null for none. */
    val cutoff: Double?,
    /** Seconds for the envelope to fall to 1/e. */
    val decay: Double,
    /** FM modulator ratio and index; used only when [wave] is `fm`. */
    val ratio: Double,
    val index: Double,
)

/** The voice a step program is shaping, reset at the start of every step. */
class ForthVoice {
    var wave = "sine"
    var gain = 0.8
    var cutoff: Double? = null
    var decay = 0.25
    var ratio = 1.0
    var index = 0.0
}

/** A grid: one token list per step. */
class ForthSequence(val name: String, val cells: List<List<ForthInterpreter.Token>>) {
    /** Next step [tickSteps] will play, and the wall-clock time the grid started. */
    var nextTime = 0L
    var startNanos = -1L
    override fun toString() = "seq $name (${cells.size} steps)"
}

/** Everything the step words share on one interpreter. */
class ForthStepState {
    /** How many times the grid has come round to the step now running; what `< … >` and `iter` read. */
    var pass: Long = 0
    /** The step running, counted in time across passes. */
    var time: Long = 0
    var cell: Int = 0
    /** True while a step program runs: only there is `< … >` an alternation rather than less-than. */
    var inStep = false
    var bpm: Double = 120.0
    var random = java.util.Random(1)
    var voice = ForthVoice()
    /** The WAV the last `render-steps` displayed. */
    var lastWav: ByteArray? = null
    /** Notes sounded since the last [take]. */
    val events = ArrayList<ForthStepEvent>()

    /** Seconds per step: steps are sixteenth notes at [bpm]. */
    val stepSeconds: Double get() = 60.0 / bpm / 4.0

    fun take(): List<ForthStepEvent> = events.toList().also { events.clear() }
}

private val NOTE = Regex("^([a-g])(#|s|b)?(-?\\d)$")
private val SEMITONE = mapOf('c' to 0, 'd' to 2, 'e' to 4, 'f' to 5, 'g' to 7, 'a' to 9, 'b' to 11)

/** MIDI number of a note name (`c4` = 60, `eb3` = 51, `f#5` = 78, `bb2` = 46), or null. */
fun forthNoteNumber(word: String): Double? {
    val m = NOTE.matchEntire(word.lowercase()) ?: return null
    val (letter, acc, octave) = m.destructured
    val shift = when (acc) { "#", "s" -> 1; "b" -> -1; else -> 0 }
    return (12 * (octave.toInt() + 1) + SEMITONE.getValue(letter[0]) + shift).toDouble()
}

/** Name of a MIDI note, sharps as `#`: 60 is `c4`, 51 is `d#3`. */
fun forthNoteName(midi: Double): String {
    val n = Math.round(midi).toInt()
    val names = listOf("c", "c#", "d", "d#", "e", "f", "f#", "g", "g#", "a", "a#", "b")
    return names[Math.floorMod(n, 12)] + (Math.floorDiv(n, 12) - 1)
}

/** Index of the `]` matching the `[` at [open]. */
internal fun matchingBracket(tokens: List<ForthInterpreter.Token>, open: Int, end: Int): Int {
    var depth = 0
    for (j in open + 1 until end) {
        when (tokens[j].text) {
            "[" -> depth++
            "]" -> if (depth == 0) return j else depth--
        }
    }
    throw ForthError("'[' without a matching ']'", tokens[open].line)
}

/**
 * Index of the `>` closing an alternation opened at [open], or -1 when this `<` is the comparison.
 *
 * Only consulted while a step runs. An alternation is `<`, at least one item, then `>`, all on one
 * line, with no control-flow or definition word between: so `x 0 < if … then` inside a step keeps
 * meaning less-than.
 */
internal fun alternationEnd(tokens: List<ForthInterpreter.Token>, open: Int, end: Int): Int {
    val line = tokens[open].line
    val stops = setOf("<", ":", ";", "if", "else", "then", "[", "]", "|", ";seq", "do", "loop", "begin")
    for (j in open + 1 until end) {
        val t = tokens[j]
        if (t.line != line) return -1
        val text = t.text.lowercase()
        if (text == ">") return if (j > open + 1) j else -1
        if (text in stops) return -1
    }
    return -1
}

/** `seq: name … | … ;seq` — define [name] as a [ForthSequence]. Returns the index after `;seq`. */
internal fun parseSeq(forth: ForthInterpreter, tokens: List<ForthInterpreter.Token>, at: Int, end: Int): Int {
    val line = tokens[at].line
    if (at + 1 >= end) throw ForthError("Expected a name after 'seq:'", line)
    val name = tokens[at + 1].text.lowercase()
    val cells = ArrayList<List<ForthInterpreter.Token>>()
    var current = ArrayList<ForthInterpreter.Token>()
    var j = at + 2
    while (j < end) {
        when (tokens[j].text.lowercase()) {
            ";seq" -> {
                cells += current
                forth.dictionary[name] = ForthWord.Constant(name, ForthSequence(name, cells))
                return j + 1
            }
            "|" -> { cells += current; current = ArrayList() }
            else -> current += tokens[j]
        }
        j++
    }
    throw ForthError("Missing ';seq' for '$name'", line)
}

/** Run step [time] of [seq]: reset the voice, set pass and cell, execute the cell's program. */
fun ForthInterpreter.runStep(seq: ForthSequence, time: Long): List<ForthStepEvent> {
    if (seq.cells.isEmpty()) return emptyList()
    val n = seq.cells.size
    steps.time = time
    steps.cell = Math.floorMod(time, n.toLong()).toInt()
    steps.pass = Math.floorDiv(time, n.toLong())
    steps.voice = ForthVoice()
    steps.events.clear()
    val body = seq.cells[steps.cell]
    val depth = stack.size
    steps.inStep = true
    try {
        execTokens(body, 0, body.size)
    } finally {
        steps.inStep = false
    }
    while (stack.size > depth) stack.removeLast()
    return steps.take()
}

/** Run the first [count] steps of [seq] and return every note they sounded. */
fun ForthInterpreter.runSteps(seq: ForthSequence, count: Int): List<ForthStepEvent> =
    (0 until count).flatMap { runStep(seq, it.toLong()) }

/** One line of the event log: `step 3  cell 1  g3 (55)  saw  lpf 800`. */
fun forthStepLogLine(e: ForthStepEvent): String = buildString {
    append("step ${e.time}  cell ${e.cell}  ${forthNoteName(e.midi)} (${e.midi.toInt()})  ${e.wave}")
    if (e.wave == "fm") append(" ${fmt(e.ratio)}:${fmt(e.index)}")
    e.cutoff?.let { append("  lpf ${fmt(it)}") }
}

private fun fmt(d: Double) = if (d == floor(d)) d.toLong().toString() else "%.2f".format(d)

/** Sample rate of [renderForthSteps]. */
const val FORTH_STEPS_SR = 48_000

/**
 * Render [events] offline, one voice per note: oscillator, exponential decay, optional one-pole
 * lowpass. `fm` is a two-operator FM voice whose modulation index decays with the note, which is
 * what makes it a bell. Mono, returned as left and right.
 */
fun renderForthSteps(events: List<ForthStepEvent>, steps: Int, stepSeconds: Double): Pair<FloatArray, FloatArray> {
    val sr = FORTH_STEPS_SR
    val tail = (events.maxOfOrNull { it.decay } ?: 0.0) * 5.0
    val frames = ((steps * stepSeconds + tail) * sr).toInt().coerceAtLeast(1)
    val out = FloatArray(frames)
    for (e in events) {
        val hz = 440.0 * 2.0.pow((e.midi - 69.0) / 12.0)
        val start = (e.time * stepSeconds * sr).toInt()
        val length = (e.decay * 5.0 * sr).toInt()
        val alpha = e.cutoff?.let { 1.0 - exp(-2.0 * PI * it / sr) } ?: 1.0
        var lp = 0.0
        for (k in 0 until length) {
            val at = start + k
            if (at >= frames) break
            val t = k.toDouble() / sr
            val env = minOf(1.0, t / 0.004) * exp(-t / e.decay)
            val phase = hz * t
            val raw = when (e.wave) {
                "saw" -> 2.0 * (phase - floor(phase + 0.5))
                "square" -> if (phase - floor(phase) < 0.5) 1.0 else -1.0
                "tri" -> 1.0 - 4.0 * kotlin.math.abs(phase - floor(phase + 0.5))
                "fm" -> sin(2 * PI * phase + e.index * env * sin(2 * PI * phase * e.ratio))
                else -> sin(2 * PI * phase)
            }
            lp += alpha * (raw - lp)
            out[at] += (lp * env * e.gain * 0.3).toFloat()
        }
    }
    return out to out.copyOf()
}

/** [renderForthSteps] as WAV bytes, which a box's output shows as an audio player. */
fun forthStepsWav(events: List<ForthStepEvent>, steps: Int, stepSeconds: Double): ByteArray {
    val (l, r) = renderForthSteps(events, steps, stepSeconds)
    return WavFloatArrayWriter.encodeStereo16(l, r, FORTH_STEPS_SR, normalizePeak = 0.9f)
}

private fun ForthInterpreter.popSeq(): ForthSequence =
    pop() as? ForthSequence ?: throw ForthError("expected a seq: grid on the stack", currentLine)

@Suppress("UNCHECKED_CAST")
private fun ForthInterpreter.popList(): List<Any> =
    pop() as? List<Any> ?: throw ForthError("expected a [ … ] list on the stack", currentLine)

/** Install the step-sequencer words on [forth]. Called from [ForthInterpreter]'s constructor. */
internal fun installStepWords(forth: ForthInterpreter) = with(forth) {
    val s = steps
    defineWord("note", "( midi -- ) Sound a note on this step with the voice set so far") {
        val v = s.voice
        s.events += ForthStepEvent(s.time, s.cell, popNum(), v.wave, v.gain, v.cutoff, v.decay, v.ratio, v.index)
    }
    for (w in listOf("sine", "saw", "square", "tri")) defineWord(w, "( -- ) Voice oscillator: $w") { s.voice.wave = w }
    defineWord("fm", "( ratio index -- ) Two-operator FM voice") {
        s.voice.index = popNum(); s.voice.ratio = popNum(); s.voice.wave = "fm"
    }
    defineWord("lpf", "( hz -- ) One-pole lowpass on the voice") { s.voice.cutoff = popNum() }
    defineWord("gain", "( g -- ) Voice level, 0..1") { s.voice.gain = popNum() }
    defineWord("decay", "( seconds -- ) Envelope time to fall to 1/e") { s.voice.decay = popNum().coerceAtLeast(0.005) }
    defineWord("pass", "( -- n ) How many times the grid has come round to this step") { push(s.pass.toDouble()) }
    defineWord("step", "( -- n ) The step running, counted in time across passes") { push(s.time.toDouble()) }
    defineWord("bpm", "( n -- ) Tempo; a step is a sixteenth note") { s.bpm = popNum().coerceAtLeast(1.0) }
    defineWord("seed", "( n -- ) Reseed choose and wchoose") { s.random = java.util.Random(popNum().toLong()) }
    defineWord("iter", "( list -- list n ) Push the pass count beside the list") { val l = popList(); push(l); push(s.pass.toDouble()) }
    defineWord("index", "( list n -- x ) Item n of the list, wrapping") {
        val n = popNum().toLong(); val l = popList()
        if (l.isEmpty()) throw ForthError("index: empty list", currentLine)
        push(l[Math.floorMod(n, l.size.toLong()).toInt()])
    }
    defineWord("choose", "( list -- x ) One item at random") {
        val l = popList()
        if (l.isEmpty()) throw ForthError("choose: empty list", currentLine)
        push(l[s.random.nextInt(l.size)])
    }
    defineWord("wchoose", "( values weights -- x ) One value at random, weighted") {
        val weights = popList().map { anyToDouble(it).coerceAtLeast(0.0) }
        val values = popList()
        if (values.size != weights.size || values.isEmpty()) {
            throw ForthError("wchoose: ${values.size} values but ${weights.size} weights", currentLine)
        }
        val total = weights.sum()
        if (total <= 0.0) throw ForthError("wchoose: weights sum to zero", currentLine)
        var r = s.random.nextDouble() * total
        var pick = values.size - 1
        for (k in weights.indices) { r -= weights[k]; if (r < 0) { pick = k; break } }
        push(values[pick])
    }
    defineWord("run-steps", "( seq n -- ) Run the first n steps and list the notes they played in the steps channel") {
        val n = popInt(); val seq = popSeq()
        val events = runSteps(seq, n)
        // A panel of its own rather than inline print output, so the log sits beside the player.
        val log = events.joinToString("\n") { forthStepLogLine(it) }
        onDisplay("steps", "<pre style='margin:0'>" + log.replace("&", "&amp;").replace("<", "&lt;") + "</pre>")
    }
    defineWord("render-steps", "( seq n -- ) Render the first n steps to an audio player in the audio channel") {
        val n = popInt(); val seq = popSeq()
        val events = runSteps(seq, n)
        val wav = forthStepsWav(events, n, s.stepSeconds)
        s.lastWav = wav
        // `.h` turns WAV bytes into the output's audio player, as `display-channel` does for any value.
        onDisplay("audio", wav.h)
    }
    defineWord("tick-steps", "( seq -- ) Launch mode: run every step now due on the wall clock, printing each note (no sound)") {
        val seq = popSeq()
        val now = System.nanoTime()
        if (seq.startNanos < 0) { seq.startNanos = now; seq.nextTime = 0 }
        val due = ((now - seq.startNanos) / 1e9 / s.stepSeconds).toLong()
        while (seq.nextTime <= due) {
            runStep(seq, seq.nextTime).forEach { onOutput(forthStepLogLine(it) + "\n") }
            seq.nextTime++
        }
    }
}
