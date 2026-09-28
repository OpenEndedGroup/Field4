@file:afield.CompletionCategory("Synthesis")
package afield.pd

import afield.four.synth.Buf
import afield.four.synth.StereoNode
import afield.four.synth.SynthContext
import java.io.Closeable
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Pure Data inside Field: one running Pd, with its own patches, its own `[dac~]` and its own
 * `[s]`/`[r]` names.
 *
 * libpd is Pd's engine with the GUI taken away. A patch is plain text, so it can be written inline
 * in a box ([openPatchText]) or opened from a `.pd` file drawn in Pd itself ([openPatch]):
 *
 * ```kotlin
 * val pd = PdInstance()
 * pd.openPatchText("""
 * #N canvas 0 0 400 300 12;
 * #X obj 20 20 osc~ 440;
 * #X obj 20 60 *~ 0.1;
 * #X obj 20 100 dac~;
 * #X connect 0 0 1 0;
 * #X connect 1 0 2 0;
 * #X connect 1 0 2 1;
 * """)
 * val (left, right) = pd.renderOffline(1.0)
 * ```
 *
 * ## One thread renders, every thread may talk
 *
 * libpd is not thread-safe, and the thread that matters is whichever one calls [render] — the audio
 * thread when a box is playing, the caller when rendering offline. So:
 *
 *  * **messages to Pd** ([send], [bang], [symbol], [list], [message]) from any thread are queued
 *    lock-free and delivered by [render] at the top of the next block, in order. A slider moved
 *    on the main thread never touches libpd itself. [flush] delivers them immediately, for when
 *    nothing is rendering.
 *  * **messages from Pd** — a `[s name]` that Field has [receive]d, or a `[print]` — arrive in libpd's
 *    hooks, on the rendering thread, in the middle of a block. There they are only queued. [poll]
 *    hands them to Kotlin callbacks on the thread that calls it; a playing box polls from its main
 *    thread loop, so no user lambda ever runs on the audio thread.
 *  * **structural calls** (opening and closing patches, arrays) take the same lock [render] holds,
 *    briefly. They are rare, and an array read that could interleave with a block would be torn.
 *
 * Every libpd call made here first makes this instance libpd's current one, on this thread; that is
 * what the `PD_MULTI` build requires and why two instances never hear each other.
 *
 * @param sampleRate Pd's sample rate; `osc~ 440` is 440Hz at this rate
 * @param inputs audio inputs Pd's `[adc~]` sees
 * @param outputs audio outputs Pd's `[dac~]` has; [render] takes the first two as left and right,
 *   and a mono Pd is heard in both
 */
class PdInstance(
    val sampleRate: Int = 48_000,
    val inputs: Int = 0,
    val outputs: Int = 2,
) : Closeable {

    /** A message Pd sent to Field. [selector] is `bang`, `float`, `symbol`, `list`, or a message name. */
    data class Message(val receiver: String, val selector: String, val args: List<Any>) {
        /** The first argument as a number, when it is one — the value of a `float` message. */
        val float: Float? get() = args.firstOrNull() as? Float

        /** The first argument as a symbol, when it is one. */
        val symbol: String? get() = args.firstOrNull() as? String

        override fun toString(): String = when (selector) {
            "bang" -> "$receiver: bang"
            "float" -> "$receiver: ${args.first()}"
            else -> "$receiver: $selector ${args.joinToString(" ")}".trimEnd()
        }
    }

    private val lock = ReentrantLock()
    private val arena: Arena = Arena.ofShared()
    private val handle: MemorySegment
    private var closed = false

    /** Pd renders in blocks of this many frames — 64 in every libpd build. */
    val blockSize: Int

    private val outgoing = ConcurrentLinkedQueue<(Arena) -> Unit>()
    private val incoming = ConcurrentLinkedQueue<Any>()   // Message or a printed line
    private val receivers = ConcurrentHashMap<String, CopyOnWriteArrayList<(Message) -> Unit>>()
    private val bindings = ConcurrentHashMap<String, MemorySegment>()
    private val printers = CopyOnWriteArrayList<(String) -> Unit>()
    private val lastSeen = ConcurrentHashMap<String, Message>()
    private val printBuffer = StringBuilder()
    private val printLog = ArrayDeque<String>()
    private val openPatches = CopyOnWriteArrayList<PdPatch>()

    // Rendering scratch: Pd renders whole 64-frame blocks, the caller asks for any number of frames,
    // so the remainder of the last block is kept for the next call.
    private var inBlock: MemorySegment
    private var outBlock: MemorySegment
    private val pendingL: FloatArray
    private val pendingR: FloatArray
    private var pendingAt = 0
    private var pendingCount = 0

    /** Frames rendered so far; Pd's own clock, in samples. */
    @Volatile
    var framesRendered: Long = 0L
        private set

    private val hooks = object : PdNative.Hooks {
        // These run on the rendering thread, inside libpd. They may only queue — and must not throw,
        // because an exception unwinding through an FFM upcall takes the whole VM down.
        override fun print(s: MemorySegment) = guard {
            synchronized(printBuffer) {
                printBuffer.append(PdNative.cString(s))
                var nl = printBuffer.indexOf("\n")
                while (nl >= 0) {
                    val line = printBuffer.substring(0, nl)
                    printBuffer.delete(0, nl + 1)
                    incoming += PrintedLine(line)
                    nl = printBuffer.indexOf("\n")
                }
            }
        }

        override fun bang(recv: MemorySegment) = guard {
            arrive(Message(PdNative.cString(recv), "bang", emptyList()))
        }

        override fun float(recv: MemorySegment, x: Float) = guard {
            arrive(Message(PdNative.cString(recv), "float", listOf(x)))
        }

        override fun symbol(recv: MemorySegment, s: MemorySegment) = guard {
            arrive(Message(PdNative.cString(recv), "symbol", listOf(PdNative.cString(s))))
        }

        override fun list(recv: MemorySegment, argc: Int, argv: MemorySegment) = guard {
            arrive(Message(PdNative.cString(recv), "list", PdNative.atoms(argc, argv)))
        }

        override fun message(recv: MemorySegment, msg: MemorySegment, argc: Int, argv: MemorySegment) = guard {
            arrive(Message(PdNative.cString(recv), PdNative.cString(msg), PdNative.atoms(argc, argv)))
        }
    }

    private class PrintedLine(val text: String)

    private fun arrive(message: Message) {
        lastSeen[message.receiver] = message
        incoming += message
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(outputs in 1..64 && inputs in 0..64) { "channel counts out of range" }
        PdNative.ensureInitialised()
        lock.lock()
        try {
            handle = PdNative.newInstance()
            check(handle != MemorySegment.NULL) {
                "libpd was built without multi-instance support; rebuild it with tools/build_libpd_native.py"
            }
            PdNative.setInstance(handle)
            PdNative.installHooks(hooks, arena)
            PdNative.initAudio(inputs, outputs, sampleRate)
            blockSize = PdNative.blockSize()
            inBlock = arena.allocate(JAVA_FLOAT, (blockSize * maxOf(1, inputs)).toLong())
            outBlock = arena.allocate(JAVA_FLOAT, (blockSize * outputs).toLong())
            // DSP on: without it every [dac~] is silent.
            PdNative.startMessage(1)
            PdNative.addFloat(1f)
            Arena.ofConfined().use { a -> PdNative.finishMessage(a.allocateFrom("pd"), a.allocateFrom("dsp")) }
        } finally {
            lock.unlock()
        }
        pendingL = FloatArray(blockSize)
        pendingR = FloatArray(blockSize)
    }

    private inline fun <T> current(block: () -> T): T = lock.withLock {
        check(!closed) { "this PdInstance is closed" }
        PdNative.setInstance(handle)
        block()
    }

    // ---- patches ----------------------------------------------------------------------------

    /**
     * Open a `.pd` file. Abstractions and `[soundfiler]` paths resolve relative to its folder.
     *
     * Pd reports an object it cannot create (`[osc 440]` for `[osc~ 440]`) on its console rather
     * than failing the open; those lines are collected in [PdPatch.problems].
     */
    fun openPatch(file: File, source: String? = null): PdPatch {
        val absolute = file.absoluteFile
        require(absolute.isFile) { "no Pd patch at $absolute" }
        val text = source ?: absolute.readText()
        val before = drainPrintsForOpen()
        val patch = current {
            Arena.ofConfined().use { a ->
                val p = PdNative.openFile(a.allocateFrom(absolute.name), a.allocateFrom(absolute.parent ?: "."))
                if (p == MemorySegment.NULL) throw PdPatchError("Pd could not open $absolute", emptyList())
                PdPatch(this, p, PdNative.dollarZero(p), absolute, text)
            }
        }
        val during = drainPrintsForOpen()
        patch.problems = PdPatch.problemsIn(text, during)
        (before + during).forEach { incoming += PrintedLine(it) }
        openPatches += patch
        return patch
    }

    /**
     * Open a patch written as text — the contents of a `.pd` file.
     *
     * The text is written to a temporary folder and opened from there, because libpd opens files and
     * nothing else. A leading blank line and common indentation are removed, so a `"""` literal can
     * sit indented in a box. A missing `#N canvas` header is supplied.
     */
    fun openPatchText(text: String, name: String = "inline"): PdPatch {
        val cleaned = normalisePatchText(text)
        val dir = Files.createTempDirectory("field-pd-").toFile().apply { deleteOnExit() }
        val safe = name.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "inline" }
        val file = File(dir, "$safe.pd")
        file.writeText(cleaned)
        file.deleteOnExit()
        return openPatch(file, text)
    }

    private fun drainPrintsForOpen(): List<String> {
        // Prints produced while opening a patch are both problems (maybe) and console lines.
        val lines = ArrayList<String>()
        val keep = ArrayList<Any>()
        while (true) {
            val item = incoming.poll() ?: break
            if (item is PrintedLine) lines += item.text else keep += item
        }
        incoming.addAll(keep)
        return lines
    }

    internal fun closePatch(patch: PdPatch) {
        if (closed) return
        current { PdNative.closeFile(patch.handle) }
        openPatches -= patch
    }

    /** The patches open in this Pd. */
    val patches: List<PdPatch> get() = openPatches.toList()

    // ---- messages to Pd ---------------------------------------------------------------------

    private fun enqueue(op: (Arena) -> Unit) {
        check(!closed) { "this PdInstance is closed" }
        outgoing += op
    }

    /** Send a number to `[r receiver]`. Queued; delivered at the next block or [flush]. */
    fun send(receiver: String, value: Number) = enqueue { a ->
        PdNative.float(a.allocateFrom(receiver), value.toFloat())
    }

    /** Send a bang to `[r receiver]`. */
    fun bang(receiver: String) = enqueue { a -> PdNative.bang(a.allocateFrom(receiver)) }

    /** Send a symbol to `[r receiver]`. */
    fun symbol(receiver: String, symbol: String) = enqueue { a ->
        PdNative.symbol(a.allocateFrom(receiver), a.allocateFrom(symbol))
    }

    /** Send a list of numbers and symbols to `[r receiver]`. */
    fun list(receiver: String, vararg items: Any) = enqueue { a ->
        PdNative.startMessage(items.size.coerceAtLeast(1))
        addAtoms(a, items)
        PdNative.finishList(a.allocateFrom(receiver))
    }

    /**
     * Send `selector args…` to `[r receiver]` — a Pd message like `set 3` or, to Pd itself,
     * `message("pd", "dsp", 0)`.
     */
    fun message(receiver: String, selector: String, vararg args: Any) = enqueue { a ->
        PdNative.startMessage(args.size.coerceAtLeast(1))
        addAtoms(a, args)
        PdNative.finishMessage(a.allocateFrom(receiver), a.allocateFrom(selector))
    }

    private fun addAtoms(a: Arena, items: Array<out Any>) {
        for (item in items) when (item) {
            is Number -> PdNative.addFloat(item.toFloat())
            is Boolean -> PdNative.addFloat(if (item) 1f else 0f)
            else -> PdNative.addSymbol(a.allocateFrom(item.toString()))
        }
    }

    /** Deliver queued messages now, then [poll]. For when nothing is calling [render]. */
    fun flush() {
        current { drainOutgoing() }
        poll()
    }

    private fun drainOutgoing() {
        if (outgoing.isEmpty()) return
        Arena.ofConfined().use { a ->
            while (true) {
                val op = outgoing.poll() ?: break
                op(a)
            }
        }
    }

    // ---- messages from Pd -------------------------------------------------------------------

    /**
     * Call [callback] with every message sent to `[s name]` in any patch of this Pd.
     *
     * Delivered by [poll], on the thread that polls — never on the audio thread. Closing the result
     * stops this callback; the binding itself lasts until the last callback for the name goes.
     */
    fun receive(name: String, callback: (Message) -> Unit): Closeable {
        val list = receivers.computeIfAbsent(name) { CopyOnWriteArrayList() }
        list += callback
        bindings.computeIfAbsent(name) { n ->
            current { Arena.ofConfined().use { a -> PdNative.bind(a.allocateFrom(n)) } }
        }
        return Closeable {
            list -= callback
            if (list.isEmpty()) {
                receivers.remove(name)
                bindings.remove(name)?.let { b -> if (!closed) current { PdNative.unbind(b) } }
            }
        }
    }

    /** Call [callback] with every whole line Pd prints — `[print]`, errors, warnings. Delivered by [poll]. */
    fun onPrint(callback: (String) -> Unit): Closeable {
        printers += callback
        return Closeable { printers -= callback }
    }

    /** The last message seen on each received name, safe to read from any thread. */
    fun lastValue(name: String): Message? = lastSeen[name]

    /** The most recent lines Pd printed, oldest first (at most 200). */
    val console: List<String> get() = synchronized(printLog) { printLog.toList() }

    /**
     * Hand every message Pd has sent since the last poll to its callbacks, on this thread.
     *
     * @return how many messages and printed lines were delivered
     */
    fun poll(): Int {
        var n = 0
        while (true) {
            val item = incoming.poll() ?: break
            n++
            when (item) {
                is PrintedLine -> {
                    synchronized(printLog) {
                        printLog.addLast(item.text)
                        while (printLog.size > 200) printLog.removeFirst()
                    }
                    for (p in printers) runCatching { p(item.text) }
                }
                is Message -> receivers[item.receiver]?.forEach { cb -> runCatching { cb(item) } }
            }
        }
        return n
    }

    // ---- arrays -----------------------------------------------------------------------------

    /** The length of Pd array `name` (a `[table name]` or an `#X array`), or -1 when there is none. */
    fun arraySize(name: String): Int = current {
        drainOutgoing()
        Arena.ofConfined().use { a -> PdNative.arraySize(a.allocateFrom(name)) }
    }

    /** Read `count` values of array `name` from `offset`. Throws when the array is missing or too short. */
    fun readArray(name: String, offset: Int = 0, count: Int = -1): FloatArray = current {
        drainOutgoing()
        Arena.ofConfined().use { a ->
            val nameC = a.allocateFrom(name)
            val size = PdNative.arraySize(nameC)
            if (size < 0) throw IllegalArgumentException("Pd has no array called '$name'")
            val n = if (count < 0) size - offset else count
            if (n <= 0) return@use FloatArray(0)
            val dest = a.allocate(JAVA_FLOAT, n.toLong())
            val status = PdNative.readArray(dest, nameC, offset, n)
            if (status != 0) throw IllegalArgumentException("cannot read $n values from '$name' at $offset (size $size)")
            dest.toArray(JAVA_FLOAT)
        }
    }

    /** Write [values] into array `name` from `offset`. Throws when the array is missing or too short. */
    fun writeArray(name: String, values: FloatArray, offset: Int = 0) {
        current {
            drainOutgoing()
            Arena.ofConfined().use { a ->
                val nameC = a.allocateFrom(name)
                val src = a.allocateFrom(JAVA_FLOAT, *values)
                val status = PdNative.writeArray(nameC, offset, src, values.size)
                if (status != 0) {
                    throw IllegalArgumentException(
                        "cannot write ${values.size} values to '$name' at $offset (size ${PdNative.arraySize(nameC)})")
                }
            }
        }
    }

    /** Resize array `name` to `size` values. */
    fun resizeArray(name: String, size: Int) {
        current {
            Arena.ofConfined().use { a ->
                if (PdNative.resizeArray(a.allocateFrom(name), size.toLong()) != 0) {
                    throw IllegalArgumentException("Pd has no array called '$name'")
                }
            }
        }
    }

    // ---- audio ------------------------------------------------------------------------------

    /**
     * Render `numFrames` of Pd's output into [outL] and [outR]. The owning thread's call.
     *
     * Queued messages are delivered first. Pd works in whole [blockSize] blocks, so a request that is
     * not a multiple of it leaves the rest of the last block for the next call: rendering 100 frames
     * then 28 gives exactly what rendering 128 would. [inL]/[inR] feed `[adc~]` when there are inputs.
     */
    fun render(outL: FloatArray, outR: FloatArray, numFrames: Int, inL: FloatArray? = null, inR: FloatArray? = null) {
        var written = 0
        lock.withLock {
            if (closed) {
                Buf.clear(outL, 0, numFrames)
                Buf.clear(outR, 0, numFrames)
                return
            }
            PdNative.setInstance(handle)
            drainOutgoing()
            while (written < numFrames) {
                if (pendingCount == 0) {
                    renderBlock(inL, inR, written)
                }
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

    private fun renderBlock(inL: FloatArray?, inR: FloatArray?, at: Int) {
        if (inputs > 0) {
            // Interleaved, as libpd wants it: frame-major, channel-minor.
            for (f in 0 until blockSize) {
                val l = inL?.getOrNull(at + f) ?: 0f
                val r = inR?.getOrNull(at + f) ?: l
                inBlock.setAtIndex(JAVA_FLOAT, (f * inputs).toLong(), l)
                if (inputs > 1) inBlock.setAtIndex(JAVA_FLOAT, (f * inputs + 1).toLong(), r)
            }
        }
        PdNative.processFloat(1, inBlock, outBlock)
        for (f in 0 until blockSize) {
            val l = outBlock.getAtIndex(JAVA_FLOAT, (f * outputs).toLong())
            pendingL[f] = l
            pendingR[f] = if (outputs > 1) outBlock.getAtIndex(JAVA_FLOAT, (f * outputs + 1).toLong()) else l
        }
        pendingAt = 0
        pendingCount = blockSize
    }

    /**
     * Render [seconds] of this Pd without an audio device, delivering Pd's messages to their callbacks
     * as it goes. The whole path a test or a documentation figure needs.
     *
     * @param chunk frames per [render] call; messages Pd sends are [poll]ed after each
     */
    fun renderOffline(seconds: Double, chunk: Int = 512): Pair<FloatArray, FloatArray> {
        require(seconds > 0.0) { "seconds must be positive" }
        val frames = (seconds * sampleRate).toInt().coerceAtLeast(1)
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        val bl = FloatArray(chunk)
        val br = FloatArray(chunk)
        var at = 0
        while (at < frames) {
            val n = minOf(chunk, frames - at)
            render(bl, br, n)
            System.arraycopy(bl, 0, left, at, n)
            System.arraycopy(br, 0, right, at, n)
            at += n
            poll()
        }
        return left to right
    }

    /** This Pd as a node in Field's audio graph. See [PdNode]. */
    val node: PdNode by lazy { PdNode(this) }

    /** Close every patch, free the Pd and its hooks. Safe to call twice. */
    override fun close() {
        lock.withLock {
            if (closed) return
            PdNative.setInstance(handle)
            for (b in bindings.values) runCatching { PdNative.unbind(b) }
            bindings.clear()
            for (p in openPatches) runCatching { PdNative.closeFile(p.handle) }
            openPatches.clear()
            PdNative.freeInstance(handle)
            closed = true
        }
        // The upcall stubs die with the arena, which is why this is after freeInstance.
        runCatching { arena.close() }
    }

    val isClosed: Boolean get() = closed

    override fun toString(): String =
        "PdInstance(${sampleRate}Hz, $inputs in / $outputs out, ${openPatches.size} patch(es)${if (closed) ", closed" else ""})"

    companion object {
        /**
         * The text of a patch, ready for Pd: common indentation removed, blank lines dropped, a
         * `#N canvas` header supplied when the text starts straight in with objects.
         */
        fun normalisePatchText(text: String): String {
            val lines = text.trimIndent().lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            val body = lines.joinToString("\n")
            return if (lines.firstOrNull()?.startsWith("#N canvas") == true) "$body\n"
            else "#N canvas 0 0 450 300 12;\n$body\n"
        }
    }
}

/** Pd could not open a patch at all. [problems] carries what it said. */
class PdPatchError(message: String, val problems: List<PdPatch.Problem>) : RuntimeException(message)

/**
 * One open patch in a [PdInstance].
 *
 * @property dollarZero the patch's `$0`, which makes a name like `$0-freq` local to this copy
 */
class PdPatch internal constructor(
    val instance: PdInstance,
    internal val handle: MemorySegment,
    val dollarZero: Int,
    val file: File,
    val source: String,
) : Closeable {

    /**
     * Something Pd said while opening the patch, placed on the line of [source] it is about when
     * that can be told. [line] is 0-based within [source]; -1 when it cannot be placed.
     */
    data class Problem(val message: String, val line: Int)

    /** What Pd complained about while opening this patch — objects it could not create, mostly. */
    var problems: List<Problem> = emptyList()
        internal set

    /** Close this patch; the rest of its [instance] keeps running. */
    override fun close() = instance.closePatch(this)

    override fun toString(): String = "PdPatch(${file.name}, \$0=$dollarZero, ${problems.size} problem(s))"

    internal companion object {
        /**
         * Place Pd's open-time console output on the patch's lines.
         *
         * Pd reports a failed object as two lines: the object's text, then `... couldn't create`.
         * The object text is found on the `#X obj` line that carries it.
         */
        fun problemsIn(source: String, printed: List<String>): List<Problem> {
            val lines = source.lines()
            val out = ArrayList<Problem>()
            // Pd 0.55 prefixes the lines of an error with "error: " and sometimes "verbose(0): ";
            // older ones did not.
            val prefix = Regex("""^(?:(?:error:|verbose\(\d+\):)\s*)+""")
            val bare = printed.map { it.trim().replace(prefix, "").trim() }
            for ((k, line) in bare.withIndex()) {
                if (line.startsWith("... couldn't create") && k > 0) {
                    val what = bare[k - 1]
                    out.removeAll { it.line < 0 && it.message.endsWith(what) }
                    val at = lines.indexOfFirst { l ->
                        val t = l.trim()
                        t.startsWith("#X obj") && t.removeSuffix(";").split(Regex("\\s+")).drop(4).joinToString(" ") == what
                    }
                    out += Problem("Pd couldn't create [$what]", at)
                } else if (printed[k].trim().startsWith("error:") || line.startsWith("warning:") ||
                    line.contains("can't connect")) {
                    out += Problem(line, -1)
                }
            }
            return out
        }
    }
}

/**
 * A [PdInstance] in Field's audio graph: `audio(pd.node)` plays it.
 *
 * A [StereoNode] because a Pd is stateful — rendering it twice in one block would advance every
 * oscillator twice — and the node's per-block cache is what prevents that when a graph reuses it.
 * Whatever thread renders the graph (the audio thread, live) becomes the thread that runs Pd.
 */
class PdNode(val pd: PdInstance, private val input: afield.four.synth.StereoRenderable? = null) : StereoNode() {
    private var inL = FloatArray(0)
    private var inR = FloatArray(0)

    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (input != null && pd.inputs > 0) {
            if (inL.size < numFrames) {
                inL = FloatArray(numFrames)
                inR = FloatArray(numFrames)
            }
            Buf.clear(inL, 0, numFrames)
            Buf.clear(inR, 0, numFrames)
            input.render(inL, inR, numFrames, ctx)
            pd.render(outL, outR, numFrames, inL, inR)
        } else {
            pd.render(outL, outR, numFrames)
        }
    }

    override fun toString(): String = "PdNode($pd)"
}
