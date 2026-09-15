@file:afield.CompletionCategory("Synthesis")
package afield.four.synth

import org.graalvm.polyglot.*
import java.io.Closeable
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min

class DoughEngine(
    wasmPath: Path,
    private val sampleRate: Int = 44_100,
) : Closeable {

    companion object {
        const val BLOCK_SIZE = 128
        const val CHANNELS = 2

        const val MAX_VOICES = 32
        const val MAX_ORBITS = 1
        const val MAX_DELAY_TIME = 1
        const val MAX_EVENTS = 64
        const val MAX_PCM = (1024 * 1024 * 196) / 4

        // From wasm-objdump:
        // memory[0] pages: initial=3200 max=3200 shared <- env.memory
        const val MEMORY_PAGES = 3200
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

    fun evaluatePath(path: String) {
        evaluate(parsePath(path))
    }

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

    fun currentTimeSeconds(): Double {
        context.enter()
        return try {
            bindings.getMember("doughGetTime").execute().asDouble()
        } finally {
            context.leave()
        }
    }

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
    }

    private fun parsePath(path: String): Map<String, String> {
        val chunks = path
            .trim()
            .lines()
            .map { it.substringBefore("//") }
            .joinToString("")
            .split("/")
            .filter { it.isNotBlank() }

        require(chunks.size % 2 == 0) {
            "Expected key/value pairs in path: $path"
        }

        val out = linkedMapOf<String, String>()
        var i = 0
        while (i < chunks.size) {
            out[chunks[i].trim()] = chunks[i + 1].trim()
            i += 2
        }
        return out
    }

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

    override fun close() {
        context.close()
    }

    class JsHost {
        internal var engine: DoughEngine? = null

        fun jsPanic(message: String) {
            error("js_panic() in C: $message")
        }

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

  globalThis.doughFillFloatArray = function (out, ptr, length) {
    const view = new DataView(globalThis.__dough.memory.buffer);
    for (let i = 0; i < length; i++) {
      out[i] = view.getFloat32(ptr + i * 4, true);
    }
  };
})();
"""

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

      fun evaluatePath(path: String) {
        pendingPathCommands.add(path)
      }

      fun evaluate(event: Map<String, Any?>) {
        pendingEventCommands.add(event)
      }

      override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        drainPendingCommands()

        var rendered = 0
        while (rendered < numFrames) {
          dough.renderBlock(blockL, blockR)
          val count = min(DoughEngine.BLOCK_SIZE, numFrames - rendered)
          System.arraycopy(blockL, 0, outL, rendered, count)
          System.arraycopy(blockR, 0, outR, rendered, count)
          rendered += count
        }
      }

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
