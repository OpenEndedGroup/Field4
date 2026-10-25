@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

import org.graalvm.polyglot.*
import java.io.Closeable
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min

/**
 * dough itself, run as WebAssembly inside GraalVM, and the two wrappers that hand its output to
 * Field's audio engine.
 *
 * [DoughEngine] loads `dough.wasm` into a polyglot context and drives it through a small JavaScript
 * shim: events go in as slash-separated key/value paths, the module's `dsp()` is called one 128-frame
 * block at a time, and the samples are read back out of wasm memory. This is dough's own voice
 * allocator, event parser, filters and effects rather than a reimplementation of them. `DoughDsp.kt`
 * is the separate Kotlin port of a handful of dough's primitives, and the two paths never meet.
 *
 * The engine does not know where its own buffers are until the module tells it. `dough_init` calls
 * back into `js_init` once with a query-string-shaped mirror of its pointers, which is where
 * `event_input`, `output`, `frame`, `framebuffer`, `pcm` and `active_voices` all come from. A pointer
 * the module does not report is not an error: `currentFrame` and `activeVoices` answer null, and
 * `framebufferCopy` answers an empty array.
 *
 * Two properties shape everything built on this. A polyglot context holding `js` admits **one
 * thread**, so whichever thread constructs a [DoughEngine] is the only one that may call any of its
 * methods, `activeVoices` included; GraalVM refuses a second thread rather than serialising it. And
 * the shim imports nothing but the memory, `js_panic` and `js_init`, so the guest has no host clock
 * and no host randomness: the same events and the same number of `renderBlock` calls give the same
 * floats, run after run, with no tolerance needed to compare two runs.
 *
 * [DoughAudioSink] is the engine wrapped as a [StereoNode], with commands queued rather than applied
 * so that a caller never reaches the engine itself, and at most `maxCommandsPerRender` of them drained
 * per block. It renders in whole 128-frame blocks and copies only the frames it was asked for, so a
 * request that is not a multiple of 128 drops the tail of its last block, which is a gap in the sound
 * rather than a delay in it; [FieldAudioEngine]'s 512-frame default is a multiple of 128 and loses
 * nothing. [parkDough] registers a sink with that engine and returns a [ParkedDough] closing both.
 *
 * Read those two against the thread rule above: the sink builds its engine in its own constructor, on
 * whichever thread called it, and then renders it from the audio thread, and neither the sink nor
 * [parkDough] has a caller in the tree. The realtime path that does run is [PatSpatialRoom]'s, which
 * builds each engine inside the thread that will render it, one engine per spatial source, since an
 * engine is built with a single orbit and cannot keep simultaneous sources apart on its own.
 */

/**
 * dough's wasm module, loaded and ready to be told what to play.
 *
 * Construct it with a path to `dough.wasm` on the thread that will render it, feed it events with
 * `evaluatePath` or `evaluate`, and pull audio a block at a time with `renderBlock`. Nothing here
 * opens a device or starts a thread: a caller decides when a block is rendered, which is what lets
 * the same engine drive a realtime ring, an offline capture or a test.
 *
 * The ceilings come from the numbers passed to `dough_init` and are compiled in for the life of the
 * engine: [MAX_VOICES] simultaneous voices, an event queue of [MAX_EVENTS], and [MAX_ORBITS] orbits.
 * That last one is 1, so simultaneous sources cannot be kept apart on separate orbits within one
 * engine; [PatSpatialRoom] builds one engine per source instead.
 */
class DoughEngine(
    wasmPath: Path,
    private val sampleRate: Int = 44_100,
) : Closeable {

    companion object {
        /**
         * Frames one `dsp()` call produces, and so the number `renderBlock` writes.
         *
         * Compiled into the module rather than chosen here, which is why `renderBlock` requires
         * arrays at least this long and returns exactly this many frames every time.
         */
        const val BLOCK_SIZE = 128

        /** Channels the module writes, interleaved in its output buffer. */
        const val CHANNELS = 2

        /** Voices the module will sound at once. `activeVoices` counts against this ceiling. */
        const val MAX_VOICES = 32

        /**
         * Orbits the engine is built with.
         *
         * One, so simultaneous sources cannot be separated by orbit inside a single engine.
         */
        const val MAX_ORBITS = 1

        /** Delay-line size handed to `dough_init`, in the module's own units. */
        const val MAX_DELAY_TIME = 1

        /** Event-queue depth handed to `dough_init`. */
        const val MAX_EVENTS = 64

        /** Floats of sample memory reserved for loaded PCM, which is 196 MB of them. */
        const val MAX_PCM = (1024 * 1024 * 196) / 4

        /**
         * Pages of shared wasm memory to allocate for the module, read off the binary itself.
         *
         * `wasm-objdump` reports `memory[0] pages: initial=3200 max=3200 shared <- env.memory`, and
         * the host has to supply exactly that, since the module imports its memory rather than
         * defining it.
         */
        const val MEMORY_PAGES = 3200

        /**
         * Bytes from one voice to the next in the module's voice array, which starts at the `vs`
         * pointer `js_init` reports.
         *
         * Not exported by the module: measured from this `dough.wasm` (the `voice` struct in
         * `dough.c`, compiled for wasm32), and pinned by `DoughVoiceLayoutTest`, which fails if a new
         * build of the module moves it. [VOICE_CHANNELS_OFFSET] and [VOICE_GROUP_OFFSET] likewise.
         */
        const val VOICE_STRIDE = 11_592

        /**
         * Offset within a voice of `ch[0]`, followed by `ch[1]`: the voice's most recent output frame,
         * after its own gain, filters and pan and before the shared delay, reverb and the module's
         * final halving.
         */
        const val VOICE_CHANNELS_OFFSET = 11_120

        /** Offset within a voice of its event's `group`, which is -1 for an event that set none. */
        const val VOICE_GROUP_OFFSET = 11_136
    }

    private val host = JsHost()

    private val context: Context =
        Context.newBuilder("js", "wasm")
            .allowExperimentalOptions(true)
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup { true }
            .allowAllAccess(true)
            .allowPolyglotAccess(PolyglotAccess.ALL)
            .option("js.webassembly", "true")
            .option("wasm.Threads", "true")
            .option("wasm.UseUnsafeMemory", "true")
            .option("wasm.DirectByteBufferMemoryAccess", "true")
            .build()

    private val bindings: Value

    private var eventInputPointer = -1
    private var outputPointer = -1
    private var doughtimePointer = -1
    private var framebufferPointer = -1
    private var framebufferLength = -1
    private var framePointer = -1
    private var pcmPointer = -1
    private var activeVoicesPointer = -1
    private var voicesPointer = -1

    init {
        host.engine = this

        context.enter()
        try {
            context.eval(Source.newBuilder("js", JS_SHIM, "dough-shim.js").buildLiteral())

            bindings = context.getBindings("js")
            bindings.putMember("__doughHost", host)

            val initOk = bindings.getMember("makeDoughFromPath").execute(
                wasmPath.toAbsolutePath().toString(),
                sampleRate,
                MAX_VOICES,
                MAX_ORBITS,
                MAX_DELAY_TIME,
                MAX_PCM,
                MAX_EVENTS,
                MEMORY_PAGES
            )

            if (!initOk.asBoolean()) {
                error("makeDoughFromPath returned false")
            }

            require(eventInputPointer >= 0) { "js_init did not populate event_input" }
            require(outputPointer >= 0) { "js_init did not populate output" }
//            require(doughtimePointer >= 0) { "js_init did not populate doughtime" }
        } finally {
            context.leave()
        }
    }

    /**
     * Send one event written as a dough path, as in `/dough/play/s/saw/n/60/gain/.3`.
     *
     * The string is read as key/value pairs separated by `/`, so it has to have an even number of
     * non-empty segments or the call fails rather than half-applying. Line breaks are allowed and
     * anything after `//` on a line is dropped, which makes a multi-line path in a box readable. The
     * key names are the module's, not Field's: `s` and `sound` are the same key, as are `n` and
     * `note`, and `pan/0` is hard left rather than centre.
     */
    fun evaluatePath(path: String) {
        evaluate(parsePath(path))
    }

    /**
     * Send one event written as a dough path, with [extra] keys added to it or overriding it.
     *
     * The way to tag a path someone else wrote, as `DoughLive` adds a `group` to every box's sound
     * without touching the string that box passed.
     */
    fun evaluatePath(path: String, extra: Map<String, Any?>) {
        evaluate(parsePath(path) + extra)
    }

    /**
     * Send one event as a map, for a caller that already has its keys and values apart.
     *
     * Values are written with `toString` and the pairs are encoded in the map's own iteration order,
     * so this and `evaluatePath` reach the module by the same route and understand the same keys.
     */
    fun evaluate(event: Map<String, Any?>) {
        val bytes = encodeEvent(event)

        context.enter()
        try {
            bindings.getMember("doughWriteBytes").execute(eventInputPointer, bytes)
            bindings.getMember("doughEvaluate").execute()
        } finally {
            context.leave()
        }
    }

    /**
     * Run the module for one block and de-interleave it into [outLeft] and [outRight].
     *
     * Always [BLOCK_SIZE] frames, whatever the caller wants; the arrays may be longer but the rest of
     * them is left alone. Calling this is what advances the engine's clock, so a caller that renders
     * more blocks than it consumes moves dough's time forward past the audio anyone hears.
     */
    fun renderBlock(outLeft: FloatArray, outRight: FloatArray) {
        require(outLeft.size >= BLOCK_SIZE) { "outLeft must have at least $BLOCK_SIZE samples" }
        require(outRight.size >= BLOCK_SIZE) { "outRight must have at least $BLOCK_SIZE samples" }

        context.enter()
        try {
            bindings.getMember("doughDsp").execute()
            bindings.getMember("doughFillOutput").execute(
                outLeft,
                outRight,
                outputPointer,
                BLOCK_SIZE,
                CHANNELS
            )
        } finally {
            context.leave()
        }
    }

    /**
     * The module's own clock, in seconds.
     *
     * It counts rendered blocks rather than wall time, so it is the position in the sound rather than
     * how long the engine has existed, and it does not move while nothing is rendering.
     */
    fun currentTimeSeconds(): Double {
        context.enter()
        return try {
            bindings.getMember("doughGetTime").execute().asDouble()
        } finally {
            context.leave()
        }
    }

    /**
     * The module's frame counter, or null if it never reported where that counter lives.
     *
     * Null is the normal answer for a build of `dough.wasm` that does not mirror `frame`, so treat it
     * as absent rather than as an error.
     */
    fun currentFrame(): Int? {
        if (framePointer < 0) return null
        context.enter()
        return try {
            bindings.getMember("doughGetInt32").execute(framePointer).asInt()
        } finally {
            context.leave()
        }
    }

    /**
     * How many of [MAX_VOICES] are sounding, or null if the engine did not report the pointer.
     *
     * The wasm module has always mirrored `active_voices`; reading it is how a caller can tell
     * polyphony from a single loud voice without analysing the audio.
     */
    fun activeVoices(): Int? {
        if (activeVoicesPointer < 0) return null
        context.enter()
        return try {
            bindings.getMember("doughGetInt32").execute(activeVoicesPointer).asInt()
        } finally {
            context.leave()
        }
    }

    /**
     * A copy of the module's visualisation framebuffer, or an empty array if it has none.
     *
     * Copied out of wasm memory on every call, so a caller that wants it per block should expect the
     * allocation. An empty result means the module did not mirror `framebuffer`, or mirrored it with
     * no length.
     */
    fun framebufferCopy(): FloatArray {
        if (framebufferPointer < 0 || framebufferLength <= 0) return FloatArray(0)

        val out = FloatArray(framebufferLength)
        context.enter()
        try {
            bindings.getMember("doughFillFloatArray").execute(out, framebufferPointer, framebufferLength)
        } finally {
            context.leave()
        }
        return out
    }

    /**
     * Every sounding voice's `group` and its last output frame, as the module left them after the
     * last [renderBlock].
     *
     * Writes `group, left, right` triples into [out] and returns how many voices it wrote: at most
     * [activeVoices], and at most `out.size / 3`. The frame is one sample per voice per block, not a
     * block's worth, so a level built from it is a running statistic over many blocks; `DoughLive`
     * keeps one per group. Returns 0 for a module that did not report where its voices are.
     */
    fun voiceFrames(out: FloatArray): Int {
        if (voicesPointer < 0 || activeVoicesPointer < 0) return 0
        context.enter()
        return try {
            val active = bindings.getMember("doughGetInt32").execute(activeVoicesPointer).asInt()
            val count = minOf(active, MAX_VOICES, out.size / 3).coerceAtLeast(0)
            if (count > 0) {
                bindings.getMember("doughReadVoices").execute(
                    out, voicesPointer, count, VOICE_STRIDE, VOICE_CHANNELS_OFFSET, VOICE_GROUP_OFFSET
                )
            }
            count
        } finally {
            context.leave()
        }
    }

    /** Where the module's voice array starts in wasm memory, or -1 if `js_init` did not say. */
    internal val voicesAddress: Int get() = voicesPointer

    /** One little-endian int32 of wasm memory. For layout probes; the address is not checked. */
    internal fun peekInt32(address: Int): Int {
        context.enter()
        return try {
            bindings.getMember("doughGetInt32").execute(address).asInt()
        } finally {
            context.leave()
        }
    }

    /** One little-endian float32 of wasm memory. For layout probes; the address is not checked. */
    internal fun peekFloat32(address: Int): Float {
        context.enter()
        return try {
            bindings.getMember("doughGetFloat32").execute(address).asFloat()
        } finally {
            context.leave()
        }
    }

    private fun parseMirror(msg: String) {
        println("js_init mirror: $msg")

        require(msg.startsWith("?")) {
            "js_init() expected pointer locations starting with '?', got: $msg"
        }

        val map = msg.removePrefix("?")
            .split("&")
            .filter { it.isNotBlank() }
            .associate { part ->
                val (k, v) = part.split("=", limit = 2)
                k to v.toInt()
            }

        eventInputPointer = map["event_input"] ?: -1
        outputPointer = map["output"] ?: -1
        doughtimePointer = map["doughtime"] ?: -1
        framebufferPointer = map["framebuffer"] ?: -1
        framebufferLength = map["framebuffer_length"] ?: -1
        framePointer = map["frame"] ?: -1
        pcmPointer = map["pcm"] ?: -1
        activeVoicesPointer = map["active_voices"] ?: -1
        voicesPointer = map["vs"] ?: -1
    }

    private fun parsePath(path: String): Map<String, String> = doughPathEvent(path)

    private fun encodeEvent(event: Map<String, Any?>): ByteArray {
        val s = buildString {
            var first = true
            for ((k, v) in event) {
                if (!first) append("/")
                append(k)
                append("/")
                append(v.toString())
                first = false
            }
            append('\u0000')
        }
        return s.encodeToByteArray()
    }

    /**
     * Close the polyglot context, releasing the module and the 200 MB of wasm memory it was given.
     *
     * Has to be called on the thread that constructed the engine, like everything else here, so an
     * engine owned by a render thread is closed in that thread's own teardown rather than by whoever
     * asked it to stop.
     */
    override fun close() {
        context.close()
    }

    /**
     * The two callbacks the wasm module imports, exposed to the shim as a host object.
     *
     * Not something to construct: each engine makes its own and attaches itself to it before the
     * module is instantiated, which is how `jsInit`'s pointer mirror finds its way back to a field.
     */
    class JsHost {
        internal var engine: DoughEngine? = null

        /** Raise whatever the module passed to `js_panic`. A dough panic is a Kotlin exception here. */
        fun jsPanic(message: String) {
            error("js_panic() in C: $message")
        }

        /**
         * Take the module's one-off pointer mirror, a string shaped `?event_input=…&output=…`.
         *
         * Every pointer the engine reads later comes from this call, so a mirror that does not start
         * with `?`, or that omits `event_input` or `output`, fails construction rather than leaving
         * the engine half-wired.
         */
        fun jsInit(message: String) {
            engine?.parseMirror(message)
                ?: error("DoughEngine host not attached")
        }
    }
}

private const val JS_SHIM = """
(function () {
  const Files = Java.type("java.nio.file.Files");
  const Paths = Java.type("java.nio.file.Paths");

  function toUint8(javaBytes) {
    const arr = Java.from(javaBytes);
    const out = new Uint8Array(arr.length);
    for (let i = 0; i < arr.length; i++) {
      out[i] = arr[i] & 0xFF;
    }
    return out;
  }

  function decodeCString(memory, ptr) {
    const u8 = new Uint8Array(memory.buffer);
    let end = ptr;
    while (end < u8.length && u8[end] !== 0) end++;

    let msg = "";
    for (let i = ptr; i < end; i++) {
      msg += String.fromCharCode(u8[i]);
    }
    return msg;
  }

  globalThis.makeDoughFromPath = function (
    wasmPath,
    sampleRate,
    maxVoices,
    maxOrbits,
    maxDelayTime,
    maxPcm,
    maxEvents,
    memoryPages
  ) {
    const host = globalThis.__doughHost;
    if (!host) {
      throw new Error("__doughHost is missing");
    }

    const javaBytes = Files.readAllBytes(Paths.get(wasmPath));
    const wasmBytes = toUint8(javaBytes);

    const memory = new WebAssembly.Memory({
      initial: memoryPages,
      maximum: memoryPages,
      shared: true,
    });

    const imports = {
      env: {
        memory,
        js_panic: function (ptr) {
          host.jsPanic(decodeCString(memory, ptr));
        },
        js_init: function (ptr) {
          host.jsInit(decodeCString(memory, ptr));
        },
      },
    };

    const module = new WebAssembly.Module(wasmBytes);
    const instance = new WebAssembly.Instance(module, imports);

    globalThis.__dough = {
      memory,
      exports: instance.exports,
    };

    instance.exports.dough_init(
      sampleRate,
      maxVoices,
      maxOrbits,
      maxDelayTime,
      maxPcm,
      maxEvents
    );

    return true;
  };

  globalThis.doughWriteBytes = function (ptr, javaBytes) {
    const src = toUint8(javaBytes);
    const dst = new Uint8Array(globalThis.__dough.memory.buffer, ptr, src.length);
    dst.set(src);
  };

  globalThis.doughEvaluate = function () {
    return globalThis.__dough.exports.evaluate();
  };

  globalThis.doughDsp = function () {
    return globalThis.__dough.exports.dsp();
  };

  globalThis.doughGetTime = function () {
    return globalThis.__dough.exports.get_time();
  };

  globalThis.doughGetInt32 = function (ptr) {
    return new DataView(globalThis.__dough.memory.buffer).getInt32(ptr, true);
  };

  globalThis.doughGetFloat32 = function (ptr) {
    return new DataView(globalThis.__dough.memory.buffer).getFloat32(ptr, true);
  };

  globalThis.doughFillOutput = function (outLeft, outRight, outputPtr, blockSize, channels) {
    const view = new DataView(globalThis.__dough.memory.buffer);
    for (let i = 0; i < blockSize; i++) {
      const base = outputPtr + i * channels * 4;
      outLeft[i] = view.getFloat32(base, true);
      outRight[i] = view.getFloat32(base + 4, true);
    }
  };

  globalThis.doughReadVoices = function (out, vs, count, stride, chOffset, groupOffset) {
    const view = new DataView(globalThis.__dough.memory.buffer);
    for (let v = 0; v < count; v++) {
      const base = vs + v * stride;
      out[v * 3] = view.getInt32(base + groupOffset, true);
      out[v * 3 + 1] = view.getFloat32(base + chOffset, true);
      out[v * 3 + 2] = view.getFloat32(base + chOffset + 4, true);
    }
  };

  globalThis.doughFillFloatArray = function (out, ptr, length) {
    const view = new DataView(globalThis.__dough.memory.buffer);
    for (let i = 0; i < length; i++) {
      out[i] = view.getFloat32(ptr + i * 4, true);
    }
  };
})();
"""

/**
 * Boot an engine, play one sawtooth note and print eight blocks of it. A hand-run smoke, not a test.
 *
 * Reads `dough.wasm` from a path relative to the working directory, so it only works when the process
 * was started from the repository root, and it prints rather than asserting.
 */
fun testDough() {
    DoughEngine(Paths.get("./src/jvmMain/resources/dough.wasm")).use { dough ->
        dough.evaluatePath("/dough/play/s/saw/n/60/gain/.3")

        val left = FloatArray(DoughEngine.BLOCK_SIZE)
        val right = FloatArray(DoughEngine.BLOCK_SIZE)

        repeat(8) {
            dough.renderBlock(left, right)
            println("t=${dough.currentTimeSeconds()} firstSampleL=${left[0]}")
        }
    }
}

    /**
     * A [DoughEngine] presented as a [StereoNode], with its commands queued instead of applied.
     *
     * `evaluatePath` and `evaluate` add to a concurrent queue and return; the queue is drained inside
     * `generateStereo`, at most [maxCommandsPerRender] commands per block, so the engine is only ever
     * spoken to from the thread that renders it. Path commands are drained before map commands, which
     * is the one place the two entry points are not interchangeable.
     *
     * The engine is built in this constructor, on whichever thread constructs the sink, and a
     * [DoughEngine] belongs to its constructing thread for life. A sink therefore has to be built on
     * the thread that will render it. Nothing in the tree constructs one; [PatSpatialRoom] is the
     * realtime path that runs, and it builds its engines inside its own render thread.
     *
     * Rendering is in whole [DoughEngine.BLOCK_SIZE] blocks, and the frames of a block a request does
     * not use are kept for the next one, so any buffer size is lossless.
     */
    class DoughAudioSink(
      wasmPath: Path,
      sampleRate: Int = 44_100,
      private val maxCommandsPerRender: Int = 256
    ) : StereoNode(), AutoCloseable {

      private val dough = DoughEngine(wasmPath = wasmPath, sampleRate = sampleRate)
      private val pendingPathCommands = ConcurrentLinkedQueue<String>()
      private val pendingEventCommands = ConcurrentLinkedQueue<Map<String, Any?>>()
      private val blockL = FloatArray(DoughEngine.BLOCK_SIZE)
      private val blockR = FloatArray(DoughEngine.BLOCK_SIZE)

      /** Queue a dough path for the next block. Callable from any thread, and it never blocks. */
      fun evaluatePath(path: String) {
        pendingPathCommands.add(path)
      }

      /** Queue an event map for the next block. Drained after every queued path command. */
      fun evaluate(event: Map<String, Any?>) {
        pendingEventCommands.add(event)
      }

      override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        drainPendingCommands()

        // Dough renders whole 128-frame blocks; what a request does not use is kept for the next
        // one, so a buffer size that is not a multiple of 128 loses nothing.
        var rendered = 0
        while (rendered < numFrames) {
          if (blockPos == DoughEngine.BLOCK_SIZE) {
            dough.renderBlock(blockL, blockR)
            blockPos = 0
          }
          val count = min(DoughEngine.BLOCK_SIZE - blockPos, numFrames - rendered)
          System.arraycopy(blockL, blockPos, outL, rendered, count)
          System.arraycopy(blockR, blockPos, outR, rendered, count)
          blockPos += count
          rendered += count
        }
      }

      /** How far into [blockL]/[blockR] the next request starts; a full block means render a new one. */
      private var blockPos = DoughEngine.BLOCK_SIZE

      private fun drainPendingCommands() {
        var consumed = 0
        while (consumed < maxCommandsPerRender) {
          val path = pendingPathCommands.poll()
          if (path != null) {
            dough.evaluatePath(path)
            consumed++
            continue
          }

          val event = pendingEventCommands.poll()
          if (event != null) {
            dough.evaluate(event)
            consumed++
            continue
          }

          break
        }
      }

      override fun close() {
        dough.close()
      }
    }

    /**
     * A running dough engine and its registration with [FieldAudioEngine], as one thing to close.
     *
     * Forwards `evaluatePath` and `evaluate` to the sink underneath, so a caller can keep playing
     * without holding both pieces. `close` unregisters the patch first and closes the engine second,
     * which is the order that matters: the mix stops asking for blocks before the module goes away.
     */
    class ParkedDough internal constructor(
      private val sink: DoughAudioSink,
      private val handle: AudioPatchHandle
    ) : AutoCloseable {

      fun evaluatePath(path: String) {
        sink.evaluatePath(path)
      }

      fun evaluate(event: Map<String, Any?>) {
        sink.evaluate(event)
      }

      override fun close() {
        handle.close()
        sink.close()
      }
    }

    /**
     * Build a [DoughAudioSink], register it with an audio engine, and hand back the pair.
     *
     * The sink is built at the engine's own `sampleRate`, since nothing between them resamples. The
     * default [wasmPath] is relative to the process working directory, so it resolves only when
     * Field was started from the repository root; pass an absolute path from anywhere else.
     *
     * Has no caller in the tree, and note where its engine is constructed: on the thread that calls
     * this, while the rendering happens on the audio thread. See [DoughAudioSink].
     */
    fun parkDough(
      wasmPath: Path = Paths.get("./src/jvmMain/resources/dough.wasm"),
      engine: FieldAudioEngine = audioEngine(),
      gain: Float = 1f,
      warmUpCycles: Int = 0,
      maxCommandsPerRender: Int = 256
    ): ParkedDough {
      val sink = DoughAudioSink(
        wasmPath = wasmPath,
        sampleRate = engine.sampleRate,
        maxCommandsPerRender = maxCommandsPerRender
      )
      val handle = engine.register(audio(sink, gain = gain, warmUpCycles = warmUpCycles))
      return ParkedDough(sink, handle)
    }
