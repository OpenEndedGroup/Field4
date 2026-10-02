@file:afield.CompletionCategory("Synthesis")
package afield.csound

import afield.four.audio.WavFloatArrayWriter
import afield.four.synth.Buf
import afield.four.synth.StereoNode
import afield.four.synth.StereoRenderable
import afield.four.synth.SynthContext
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
        val all = listOf(
            "-n", "-d", "-m0",
            "--sample-rate=$sampleRate", "--ksmps=$ksmps", "--nchnls=$outputs",
            "--nchnls_i=${maxOf(inputs, 1)}", "--0dbfs=1",
        ) + options
        val compileProblems: List<Problem>
        Arena.ofConfined().use { a ->
            for (o in all) CsoundNative.setOption(cs, a.allocateFrom(o))
            val orcStatus = CsoundNative.compileOrc(cs, a.allocateFrom(normalise(orchestra)))
            val sco = normalise(score)
            val scoStatus = if (orcStatus == 0 && sco.isNotBlank()) CsoundNative.readScore(cs, a.allocateFrom(sco)) else 0
            val startStatus = if (orcStatus == 0 && scoStatus == 0) CsoundNative.start(cs) else 0
            val printed = drainMessages()
            compileProblems = problemsIn(printed)
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

    /** Call [callback] with every whole line Csound prints. Delivered by [poll]. */
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
        val lines = drainMessages()
        for (l in lines) {
            remember(l)
            for (cb in listeners) runCatching { cb(l) }
        }
        return lines.size
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
                val l = inL?.getOrNull(at + f) ?: 0f
                val r = inR?.getOrNull(at + f) ?: l
                spin.setAtIndex(JAVA_DOUBLE, (f * inputs).toLong(), l * zeroDbfs)
                if (inputs > 1) spin.setAtIndex(JAVA_DOUBLE, (f * inputs + 1).toLong(), r * zeroDbfs)
            }
            if (CsoundNative.performKsmps(cs) != 0) finished = true
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
    fun node(input: StereoRenderable? = null): CsoundNode = CsoundNode(this, input)

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
        /** Common indentation removed, so a `"""` literal can sit indented in a box. Line count is kept. */
        fun normalise(text: String): String = text.trimIndent() + "\n"

        private val lineRe = Regex("""line:?\s*(\d+)""")

        /**
         * The errors in what Csound printed while compiling, each placed on its orchestra line when
         * Csound said which. A syntax error is printed as `error: syntax error, … line N:` followed by
         * the offending text; an unknown opcode as `… Line: N` further down.
         */
        fun problemsIn(printed: List<String>): List<Problem> {
            val out = ArrayList<Problem>()
            for ((k, raw) in printed.withIndex()) {
                val line = raw.trim()
                val isError = line.startsWith("error:", ignoreCase = true) || line.contains("syntax error") ||
                    line.startsWith("INIT ERROR") || line.contains("not found")
                if (!isError || line.startsWith("Parsing failed")) continue
                val lookahead = (k until minOf(printed.size, k + 6)).map { printed[it] }
                val n = lookahead.firstNotNullOfOrNull { lineRe.find(it.lowercase())?.groupValues?.get(1)?.toIntOrNull() } ?: -1
                out += Problem(line.removePrefix("error:").trim(), n)
            }
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
 * graph — the audio thread, live — becomes the thread that runs Csound's perform loop.
 */
class CsoundNode(val csound: CsoundInstance, private val input: StereoRenderable? = null) : StereoNode() {
    private var inL = FloatArray(0)
    private var inR = FloatArray(0)

    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
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
    }

    override fun toString(): String = "CsoundNode($csound)"
}
