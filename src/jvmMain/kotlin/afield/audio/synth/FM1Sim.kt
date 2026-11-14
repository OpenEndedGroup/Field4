@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

import afield.media.image.util.PngEncoder
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import java.io.Closeable
import java.io.File
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The M-VAVE FM-1 synthesizer's browser simulator, running inside Field's audio engine.
 *
 * The FM-1 is a pocket 8-track, 64-step synth. Its open firmware (github.com/m-vave/fm1_open)
 * builds a simulator from the same C as the device: the sequencer, the 303, 808, pluck, PSG and
 * sampler voices, and a DX7-compatible FM core. Compiled to WebAssembly it has no imports at all,
 * so it runs in GraalWasm exactly as it does in a web page, and `node` puts it in the mix:
 *
 * ```kotlin
 * val fm1 = FM1Sim.shared
 *
 * fm1.preset(1)        // a factory project, 0 until FM1Sim.FACTORY_PROJECTS: change it and re-run
 * fm1.start()          // MIDI Start: restarts the pattern from step 1, playing or not
 * fm1.level(0.8)
 *
 * fm1.node
 * ```
 *
 * Launching the box plays it, and the pattern keeps going across re-runs; stopping the box takes it
 * out of the mix. Every track listens on a MIDI channel, so [note], [noteOn], [cc], [bend] and
 * [program] play and shape any track by pitch; [play] hands a track a Field pattern, [modulate]
 * lets a [Signal] turn one of its controls, and [voice] sends it a DX7 voice built in Field. [tap],
 * [key], [encoder] and [step] reach the panel itself. It is the simulator, not the hardware: the
 * device runs a closed engine library that the web build leaves out, so the two may differ.
 *
 * ## One context, one thread at a time
 *
 * A GraalVM context can be entered by one thread at a time and `render` runs on the audio thread.
 * Calls that change the sound are queued on the simulator's own block clock (128 frames at
 * 44,100 Hz, about 2.9 ms) and applied at the start of the block they are due in, so a note
 * scheduled ahead lands on its block whatever thread asked. Calls that answer with data
 * ([tracks], [screen], [exportProject], [voices]) take the simulator between two blocks: they wait
 * for the block in progress to finish, run the queued calls already due, and then read.
 *
 * [shared] keeps one simulator for the JVM, so re-running a box reuses it rather than leaking a
 * context per run, and the sequencer keeps playing across edits. [create] makes a private one.
 *
 * The simulator renders at a fixed 44,100 Hz; [node] resamples to Field's rate with a four-point
 * cubic (Catmull-Rom) interpolator, which is exact at 44.1 kHz and two simulator frames late.
 */
class FM1Sim private constructor(wasm: ByteArray) : Closeable {

    companion object {
        /** Frames the simulator renders per `sim_render` call. */
        const val BLOCK: Int = 128

        /** The simulator's only sample rate. */
        const val SAMPLE_RATE: Float = 44100f

        /** Simulator blocks per second, about 344.5. */
        const val BLOCKS_PER_SECOND: Double = SAMPLE_RATE / BLOCK.toDouble()

        /** The factory projects built into the firmware; [preset] takes 0 until this. */
        const val FACTORY_PROJECTS: Int = 16

        /** Tracks in a project. */
        const val TRACKS: Int = 8

        /** Bytes in an exported project: the 4,064-byte project and its 200-byte tuning block. */
        const val PROJECT_BYTES: Int = 4264

        /** The process-wide simulator, booted with factory storage on first use. */
        val shared: FM1Sim by lazy { FM1Sim(loadWasm()).also { it.boot(clear = true) } }

        /** A private simulator with its own context; [close] it when done. */
        fun create(): FM1Sim = FM1Sim(loadWasm()).also { it.boot(clear = true) }

        private fun loadWasm(): ByteArray =
            FM1Sim::class.java.getResourceAsStream("/fm1/fm1-sim.wasm")?.use { it.readBytes() }
                ?: error("FM1Sim: /fm1/fm1-sim.wasm is not on the classpath")

        /** Panel buttons, by the index `sim_control(0, …)` takes. */
        const val BUTTONS: Int = 14

        /** Keyboard keys, by the index `sim_control(1, …)` takes. */
        const val KEYS: Int = 27

        /** Key indices of the sixteen white keys, left to right (the panel's step keys 01-16). */
        val WHITE_KEYS: IntArray = intArrayOf(0, 2, 4, 6, 7, 9, 11, 12, 14, 16, 18, 19, 21, 23, 24, 26)

        /** Width and height of the simulator's LCD, in pixels. */
        const val SCREEN: Int = 240

        /** Seconds to whole simulator blocks; rejects a negative or non-finite time. */
        fun blocks(seconds: Double): Long {
            require(seconds.isFinite() && seconds >= 0.0) { "FM1Sim: a time must be finite and >= 0, was $seconds" }
            return (seconds * BLOCKS_PER_SECOND).roundToLong()
        }

        /** Events kept for [drainMidi] before the oldest are dropped. */
        const val MIDI_OUT_CAPACITY: Int = 8192
    }

    /** Panel button indices, in the simulator's numbering. */
    object Button {
        const val OCT_DOWN = 0; const val OCT_UP = 1
        const val FX = 2; const val SEL = 3; const val ENV = 4; const val LFO = 5; const val EDIT = 6; const val GLO = 7
        const val HOME = 8; const val SAVE = 9; const val ARP = 10; const val SEQ = 11; const val PLAY = 12; const val REC = 13
    }

    /**
     * MIDI controller numbers the firmware's voices answer to.
     *
     * Every track takes [VOLUME], [PAN], [EXPRESSION], [REVERB] and [MOD_WHEEL]. The 303 adds
     * [CUTOFF], [RESONANCE], [DECAY], [ENV_MOD] (on the mod wheel) and [SLIDE]; the pluck takes
     * [DAMPING], [CUTOFF] and [RELEASE]; the PSG [CUTOFF] and [RELEASE]. A DX7 voice is shaped by its
     * patch, and answers [MOD_WHEEL], [BREATH], [FOOT] and [SUSTAIN] through the patch's own
     * modulation sensitivities.
     */
    object CC {
        const val MOD_WHEEL = 1; const val BREATH = 2; const val FOOT = 4; const val SLIDE = 5
        const val VOLUME = 7; const val PAN = 10; const val EXPRESSION = 11; const val SUSTAIN = 64
        const val RESONANCE = 71; const val RELEASE = 72; const val CUTOFF = 74; const val DECAY = 75
        const val DAMPING = 75; const val ENV_MOD = 1; const val REVERB = 91
    }

    /** The six sound sources a track can hold, in the firmware's order. */
    enum class SourceType { DX7, BASS_303, DRUM_808, PSG, PLUCK, SAMPLER, UNKNOWN }

    /** Whether a track's notes also leave the simulator as MIDI, for [drainMidi]. */
    enum class MidiOut { OFF, ALSO, ONLY }

    private val closed = AtomicBoolean(false)
    private val lock = ReentrantLock()

    /** A call due at simulator block [due]; [seq] keeps calls due together in the order they were made. */
    private class Scheduled(val due: Long, val seq: Long, val call: (Value) -> Unit)

    private val incoming = ConcurrentLinkedQueue<Scheduled>()
    private val agenda = PriorityQueue<Scheduled>(compareBy<Scheduled>({ it.due }, { it.seq }))
    private val sequence = AtomicLong()

    /** Simulator blocks rendered so far; the clock every scheduled call is measured on. */
    @Volatile var blocksRendered = 0L
        private set

    /** Simulator frames rendered so far, at 44,100 Hz. */
    val frame: Long get() = blocksRendered * BLOCK

    @Volatile private var playingSnapshot = false

    /** Whether the sequencer is running, as of the last few blocks rendered. */
    val isPlaying: Boolean get() = playingSnapshot

    private val context: Context = Context.newBuilder("js", "wasm")
        .allowExperimentalOptions(true)
        .allowHostAccess(HostAccess.ALL)
        .allowPolyglotAccess(PolyglotAccess.ALL)
        .allowHostClassLookup { true }
        .option("js.webassembly", "true")
        .option("wasm.UseUnsafeMemory", "true")
        .build()

    private val exports: Value
    private val fnRender: Value
    private val fnState: Value
    private val fnMidiOut: Value
    private val memory: Value
    private val fileBuffer: Long

    private val scratch = ByteArray(BLOCK * 2 * 4)
    private val scratchFloats = java.nio.ByteBuffer.wrap(scratch)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .asFloatBuffer()

    /** One rendered simulator block, interleaved stereo, and the next frame of it to read. */
    private val block = FloatArray(BLOCK * 2)
    private var blockIndex = BLOCK
    /** The four simulator frames the interpolator spans, per channel, and how far between [1] and [2]. */
    private val windowL = FloatArray(4)
    private val windowR = FloatArray(4)
    private var frac = 0.0

    @Volatile private var captureMidi = false
    private val midiOut = ConcurrentLinkedQueue<FM1MidiEvent>()
    private val midiOutSize = AtomicInteger()

    /** MIDI events dropped because nobody drained them; see [drainMidi]. */
    val midiDropped: Long get() = midiDroppedCount.get()
    private val midiDroppedCount = AtomicLong()

    init {
        context.enter()
        try {
            context.getBindings("js").putMember("__fm1Bytes", wasm)
            exports = context.eval(
                Source.newBuilder(
                    "js",
                    "(function(){ var m = new WebAssembly.Module(new Uint8Array(Java.from(__fm1Bytes)));" +
                        " return new WebAssembly.Instance(m, {}).exports; })()",
                    "fm1-sim.js"
                ).buildLiteral()
            )
            exports.getMember("_initialize")?.takeIf { it.canExecute() }?.execute()
            fnRender = exports.getMember("sim_render")
            fnState = exports.getMember("sim_state")
            fnMidiOut = exports.getMember("sim_midi_output")
            memory = exports.getMember("memory").getMember("buffer")
            check(memory.hasBufferElements()) { "FM1Sim: wasm memory has no buffer interop" }
            check(exports.getMember("sim_block_frames").execute().asInt() == BLOCK)
            fileBuffer = exports.getMember("sim_file").execute().asLong()
        } catch (t: Throwable) {
            runCatching { context.leave() }
            runCatching { context.close(true) }
            throw t
        }
        runCatching { context.leave() }
    }

    // ── scheduling ─────────────────────────────────────────────────────────────────────────

    /** Run [call] against the module's exports at the start of simulator block [due] (or now, if past). */
    private fun at(due: Long, call: (Value) -> Unit) {
        if (!closed.get()) incoming.add(Scheduled(due, sequence.getAndIncrement(), call))
    }

    private fun queue(delayBlocks: Long = 0, call: (Value) -> Unit) = at(blocksRendered + delayBlocks, call)

    private fun call(name: String, vararg args: Any) = queue { it.getMember(name).execute(*args) }

    private fun callLater(delayBlocks: Long, name: String, vararg args: Any) =
        queue(delayBlocks) { it.getMember(name).execute(*args) }

    /** Move new calls onto the agenda and run every one due by now. Caller holds the lock, entered. */
    private fun runDue() {
        while (true) agenda.add(incoming.poll() ?: break)
        while (true) {
            val next = agenda.peek() ?: break
            if (next.due > blocksRendered) break
            agenda.poll()
            next.call(exports)
        }
    }

    /** Take the simulator between two blocks, run what is due, then [read] it on this thread. */
    private fun <T> between(read: (Value) -> T): T {
        check(!closed.get()) { "FM1Sim: this simulator is closed" }
        return lock.withLock {
            context.enter()
            try {
                runDue()
                read(exports)
            } finally {
                runCatching { context.leave() }
            }
        }
    }

    private fun Value.int(name: String, vararg args: Any): Int = getMember(name).execute(*args).asInt()

    private fun readBytes(ptr: Long, n: Int): ByteArray = ByteArray(n).also { memory.readBuffer(ptr, it, 0, n) }

    private fun writeFile(bytes: ByteArray) {
        for (i in bytes.indices) memory.writeBufferByte(fileBuffer + i, bytes[i])
    }

    private fun cString(ptr: Long, max: Int = 64): String {
        val sb = StringBuilder()
        var i = 0
        while (i < max) {
            val b = memory.readBufferByte(ptr + i).toInt() and 0xff
            if (b == 0) break
            sb.append(b.toChar()); i++
        }
        return sb.toString()
    }

    // ── the panel ──────────────────────────────────────────────────────────────────────────

    /**
     * Press and release panel button `index`, holding it for about 23 ms.
     *
     * The firmware polls its controls every 10 ms of simulated time, so a press and release in the
     * same block would never be seen; the release is queued eight blocks later.
     */
    fun tap(index: Int) {
        require(index in 0 until BUTTONS) { "FM1Sim: button $index is outside 0 until $BUTTONS" }
        call("sim_control", 0, index, 1)
        callLater(8, "sim_control", 0, index, 0)
    }

    /** Press PLAY/STOP, which toggles the sequencer; [start] and [stop] say which you mean. */
    fun play() = tap(Button.PLAY)

    /** Start the sequencer from its first step, as a MIDI Start does, whether it was running or not. */
    fun start(afterSeconds: Double = 0.0) = midiAt(blocksRendered + blocks(afterSeconds), 0xFA)

    /** Stop the sequencer, as a MIDI Stop does. */
    fun stop(afterSeconds: Double = 0.0) = midiAt(blocksRendered + blocks(afterSeconds), 0xFC)

    /** Carry on from where [stop] left the sequencer, as a MIDI Continue does. */
    fun resume() = midi(0xFB)

    /**
     * Play white key `n` (1..16) for `blocks` simulator blocks (128 frames, about 2.9 ms each),
     * starting `afterBlocks` blocks from now.
     *
     * The sixteen white keys are also the step keys, numbered 01-16 on the panel. A key plays the
     * track the panel has selected, in the panel's octave; [note] plays any track by pitch.
     */
    fun whiteKey(n: Int, blocks: Int = 40, afterBlocks: Int = 0) {
        require(n in 1..WHITE_KEYS.size) { "FM1Sim: white key $n is outside 1..${WHITE_KEYS.size}" }
        val index = WHITE_KEYS[n - 1]
        callLater(afterBlocks.toLong(), "sim_control", 1, index, 1)
        callLater(afterBlocks.toLong() + blocks, "sim_control", 1, index, 0)
    }

    /** Restart the simulator; `clear` wipes stored projects and reloads the factory samples. */
    fun boot(clear: Boolean = false) = call("sim_boot", if (clear) 1 else 0)

    /** Load factory project `slot` (0 is "NEON KEYS"), 0 until [FACTORY_PROJECTS]. */
    fun preset(slot: Int) {
        require(slot in 0 until FACTORY_PROJECTS) { "FM1Sim: factory project $slot is outside 0 until $FACTORY_PROJECTS" }
        call("sim_preset", slot)
    }

    /** The name of factory project `slot`. */
    fun factoryName(slot: Int): String {
        require(slot in 0 until FACTORY_PROJECTS) { "FM1Sim: factory project $slot is outside 0 until $FACTORY_PROJECTS" }
        return between { cString(it.getMember("sim_factory_name").execute(slot).asLong()).trim() }
    }

    /** Press or release keyboard key `index` (0 until [KEYS]). */
    fun key(index: Int, down: Boolean) {
        require(index in 0 until KEYS) { "FM1Sim: key $index is outside 0 until $KEYS" }
        call("sim_control", 1, index, if (down) 1 else 0)
    }

    /** Press or release panel button `index` (0 until [BUTTONS]). */
    fun button(index: Int, down: Boolean) {
        require(index in 0 until BUTTONS) { "FM1Sim: button $index is outside 0 until $BUTTONS" }
        call("sim_control", 0, index, if (down) 1 else 0)
    }

    /** Turn encoder `index` (0 until 7) by `delta` detents; the firmware takes at most 127 a scan. */
    fun encoder(index: Int, delta: Int) = call("sim_control", 2, index, delta.coerceIn(-127, 127))

    /** Set the output-level knob, 0..1 (the knob's travel; the firmware ignores anything past it). */
    fun level(value: Double) {
        require(!value.isNaN()) { "FM1Sim: level is NaN" }
        call("sim_control", 3, 0, (value.coerceIn(0.0, 1.0) * 1023).toInt())
    }

    /** Toggle step `step` of the selected track. */
    fun step(step: Int) = call("sim_step", step, 1)

    /** Select track `track` on the panel, so the keys, encoders and screen work on it. */
    fun select(track: Int) {
        requireTrack(track)
        call("sim_select", track, 6) // 6: keep the track's source
    }

    /** Release every key, button and encoder. */
    fun release() = call("sim_release")

    /** Silence every voice and drop queued MIDI. */
    fun panic() = call("sim_panic")

    // ── MIDI in ────────────────────────────────────────────────────────────────────────────

    private fun requireTrack(track: Int) =
        require(track in 0 until TRACKS) { "FM1Sim: track $track is outside 0 until $TRACKS" }

    private fun requireData(name: String, value: Int) =
        require(value in 0..127) { "FM1Sim: $name must be a MIDI data byte 0..127, was $value" }

    /** Hand the firmware one MIDI message, as if it came in over USB. Caller holds the lock, entered. */
    private fun send(e: Value, bytes: ByteArray) {
        writeFile(bytes)
        e.getMember("sim_midi").execute(bytes.size)
    }

    private fun channelOf(e: Value, track: Int): Int = e.int("sim_midi_setting", track, 1)

    /** Send raw MIDI bytes (a complete short message, or a SysEx from F0 to F7) now. */
    fun midi(vararg bytes: Int) = midiAt(blocksRendered, *bytes)

    /** Send raw MIDI bytes at simulator block [block], on [blocksRendered]'s clock. */
    fun midiAt(block: Long, vararg bytes: Int) {
        require(bytes.isNotEmpty() && bytes.all { it in 0..255 }) { "FM1Sim: MIDI bytes must be 0..255" }
        val message = ByteArray(bytes.size) { bytes[it].toByte() }
        at(block) { send(it, message) }
    }

    /** A message on [track]'s channel, resolved when it is sent so a re-routed track still hears it. */
    private fun channelMessage(block: Long, track: Int, status: Int, a: Int, b: Int = -1) {
        at(block) { e ->
            val ch = channelOf(e, track)
            send(e, if (b < 0) byteArrayOf((status or ch).toByte(), a.toByte())
            else byteArrayOf((status or ch).toByte(), a.toByte(), b.toByte()))
        }
    }

    /** Start note [note] (MIDI 0..127; 60 is middle C) on [track], [afterSeconds] from now. */
    fun noteOn(track: Int, note: Int, velocity: Int = 100, afterSeconds: Double = 0.0) {
        requireTrack(track); requireData("note", note)
        require(velocity in 1..127) { "FM1Sim: a note-on velocity must be 1..127, was $velocity" }
        channelMessage(blocksRendered + blocks(afterSeconds), track, 0x90, note, velocity)
    }

    /** Release note [note] on [track], [afterSeconds] from now. */
    fun noteOff(track: Int, note: Int, afterSeconds: Double = 0.0) {
        requireTrack(track); requireData("note", note)
        channelMessage(blocksRendered + blocks(afterSeconds), track, 0x80, note, 0)
    }

    /**
     * Play [note] on [track] for [seconds], starting [afterSeconds] from now.
     *
     * A fractional note is rounded to the nearest semitone: the firmware plays whole MIDI notes. On
     * a drum track (808 or sampler) the note picks the drum, as [FM1_DRUM_NOTES] lists.
     */
    fun note(track: Int, note: Number, seconds: Double = 0.25, velocity: Int = 100, afterSeconds: Double = 0.0) {
        val n = note.toDouble()
        require(n.isFinite()) { "FM1Sim: note is $n" }
        val from = blocksRendered + blocks(afterSeconds)
        scheduleNote(track, n.roundToInt(), velocity, from, from + blocks(seconds).coerceAtLeast(1L))
    }

    /** Play every note of [notes] on [track] together, struck in the same block. */
    fun chord(track: Int, notes: List<Number>, seconds: Double = 0.5, velocity: Int = 100, afterSeconds: Double = 0.0) =
        notes.forEach { note(track, it, seconds, velocity, afterSeconds) }

    /** Note on at block [from], note off at block [until]; both on [blocksRendered]'s clock. */
    fun scheduleNote(track: Int, note: Int, velocity: Int, from: Long, until: Long) {
        requireTrack(track); requireData("note", note)
        require(velocity in 1..127) { "FM1Sim: a note-on velocity must be 1..127, was $velocity" }
        require(until > from) { "FM1Sim: a note must end after it starts ($from..$until)" }
        channelMessage(from, track, 0x90, note, velocity)
        channelMessage(until, track, 0x80, note, 0)
    }

    /** Set controller [controller] of [track] to [value] (0..127); [CC] names the ones the voices use. */
    fun cc(track: Int, controller: Int, value: Int, afterSeconds: Double = 0.0) {
        requireTrack(track); requireData("controller", controller); requireData("value", value)
        channelMessage(blocksRendered + blocks(afterSeconds), track, 0xB0, controller, value)
    }

    /**
     * Bend [track]'s pitch by [amount] of the bend range: -1 is all the way down, +1 all the way up
     * (the DX7 voices bend two semitones). Past ±1 is the end of the wheel.
     */
    fun bend(track: Int, amount: Double, afterSeconds: Double = 0.0) {
        requireTrack(track)
        require(!amount.isNaN()) { "FM1Sim: bend amount is NaN" }
        val v = bendValue(amount)
        channelMessage(blocksRendered + blocks(afterSeconds), track, 0xE0, v and 127, v shr 7)
    }

    /**
     * Switch [track] to program [program].
     *
     * On a DX7 track that is one of the 128 voices of the voice library, 32 to each of its four
     * banks ([voices]); on a 303 it picks the saw (0) or square (1) wave; on the PSG, one of four.
     */
    fun program(track: Int, program: Int, afterSeconds: Double = 0.0) {
        requireTrack(track); requireData("program", program)
        channelMessage(blocksRendered + blocks(afterSeconds), track, 0xC0, program)
    }

    /** Hand the simulator a Java Sound MIDI message (a note, a controller, a SysEx), as it arrives. */
    fun receive(message: javax.sound.midi.MidiMessage) {
        val b = message.message
        if (b.isEmpty()) return
        midi(*IntArray(b.size) { b[it].toInt() and 0xff })
    }

    private val listening: MutableSet<MidiInput> = ConcurrentHashMap.newKeySet()

    /**
     * Play the simulator from a MIDI device: every message [input] receives goes to the
     * simulator, which routes it by channel to the tracks that listen there ([FM1Track.channel]).
     * Listening twice to the same input is the same as once.
     */
    fun listen(input: MidiInput) {
        if (listening.add(input)) input.onMessage { message, _ -> if (!closed.get()) receive(message) }
    }

    // ── state ──────────────────────────────────────────────────────────────────────────────

    /** The eight tracks as the sequencer last reported them. */
    fun tracks(): List<FM1Track> = between { e ->
        val ptr = e.getMember("sim_state").execute().asLong()
        val s = readBytes(ptr, 225)
        fun u(i: Int) = s[i].toInt() and 0xff
        (0 until TRACKS).map { t ->
            val a = 8 + t * 20
            var steps = 0L
            for (i in 0 until 8) steps = steps or (u(a + 12 + i).toLong() shl (8 * i))
            FM1Track(
                index = t,
                source = if (u(a) < 6) SourceType.entries[u(a)] else SourceType.UNKNOWN,
                program = u(a + 1), volume = u(a + 2), level = u(a + 3),
                muted = u(a + 4) != 0, solo = u(a + 5) != 0, playing = u(a + 6) != 0, playStep = u(a + 7),
                bpm = u(a + 10) or (u(a + 11) shl 8), activeSteps = steps, length = u(209 + t),
                channel = e.int("sim_midi_setting", t, 1)
            )
        }
    }

    /** The name of the project loaded now. */
    val projectName: String get() = between { cString(it.getMember("sim_project_name").execute().asLong(), 13).trim() }

    /** The project's tempo, in beats a minute. */
    val bpm: Int get() = tracks()[0].bpm

    /**
     * Cycles a second for a Field pattern that should run one cycle to the project's bar: four beats
     * of [bpm]. [play] uses it when no `cps` is given.
     */
    val cps: Double get() = bpm / 240.0

    /** The LCD as ARGB pixels, [SCREEN] by [SCREEN], row by row from the top. */
    fun screen(): IntArray = between { e ->
        val raw = readBytes(e.getMember("sim_pixels").execute().asLong(), SCREEN * SCREEN * 2)
        IntArray(SCREEN * SCREEN) { i ->
            val v = (raw[2 * i].toInt() and 0xff) or ((raw[2 * i + 1].toInt() and 0xff) shl 8)
            val r = (v shr 11) and 31; val g = (v shr 5) and 63; val b = v and 31
            (0xff shl 24) or ((r * 255 / 31) shl 16) or ((g * 255 / 63) shl 8) or (b * 255 / 31)
        }
    }

    /**
     * The LCD as a PNG, which `display(...)` shows as a picture, each pixel drawn as a [scale] by
     * [scale] square so the 240-pixel screen can be read at a useful size.
     */
    fun screenPng(scale: Int = 1): ByteArray {
        require(scale in 1..16) { "FM1Sim: screen scale is 1..16, not $scale" }
        val px = screen()
        if (scale == 1) return PngEncoder.encode(px, SCREEN, SCREEN)
        val w = SCREEN * scale
        val big = IntArray(w * w) { i -> px[(i / w / scale) * SCREEN + (i % w) / scale] }
        return PngEncoder.encode(big, w, w)
    }

    /**
     * The four rows of labels and values the screen shows beside its encoders, as the firmware
     * lists them for the web page: `"LEVEL" to "96"`, `"SOURCE" to "DX7 FM"`, ...
     */
    fun knobs(): List<Pair<String, String>> = between { e ->
        val ptr = e.getMember("sim_knobs").execute().asLong()
        val raw = readBytes(ptr, 16 * 16)
        (0 until 8).map { k ->
            fun cell(i: Int) = String(raw, i * 16, 16, Charsets.ISO_8859_1).substringBefore('\u0000').trim()
            cell(2 * k) to cell(2 * k + 1)
        }.filter { it.first.isNotEmpty() }
    }

    // ── projects and voices ────────────────────────────────────────────────────────────────

    /** The project loaded now, as the bytes the FM-1 itself exports: [PROJECT_BYTES] long. */
    fun exportProject(): ByteArray = between { e ->
        val r = e.int("sim_export")
        check(r == 0) { "FM1Sim: the firmware refused to export the project (error $r)" }
        readBytes(fileBuffer, PROJECT_BYTES)
    }

    /** Load a project from [exportProject]'s bytes, or from the 4,064-byte project alone. */
    fun importProject(bytes: ByteArray) {
        require(bytes.size == PROJECT_BYTES || bytes.size == 4064) {
            "FM1Sim: a project is $PROJECT_BYTES or 4064 bytes, not ${bytes.size}"
        }
        between { e ->
            writeFile(bytes)
            val r = e.int("sim_import", bytes.size)
            check(r == 0) { "FM1Sim: the firmware refused the project (error $r)" }
        }
    }

    /** Write the loaded project to [file], creating its folder. */
    fun saveProject(file: File): File {
        file.absoluteFile.parentFile?.mkdirs()
        file.writeBytes(exportProject())
        return file
    }

    /** Load the project in [file], as [saveProject] wrote it. */
    fun loadProject(file: File) = importProject(file.readBytes())

    /** Factory project [slot] as project bytes, without loading it. */
    fun factoryProject(slot: Int): ByteArray {
        require(slot in 0 until FACTORY_PROJECTS) { "FM1Sim: factory project $slot is outside 0 until $FACTORY_PROJECTS" }
        return between { e ->
            check(e.int("sim_factory_project", slot) == 0) { "FM1Sim: no factory project $slot" }
            readBytes(fileBuffer, PROJECT_BYTES)
        }
    }

    /** The 32 DX7 voices of voice-library bank [bank] (0..3), which DX7 tracks pick by [program]. */
    fun voices(bank: Int): List<Dx7Voice> {
        require(bank in 0..3) { "FM1Sim: the voice library has banks 0..3, not $bank" }
        return between { e ->
            val r = e.int("sim_library_read", bank)
            check(r == 0) { "FM1Sim: could not read voice bank $bank (error $r)" }
            Dx7Voice.bank(readBytes(fileBuffer, 4096))
        }
    }

    /**
     * Replace voice-library bank [bank] (0..3) with [voices], 32 of them; program `bank * 32 + i`
     * then plays `voices[i]` on any DX7 track. Written to the simulator's flash, so it lasts until
     * the simulator is booted clear.
     */
    fun setVoices(bank: Int, voices: List<Dx7Voice>) {
        require(bank in 0..3) { "FM1Sim: the voice library has banks 0..3, not $bank" }
        require(voices.size == 32) { "FM1Sim: a bank is 32 voices, not ${voices.size}" }
        val raw = ByteArray(4096)
        voices.forEachIndexed { i, v -> v.toVmem().copyInto(raw, i * 128) }
        between { e ->
            writeFile(raw)
            val r = e.int("sim_library_write", bank)
            check(r == 0) { "FM1Sim: could not write voice bank $bank (error $r)" }
        }
    }

    /** Load a DX7 32-voice bank (a `.syx` bulk dump, or its 4,096 bytes of voice data) into [bank]. */
    fun loadDx7Bank(file: File, bank: Int = 0) = setVoices(bank, Dx7Voice.bank(file.readBytes()))

    /**
     * Give DX7 track [track] the voice [voice], as a DX7 single-voice SysEx on the track's channel.
     *
     * The track keeps it until its program changes. The firmware takes it on its next control scan,
     * a few milliseconds later; notes already sounding keep the old voice.
     */
    fun voice(track: Int, voice: Dx7Voice) {
        requireTrack(track)
        at(blocksRendered) { e -> send(e, voice.toSysex(channelOf(e, track))) }
    }

    // ── MIDI out ───────────────────────────────────────────────────────────────────────────

    /**
     * Send [track]'s notes out of the simulator as MIDI, for [drainMidi]: [MidiOut.ALSO] keeps
     * playing them, [MidiOut.ONLY] turns the track's own voice off, so the FM-1 sequences something
     * else.
     */
    fun midiOut(track: Int, mode: MidiOut) {
        requireTrack(track)
        captureMidi = true
        queue { e ->
            e.getMember("sim_midi_capture").execute(1)
            val now = e.int("sim_midi_setting", track, 0)
            e.getMember("sim_midi_edit").execute(track, 0, mode.ordinal - now)
        }
    }

    /**
     * The MIDI the simulator has sent since the last call, oldest first, each stamped with the
     * simulator frame it left on. Only tracks given a [midiOut] send any.
     */
    fun drainMidi(): List<FM1MidiEvent> {
        val out = ArrayList<FM1MidiEvent>()
        while (true) {
            out += midiOut.poll() ?: break
            midiOutSize.decrementAndGet()
        }
        return out
    }

    /** Drain the firmware's MIDI capture after a block. Caller holds the lock, entered. */
    private fun collectMidi() {
        while (true) {
            val v = fnMidiOut.execute().asInt()
            if (v == 0) break
            val n = v and 255
            // The web build mirrors every message to two ports; keep port 1's copy.
            if ((v shr 8) != 1 || n == 0) continue
            val bytes = readBytes(fileBuffer, n)
            if (midiOutSize.incrementAndGet() > MIDI_OUT_CAPACITY) {
                midiOut.poll(); midiOutSize.decrementAndGet(); midiDroppedCount.incrementAndGet()
            }
            midiOut.add(FM1MidiEvent(frame, IntArray(n) { bytes[it].toInt() and 0xff }))
        }
    }

    // ── modulation ─────────────────────────────────────────────────────────────────────────

    /** A [Signal] turning a controller ([controller] >= 0) or the pitch bend (-1) of a track. */
    internal class Modulation(val track: Int, val controller: Int, val signal: Signal) {
        var last = Int.MIN_VALUE
        var buffer = FloatArray(0)
    }

    internal val modulations = CopyOnWriteArrayList<Modulation>()

    /**
     * Let [signal] turn controller [controller] of [track]: 0 is the bottom of the controller and 1
     * the top, read once per audio block on the audio thread, inside the [node] that plays the
     * simulator, and sent only when it changes.
     *
     * The signal is rendered by the same audio block as everything else, so an LFO here is the same
     * LFO wherever else it is heard. A NaN holds the last value; past 0..1 is the end of the
     * controller, since MIDI has seven bits. Calling it again for the same track and controller
     * replaces the signal.
     */
    fun modulate(track: Int, controller: Int, signal: Signal) {
        requireTrack(track); requireData("controller", controller)
        modulations.removeIf { it.track == track && it.controller == controller }
        modulations += Modulation(track, controller, signal)
    }

    /** Let [signal] bend [track]'s pitch, -1..1 of the bend range, as [modulate] does a controller. */
    fun modulateBend(track: Int, signal: Signal) {
        requireTrack(track)
        modulations.removeIf { it.track == track && it.controller == -1 }
        modulations += Modulation(track, -1, signal)
    }

    /** Stop every [modulate] on [track] (all tracks if null); the controls stay where they were. */
    fun stopModulating(track: Int? = null) {
        modulations.removeIf { track == null || it.track == track }
    }

    private fun bendValue(amount: Double): Int = ((amount.coerceIn(-1.0, 1.0) + 1.0) * 8191.5).toInt().coerceIn(0, 16383)

    /** Sample every modulation for this audio block and queue the ones that moved. Audio thread. */
    internal fun applyModulations(numFrames: Int, ctx: SynthContext) {
        for (m in modulations) {
            if (m.buffer.size < numFrames) m.buffer = FloatArray(numFrames)
            m.signal.render(m.buffer, numFrames, ctx)
            val x = m.buffer[0].toDouble()
            if (x.isNaN()) continue
            if (m.controller < 0) {
                val v = bendValue(x)
                if (v != m.last) { m.last = v; channelMessage(blocksRendered, m.track, 0xE0, v and 127, v shr 7) }
            } else {
                val v = (x.coerceIn(0.0, 1.0) * 127.0).roundToInt()
                if (v != m.last) { m.last = v; channelMessage(blocksRendered, m.track, 0xB0, m.controller, v) }
            }
        }
    }

    // ── patterns ───────────────────────────────────────────────────────────────────────────

    internal val feeders = ConcurrentHashMap<Int, FM1PatternFeeder>()

    /**
     * Play [pattern] on [track], one cycle to a bar of the project's tempo unless [cps] says
     * otherwise, and keep playing it until [stopPatterns] or another [play] on the same track.
     *
     * Pitch comes from the `note` parameter, a drum name ([FM1_DRUM_NOTES]: `bd`, `sd`, `hh`, ...) or
     * the atom read as a note (`c5` is middle C, Tidal's numbering); `gain` scales [velocity]. A
     * feeder thread stays [aheadSeconds] ahead of the audio and puts each note on its simulator
     * block, so the timing is the block's (2.9 ms) and not the thread's.
     */
    fun play(
        pattern: PatPattern<String>,
        track: Int = 0,
        cps: Double? = null,
        sustain: Double = FAUST_PATTERN_SUSTAIN,
        velocity: Int = 100,
        aheadSeconds: Double = 0.25
    ): FM1PatternFeeder {
        requireTrack(track)
        val rate = cps ?: this.cps
        require(rate.isFinite() && rate > 0.0) { "FM1Sim: cps must be finite and > 0, was $rate" }
        val feeder = FM1PatternFeeder(this, pattern, track, rate, sustain, velocity, aheadSeconds)
        feeders.put(track, feeder)?.stop()
        feeder.start()
        return feeder
    }

    /**
     * Schedule [cycles] cycles of [pattern] on [track] at once, starting at block [fromBlock]: the
     * deterministic version of [play], for rendering offline.
     *
     * @return how many notes were scheduled
     */
    fun schedule(
        pattern: PatPattern<String>,
        track: Int = 0,
        cycles: Int = 1,
        cps: Double = 0.5,
        sustain: Double = FAUST_PATTERN_SUSTAIN,
        velocity: Int = 100,
        fromBlock: Long = blocksRendered
    ): Int {
        requireTrack(track)
        require(cycles > 0) { "FM1Sim: cycles must be > 0" }
        val notes = fm1NotesForArc(pattern, PatArc(PatQ.ZERO, PatQ.of(cycles)), cps, sustain, velocity, fromBlock)
        notes.notes.forEach { scheduleNote(track, it.note, it.velocity, it.startBlock, it.endBlock) }
        return notes.notes.size
    }

    /** Stop the [play] feeding [track] (every track if null). Notes already scheduled still play. */
    fun stopPatterns(track: Int? = null) {
        if (track == null) feeders.values.forEach { it.stop() }.also { feeders.clear() }
        else feeders.remove(track)?.stop()
    }

    // ── rendering ──────────────────────────────────────────────────────────────────────────

    /** Pull one 44.1 kHz block out of the module. Caller holds the lock, entered. */
    private fun renderBlock() {
        runDue()
        val ptr = fnRender.execute().asLong()
        memory.readBuffer(ptr, scratch, 0, scratch.size)
        scratchFloats.position(0)
        scratchFloats.get(block, 0, block.size)
        if (captureMidi) collectMidi()
        // Byte 6 of each track's view is whether it is playing. Sampled every 32 blocks (~93 ms),
        // since taking the snapshot is not free.
        if (blocksRendered % 32 == 0L) {
            val state = fnState.execute().asLong()
            var any = false
            for (t in 0 until TRACKS) if (memory.readBufferByte(state + 8 + t * 20 + 6) != 0.toByte()) any = true
            playingSnapshot = any
        }
        blocksRendered++
    }

    private fun advance() {
        if (blockIndex >= BLOCK) {
            renderBlock(); blockIndex = 0
        }
        windowL[0] = windowL[1]; windowL[1] = windowL[2]; windowL[2] = windowL[3]
        windowR[0] = windowR[1]; windowR[1] = windowR[2]; windowR[2] = windowR[3]
        windowL[3] = block[blockIndex * 2]; windowR[3] = block[blockIndex * 2 + 1]
        blockIndex++
    }

    private fun hermite(w: FloatArray, t: Float): Float {
        val c1 = 0.5f * (w[2] - w[0])
        val c2 = w[0] - 2.5f * w[1] + 2f * w[2] - 0.5f * w[3]
        val c3 = 0.5f * (w[3] - w[0]) + 1.5f * (w[1] - w[2])
        return ((c3 * t + c2) * t + c1) * t + w[1]
    }

    /**
     * Render `numFrames` at `sampleRate`, resampling from the simulator's 44,100 Hz with a
     * four-point cubic interpolator.
     *
     * A failure inside the module fills silence and stops this simulator rather than the audio thread.
     */
    fun render(outL: FloatArray, outR: FloatArray, numFrames: Int, sampleRate: Float) {
        if (closed.get() || !(sampleRate > 0f) || sampleRate.isInfinite()) {
            java.util.Arrays.fill(outL, 0, numFrames, 0f); java.util.Arrays.fill(outR, 0, numFrames, 0f)
            return
        }
        val step = SAMPLE_RATE / sampleRate.toDouble()
        lock.withLock {
            context.enter()
            try {
                for (i in 0 until numFrames) {
                    val t = frac.toFloat()
                    outL[i] = hermite(windowL, t)
                    outR[i] = hermite(windowR, t)
                    frac += step
                    while (frac >= 1.0) { frac -= 1.0; advance() }
                }
            } catch (t: Throwable) {
                java.util.Arrays.fill(outL, 0, numFrames, 0f); java.util.Arrays.fill(outR, 0, numFrames, 0f)
                if (closed.compareAndSet(false, true)) println("FM1Sim: stopped after a failure while rendering: $t")
            } finally {
                runCatching { context.leave() }
            }
        }
    }

    /** The simulator as a node in Field's audio graph. */
    val node: StereoRenderable by lazy { FM1SimNode(this) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopPatterns()
        lock.withLock { runCatching { context.close(true) } }
    }

    override fun toString(): String = synthPrettyObject(
        this, "sampleRate" to SAMPLE_RATE, "blocksRendered" to blocksRendered, "closed" to closed.get()
    )
}

/**
 * One track of an [FM1Sim] project, as the sequencer reports it.
 *
 * @param index the track, 0 until 8
 * @param source what makes its sound
 * @param program the DX7 voice, 303 wave or PSG shape it plays
 * @param volume its fader, 0..127
 * @param level its meter, 0..255, as the panel last drew it
 * @param muted whether it is muted
 * @param solo whether it is soloed
 * @param playing whether its sequence is running
 * @param playStep the step it is on
 * @param bpm the project tempo
 * @param activeSteps one bit per step that holds a note, step 0 in the lowest bit
 * @param length how many steps its sequence runs before it repeats
 * @param channel the MIDI channel it listens on (0-based), which [FM1Sim.note] uses
 */
data class FM1Track(
    val index: Int, val source: FM1Sim.SourceType, val program: Int, val volume: Int, val level: Int,
    val muted: Boolean, val solo: Boolean, val playing: Boolean, val playStep: Int, val bpm: Int,
    val activeSteps: Long, val length: Int, val channel: Int
) {
    /** The steps that hold a note, in order. */
    val steps: List<Int> get() = (0 until length.coerceAtMost(64)).filter { (activeSteps shr it) and 1L == 1L }
}

/**
 * A MIDI message the simulator sent: its [bytes], and the simulator [frame] (44,100 a second) it
 * left on.
 */
class FM1MidiEvent(val frame: Long, val bytes: IntArray) {
    /** Seconds since the simulator started. */
    val seconds: Double get() = frame / FM1Sim.SAMPLE_RATE.toDouble()

    override fun toString(): String = "FM1MidiEvent(%.3fs %s)".format(seconds, bytes.joinToString(" ") { "%02x".format(it) })
}

/** [FM1Sim] in the audio graph; a [StereoNode] so two readers in one block don't advance it twice. */
class FM1SimNode(val sim: FM1Sim) : StereoNode() {
    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (sim.modulations.isNotEmpty()) sim.applyModulations(numFrames, ctx)
        sim.render(outL, outR, numFrames, ctx.sampleRate)
    }
}
