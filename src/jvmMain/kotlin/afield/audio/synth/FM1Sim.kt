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
 * fm1.preset(0)          // "NEON KEYS"
 * fm1.key(0, true)       // hold the lowest key
 * fm1.node
 * ```
 *
 * Returning `fm1.node` from a box plays it. It is the simulator, not the hardware: the device runs a
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
    }

    private val closed = AtomicBoolean(false)
    private val pending = ConcurrentLinkedQueue<(Value) -> Unit>()

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
    private fun queue(call: (Value) -> Unit) {
        if (!closed.get()) pending.add(call)
    }

    private fun call(name: String, vararg args: Any) = queue { it.getMember(name).execute(*args) }

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
            val c = pending.poll() ?: break
            c(exports)
        }
        val ptr = fnRender.execute().asLong()
        memory.readBuffer(ptr, scratch, 0, scratch.size)
        scratchFloats.position(0)
        scratchFloats.get(block, 0, block.size)
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
