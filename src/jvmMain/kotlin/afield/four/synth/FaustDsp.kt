@file:afield.CompletionCategory("Synthesis")
package afield.four.synth

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * One compiled Faust program, instantiated and ready to make sound.
 *
 * A [FaustProgram] is bytes; this is the running thing. It holds its own WebAssembly instance in its
 * own GraalVM context, and [render] pulls audio out of it a block at a time — which is exactly the
 * shape [StereoRenderable] wants, so a Faust program drops into Field's audio graph next to an
 * oscillator with no adaptation.
 *
 * ## It is fast, and the cost is the boundary rather than the DSP
 *
 * Measured for a sawtooth-through-a-filter voice at 48kHz, where a 128-frame block has 2667µs to
 * render in:
 *
 *  * `compute` on its own: **7.6µs** (353× realtime). Generated Faust is not the bottleneck.
 *  * [render], end to end through this class: **60.4µs** (44× realtime).
 *
 * The gap is entirely the JVM↔WebAssembly boundary, and shrinking it was three changes, each measured
 * by `synth.faust.smoke`: copying the output a sample at a time cost 148µs a block; one bulk
 * [Value.readBuffer] per channel brought that to 94µs; resolving the JS entry points once at
 * construction rather than per block brought it to 60µs. What is left is dominated by the per-block
 * [Context.enter], which is where to look next if it ever matters.
 *
 * The compiler, by contrast, *is* slow — see [FaustEngine], which keeps it behind a separate context
 * for exactly that reason.
 *
 * ## The context is single-threaded, so parameters are queued
 *
 * A GraalVM [Context] cannot be entered by two threads at once, and [render] runs on the audio
 * thread. So [setParam] does not touch WebAssembly at all: it enqueues, and [render] drains the queue
 * before computing. That is also the right audio-engineering answer — no lock on the audio thread,
 * and no parameter changing halfway through a block.
 *
 * A queue rather than a last-write-wins map, because a `button` is momentary: collapsing a
 * press and its release into one value would silently swallow a note.
 */
class FaustDsp internal constructor(
    val program: FaustProgram,
    val sampleRate: Int
) : Closeable {

    companion object {
        /**
         * Largest block this will render in one `compute` call.
         *
         * Scratch buffers are reserved inside the DSP's WebAssembly memory once, at construction, and
         * WebAssembly memory cannot be grown without invalidating every existing view — so the size
         * is fixed up front and [render] loops for anything larger.
         */
        const val MAX_FRAMES: Int = 4096
    }

    private val closed = AtomicBoolean(false)
    private val pendingParams = ConcurrentLinkedQueue<ParamWrite>()

    private data class ParamWrite(val index: Int, val value: Float)

    private val context: Context = Context.newBuilder("js", "wasm")
        .engine(FaustEngine.sharedEngine)
        .allowExperimentalOptions(true)
        .allowHostAccess(HostAccess.ALL)
        .allowHostClassLookup { true }
        .allowAllAccess(true)
        .allowPolyglotAccess(PolyglotAccess.ALL)
        .option("js.webassembly", "true")
        .option("wasm.UseUnsafeMemory", "true")
        .build()

    private val bindings: Value

    /** Channel counts as the compiled module reports them, not as the JSON claimed. */
    val numInputs: Int
    val numOutputs: Int

    /**
     * The most recent value of every read-only control (`hbargraph`/`vbargraph`), by control index.
     *
     * Sampled during [render] because reading it needs the context, and published here so a UI thread
     * can show a meter without ever entering WebAssembly. Never null, may be stale by a block.
     */
    private val meterSnapshot = HashMap<Int, Float>()
    private val meterIndices: IntArray = program.ui.meters.map { it.index }.filter { it >= 0 }.toIntArray()

    // The DSP's WebAssembly memory, as a buffer the host can read in bulk. Null when the runtime does
    // not support buffer interop, in which case render falls back to the JS copy.
    private var fastBuffer: Value? = null
    private var outBase: Long = 0
    private var inBase: Long = 0

    // The JS entry points, resolved once at construction rather than per audio block.
    private val fnCompute: Value
    private val fnRender: Value
    private val fnWriteInput: Value
    private val fnSetParam: Value
    private val fnGetParam: Value

    // Reused across blocks: one channel of bytes, decoded through a FloatBuffer view so the
    // little-endian conversion is native rather than a Kotlin loop.
    private val scratch = ByteArray(MAX_FRAMES * 4)
    private val scratchFloats = java.nio.ByteBuffer.wrap(scratch)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .asFloatBuffer()

    init {
        context.enter()
        try {
            context.eval(source(FAUST_DSP_SHIM))
            context.getBindings("js").putMember("__dspWasmBytes", program.wasm)
            bindings = context.getBindings("js")

            val setup = context.eval(
                source("__faustDspSetup($sampleRate, ${program.ui.inputs}, ${program.ui.outputs}, $MAX_FRAMES)")
            )
            val error = setup.getMember("error")
            if (!error.isNull) {
                error("Faust: could not instantiate '${program.name}': ${error.asString()}")
            }
            numInputs = setup.getMember("inputs").asInt()
            numOutputs = setup.getMember("outputs").asInt()

            // Resolved once. A getMember per block is a hash lookup and a Value allocation on the
            // audio thread, for a function that never changes.
            fnCompute = bindings.getMember("__faustDspCompute")
            fnRender = bindings.getMember("__faustDspRender")
            fnWriteInput = bindings.getMember("__faustDspWriteInput")
            fnSetParam = bindings.getMember("__faustDspSetParam")
            fnGetParam = bindings.getMember("__faustDspGetParam")

            val offsets = context.eval(source("__faustDspOffsets()"))
            outBase = offsets.getMember("outBase").asLong()
            inBase = offsets.getMember("inBase").asLong()

            // Prefer one bulk read per channel over one interop call per sample. Measured: the
            // per-sample path costs ~148us for a 128-frame block (18x realtime), against ~7.6us for
            // `compute` alone — i.e. almost all of it was the copy, not the DSP. Probed rather than
            // assumed, so a runtime without buffer interop still works through the JS fallback.
            val buffer = context.eval(source("__faustDspBuffer()"))
            fastBuffer = runCatching {
                if (!buffer.hasBufferElements()) return@runCatching null
                buffer.readBuffer(outBase, scratch, 0, 4)
                buffer
            }.getOrNull()

            // Faust initialises every control to its declared default, but Field may already hold a
            // value for it from a previous run of the box; callers apply those through setParam.
            if (numOutputs == 0) {
                error("Faust: '${program.name}' has no audio outputs — `process` must produce at least one signal")
            }
        } catch (t: Throwable) {
            runCatching { context.leave() }
            runCatching { context.close(true) }
            throw t
        }
        runCatching { context.leave() }
    }

    /**
     * Queue a parameter change. Safe from any thread; applied at the start of the next [render].
     *
     * @param index the control's [FaustControl.index]
     */
    fun setParam(index: Int, value: Float) {
        if (index < 0 || closed.get()) return
        pendingParams.add(ParamWrite(index, value))
    }

    /** Queue a change by control, honouring its declared range. */
    fun setParam(control: FaustControl, value: Double) {
        setParam(control.index, value.coerceIn(control.min, control.max).toFloat())
    }

    /** The last sampled value of a meter control, or 0 if it has not been rendered yet. */
    fun meterValue(index: Int): Float = synchronized(meterSnapshot) { meterSnapshot[index] ?: 0f }

    /** Every meter's last sampled value, by control index. */
    fun meterValues(): Map<Int, Float> = synchronized(meterSnapshot) { HashMap(meterSnapshot) }

    /**
     * Render `numFrames` of audio.
     *
     * `inL`/`inR` are the DSP's input when it declares any; a program with no inputs ignores them. A
     * mono program is copied to both output channels, which is what a person means by "play it".
     */
    fun render(
        outL: FloatArray,
        outR: FloatArray,
        numFrames: Int,
        inL: FloatArray? = null,
        inR: FloatArray? = null
    ) {
        if (closed.get()) return
        context.enter()
        try {
            drainParams()

            val buffer = fastBuffer
            var done = 0
            while (done < numFrames) {
                val count = min(MAX_FRAMES, numFrames - done)
                if (numInputs > 0) {
                    writeInput(buffer, inL, inR, done, count)
                }
                if (buffer != null) {
                    fnCompute.execute(count)
                    readChannel(buffer, 0, outL, done, count)
                    if (numOutputs > 1) {
                        readChannel(buffer, 1, outR, done, count)
                    } else {
                        // Mono to both channels: "play it" means hear it in both ears.
                        System.arraycopy(outL, done, outR, done, count)
                    }
                } else {
                    fnRender.execute(outL, outR, done, count)
                }
                done += count
            }

            if (meterIndices.isNotEmpty()) sampleMeters()
        } catch (t: Throwable) {
            // A failing DSP must not take the audio thread down with it: fill silence and stop.
            java.util.Arrays.fill(outL, 0, numFrames, 0f)
            java.util.Arrays.fill(outR, 0, numFrames, 0f)
            if (closed.compareAndSet(false, true)) {
                println("Faust: '${program.name}' failed while rendering and was stopped: $t")
            }
        } finally {
            runCatching { context.leave() }
        }
    }

    /**
     * Copy one output channel out of WebAssembly memory. Caller has entered the context.
     *
     * One `readBuffer` for the whole channel, then a `FloatBuffer` view to convert — so a block costs
     * two interop calls rather than two per sample.
     */
    private fun readChannel(buffer: Value, channel: Int, out: FloatArray, offset: Int, count: Int) {
        val byteOffset = outBase + channel.toLong() * MAX_FRAMES * 4L
        buffer.readBuffer(byteOffset, scratch, 0, count * 4)
        scratchFloats.position(0)
        scratchFloats.get(out, offset, count)
    }

    /**
     * Copy input channels into WebAssembly memory. Caller has entered the context.
     *
     * There is no bulk buffer *write* in the polyglot API, so this is per-sample either way; going
     * through `writeBufferFloat` at least avoids the extra hop through a JS function.
     */
    private fun writeInput(buffer: Value?, inL: FloatArray?, inR: FloatArray?, offset: Int, count: Int) {
        if (buffer == null) {
            fnWriteInput.execute(inL ?: EMPTY, inR ?: EMPTY, offset, count)
            return
        }
        val order = java.nio.ByteOrder.LITTLE_ENDIAN
        for (i in 0 until count) {
            val l = inL?.getOrElse(offset + i) { 0f } ?: 0f
            buffer.writeBufferFloat(order, inBase + i.toLong() * 4L, l)
        }
        if (numInputs > 1) {
            val base = inBase + MAX_FRAMES.toLong() * 4L
            for (i in 0 until count) {
                val r = inR?.getOrElse(offset + i) { 0f } ?: 0f
                buffer.writeBufferFloat(order, base + i.toLong() * 4L, r)
            }
        }
    }

    /** Caller has entered the context. */
    private fun drainParams() {
        var applied = 0
        while (applied < MAX_PARAM_WRITES_PER_BLOCK) {
            val write = pendingParams.poll() ?: break
            fnSetParam.execute(write.index, write.value)
            applied++
        }
    }

    /** Caller has entered the context. */
    private fun sampleMeters() {
                synchronized(meterSnapshot) {
            for (index in meterIndices) {
                meterSnapshot[index] = fnGetParam.execute(index).asFloat()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { context.close(true) }
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "program" to program.name,
        "sampleRate" to sampleRate,
        "inputs" to numInputs,
        "outputs" to numOutputs,
        "closed" to closed.get()
    )

    private fun source(code: String): Source =
        Source.newBuilder("js", code, "faust-dsp.js").buildLiteral()
}

private val EMPTY = FloatArray(0)
private const val MAX_PARAM_WRITES_PER_BLOCK = 512

/**
 * A [FaustDsp] as a node in Field's audio graph.
 *
 * Extends [StereoNode] rather than implementing [StereoRenderable] directly because a Faust program
 * is stateful: reusing one twice in a block must not advance its filters twice.
 */
class FaustNode(
    val dsp: FaustDsp,
    private val input: StereoRenderable? = null
) : StereoNode() {

    private var inL: FloatArray = FloatArray(0)
    private var inR: FloatArray = FloatArray(0)

    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (dsp.numInputs > 0 && input != null) {
            if (inL.size < numFrames) {
                inL = FloatArray(numFrames)
                inR = FloatArray(numFrames)
            }
            Buf.clear(inL, 0, numFrames)
            Buf.clear(inR, 0, numFrames)
            input.render(inL, inR, numFrames, ctx)
            dsp.render(outL, outR, numFrames, inL, inR)
        } else {
            dsp.render(outL, outR, numFrames)
        }
    }
}

// The Faust WebAssembly ABI, driven from JS because that is what the measurement was taken through
// and because GraalWasm's own import wiring cannot supply these math functions as conveniently.
//
// The module exports `compute(dsp, count, inputsPtr, outputsPtr)` where the pointer arguments are
// addresses of *arrays of pointers*, one per channel, and `dsp` is 0 because the factory was built
// with internal memory (the DSP struct sits at offset 0 of the module's own memory).
private const val FAUST_DSP_SHIM: String = """
var __dspInstance = null, __dspExports = null, __dspMemory = null;
var __dspOutPtrArray = 0, __dspInPtrArray = 0;
var __dspOutF32 = null, __dspInF32 = null;
var __dspOutBase = 0, __dspInBase = 0, __dspMaxFrames = 0;
var __dspInputs = 0, __dspOutputs = 0;

// Faust's wasm backend imports scalar math from `env`. Which of these a given program needs depends
// on the program, so the whole table is supplied; unused entries cost nothing.
function __faustDspEnv() {
  var rem = function (a, b) { return a - Math.round(a / b) * b; };
  var copysign = function (x, y) { return (y < 0) !== (x < 0) ? -x : x; };
  var env = {
    _abs: Math.abs, _acosf: Math.acos, _asinf: Math.asin, _atanf: Math.atan, _atan2f: Math.atan2,
    _ceilf: Math.ceil, _cosf: Math.cos, _expf: Math.exp, _exp2f: function (x) { return Math.pow(2, x); },
    _exp10f: function (x) { return Math.pow(10, x); }, _floorf: Math.floor,
    _fmodf: function (a, b) { return a % b; }, _logf: Math.log, _log2f: Math.log2, _log10f: Math.log10,
    _max_f: Math.max, _min_f: Math.min, _powf: Math.pow, _remainderf: rem, _rintf: Math.round,
    _roundf: Math.round, _sinf: Math.sin, _sqrtf: Math.sqrt, _tanf: Math.tan, _truncf: Math.trunc,
    _acoshf: Math.acosh, _asinhf: Math.asinh, _atanhf: Math.atanh,
    _coshf: Math.cosh, _sinhf: Math.sinh, _tanhf: Math.tanh,
    _isnanf: function (x) { return isNaN(x) ? 1 : 0; },
    _isinff: function (x) { return !isFinite(x) && !isNaN(x) ? 1 : 0; },
    _copysignf: copysign
  };
  // The double-precision spellings, for a factory compiled with -double.
  var single = Object.keys(env);
  for (var i = 0; i < single.length; i++) {
    var name = single[i];
    if (name.charAt(name.length - 1) === "f") env[name.substring(0, name.length - 1)] = env[name];
  }
  env._max_ = Math.max;
  env._min_ = Math.min;
  env.table = new WebAssembly.Table({ initial: 0, element: "anyfunc" });
  return env;
}

function __faustDspSetup(sampleRate, declaredInputs, declaredOutputs, maxFrames) {
  try {
    var bytes = new Uint8Array(Java.from(__dspWasmBytes));
    var module = new WebAssembly.Module(bytes);
    __dspInstance = new WebAssembly.Instance(module, { env: __faustDspEnv() });
    __dspExports = __dspInstance.exports;
    __dspMemory = __dspExports.memory;

    __dspInputs = __dspExports.getNumInputs ? __dspExports.getNumInputs(0) : declaredInputs;
    __dspOutputs = __dspExports.getNumOutputs ? __dspExports.getNumOutputs(0) : declaredOutputs;
    __dspMaxFrames = maxFrames;

    // Reserve scratch above whatever the DSP itself occupies. `memory.grow` detaches every existing
    // view, so it happens here, once, before any view is made.
    var channels = Math.max(1, Math.max(__dspInputs, __dspOutputs));
    var ptrBytes = 2 * channels * 4;
    var audioBytes = 2 * channels * maxFrames * 4;
    var base = ((__dspMemory.buffer.byteLength - (ptrBytes + audioBytes + 64)) | 0);
    if (base < 0) {
      var extra = Math.ceil((ptrBytes + audioBytes + 65536) / 65536);
      __dspMemory.grow(extra);
    }
    // Place scratch in the last whole pages of memory, well clear of the DSP struct and any
    // delay lines Faust allocated for it.
    var total = __dspMemory.buffer.byteLength;
    var scratch = (total - (ptrBytes + audioBytes) - 16) & ~15;
    __dspOutPtrArray = scratch;
    __dspInPtrArray = scratch + channels * 4;
    __dspOutBase = (__dspInPtrArray + channels * 4 + 15) & ~15;
    __dspInBase = __dspOutBase + channels * maxFrames * 4;

    var ptrs = new Int32Array(__dspMemory.buffer, __dspOutPtrArray, 2 * channels);
    for (var c = 0; c < channels; c++) {
      ptrs[c] = __dspOutBase + c * maxFrames * 4;
      ptrs[channels + c] = __dspInBase + c * maxFrames * 4;
    }
    __dspOutF32 = new Float32Array(__dspMemory.buffer, __dspOutBase, channels * maxFrames);
    __dspInF32 = new Float32Array(__dspMemory.buffer, __dspInBase, channels * maxFrames);

    __dspExports.init(0, sampleRate);
    return { error: null, inputs: __dspInputs, outputs: __dspOutputs };
  } catch (e) {
    return { error: String(e) + (e && e.stack ? " | " + e.stack : ""), inputs: 0, outputs: 0 };
  }
}

function __faustDspSetParam(index, value) {
  __dspExports.setParamValue(0, index, value);
}

function __faustDspGetParam(index) {
  return __dspExports.getParamValue(0, index);
}

function __faustDspWriteInput(inL, inR, offset, count) {
  var stride = __dspMaxFrames;
  var haveR = __dspInputs > 1 && inR && inR.length > 0;
  for (var i = 0; i < count; i++) {
    var l = inL && inL.length > offset + i ? inL[offset + i] : 0;
    __dspInF32[i] = l;
    if (haveR) __dspInF32[stride + i] = inR.length > offset + i ? inR[offset + i] : 0;
  }
}

// Just the compute call. The output copy is done host-side with one bulk buffer read per channel;
// see FaustDsp.render for why.
function __faustDspCompute(count) {
  __dspExports.compute(0, count, __dspInPtrArray, __dspOutPtrArray);
}

function __faustDspBuffer() { return __dspMemory.buffer; }

function __faustDspOffsets() {
  return { outBase: __dspOutBase, inBase: __dspInBase, maxFrames: __dspMaxFrames };
}

// The fallback copy, for a runtime where the memory does not expose buffer interop. Correct but
// roughly 20x slower than the bulk read, because every sample crosses the language boundary.
function __faustDspRender(outL, outR, offset, count) {
  __dspExports.compute(0, count, __dspInPtrArray, __dspOutPtrArray);
  var stride = __dspMaxFrames;
  if (__dspOutputs > 1) {
    for (var i = 0; i < count; i++) {
      outL[offset + i] = __dspOutF32[i];
      outR[offset + i] = __dspOutF32[stride + i];
    }
  } else {
    // Mono to both channels: "play it" means hear it in both ears.
    for (var i = 0; i < count; i++) {
      var v = __dspOutF32[i];
      outL[offset + i] = v;
      outR[offset + i] = v;
    }
  }
}
"""
