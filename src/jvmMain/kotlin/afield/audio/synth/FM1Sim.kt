@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

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
 * fm1.preset(1)                     // a factory project: change the slot (0..3) and re-run
 * if (!fm1.isPlaying) fm1.play()    // PLAY/STOP is a toggle, so only press it when stopped
 * fm1.level(0.8)
 *
 * fm1.node
 * ```
 *
 * Launching the box plays it, and the pattern keeps going across re-runs; stopping the box takes it
 * out of the mix. [whiteKey] plays a note on top, [tap] presses any [Button], and [key], [encoder]
 * and [step] reach the rest of the panel. It is the simulator, not the hardware: the device runs a
 * closed engine library that the web build leaves out, so the two may differ.
 *
 * ## One context, shared, single-threaded
 *
 * A GraalVM context can be entered by one thread at a time and `render` runs on the audio thread,
 * so every control call is queued and applied at the start of the next block. [shared] keeps one
 * simulator for the JVM, so re-running a box reuses it rather than leaking a context per run, and
 * the sequencer keeps playing across edits.
 *
 * The simulator renders at a fixed 44,100 Hz; [node] resamples linearly to Field's rate.
 */
class FM1Sim private constructor(wasm: ByteArray) : Closeable {

    companion object {
        /** Frames the simulator renders per `sim_render` call. */
        const val BLOCK: Int = 128

        /** The simulator's only sample rate. */
        const val SAMPLE_RATE: Float = 44100f

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
    }

    private val closed = AtomicBoolean(false)
    /** A queued call and how many simulator blocks after it is drained it should run. */
    private class Pending(val delayBlocks: Int, val call: (Value) -> Unit)

    private val pending = ConcurrentLinkedQueue<Pending>()

    /** Calls waiting for their block, owned by the audio thread: (due block, call). */
    private val waiting = ArrayList<Pair<Long, (Value) -> Unit>>()
    /** Simulator blocks rendered so far; zero means nothing has pulled audio from it yet. */
    @Volatile var blocksRendered = 0L
        private set

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
    private val memory: Value

    private val scratch = ByteArray(BLOCK * 2 * 4)
    private val scratchFloats = java.nio.ByteBuffer.wrap(scratch)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .asFloatBuffer()

    /** One rendered simulator block, interleaved stereo, and how far into it the reader is. */
    private val block = FloatArray(BLOCK * 2)
    private var blockPos = BLOCK.toDouble()
    private var lastL = 0f
    private var lastR = 0f

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
            memory = exports.getMember("memory").getMember("buffer")
            check(memory.hasBufferElements()) { "FM1Sim: wasm memory has no buffer interop" }
            check(exports.getMember("sim_block_frames").execute().asInt() == BLOCK)
        } catch (t: Throwable) {
            runCatching { context.leave() }
            runCatching { context.close(true) }
            throw t
        }
        runCatching { context.leave() }
    }

    /** Queue a call against the module's exports; it runs on the audio thread before the next block. */
    private fun queue(delayBlocks: Int = 0, call: (Value) -> Unit) {
        if (!closed.get()) pending.add(Pending(delayBlocks, call))
    }

    private fun call(name: String, vararg args: Any) = queue { it.getMember(name).execute(*args) }

    private fun callLater(delayBlocks: Int, name: String, vararg args: Any) =
        queue(delayBlocks) { it.getMember(name).execute(*args) }

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

    /** Start or stop the sequencer, as the PLAY/STOP button does. */
    fun play() = tap(Button.PLAY)

    /**
     * Play white key `n` (1..16) for `blocks` simulator blocks (128 frames each).
     *
     * The sixteen white keys are also the step keys, numbered 01-16 on the panel.
     */
    fun whiteKey(n: Int, blocks: Int = 40) {
        require(n in 1..WHITE_KEYS.size) { "FM1Sim: white key $n is outside 1..${WHITE_KEYS.size}" }
        val index = WHITE_KEYS[n - 1]
        call("sim_control", 1, index, 1)
        callLater(blocks, "sim_control", 1, index, 0)
    }

    /** Panel button indices, in the simulator's numbering. */
    object Button {
        const val OCT_DOWN = 0; const val OCT_UP = 1
        const val FX = 2; const val SEL = 3; const val ENV = 4; const val LFO = 5; const val EDIT = 6; const val GLO = 7
        const val HOME = 8; const val SAVE = 9; const val ARP = 10; const val SEQ = 11; const val PLAY = 12; const val REC = 13
    }

    /** Restart the simulator; `clear` wipes stored projects and reloads the factory samples. */
    fun boot(clear: Boolean = false) = call("sim_boot", if (clear) 1 else 0)

    /** Load a built-in preset project by slot (0 is "NEON KEYS"). */
    fun preset(slot: Int) = call("sim_preset", slot)

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

    /** Turn encoder `index` (0 until 7) by `delta` detents, -127..127. */
    fun encoder(index: Int, delta: Int) = call("sim_control", 2, index, delta.coerceIn(-127, 127))

    /** Set the output-level knob, 0..1. */
    fun level(value: Double) = call("sim_control", 3, 0, (value.coerceIn(0.0, 1.0) * 1023).toInt())

    /** Toggle step `step` of the selected track. */
    fun step(step: Int) = call("sim_step", step, 1)

    /** Release every key, button and encoder. */
    fun release() = call("sim_release")

    /** Silence every voice. */
    fun panic() = call("sim_panic")

    /** Pull one 44.1 kHz block out of the module. Caller has entered the context. */
    private fun renderBlock() {
        while (true) {
            val p = pending.poll() ?: break
            if (p.delayBlocks <= 0) p.call(exports) else waiting.add(blocksRendered + p.delayBlocks to p.call)
        }
        if (waiting.isNotEmpty()) {
            val due = waiting.filter { it.first <= blocksRendered }
            if (due.isNotEmpty()) {
                waiting.removeAll(due)
                due.forEach { it.second(exports) }
            }
        }
        val ptr = fnRender.execute().asLong()
        memory.readBuffer(ptr, scratch, 0, scratch.size)
        scratchFloats.position(0)
        scratchFloats.get(block, 0, block.size)
        // Byte 14 of the screen snapshot is the transport, as the web page reads it. Sampled every
        // 32 blocks (~93 ms), since taking the snapshot is not free.
        if (blocksRendered % 32 == 0L) {
            val state = fnState.execute().asLong()
            playingSnapshot = memory.readBufferByte(state + 14) != 0.toByte()
        }
        blocksRendered++
    }

    /**
     * Render `numFrames` at `sampleRate`, resampling linearly from the simulator's 44,100 Hz.
     *
     * A failure inside the module fills silence and stops this simulator rather than the audio thread.
     */
    fun render(outL: FloatArray, outR: FloatArray, numFrames: Int, sampleRate: Float) {
        if (closed.get()) {
            java.util.Arrays.fill(outL, 0, numFrames, 0f); java.util.Arrays.fill(outR, 0, numFrames, 0f)
            return
        }
        val stepPerFrame = SAMPLE_RATE / sampleRate.toDouble()
        context.enter()
        try {
            for (i in 0 until numFrames) {
                blockPos += stepPerFrame
                while (blockPos >= BLOCK) {
                    lastL = block[(BLOCK - 1) * 2]; lastR = block[(BLOCK - 1) * 2 + 1]
                    renderBlock()
                    blockPos -= BLOCK
                }
                val index = blockPos.toInt()
                val frac = (blockPos - index).toFloat()
                val l0 = if (index == 0) lastL else block[(index - 1) * 2]
                val r0 = if (index == 0) lastR else block[(index - 1) * 2 + 1]
                outL[i] = l0 + (block[index * 2] - l0) * frac
                outR[i] = r0 + (block[index * 2 + 1] - r0) * frac
            }
        } catch (t: Throwable) {
            java.util.Arrays.fill(outL, 0, numFrames, 0f); java.util.Arrays.fill(outR, 0, numFrames, 0f)
            if (closed.compareAndSet(false, true)) println("FM1Sim: stopped after a failure while rendering: $t")
        } finally {
            runCatching { context.leave() }
        }
    }

    /** The simulator as a node in Field's audio graph. */
    val node: StereoRenderable by lazy { FM1SimNode(this) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { context.close(true) }
    }

    override fun toString(): String = synthPrettyObject(this, "sampleRate" to SAMPLE_RATE, "closed" to closed.get())
}

/** [FM1Sim] in the audio graph; a [StereoNode] so two readers in one block don't advance it twice. */
class FM1SimNode(val sim: FM1Sim) : StereoNode() {
    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        sim.render(outL, outR, numFrames, ctx.sampleRate)
    }
}
