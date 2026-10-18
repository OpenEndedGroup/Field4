@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.csound
/**
 * A running Csound you can render offline or play through Field's audio engine, with queued score
 * events, live recompilation and thread-safe control channels.
 */


import afield.audio.engine.WavFloatArrayWriter
import afield.audio.synth.Buf
import afield.audio.synth.StereoNode
import afield.audio.synth.StereoRenderable
import afield.audio.synth.SynthContext
import java.io.Closeable
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Csound inside Field: one running Csound, with its own orchestra, score and channels.
 *
 * ```kotlin
 * val cs = CsoundInstance("""
 * instr 1
 *   a1 oscili 0.25, 440
 *   outs a1, a1
 * endin
 * """, "i1 0 1")
 * val (left, right) = cs.renderOffline()
 * ```
 *
 * The rate header (`sr`, `ksmps`, `nchnls`, `0dbfs`) comes from the constructor, not the orchestra:
 * Field's audio engine fixes the sample rate, and `0dbfs = 1` makes Csound's samples Field's samples.
 * An orchestra that sets them itself is overridden.
 *
 * ## One thread performs, every thread may talk
 *
 * Csound is not thread-safe, and the thread that matters is whichever one calls [render] — the
 * audio thread when a box is playing, the caller when rendering offline. So:
 *
 *  * **score events and new instruments** ([event], [compile]) are queued and delivered by [render]
 *    at the top of the next block, in order.
 *  * **control channels** ([setChannel], [channel]) are Csound's own thread-safe cells: a slider
 *    writes one from the main thread and `chnget` reads it on the next k-cycle.
 *  * **messages** Csound prints go to its message buffer; [poll] hands whole lines to [onMessage]
 *    callbacks on the thread that polls — the box's main-thread loop, never the audio thread.
 *
 * @throws CsoundCompileError when the orchestra or score does not compile; its [CsoundCompileError.problems]
 *   carry the orchestra line each error is on
 */
class CsoundInstance(
    orchestra: String,
    score: String = "",
    val sampleRate: Int = 48_000,
    val ksmps: Int = 32,
    val outputs: Int = 2,
    val inputs: Int = 0,
    options: List<String> = emptyList(),
) : Closeable {

    /** Something Csound said about the orchestra; [line] is 1-based in the orchestra text, or -1. */
    data class Problem(val message: String, val line: Int)

    private val lock = ReentrantLock()
    private val arena: Arena = Arena.ofShared()
    private val cs: MemorySegment
    private var closed = false

    private val outgoing = ConcurrentLinkedQueue<(Arena) -> Unit>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val partial = StringBuilder()
    private val log = ArrayDeque<String>()
    private val channelNames = java.util.concurrent.ConcurrentHashMap<String, MemorySegment>()

    // ---- the data plane's shared state ---------------------------------------------------------
    // outvalue: written on the rendering thread by the upcall, coalesced per name, delivered by poll.
    private val outvalues = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val outvalueStrings = ConcurrentLinkedQueue<Pair<String, String>>()
    private val lastOutString = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val delivered = HashMap<String, Any>()
    private val outvalueListeners = java.util.concurrent.ConcurrentHashMap<String, CopyOnWriteArrayList<(Any) -> Unit>>()
    // invalue: what Kotlin has set; read by the upcall on the rendering thread.
    private val invalues = java.util.concurrent.ConcurrentHashMap<String, Double>()
    // MIDI bytes queued by Kotlin, read by Csound's host MIDI callback on the rendering thread.
    private val midiIn = ConcurrentLinkedQueue<ByteArray>()
    // a-rate channels: inputs are filled from Field signals per block, outputs captured per block.
    private val audioInputs = java.util.concurrent.ConcurrentHashMap<String, AudioFifo>()
    private val audioOutputs = java.util.concurrent.ConcurrentHashMap<String, AudioFifo>()
    private val audioScratch: MemorySegment
    private val spinL = AudioFifo(1 shl 15)
    private val spinR = AudioFifo(1 shl 15)

    private val spout: MemorySegment
    private val spin: MemorySegment
    private val zeroDbfs: Double
    private val pendingL: FloatArray
    private val pendingR: FloatArray
    private var pendingAt = 0
    private var pendingCount = 0

    /** True once the score has ended (an `e`, or the last event finished); [render] then gives silence. */
    @Volatile
    var finished: Boolean = false
        private set

    /** Frames rendered so far. */
    @Volatile
    var framesRendered: Long = 0L
        private set

    /** What Csound reported while compiling — warnings, mostly, when the constructor returned. */
    val problems: List<Problem>

    init {
        require(sampleRate > 0 && ksmps > 0) { "sampleRate and ksmps must be positive" }
        require(outputs in 1..64 && inputs in 0..64) { "channel counts out of range" }
        CsoundNative.ensureInitialised()
        cs = CsoundNative.create()
        check(cs != MemorySegment.NULL) { "csoundCreate failed" }
        CsoundNative.createMessageBuffer(cs)
        audioScratch = arena.allocate(8L * ksmps)
        installCallbacks()
        val all = listOf(
            "-n", "-d", "-m0", "-+rtmidi=hostbased", "-M0",
            "--sample-rate=$sampleRate", "--ksmps=$ksmps", "--nchnls=$outputs",
            "--nchnls_i=${maxOf(inputs, 1)}", "--0dbfs=1",
        ) + options
        val compileProblems: List<Problem>
        Arena.ofConfined().use { a ->
            for (o in all) CsoundNative.setOption(cs, a.allocateFrom(o))
            // Orchestra and score go in together as CSD text, the way a .csd file does: that is the
            // path on which a named instrument (`i "Filter" 0 z`) is known to the score, and on which
            // the score ends performance when it ends. The two tag lines before the orchestra are why
            // CSD_ORC_LINE is subtracted from the line numbers Csound reports.
            val csd = "<CsoundSynthesizer>\n<CsInstruments>\n" + normalise(orchestra) + "</CsInstruments>\n<CsScore>\n" +
                normalise(score) + "</CsScore>\n</CsoundSynthesizer>\n"
            val orcStatus = CsoundNative.compileCsdText(cs, a.allocateFrom(csd))
            val scoStatus = 0
            val startStatus = if (orcStatus == 0) CsoundNative.start(cs) else 0
            val printed = drainMessages()
            compileProblems = problemsIn(printed).map { if (it.line > CSD_ORC_LINE) it.copy(line = it.line - CSD_ORC_LINE) else it }
            if (orcStatus != 0 || scoStatus != 0 || startStatus != 0) {
                CsoundNative.destroyMessageBuffer(cs)
                CsoundNative.destroy(cs)
                arena.close()
                val what = when {
                    orcStatus != 0 -> "the orchestra did not compile"
                    scoStatus != 0 -> "the score did not read"
                    else -> "Csound did not start"
                }
                throw CsoundCompileError(what, compileProblems.ifEmpty { listOf(Problem(printed.joinToString("\n").ifBlank { what }, -1)) }, printed)
            }
            printed.forEach { remember(it) }
        }
        problems = compileProblems
        spout = CsoundNative.spout(cs).reinterpret(8L * ksmps * outputs)
        spin = CsoundNative.spin(cs).reinterpret(8L * ksmps * maxOf(inputs, 1))
        zeroDbfs = CsoundNative.zeroDbfs(cs)
        pendingL = FloatArray(ksmps)
        pendingR = FloatArray(ksmps)
        repeat(ksmps) { spinL.push(0f); spinR.push(0f) }
        applyDeclaredDefaults()
    }

    /**
     * Give each `chn_k` channel the default it declares. Csound 6 records `idflt` only as a hint for
     * the host and leaves the channel at 0, so `chn_k "cutoff", 1, 3, 2400, 200, 8000` followed by
     * `chnget "cutoff"` read 0 until something wrote it: a `butterlp` at 0 Hz, and a silent box. Field
     * is the host, so it writes the default once, before the first note's init pass. Only at
     * construction: a live recompile must not reset a channel Field has since set.
     *
     * The declared channels come from Csound itself ([channels], `csoundListChannels`), not from the
     * orchestra text: a `chn_k` whose name is a string variable, one inside an `#include`, or one in
     * a macro is found the same way as a literal one, and a commented-out `chn_k` is not found.
     */
    private fun applyDeclaredDefaults() {
        for (c in channels()) {
            val h = c.hints ?: continue
            if (c.kind == CsoundNative.CONTROL_CHANNEL && h.default != 0.0 && channel(c.name) == 0.0) setChannel(c.name, h.default)
        }
    }

    // ---- upcalls: these run on the rendering thread, inside Csound. They may only read and queue —
    // and must not throw, because an exception unwinding through an FFM upcall takes the VM down.

    private fun typeName(type: MemorySegment): String =
        if (type == MemorySegment.NULL) "k"
        else CsoundNative.cString(type.reinterpret(8).get(ADDRESS, 0))

    @Suppress("unused")
    private fun onOutputChannel(csound: MemorySegment, name: MemorySegment, value: MemorySegment, type: MemorySegment) {
        try {
            val n = CsoundNative.cString(name)
            if (typeName(type) == "S") {
                val str = CsoundNative.cString(value)
                if (lastOutString.put(n, str) != str) outvalueStrings += n to str
            } else {
                outvalues[n] = value.reinterpret(8).get(JAVA_DOUBLE, 0)
            }
        } catch (_: Throwable) {
        }
    }

    @Suppress("unused")
    private fun onInputChannel(csound: MemorySegment, name: MemorySegment, value: MemorySegment, type: MemorySegment) {
        try {
            if (typeName(type) == "S") return   // the string buffer's size is not passed; see setInvalue
            val v = invalues[CsoundNative.cString(name)] ?: return
            value.reinterpret(8).set(JAVA_DOUBLE, 0, v)
        } catch (_: Throwable) {
        }
    }

    @Suppress("unused")
    private fun onMidiOpen(csound: MemorySegment, userData: MemorySegment, dev: MemorySegment): Int = 0

    @Suppress("unused")
    private fun onMidiClose(csound: MemorySegment, userData: MemorySegment): Int = 0

    @Suppress("unused")
    private fun onMidiRead(csound: MemorySegment, userData: MemorySegment, buf: MemorySegment, n: Int): Int {
        return try {
            val out = buf.reinterpret(n.toLong())
            var at = 0
            while (true) {
                val next = midiIn.peek() ?: break
                if (at + next.size > n) break
                midiIn.poll()
                for (b in next) out.set(java.lang.foreign.ValueLayout.JAVA_BYTE, (at++).toLong(), b)
            }
            at
        } catch (_: Throwable) {
            0
        }
    }

    private fun installCallbacks() {
        val lookup = java.lang.invoke.MethodHandles.lookup()
        val linker = CsoundNative.linker()
        fun stub(name: String, d: java.lang.foreign.FunctionDescriptor): MemorySegment {
            val type = d.toMethodType()
            return linker.upcallStub(lookup.findVirtual(CsoundInstance::class.java, name, type).bindTo(this), d, arena)
        }
        CsoundNative.setOutputChannelCallback(cs, stub("onOutputChannel", CsoundNative.CHANNEL_CALLBACK))
        CsoundNative.setInputChannelCallback(cs, stub("onInputChannel", CsoundNative.CHANNEL_CALLBACK))
        CsoundNative.setHostImplementedMIDIIO(cs, 1)
        CsoundNative.setMidiInOpen(cs, stub("onMidiOpen", CsoundNative.MIDI_OPEN))
        CsoundNative.setMidiRead(cs, stub("onMidiRead", CsoundNative.MIDI_READ))
        CsoundNative.setMidiInClose(cs, stub("onMidiClose", CsoundNative.MIDI_CLOSE))
    }

    /** Run [block] with the raw CSOUND* under the perform lock. For this package's own bindings. */
    internal fun <T> withHandle(block: (MemorySegment) -> T): T = lock.withLock {
        check(!closed) { "this CsoundInstance is closed" }
        block(cs)
    }

    /** Csound's version as a number: 6181 is 6.18.1. */
    val version: Int get() = CsoundNative.version()

    /** Csound's score clock, in seconds. */
    val scoreTime: Double get() = lock.withLock { if (closed) 0.0 else CsoundNative.scoreTime(cs) }

    // ---- talking to Csound ------------------------------------------------------------------

    private fun enqueue(op: (Arena) -> Unit) {
        check(!closed) { "this CsoundInstance is closed" }
        outgoing += op
    }

    /** A score line, `i1 0 2 440` or `f1 0 4096 10 1`, delivered at the next block. */
    fun event(line: String) = enqueue { a -> CsoundNative.inputMessage(cs, a.allocateFrom(line)) }

    /** A score line built from numbers: `event('i', 1, 0, 2, 440)`. */
    fun event(type: Char, vararg fields: Number) = event("$type " + fields.joinToString(" "))

    /**
     * Compile more orchestra code into the running Csound — a new or redefined `instr` — at the next
     * block. Errors arrive as messages.
     */
    fun compile(orchestra: String) = enqueue { a -> CsoundNative.compileOrc(cs, a.allocateFrom(normalise(orchestra))) }

    private fun channelName(name: String): MemorySegment = channelNames.computeIfAbsent(name) { arena.allocateFrom(it) }

    /** Write control channel [name]; `chnget "name"` sees it on the next k-cycle. Any thread. */
    fun setChannel(name: String, value: Number) {
        check(!closed) { "this CsoundInstance is closed" }
        CsoundNative.setControlChannel(cs, channelName(name), value.toDouble())
    }

    /** Read control channel [name], as a `chnset` last left it. Any thread; 0 for an unknown channel. */
    fun channel(name: String): Double {
        if (closed) return 0.0
        Arena.ofConfined().use { a ->
            val err = a.allocate(JAVA_INT)
            return CsoundNative.getControlChannel(cs, channelName(name), err)
        }
    }

    /** Write string channel [name]; `chnget "name"` into an S variable sees it. Any thread. */
    fun setStringChannel(name: String, value: String) {
        check(!closed) { "this CsoundInstance is closed" }
        Arena.ofConfined().use { a -> CsoundNative.setStringChannel(cs, channelName(name), a.allocateFrom(value)) }
    }

    /** Read string channel [name], as `chnset Sval, "name"` last left it. "" for an unknown channel. */
    fun stringChannel(name: String): String {
        if (closed) return ""
        val n = channelName(name)
        val size = CsoundNative.channelDatasize(cs, n)
        if (size <= 0) return ""
        Arena.ofConfined().use { a ->
            val buf = a.allocate((size + 1).toLong())
            CsoundNative.getStringChannel(cs, n, buf)
            return buf.getString(0)
        }
    }

    /**
     * The value `invalue "name"` reads, at k-rate, on the rendering thread. Any thread may set it.
     * Numbers only: Csound does not tell the host how large an `invalue` string buffer is, so strings
     * go through [setStringChannel] and `chnget`.
     */
    fun setInvalue(name: String, value: Number) {
        invalues[name] = value.toDouble()
    }

    /**
     * Call [callback] when the orchestra's `outvalue "name", …` sends something: a [Double] for a
     * k-rate value, a [String] for an S value. Delivered by [poll] on the polling thread. A k-rate
     * `outvalue` runs every control cycle; the callback is called once per poll, and only when the
     * value changed. A string is delivered every time it changes.
     */
    fun onOutvalue(name: String, callback: (Any) -> Unit): Closeable {
        val list = outvalueListeners.computeIfAbsent(name) { CopyOnWriteArrayList() }
        list += callback
        return Closeable { list -= callback }
    }

    /** The last value `outvalue "name"` sent, or null. Any thread. */
    fun outvalue(name: String): Any? = outvalues[name] ?: lastOutString[name]

    /** The length of function table [number], or -1 when there is none. */
    fun tableLength(number: Int): Int = lock.withLock {
        check(!closed) { "this CsoundInstance is closed" }
        CsoundNative.tableLength(cs, number)
    }

    /**
     * Copy [values] into function table [number] (`csoundTableCopyIn`), under the perform lock, so the
     * next block reads the new contents. The table keeps its length; [values] must have exactly that many.
     */
    fun writeTable(number: Int, values: DoubleArray) {
        lock.withLock {
            check(!closed) { "this CsoundInstance is closed" }
            val n = CsoundNative.tableLength(cs, number)
            require(n >= 0) { "Csound has no table $number" }
            require(values.size == n) { "table $number has $n values; got ${values.size}" }
            Arena.ofConfined().use { a -> CsoundNative.tableCopyIn(cs, number, a.allocateFrom(JAVA_DOUBLE, *values)) }
        }
    }

    /** [writeTable] from floats. */
    fun writeTable(number: Int, values: FloatArray) = writeTable(number, DoubleArray(values.size) { values[it].toDouble() })

    /** The [ControlHints] `chn_k` declared for channel [name], or null when it declared none. */
    fun hints(name: String): ControlHints? = lock.withLock {
        if (closed) return null
        Arena.ofConfined().use { a ->
            // controlChannelHints_t: int behav; MYFLT dflt, min, max; int x, y, width, height; char *attributes
            val h = a.allocate(64)
            if (CsoundNative.controlChannelHints(cs, channelName(name), h) != 0) return null
            val behav = h.get(JAVA_INT, 0)
            if (behav == 0) return null
            ControlHints(behav, h.get(JAVA_DOUBLE, 8), h.get(JAVA_DOUBLE, 16), h.get(JAVA_DOUBLE, 24))
        }
    }

    /**
     * Every channel the running orchestra has allocated, as Csound lists them (`csoundListChannels`):
     * those declared with `chn_k`/`chn_a`/`chn_S` and those a `chnget`/`chnset` created. A control
     * channel carries its [ControlHints] when `chn_k` declared any.
     */
    fun channels(): List<ChannelInfo> = lock.withLock {
        if (closed) return emptyList()
        Arena.ofConfined().use { a ->
            val out = a.allocate(ADDRESS)
            val n = CsoundNative.listChannels(cs, out)
            val list = out.get(ADDRESS, 0)
            if (n <= 0 || list == MemorySegment.NULL) return emptyList()
            try {
                val all = list.reinterpret(n * CsoundNative.CHANNEL_INFO_SIZE)
                (0 until n).map { i ->
                    val base = i * CsoundNative.CHANNEL_INFO_SIZE
                    val type = all.get(JAVA_INT, base + 8)
                    val h = base + CsoundNative.CHANNEL_INFO_HINTS_OFFSET
                    val behav = all.get(JAVA_INT, h)
                    ChannelInfo(
                        name = CsoundNative.cString(all.get(ADDRESS, base)),
                        kind = type and CsoundNative.CHANNEL_TYPE_MASK,
                        input = type and CsoundNative.INPUT_CHANNEL != 0,
                        output = type and CsoundNative.OUTPUT_CHANNEL != 0,
                        hints = if (behav == 0) null
                        else ControlHints(behav, all.get(JAVA_DOUBLE, h + 8), all.get(JAVA_DOUBLE, h + 16), all.get(JAVA_DOUBLE, h + 24)),
                    )
                }
            } finally {
                CsoundNative.deleteChannelList(cs, list)
            }
        }
    }

    /**
     * One allocated channel. [kind] is [CsoundNative.CONTROL_CHANNEL], [CsoundNative.AUDIO_CHANNEL] or
     * [CsoundNative.STRING_CHANNEL]; [input]/[output] are the direction bits Csound holds, which accumulate: a `chnget` or `chnset`
     * anywhere in the orchestra adds its direction, so a `chn_k "gain", 1` read by `chnget` was listed as
     * both in this checkout's pinned Csound. Read them as "something uses it this way", not as the declared `imode`.
     */
    data class ChannelInfo(val name: String, val kind: Int, val input: Boolean, val output: Boolean, val hints: ControlHints?)

    /** A channel's declared range: `chn_k "name", imode, itype, idflt, imin, imax`. [type] 1 integer, 2 linear, 3 exponential. */
    data class ControlHints(val type: Int, val default: Double, val min: Double, val max: Double)

    /**
     * A MIDI message to the orchestra, as if from a device: `massign`, `notnum`, `veloc`, `midic7`
     * read it. Bytes are queued and read by Csound's host-MIDI callback on the next block.
     */
    fun midi(status: Int, data1: Int, data2: Int) {
        midiIn += byteArrayOf(status.toByte(), data1.toByte(), data2.toByte())
    }

    /** Note on, 0-based [channel]; velocity 0 is a note off. */
    fun noteOn(channel: Int, pitch: Int, velocity: Int) = midi(0x90 or (channel and 15), pitch, velocity)

    /** Note off, 0-based [channel]. */
    fun noteOff(channel: Int, pitch: Int) = midi(0x80 or (channel and 15), pitch, 0)

    /** A controller, 0-based [channel]. */
    fun controlChange(channel: Int, controller: Int, value: Int) = midi(0xB0 or (channel and 15), controller, value)

    /**
     * Compile [orchestra] into the running Csound now — a new or redefined `instr` — and say what
     * Csound said. Takes the perform lock, so it waits for the current block and holds the next one
     * for as long as the compile takes (milliseconds). Problems' lines are lines of [orchestra].
     *
     * @return the problems; empty when it compiled
     */
    fun compileNow(orchestra: String): CompileResult = lock.withLock {
        check(!closed) { "this CsoundInstance is closed" }
        val before = drainMessages()
        before.forEach { pushLine(it) }
        val status = Arena.ofConfined().use { a -> CsoundNative.compileOrc(cs, a.allocateFrom(normalise(orchestra))) }
        val printed = drainMessages()
        // Csound keeps counting orchestra lines from where the CSD's orchestra began, so a live
        // compile's lines are offset by the same CSD_ORC_LINE as the first one's.
        val problems = problemsIn(printed).map { if (it.line > CSD_ORC_LINE) it.copy(line = it.line - CSD_ORC_LINE) else it }
        // An error that has its own line marker is not printed a second time below the box.
        if (problems.none { it.line > 0 }) printed.forEach { pushLine(it) }
        CompileResult(status == 0, problems, printed)
    }

    /** Read score text into the running Csound now: its events start relative to the current score time. */
    fun scoreNow(score: String): CompileResult = lock.withLock {
        check(!closed) { "this CsoundInstance is closed" }
        val status = Arena.ofConfined().use { a -> CsoundNative.readScore(cs, a.allocateFrom(normalise(score))) }
        val printed = drainMessages()
        printed.forEach { pushLine(it) }
        CompileResult(status == 0, problemsIn(printed), printed)
    }

    /** What [compileNow] or [scoreNow] did. */
    data class CompileResult(val ok: Boolean, val problems: List<Problem>, val printed: List<String>)

    // Lines drained while holding the lock are kept for the next poll's listeners.
    private val held = ConcurrentLinkedQueue<String>()
    private fun pushLine(line: String) { held += line }

    // ---- a-rate channels -------------------------------------------------------------------

    /** A one-reader one-writer sample FIFO between the audio thread's two sides of a block. */
    internal class AudioFifo(capacity: Int) {
        private val data = FloatArray(capacity)
        private var read = 0
        private var write = 0
        var size = 0
            private set
        fun push(src: FloatArray, n: Int) {
            for (i in 0 until n) {
                if (size == data.size) { read = (read + 1) % data.size; size-- }
                data[write] = src[i]; write = (write + 1) % data.size; size++
            }
        }
        fun push(v: Float) {
            if (size == data.size) { read = (read + 1) % data.size; size-- }
            data[write] = v; write = (write + 1) % data.size; size++
        }
        fun pop(): Float {
            if (size == 0) return 0f
            val v = data[read]; read = (read + 1) % data.size; size--
            return v
        }
    }

    /**
     * Declare a-rate input channel [name]: `chnget "name"` into an a-variable reads what [CsoundNode]
     * feeds it from a Field signal. The first block reads [ksmps] frames of silence: that is the
     * input's latency, one control period, so that a render of any length always has a whole block of
     * input ready.
     */
    fun declareAudioInput(name: String) {
        audioInputs.computeIfAbsent(name) { AudioFifo(1 shl 15).also { f -> repeat(ksmps) { f.push(0f) } } }
    }

    /** Push [n] frames of [samples] into a-rate input channel [name]. The rendering thread's call. */
    fun feedAudio(name: String, samples: FloatArray, n: Int) {
        (audioInputs[name] ?: throw IllegalArgumentException("no audio input '$name'; declareAudioInput first")).push(samples, n)
    }

    /**
     * Declare a-rate output channel [name]: what the orchestra `chnset`s into it is captured every
     * block, and [pullAudio] (or [audioSignal]) reads it back in step with [render].
     */
    fun declareAudioOutput(name: String) {
        audioOutputs.computeIfAbsent(name) { AudioFifo(1 shl 15) }
    }

    /** Read [n] frames captured from a-rate output channel [name] into [out]. The rendering thread's call. */
    fun pullAudio(name: String, out: FloatArray, n: Int) {
        val f = audioOutputs[name] ?: throw IllegalArgumentException("no audio output '$name'; declareAudioOutput first")
        for (i in 0 until n) out[i] = f.pop()
    }

    /** The values of function table [number] (an `f` statement or `ftgen`), or null when there is none. */
    fun table(number: Int): DoubleArray? = lock.withLock {
        check(!closed) { "this CsoundInstance is closed" }
        Arena.ofConfined().use { a ->
            val ptr = a.allocate(ADDRESS)
            val n = CsoundNative.getTable(cs, ptr, number)
            if (n <= 0) return null
            ptr.get(ADDRESS, 0).reinterpret(8L * n).toArray(JAVA_DOUBLE)
        }
    }

    // ---- messages from Csound ---------------------------------------------------------------

    /**
     * Call [callback] with every whole line Csound prints, except its routine end-of-performance
     * chatter ([isRoutineChatter]), which only [console] keeps. Delivered by [poll].
     */
    fun onMessage(callback: (String) -> Unit): Closeable {
        listeners += callback
        return Closeable { listeners -= callback }
    }

    /** The most recent lines Csound printed, oldest first (at most 200). */
    val console: List<String> get() = synchronized(log) { log.toList() }

    private fun remember(line: String) = synchronized(log) {
        log.addLast(line)
        while (log.size > 200) log.removeFirst()
    }

    // Csound's message buffer holds fragments; this joins them into lines.
    private fun drainMessages(): List<String> {
        val lines = ArrayList<String>()
        synchronized(partial) {
            while (CsoundNative.messageCount(cs) > 0) {
                partial.append(CsoundNative.cString(CsoundNative.firstMessage(cs)))
                CsoundNative.popFirstMessage(cs)
            }
            var nl = partial.indexOf("\n")
            while (nl >= 0) {
                lines += partial.substring(0, nl).trimEnd()
                partial.delete(0, nl + 1)
                nl = partial.indexOf("\n")
            }
        }
        return lines
    }

    /** Hand every line Csound has printed since the last poll to [onMessage] callbacks, on this thread. */
    fun poll(): Int {
        if (closed) return 0
        var n = 0
        while (true) {
            val (name, str) = outvalueStrings.poll() ?: break
            outvalueListeners[name]?.forEach { cb -> runCatching { cb(str) } }
            n++
        }
        for ((name, v) in outvalues) {
            if (delivered[name] == v) continue
            delivered[name] = v
            outvalueListeners[name]?.forEach { cb -> runCatching { cb(v) } }
            n++
        }
        val lines = ArrayList<String>()
        while (true) lines += held.poll() ?: break
        lines += drainMessages()
        for (l in lines) {
            remember(l)
            if (isRoutineChatter(l)) continue   // kept in [console], not shown
            for (cb in listeners) runCatching { cb(l) }
        }
        return lines.size + n
    }

    // ---- audio ------------------------------------------------------------------------------

    /**
     * Render [numFrames] of Csound's output into [outL] and [outR]. The owning thread's call.
     *
     * Csound works in whole `ksmps` blocks; the remainder of the last block is kept for the next
     * call, so rendering 100 then 28 frames gives exactly what rendering 128 would. [inL]/[inR]
     * feed `ins`/`inch` when the instance has inputs.
     */
    fun render(outL: FloatArray, outR: FloatArray, numFrames: Int, inL: FloatArray? = null, inR: FloatArray? = null) {
        lock.withLock {
            if (closed) {
                Buf.clear(outL, 0, numFrames)
                Buf.clear(outR, 0, numFrames)
                return
            }
            if (outgoing.isNotEmpty()) Arena.ofConfined().use { a ->
                while (true) (outgoing.poll() ?: break)(a)
            }
            if (inputs > 0) {
                for (i in 0 until numFrames) {
                    val l = inL?.getOrNull(i) ?: 0f
                    spinL.push(l); spinR.push(inR?.getOrNull(i) ?: l)
                }
            }
            var written = 0
            while (written < numFrames) {
                if (pendingCount == 0) performBlock(inL, inR, written)
                val take = minOf(pendingCount, numFrames - written)
                System.arraycopy(pendingL, pendingAt, outL, written, take)
                System.arraycopy(pendingR, pendingAt, outR, written, take)
                pendingAt += take
                pendingCount -= take
                written += take
            }
            framesRendered += numFrames
        }
    }

    private fun performBlock(inL: FloatArray?, inR: FloatArray?, at: Int) {
        if (finished) {
            pendingL.fill(0f)
            pendingR.fill(0f)
        } else {
            if (inputs > 0) for (f in 0 until ksmps) {
                val l = spinL.pop()
                val r = spinR.pop()
                spin.setAtIndex(JAVA_DOUBLE, (f * inputs).toLong(), l * zeroDbfs)
                if (inputs > 1) spin.setAtIndex(JAVA_DOUBLE, (f * inputs + 1).toLong(), r * zeroDbfs)
            }
            for ((name, fifo) in audioInputs) {
                val scale = zeroDbfs
                for (f in 0 until ksmps) audioScratch.setAtIndex(JAVA_DOUBLE, f.toLong(), fifo.pop() * scale)
                CsoundNative.setAudioChannel(cs, channelName(name), audioScratch)
            }
            if (CsoundNative.performKsmps(cs) != 0) finished = true
            for ((name, fifo) in audioOutputs) {
                CsoundNative.getAudioChannel(cs, channelName(name), audioScratch)
                val inv = 1.0 / zeroDbfs
                for (f in 0 until ksmps) fifo.push((audioScratch.getAtIndex(JAVA_DOUBLE, f.toLong()) * inv).toFloat())
            }
            val scale = 1.0 / zeroDbfs
            for (f in 0 until ksmps) {
                val l = spout.getAtIndex(JAVA_DOUBLE, (f * outputs).toLong()) * scale
                pendingL[f] = l.toFloat()
                pendingR[f] = if (outputs > 1) (spout.getAtIndex(JAVA_DOUBLE, (f * outputs + 1).toLong()) * scale).toFloat() else l.toFloat()
            }
        }
        pendingAt = 0
        pendingCount = ksmps
    }

    /**
     * Render without an audio device: [seconds] of output, or — when null — until the score ends
     * (capped at [maxSeconds]). Messages are [poll]ed as it goes.
     */
    fun renderOffline(seconds: Double? = null, maxSeconds: Double = 600.0, chunk: Int = 1024): Pair<FloatArray, FloatArray> {
        val limit = ((seconds ?: maxSeconds) * sampleRate).toLong().coerceAtLeast(1)
        val left = FloatArrayBuilder()
        val right = FloatArrayBuilder()
        val bl = FloatArray(chunk)
        val br = FloatArray(chunk)
        var at = 0L
        while (at < limit && (seconds != null || !finished)) {
            val n = minOf(chunk.toLong(), limit - at).toInt()
            render(bl, br, n)
            left.add(bl, n)
            right.add(br, n)
            at += n
            poll()
        }
        return left.build() to right.build()
    }

    /** [renderOffline] into a 16-bit stereo `.wav`. Returns the file. */
    fun renderToWav(file: File, seconds: Double? = null): File {
        val (l, r) = renderOffline(seconds)
        file.absoluteFile.parentFile?.mkdirs()
        WavFloatArrayWriter.writeStereo16(file, l, r, sampleRate)
        return file
    }

    /** This Csound as a node in Field's audio graph, optionally fed by [input]. */
    fun node(input: StereoRenderable? = null, audioIn: Map<String, afield.audio.synth.Signal> = emptyMap()): CsoundNode =
        CsoundNode(this, input, audioIn)

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            runCatching { CsoundNative.cleanup(cs) }
            CsoundNative.destroyMessageBuffer(cs)
            CsoundNative.destroy(cs)
        }
        runCatching { arena.close() }
    }

    val isClosed: Boolean get() = closed

    override fun toString(): String =
        "CsoundInstance(${sampleRate}Hz, ksmps $ksmps, $outputs out${if (finished) ", score ended" else ""}${if (closed) ", closed" else ""})"

    private class FloatArrayBuilder {
        private var data = FloatArray(1 shl 16)
        private var size = 0
        fun add(src: FloatArray, n: Int) {
            if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
            System.arraycopy(src, 0, data, size, n)
            size += n
        }
        fun build(): FloatArray = data.copyOf(size)
    }

    companion object {
        /**
         * Common indentation removed, so a `"""` literal can sit indented in a box. Leading blank
         * lines are kept, so the line numbers Csound reports are lines of [text] as written.
         */
        fun normalise(text: String): String {
            val leading = text.lines().takeWhile { it.isBlank() }.size.coerceAtMost(text.lines().size - 1)
            return "\n".repeat(leading) + text.trimIndent() + "\n"
        }

        private val CHATTER = listOf(
            Regex("""^Score finished in csoundPerformKsmps\(\)( with \d+)?\.?$"""),
            Regex("""^SECTION \d+:$"""),
            Regex("""^end of (score|section \d+)\.?.*$"""),
            Regex("""^new alloc for instr .*$"""),
            Regex("""^inactive allocs returned to freespace$"""),
            Regex("""^overall amps:.*$"""),
            Regex("""^\s*overall samples out of range:.*$"""),
            Regex("""^\d+ errors in performance$"""),
            Regex("""^\s*B\s+[\d.]+\s+(\.\.|-)\s*[\d.]+\s+T\s+[\d.]+.*$"""),
            Regex("""^\s*rtevent:\s+T\s+[\d.]+.*$"""),
        )

        /**
         * Csound's routine report on a performance going as planned — "Score finished in
         * csoundPerformKsmps() with 2.", "SECTION 1:", section and score ends, allocation notes, the
         * per-section amplitude lines. Warnings, errors and everything an orchestra prints itself
         * (`prints`, `printks`) are not chatter.
         */
        fun isRoutineChatter(line: String): Boolean {
            val t = line.trim()
            return t.isEmpty() || CHATTER.any { it.matches(t) }
        }

        /** Lines of CSD wrapping before the orchestra's first line. */
        internal const val CSD_ORC_LINE = 2

        private val lineRe = Regex("""line:?\s*(\d+)""")

        /**
         * The errors in what Csound printed while compiling, each placed on its orchestra line when
         * Csound said which. A syntax error is printed as `error: syntax error, … line N:` followed by
         * the offending text; an unknown opcode as `… Line: N` further down.
         */
        fun problemsIn(printed: List<String>): List<Problem> {
            val out = ArrayList<Problem>()
            val summaries = ArrayList<String>()
            fun isStart(t: String) = t.startsWith("error:", ignoreCase = true) || t.startsWith("INIT ERROR") ||
                (t.contains("syntax error") && !t.contains("Parsing failed"))
            fun isSummary(t: String) = t.contains("Parsing failed") || t.startsWith("Stopping on parser failure")
            var k = 0
            while (k < printed.size) {
                val first = printed[k].trim()
                if (isSummary(first)) { summaries += first.substringAfter("),").trim(); k++; continue }
                if (!isStart(first) && !first.contains("not found")) { k++; continue }
                // One diagnostic: the error line and its continuation lines, up to the next error or
                // the parser's summary. The line number is read from them and not repeated in the text.
                val body = ArrayList<String>()
                body += first.removePrefix("error:").trim()
                var n = lineRe.find(first.lowercase())?.groupValues?.get(1)?.toIntOrNull() ?: -1
                k++
                while (k < printed.size) {
                    val t = printed[k].trim()
                    if (isStart(t) || isSummary(t)) break
                    k++
                    if (t.isEmpty()) continue
                    val ln = lineRe.find(t.lowercase())?.groupValues?.get(1)?.toIntOrNull()
                    if (ln != null && n < 0) n = ln
                    if (Regex("""^(line:?\s*\d+:?|from file .*)$""", RegexOption.IGNORE_CASE).matches(t)) continue
                    body += t
                }
                out += Problem(body.joinToString("\n"), n)
            }
            if (out.isEmpty() && summaries.isNotEmpty()) out += Problem(summaries.distinct().joinToString("\n"), -1)
            return out.distinctBy { it.message to it.line }
        }
    }
}

/** The orchestra or score did not compile. [problems] carry what Csound said, placed on lines. */
class CsoundCompileError(message: String, val problems: List<CsoundInstance.Problem>, val printed: List<String>) :
    RuntimeException(message + problems.joinToString("") { "\n  " + (if (it.line >= 0) "line ${it.line}: " else "") + it.message })

/**
 * A [CsoundInstance] in Field's audio graph. A [StereoNode] because Csound is stateful: the node's
 * per-block cache keeps a graph that reuses it from performing twice. Whatever thread renders the
 * graph — the audio thread, live — becomes the thread that runs Csound's perform loop, and the only
 * thread that touches its audio.
 *
 * Field audio goes in two ways: [input] feeds `ins`/`inch` (the instance needs `inputs = 2`), and
 * each of [audioIn] feeds the a-rate channel of that name, read with `ain chnget "name"`. Csound's
 * audio comes back as `outs` (this node's own stereo output) and as Field [Signal]s from [outL],
 * [outR] and [audioOut] (`chnset aout, "name"`), which compose with the rest of a synth graph.
 *
 * Latency: Csound performs whole `ksmps` blocks. Its output is in step with the block the engine
 * asked for; audio *into* Csound (both paths) is delayed by exactly `ksmps` frames, so that every
 * block always has a whole control period of input whatever size the engine's buffer is.
 */
class CsoundNode(
    val csound: CsoundInstance,
    private val input: StereoRenderable? = null,
    audioIn: Map<String, afield.audio.synth.Signal> = emptyMap(),
) : StereoNode() {
    private val audioIn = java.util.concurrent.ConcurrentHashMap(audioIn)
    private var inL = FloatArray(0)
    private var inR = FloatArray(0)
    private var scratch = FloatArray(0)
    private var lastL = FloatArray(0)
    private var lastR = FloatArray(0)
    private var lastFrames = 0
    private val outs = java.util.concurrent.ConcurrentHashMap<String, afield.audio.synth.Signal>()

    init {
        for (name in audioIn.keys) csound.declareAudioInput(name)
    }

    /**
     * Feed [signal] into a-rate channel [name] from the next block on, replacing what fed it before.
     * Safe from any thread; the signal is rendered on the audio thread, as part of this node's block.
     */
    fun connect(name: String, signal: afield.audio.synth.Signal) {
        csound.declareAudioInput(name)
        audioIn[name] = signal
    }

    /** Stop feeding channel [name]; the orchestra reads silence there. */
    fun disconnect(name: String) {
        audioIn.remove(name)
    }

    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (scratch.size < numFrames) scratch = FloatArray(numFrames)
        for ((name, sig) in audioIn) {
            sig.render(scratch, numFrames, ctx)
            csound.feedAudio(name, scratch, numFrames)
        }
        if (input != null && csound.inputs > 0) {
            if (inL.size < numFrames) {
                inL = FloatArray(numFrames)
                inR = FloatArray(numFrames)
            }
            Buf.clear(inL, 0, numFrames)
            Buf.clear(inR, 0, numFrames)
            input.render(inL, inR, numFrames, ctx)
            csound.render(outL, outR, numFrames, inL, inR)
        } else {
            csound.render(outL, outR, numFrames)
        }
        if (lastL.size < numFrames) { lastL = FloatArray(numFrames); lastR = FloatArray(numFrames) }
        System.arraycopy(outL, 0, lastL, 0, numFrames)
        System.arraycopy(outR, 0, lastR, 0, numFrames)
        lastFrames = numFrames
    }

    private fun ensure(numFrames: Int, ctx: SynthContext) {
        if (scratch.size < numFrames) scratch = FloatArray(numFrames)
        val l = FloatArray(numFrames)
        render(l, FloatArray(numFrames), numFrames, ctx)   // cached per cycle: performs at most once
    }

    /** Csound's left `outs` as a Field signal. */
    val outL: afield.audio.synth.Signal get() = outs.computeIfAbsent("\u0000L") { side(true) }

    /** Csound's right `outs` as a Field signal. */
    val outR: afield.audio.synth.Signal get() = outs.computeIfAbsent("\u0000R") { side(false) }

    private fun side(left: Boolean) = object : afield.audio.synth.Signal() {
        override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
            ensure(numFrames, ctx)
            System.arraycopy(if (left) lastL else lastR, 0, output, 0, numFrames)
        }
        override fun toString() = "CsoundNode.${if (left) "outL" else "outR"}"
    }

    /**
     * The a-rate channel [name] the orchestra writes (`chnset asig, "name"`), as a Field signal. One
     * signal per name: asking twice gives the same one.
     */
    fun audioOut(name: String): afield.audio.synth.Signal = outs.computeIfAbsent(name) {
        csound.declareAudioOutput(name)
        object : afield.audio.synth.Signal() {
            override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
                ensure(numFrames, ctx)
                csound.pullAudio(name, output, numFrames)
            }
            override fun toString() = "CsoundNode.audioOut($name)"
        }
    }

    override fun toString(): String = "CsoundNode($csound)"
}
