@file:afield.CompletionCategory("Machine learning")

package afield.onnx

import afield.logger.lprintln
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.CoreMLFlags
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * ONNX models, running inside Field.
 *
 * [ONNX Runtime](https://onnxruntime.ai) reaches the JVM through Microsoft's own JNI artifact —
 * `com.microsoft.onnxruntime:onnxruntime`, already on the classpath because
 * [afield.ai.SemanticRetrievalService] embeds text. This object is the part that makes a *live-coded*
 * model practical rather than merely possible, and it is one idea:
 *
 * > **A session is cached in a JVM singleton, so re-running a box does not rebuild it.**
 *
 * That matters more than it sounds. Session construction is the expensive half of ONNX — hundreds of
 * milliseconds to several seconds, because the graph is parsed, fused and specialised for the
 * execution provider. Frame-time inference is the cheap half. A box that says `depthAnything()` and
 * gets re-evaluated on every keystroke must not pay session construction on every keystroke, and a
 * recurrent model (RVM, Silero) must not lose its hidden state either.
 *
 * Field recompiles the *box*, not the application. [sessions] lives in an `object` loaded by the
 * application class loader, so the map outlives every recompilation of the code that reads it — the
 * same discipline [afield.four.synth.FaustEngine] uses for compiled DSP. Key a session by something
 * stable (its [SessionSpec], i.e. file plus provider plus thread counts) and hot-reload becomes free.
 * Build an [OrtSession] yourself in box code and every keystroke costs a rebuild.
 *
 * ## What was measured, on this machine
 *
 * Apple Silicon, 10 cores, Depth Anything V2 Small at 518x518. These numbers are why the defaults
 * are what they are, and each one contradicts a plausible guess:
 *
 *  * **CoreML does not help this model.** The provider claims 43 of 848 nodes, across 19 partitions;
 *    the boundary crossings cost about what the accelerated nodes save (≈470ms vs ≈388ms for plain
 *    CPU — i.e. CoreML is *slower*). [OnnxProvider.AUTO] therefore resolves to CPU. CoreML is still
 *    worth requesting for transformer encoders, where the partition count is small.
 *  * **fp16 weights are slower than fp32 on CPU**, ≈462ms vs ≈388ms, because every operator upcasts.
 *    fp16's win is download size (50MB vs 99MB), not speed. Pick it to save disk, not time.
 *  * **Pinning intra-op threads costs throughput.** `setIntraOpNumThreads(4)` measured ≈457ms against
 *    ≈388ms for the runtime's own default. [SessionSpec.intraOpThreads] defaults to 0, meaning "let
 *    ORT decide". Set it only when you have measured that the render thread is actually starving.
 *
 * The general shape: nothing on a CPU execution provider is frame-rate. 388ms is 2.6fps. Inference
 * belongs on its own thread with the renderer reading the most recent finished result — see
 * [DepthAnythingWorker] — and resolution is a far bigger lever than any provider flag, because cost
 * is roughly quadratic in it.
 *
 * ## What is not here
 *
 * There is no `IoBinding` in the Java API — verified by inspecting the 1.27.0 jar, not inferred from
 * documentation. Outputs are therefore always copies, and zero-copy CUDA/Vulkan interop is not
 * reachable from Java today without dropping to Panama downcalls against the same loaded library.
 * That is fine for everything here: a 518x518 fp32 depth map is 1.07MB, and the copy is invisible
 * beside a 388ms inference.
 *
 * @see OnnxModel for the download-and-cache side
 * @see DepthAnything for a worked capability built on top
 */
object OnnxRuntime {

    /**
     * The process-global environment.
     *
     * `OrtEnvironment.getEnvironment()` is a singleton inside ORT itself; holding it here just avoids
     * the lookup and makes the lifetime explicit. It is deliberately never closed — closing it while
     * any session is alive crashes the process, and Field has no moment at which that is knowably
     * safe.
     */
    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private val sessions = ConcurrentHashMap<SessionSpec, LoadedSession>()

    /** How many sessions are currently cached; useful in tests to prove reuse actually happens. */
    val liveSessionCount: Int get() = sessions.size

    /**
     * Which execution provider to ask for.
     *
     * Requesting one is not getting one: every provider except [CPU] falls back to CPU if the native
     * library was built without it, and the fallback is reported through [LoadedSession.provider]
     * rather than thrown, because a slow model is better than a dead box.
     */
    enum class OnnxProvider {
        /** CPU today. See the measurement note on [OnnxRuntime] for why this is not CoreML. */
        AUTO,

        /** The always-present reference provider. */
        CPU,

        /** Apple's NN accelerator via the classic CoreML path. */
        COREML,

        /** CoreML asking for an ML Program, which supports more operators on recent macOS. */
        COREML_MLPROGRAM,

        /** NVIDIA, and only from the separate `onnxruntime_gpu` artifact on Linux/Windows x64. */
        CUDA
    }

    /**
     * Everything that changes the identity of a session, and therefore the cache key.
     *
     * Two specs that differ only in [intraOpThreads] are genuinely different sessions — the thread
     * pool is baked in at construction — so this is a `data class` and not just the model path.
     *
     * @param model an on-disk `.onnx` file; use [OnnxModel.ensureLocal] to get one
     * @param provider which execution provider to request
     * @param intraOpThreads threads within one operator; 0 leaves ORT's default alone (recommended)
     * @param interOpThreads threads across independent operators; 0 leaves ORT's default alone
     */
    data class SessionSpec(
        val model: Path,
        val provider: OnnxProvider = OnnxProvider.AUTO,
        val intraOpThreads: Int = 0,
        val interOpThreads: Int = 0
    )

    /**
     * A session plus the provider it actually got, as opposed to the one that was asked for.
     *
     * @param provider lowercase name of the provider in force, e.g. `"cpu"` or `"coreml"`
     */
    class LoadedSession internal constructor(
        val spec: SessionSpec,
        val session: OrtSession,
        val provider: String
    ) {
        /** Input names the graph declares, in declaration order. */
        val inputNames: List<String> get() = session.inputNames.toList()

        /** Output names the graph declares, in declaration order. */
        val outputNames: List<String> get() = session.outputNames.toList()

        override fun toString(): String = "LoadedSession(${spec.model.fileName}, $provider)"
    }

    /**
     * The cached session for [spec], building it on first request.
     *
     * Safe to call every frame and from box code that is re-evaluated constantly; that is the point.
     * Construction happens at most once per distinct spec, under
     * [ConcurrentHashMap.computeIfAbsent], so two threads racing on the same new spec produce one
     * session rather than two.
     */
    fun session(spec: SessionSpec): LoadedSession = sessions.computeIfAbsent(spec) { build(it) }

    /** Convenience for the common case: a model file and nothing else to say about it. */
    fun session(model: Path, provider: OnnxProvider = OnnxProvider.AUTO): LoadedSession =
        session(SessionSpec(model, provider))

    /**
     * Drop and close one cached session.
     *
     * The only correct reason to call this is that the file on disk changed, or that a model is
     * genuinely finished with and its memory is wanted back. Calling it because a box re-ran defeats
     * the entire purpose of this object.
     */
    fun evict(spec: SessionSpec): Boolean {
        val removed = sessions.remove(spec) ?: return false
        runCatching { removed.session.close() }
            .onFailure { lprintln("warning,onnx", "failed to close session for ${spec.model.fileName}: ${it.message}") }
        return true
    }

    /** Close every cached session. For shutdown and for tests that want a cold measurement. */
    fun closeAll() {
        sessions.keys.toList().forEach { evict(it) }
    }

    private fun build(spec: SessionSpec): LoadedSession {
        require(Files.isRegularFile(spec.model)) { "No ONNX model at ${spec.model}" }

        val started = System.nanoTime()
        val requested = spec.provider
        val (session, provider) = tryProvider(spec, requested) ?: run {
            // Every provider except CPU is optional in the native library; a missing one must not be
            // fatal, because the same box has to run on a laptop and a CUDA box.
            val fallback = tryProvider(spec, OnnxProvider.CPU)
                ?: error("Could not create any ONNX session for ${spec.model.fileName}")
            lprintln(
                "warning,onnx",
                "${requested.name.lowercase(Locale.ROOT)} execution provider unavailable for " +
                    "${spec.model.fileName}; using CPU"
            )
            fallback
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        lprintln("info,onnx", "loaded ${spec.model.fileName} on $provider in ${ms}ms")
        return LoadedSession(spec, session, provider)
    }

    private fun tryProvider(spec: SessionSpec, provider: OnnxProvider): Pair<OrtSession, String>? =
        runCatching {
            val options = OrtSession.SessionOptions()
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (spec.intraOpThreads > 0) options.setIntraOpNumThreads(spec.intraOpThreads)
            if (spec.interOpThreads > 0) options.setInterOpNumThreads(spec.interOpThreads)

            val name = when (provider) {
                OnnxProvider.AUTO, OnnxProvider.CPU -> "cpu"
                OnnxProvider.COREML -> {
                    options.addCoreML(); "coreml"
                }
                OnnxProvider.COREML_MLPROGRAM -> {
                    options.addCoreML(EnumSet.of(CoreMLFlags.CREATE_MLPROGRAM)); "coreml-mlprogram"
                }
                OnnxProvider.CUDA -> {
                    options.addCUDA(); "cuda"
                }
            }
            env.createSession(spec.model.toAbsolutePath().toString(), options) to name
        }.getOrNull()
}

/**
 * A model that can be fetched once and then found again.
 *
 * Weights are not in the repository — they are tens to hundreds of megabytes and separately licensed
 * — so a model is a URL plus somewhere to put it. [ensureLocal] is idempotent: the second call is a
 * file-exists check.
 *
 * The cache root is `~/.afield/onnx/models`, overridable with the `afield.onnx.cacheRoot` system
 * property or `AFIELD_ONNX_CACHE_ROOT` in the environment, matching the convention
 * [afield.ai.SemanticRetrievalService] already established for its own models.
 *
 * @param id stable short name, used in logs
 * @param url where to fetch the weights
 * @param fileName path under the cache root, `subdir/model.onnx` style
 * @param sha256 lowercase hex digest; verified after download when non-blank
 * @param note what this variant is for, in one line
 */
data class OnnxModel(
    val id: String,
    val url: String,
    val fileName: String,
    val sha256: String = "",
    val note: String = ""
) {
    /** Where this model lives once fetched, whether or not it has been. */
    val localPath: Path get() = cacheRoot.resolve(fileName).toAbsolutePath().normalize()

    /** True when the weights are already on disk, so [ensureLocal] will not touch the network. */
    val isPresent: Boolean get() = Files.isRegularFile(localPath)

    /**
     * The local file, downloading it if absent.
     *
     * Downloads land on a `.part` sibling and are moved into place atomically, so an interrupted
     * fetch cannot leave a truncated model that then fails to parse in a way that looks like a bug
     * in the model rather than in the download.
     *
     * @throws IllegalStateException on a non-2xx response, or on a [sha256] mismatch
     */
    fun ensureLocal(): Path {
        val target = localPath
        if (Files.isRegularFile(target)) return target

        Files.createDirectories(target.parent)
        val part = target.resolveSibling("${target.fileName}.part")
        lprintln("info,onnx", "fetching $id from $url")

        val response = httpClient.send(
            HttpRequest.newBuilder(URI.create(url)).GET().header("User-Agent", "Field4 onnx").build(),
            HttpResponse.BodyHandlers.ofFile(part)
        )
        if (response.statusCode() !in 200..299) {
            runCatching { Files.deleteIfExists(part) }
            error("Download failed for $url: HTTP ${response.statusCode()}")
        }
        if (sha256.isNotBlank()) {
            val actual = digestOf(part)
            if (!actual.equals(sha256, ignoreCase = true)) {
                runCatching { Files.deleteIfExists(part) }
                error("Checksum mismatch for $id: expected $sha256, got $actual")
            }
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        lprintln("info,onnx", "cached $id at $target (${Files.size(target) / 1_048_576}MB)")
        return target
    }

    /** The cached session for this model, fetching the weights first if needed. */
    fun session(provider: OnnxRuntime.OnnxProvider = OnnxRuntime.OnnxProvider.AUTO): OnnxRuntime.LoadedSession =
        OnnxRuntime.session(ensureLocal(), provider)

    companion object {
        /** Root of the on-disk model cache. */
        val cacheRoot: Path = Paths.get(
            System.getProperty("afield.onnx.cacheRoot")
                ?: System.getenv("AFIELD_ONNX_CACHE_ROOT")
                ?: Paths.get(System.getProperty("user.home"), ".afield", "onnx", "models").toString()
        ).toAbsolutePath().normalize()

        private val httpClient: HttpClient by lazy {
            HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build()
        }

        private fun digestOf(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { stream ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
