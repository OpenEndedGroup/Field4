package afield.lang.forth

import afield.audio.synth.AudioPatchHandle
import afield.audio.synth.DirtRender
import afield.audio.synth.FieldAudioEngine
import afield.audio.synth.PatArc
import afield.audio.synth.PatEvent
import afield.audio.synth.PatPattern
import afield.audio.synth.PatQ
import afield.audio.synth.PatSoundDocs
import afield.audio.synth.PatSynthBank
import afield.audio.synth.PatSynthNote
import afield.audio.synth.PatWave
import afield.audio.synth.SynthContext
import afield.audio.synth.audio
import afield.audio.synth.patDrumHz
import afield.audio.synth.patMidiToHz
import afield.audio.synth.patRenderStereoDirt
import afield.ui.visualization.AudioVisualizer
import kotlin.math.floor

/*
 * ForthField's step sequencer: a grid of steps, each step a short ForthField program.
 *
 * The design is Raphaël Forment's Cagire (https://cagire.raphaelforment.fr, AGPL-3.0-or-later), a
 * Forth step sequencer in which a step is not a note but a program that decides the note each time
 * the grid comes round to it. ForthField takes the idea and a handful of its words:
 *
 * - note names are literals: `c4` pushes 60, `eb3` 51, `f#5` 78 (a defined word of the same name wins);
 * - `note ( midi -- )` sounds a note on this step with the voice set up so far, `sound ( name -- )`
 *   a named sound (`s" bd" sound`);
 * - inside a step, `< a b c >` picks one item per pass: the first time the grid reaches this step `a`,
 *   then `b`, …; outside a step `<` and `>` are the comparisons;
 * - `[ a b c ]` is a list, `iter ( list -- list n )` pushes the pass count and
 *   `index ( list n -- x )` takes item `n` of the list, wrapping;
 * - `choose ( list -- x )` and `wchoose ( values weights -- x )` pick at random from a seeded generator.
 *
 * A grid is written `seq: name  program | program | … ;seq`. Each `|` ends a step; an empty step is
 * a rest. `name n run-steps` lists what the first n steps played in a `steps` panel.
 *
 * Nothing here makes samples of its own. Every note becomes a [PatSynthNote] on a [PatSynthBank], the
 * pattern language's voice: `render-steps` renders a bank offline to an audio player, and in launch
 * mode `name tick-steps frame` schedules each step on a bank registered with [FieldAudioEngine] as it
 * comes due (the Runloop only hands notes over; the audio thread renders them). `steps->pat` turns the
 * grid into a [PatPattern], so everything that plays patterns plays it; `render-dirt` is that pattern
 * through the pinned Dirt-Samples.
 */

/** One note a step program sounded. */
data class ForthStepEvent(
    /** Index of the step in time: 0, 1, 2, … across every pass of the grid. */
    val time: Long,
    /** Which cell of the grid produced it. */
    val cell: Int,
    /** MIDI note, or null for a named [sound]. */
    val midi: Double?,
    /** A sound name (`bd`, `hh`, …) when the step used `sound` rather than `note`. */
    val sound: String?,
    val wave: String,
    val gain: Double,
    /** One-pole lowpass corner in Hz, or null for none. */
    val cutoff: Double?,
    /** Seconds the note holds before it decays. */
    val dur: Double,
)

/** The voice a step program is shaping, reset at the start of every step. */
class ForthVoice {
    var wave = "sine"
    var gain = 0.8
    var cutoff: Double? = null
    var dur = 0.1
}

/** A grid: one token list per step. */
class ForthSequence(val name: String, val cells: List<List<ForthInterpreter.Token>>) {
    /** Next step `tick-steps` will play, and the wall-clock time the grid started. */
    var nextTime = 0L
    var startNanos = -1L
    override fun toString() = "seq $name (${cells.size} steps)"
}

/** Everything the step words share on one interpreter. */
class ForthStepState : AutoCloseable {
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
    /** What the last `render-steps` or `render-dirt` displayed. */
    var lastAudio: PatSoundDocs.Stereo? = null
    /** Notes sounded since the last [take]. */
    val events = ArrayList<ForthStepEvent>()

    /** The bank `tick-steps` plays on, and its registration with the engine; null until first used. */
    var liveBank: PatSynthBank? = null
    private var liveHandle: AudioPatchHandle? = null

    /** Seconds per step: steps are sixteenth notes at [bpm]. */
    val stepSeconds: Double get() = 60.0 / bpm / 4.0

    fun take(): List<ForthStepEvent> = events.toList().also { events.clear() }

    /** The live bank, registered with [FieldAudioEngine] the first time it is asked for. */
    fun live(): PatSynthBank = liveBank ?: PatSynthBank(voices = 32).also {
        liveBank = it
        liveHandle = FieldAudioEngine.shared().register(audio(it, gain = 1f))
    }

    /** Stop the live bank. Called when the box stops. */
    override fun close() {
        liveHandle?.close()
        liveHandle = null
        liveBank = null
    }
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

/** One line of the event log: `step 3  cell 1  g3 (55)  saw  lpf 800`, or `step 0  cell 0  bd`. */
fun forthStepLogLine(e: ForthStepEvent): String = buildString {
    append("step ${e.time}  cell ${e.cell}  ")
    if (e.sound != null) append(e.sound)
    else append("${forthNoteName(e.midi!!)} (${e.midi.toInt()})  ${e.wave}")
    e.cutoff?.let { append("  lpf ${fmt(it)}") }
}

private fun fmt(d: Double) = if (d == floor(d)) d.toLong().toString() else "%.2f".format(d)

private fun waveOf(name: String) = when (name) {
    "saw" -> PatWave.SAW
    "square" -> PatWave.SQUARE
    "tri" -> PatWave.TRIANGLE
    else -> PatWave.SINE
}

/**
 * [e] as a note on [bank], starting at [startFrame]. A named sound takes its pitch and wave from the
 * pattern voice's drum map, as an unpitched `pat` atom does.
 */
fun forthStepNote(bank: PatSynthBank, e: ForthStepEvent, startFrame: Long): PatSynthNote {
    val drum = e.sound?.let { patDrumHz(it, bank.voice.baseHz) }
    return PatSynthNote(
        startFrame = startFrame,
        holdFrames = (e.dur * bank.sampleRate).toInt().coerceAtLeast(0),
        tailFrames = (bank.voice.decay * 4.0 * bank.sampleRate).toInt().coerceAtLeast(1),
        hz = drum?.first ?: patMidiToHz(e.midi!!),
        wave = drum?.second ?: waveOf(e.wave),
        gain = e.gain,
        pan = 0.5,
        cutoffHz = e.cutoff,
        seed = e.time * 1_000_003L + e.cell,
    )
}

/**
 * Render [events] offline through a [PatSynthBank], block by block, as the engine would play them.
 * Returns left and right at [PatSoundDocs.SR].
 */
fun renderForthSteps(events: List<ForthStepEvent>, steps: Int, stepSeconds: Double): PatSoundDocs.Stereo {
    val sr = PatSoundDocs.SR
    val bank = PatSynthBank(voices = 32, sampleRate = sr)
    bank.schedule(events.map { forthStepNote(bank, it, Math.round(it.time * stepSeconds * sr)) })
    val tail = (events.maxOfOrNull { it.dur } ?: 0.0) + bank.voice.decay * 4.0
    val frames = ((steps * stepSeconds + tail) * sr).toInt().coerceAtLeast(1)
    val left = FloatArray(frames); val right = FloatArray(frames)
    val block = 128
    val l = FloatArray(block); val r = FloatArray(block)
    val ctx = SynthContext(sr.toFloat(), block)
    var at = 0
    var cycle = 1L
    while (at < frames) {
        val n = minOf(block, frames - at)
        l.fill(0f); r.fill(0f)
        ctx.cycleId = cycle++
        ctx.time = at.toFloat() / sr
        bank.render(l, r, n, ctx)
        System.arraycopy(l, 0, left, at, n); System.arraycopy(r, 0, right, at, n)
        at += n
    }
    return PatSoundDocs.Stereo(left, right)
}

/**
 * The first [steps] steps of [seq] as a pattern whose cycle is those steps, repeating. A named sound
 * is the event's value (so Dirt plays it as a sample); a note is its MIDI number, with `note`,
 * `gain` and `cutoff` annotations as `pat` writes them.
 */
fun ForthInterpreter.forthStepsPattern(seq: ForthSequence, steps: Int): PatPattern<String> {
    require(steps > 0) { "steps must be > 0" }
    val events = runSteps(seq, steps)
    return PatPattern { arc ->
        val first = floor(arc.start.toDouble()).toLong()
        val last = Math.ceil(arc.end.toDouble()).toLong()
        (first until last).flatMap { cycle ->
            events.mapNotNull { e ->
                val start = PatQ.of(cycle * steps + e.time, steps.toLong())
                val end = PatQ.of(cycle * steps + e.time + 1, steps.toLong())
                if (end <= arc.start || start >= arc.end) return@mapNotNull null
                val notes = buildMap {
                    e.midi?.let { put("note", fmt(it)) }
                    put("gain", fmt(e.gain))
                    e.cutoff?.let { put("cutoff", fmt(it)) }
                }
                PatEvent(PatArc(start, end), e.sound ?: fmt(e.midi!!), annotations = notes)
            }
        }
    }
}

/** Show [audio] as the box output's audio player, and keep it for [ForthStepsDocs]. */
private fun ForthInterpreter.showAudio(audio: PatSoundDocs.Stereo) {
    steps.lastAudio = audio
    onDisplay("audio", AudioVisualizer.renderSamplesHtml(audio.mono, PatSoundDocs.SR))
}

private fun ForthInterpreter.popSeq(): ForthSequence =
    pop() as? ForthSequence ?: throw ForthError("expected a seq: grid on the stack", currentLine)

@Suppress("UNCHECKED_CAST")
private fun ForthInterpreter.popList(): List<Any> =
    pop() as? List<Any> ?: throw ForthError("expected a [ … ] list on the stack", currentLine)

/** Install the step-sequencer words on [forth]. Called from [ForthInterpreter]'s constructor. */
internal fun installStepWords(forth: ForthInterpreter) = with(forth) {
    val s = steps
    fun emit(midi: Double?, sound: String?) {
        val v = s.voice
        s.events += ForthStepEvent(s.time, s.cell, midi, sound, v.wave, v.gain, v.cutoff, v.dur)
    }
    defineWord("note", "( midi -- ) Sound a note on this step with the voice set so far") { emit(popNum(), null) }
    defineWord("sound", "( name -- ) Sound a named sound on this step: a Dirt sample, or the pattern voice's drum") {
        emit(null, pop().toString())
    }
    for (w in listOf("sine", "saw", "square", "tri")) defineWord(w, "( -- ) Voice oscillator: $w") { s.voice.wave = w }
    defineWord("lpf", "( hz -- ) One-pole lowpass on the voice") { s.voice.cutoff = popNum() }
    defineWord("gain", "( g -- ) Voice level, 0..1") { s.voice.gain = popNum() }
    defineWord("dur", "( seconds -- ) How long the note holds before it decays") { s.voice.dur = popNum().coerceAtLeast(0.0) }
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
    defineWord("render-steps", "( seq n -- ) Render the first n steps through the pattern voice to an audio player") {
        val n = popInt(); val seq = popSeq()
        showAudio(renderForthSteps(runSteps(seq, n), n, s.stepSeconds))
    }
    defineWord("steps->pat", "( seq n -- pat ) The first n steps as a repeating pattern, one cycle long") {
        val n = popInt(); val seq = popSeq()
        push(forthStepsPattern(seq, n))
    }
    defineWord("render-dirt", "( seq n -- ) Render the first n steps through the pinned Dirt-Samples to an audio player") {
        val n = popInt(); val seq = popSeq()
        val result = patRenderStereoDirt(
            forthStepsPattern(seq, n), cycles = 1, cps = 1.0 / (n * s.stepSeconds),
            library = DirtRender.requirePinnedLibrary(), strict = true,
        )
        showAudio(result.audio)
    }
    defineWord("tick-steps", "( seq -- ) Launch mode: play every step now due through Field's audio engine, listing each note") {
        val seq = popSeq()
        val bank = s.live()
        val now = System.nanoTime()
        if (seq.startNanos < 0) { seq.startNanos = now; seq.nextTime = 0 }
        val due = ((now - seq.startNanos) / 1e9 / s.stepSeconds).toLong()
        while (seq.nextTime <= due) {
            // Hand the note over a little ahead, at the frame its step falls on; the audio thread starts it.
            val atSec = seq.startNanos / 1e9 + seq.nextTime * s.stepSeconds + 0.05
            val lead = ((atSec - now / 1e9) * bank.sampleRate).toLong().coerceAtLeast(0)
            runStep(seq, seq.nextTime).forEach {
                bank.schedule(forthStepNote(bank, it, bank.frame + lead))
                onOutput(forthStepLogLine(it) + "\n")
            }
            seq.nextTime++
        }
    }
}
