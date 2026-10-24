package afield.math.temporal

import afield.runtime.lifecycle.RunScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Mistral's pretrained Timestral forecaster behind the [TemporalSession] interface, so a box can
 * continue a signal from its recent history alone, with no recording to remember and no training
 * step.
 *
 * The model runs in its own Python process rather than inside Field, started through `uv` from
 * the pinned dependencies of `python/fieldtemporal/timestral_worker.py`, so PyTorch never loads
 * into the JVM or into the Python-box environment. `propose` sends the request's history across
 * as float32 and returns the median as its proposal with a quantile envelope around it. Those
 * quantiles are per-step marginals, describing the uncertainty at each step rather than a set of
 * whole futures, which is why this session offers no sampled alternatives.
 *
 * Nothing downloads until `install` is called, and that call blocks on the download. `status`
 * starts nothing and answers beforehand whether a session could start without the network, as a
 * [ProviderStatus] carrying both the cause and the fix when it could not, so a box can ask before
 * it builds anything. Afterwards a worker honours `AFIELD_OFFLINE=1`. A session
 * created inside a box run belongs to that run, and its worker exits when the box stops or
 * re-runs.
 *
 * Pair it with [RecedingHorizon] to step a forecast from a frame loop without ever waiting on
 * one, or with [ForecastJudge] to have it rate an [Improviser]'s proposals rather than produce
 * samples itself. Tests: `TimestralSessionTest`, which needs the downloaded checkpoint and so
 * runs only with `AFIELD_TIMESTRAL_TEST=1`.
 */

/** Where an optional provider stands, with the reason when it is not usable. */
sealed class ProviderStatus {
    data class Installed(val detail: String) : ProviderStatus()
    data class Missing(val cause: String, val fix: String) : ProviderStatus()
    data class Failed(val cause: String) : ProviderStatus()
}

/**
 * Mistral's Timestral forecaster (Apache-2.0 code, Modified-MIT weights) as a [TemporalSession],
 * running zero-shot with no fine-tuning.
 *
 * The model runs in a separate Python process, `python/fieldtemporal/timestral_worker.py`,
 * started through `uv run --script` in an environment uv builds from the script's own pinned
 * dependencies, so Torch never loads into Field or into the Python-box venv. Nothing downloads
 * until [install] is called; after that the worker honours `AFIELD_OFFLINE=1`.
 *
 * Each proposal sends the request's history as context (`(channels, time)` float32) and returns
 * the median as the proposal and all 21 quantiles as the envelope. Quantiles are per-sample
 * marginals: they describe uncertainty at each step, and sampling them independently would
 * invent a dependence structure the model never stated, so this session does not sample.
 *
 * [clipNonnegative] is off by default (the library's default is on): a control signal that
 * happens to stay positive should not be clamped at zero. [symmetricAveraging] (on) averages
 * with the forecast of the negated series and doubles the cost.
 *
 * Created inside a box run, the session belongs to that run and its worker exits when the box
 * stops or re-runs.
 */
class TimestralSession(
    val model: String = "mistralai/Timestral-28M",
    val device: String = "cpu",
    val symmetricAveraging: Boolean = true,
    val clipNonnegative: Boolean = false,
    val threads: Int = 0,
) : TemporalSession {
    override val capabilities = TemporalCapabilities(quantileForecast = true, futureCovariates = true, maskedHistory = true)

    private val process: Process
    private val toWorker: DataOutputStream
    private val fromWorker: DataInputStream
    private val io = Executors.newSingleThreadExecutor { Thread(it, "timestral-worker-io").apply { isDaemon = true } }
    private val ids = AtomicLong()
    @Volatile private var closed = false

    /** What the worker reported at start: model, versions, device, load time. */
    val info: JsonObject

    init {
        val cmd = workerCommand(model, device, threads)
        process = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.appendTo(logFile())).start()
        toWorker = DataOutputStream(process.outputStream.buffered())
        fromWorker = DataInputStream(process.inputStream.buffered())
        info = try { exchange(buildJsonObject { put("op", "hello") }, emptyList()).first["info"]!!.jsonObject }
        catch (e: Throwable) { process.destroyForcibly(); throw IllegalStateException("Timestral worker failed to start (${status(model)}); log: ${logFile()}", e) }
        RunScope.current()?.own(this)
    }

    /** Quantile levels of every envelope, lowest first. */
    val quantileLevels: DoubleArray = info["quantileLevels"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()

    override fun propose(request: ContinuationRequest): CompletableFuture<Continuation> {
        val f = CompletableFuture<Continuation>()
        io.execute {
            if (closed) { f.cancel(false); return@execute }
            try {
                val h = request.history
                val ctx = Array(h.width) { c -> FloatArray(h.length) { h.values[c][it].toFloat() } }
                val arrays = mutableListOf<Pair<String, Array<FloatArray>>>("context" to ctx)
                request.known?.let { k -> arrays.add("known" to Array(k.size) { c -> FloatArray(k[c].size) { k[c][it].toFloat() } }) }
                val t0 = System.nanoTime()
                val (hdr, out) = exchange(buildJsonObject {
                    put("op", "forecast"); put("horizon", request.horizon); put("generation", request.revision)
                    put("symmetricAveraging", symmetricAveraging); put("clipNonnegative", clipNonnegative)
                }, arrays)
                val q = out.getValue("quantiles") // (V, H, Q)
                val med = out.getValue("median") // (V, H)
                val (v, hz, nq) = q.shape
                val quantiles = Array(v) { c -> Array(hz) { t -> DoubleArray(nq) { k -> q.data[(c * hz + t) * nq + k].toDouble() } } }
                val proposal = Array(v) { c -> DoubleArray(hz) { t -> med.data[c * hz + t].toDouble() } }
                f.complete(Continuation(request.revision, proposal, quantiles, quantileLevels, mapOf(
                    "modelSeconds" to hdr["modelSeconds"]?.jsonPrimitive?.double,
                    "roundTripSeconds" to (System.nanoTime() - t0) / 1e9,
                    "model" to model,
                )))
            } catch (e: Throwable) { f.completeExceptionally(e) }
        }
        return f
    }

    private class Arr(val shape: List<Int>, val data: FloatArray)

    private fun exchange(header: JsonObject, arrays: List<Pair<String, Array<FloatArray>>>): Pair<JsonObject, Map<String, Arr>> {
        val id = ids.incrementAndGet()
        val full = JsonObject(header + mapOf(
            "id" to JsonPrimitive(id),
            "arrays" to buildJsonArray { for ((name, a) in arrays) add(buildJsonObject { put("name", name); put("dtype", "f4"); put("shape", JsonArray(listOf(JsonPrimitive(a.size), JsonPrimitive(a.firstOrNull()?.size ?: 0)))) }) },
        ))
        val raw = full.toString().toByteArray(Charsets.UTF_8)
        toWorker.writeInt(raw.size); toWorker.write(raw)
        for ((_, a) in arrays) {
            val bb = ByteBuffer.allocate(a.sumOf { it.size } * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (row in a) for (x in row) bb.putFloat(x)
            toWorker.write(bb.array())
        }
        toWorker.flush()
        val len = try { fromWorker.readInt() } catch (e: java.io.EOFException) { error("Timestral worker exited (code ${runCatching { process.exitValue() }.getOrNull()}); log: ${logFile()}") }
        val reply = Json.parseToJsonElement(String(ByteArray(len).also { fromWorker.readFully(it) }, Charsets.UTF_8)).jsonObject
        val out = LinkedHashMap<String, Arr>()
        for (spec in reply["arrays"]?.jsonArray.orEmpty()) {
            val s = spec.jsonObject
            val shape = s["shape"]!!.jsonArray.map { it.jsonPrimitive.int }
            val n = shape.fold(1) { a, b -> a * b }
            val bytes = ByteArray(n * 4).also { fromWorker.readFully(it) }
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            out[s["name"]!!.jsonPrimitive.content] = Arr(shape, FloatArray(n).also { fb.get(it) })
        }
        check(reply["id"]?.jsonPrimitive?.content == id.toString()) { "worker replied to ${reply["id"]}, expected $id" }
        if (reply["ok"]?.jsonPrimitive?.content != "true") error("Timestral worker: ${reply["error"]?.jsonPrimitive?.content}")
        return reply to out
    }

    override fun close() {
        if (closed) return
        closed = true
        io.execute { runCatching { exchange(buildJsonObject { put("op", "close") }, emptyList()) } }
        io.shutdown()
        if (!io.awaitTermination(2, TimeUnit.SECONDS)) io.shutdownNow()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    override fun toString() = "TimestralSession($model on $device, loaded in ${"%.1f".format(info["loadSeconds"]?.jsonPrimitive?.double ?: Double.NaN)} s)"

    companion object {
        /** The worker script, found above the working directory (a Field checkout). */
        fun workerScript(): File? {
            var at: File? = File(System.getProperty("user.dir")).absoluteFile
            while (at != null) {
                val f = File(at, "python/fieldtemporal/timestral_worker.py")
                if (f.isFile) return f
                at = at.parentFile
            }
            return null
        }

        /** `uv`, looked for by path as well as on `PATH` (a JVM launched from a GUI rarely has it). */
        fun uv(): String? {
            System.getenv("AFIELD_UV")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            val searched = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map { Paths.get(it, "uv") } +
                listOf(Paths.get("/opt/homebrew/bin/uv"), Paths.get("/usr/local/bin/uv"), Paths.get(System.getProperty("user.home"), ".local", "bin", "uv"))
            return searched.firstOrNull { Files.isExecutable(it) }?.toString()
        }

        private fun workerCommand(model: String, device: String, threads: Int): List<String> {
            val uv = uv() ?: error("Timestral needs `uv` (https://docs.astral.sh/uv/); set AFIELD_UV to its path")
            val script = workerScript() ?: error("python/fieldtemporal/timestral_worker.py not found above ${System.getProperty("user.dir")}")
            val offline = System.getenv("AFIELD_OFFLINE") == "1"
            return listOfNotNull(uv, "run", if (offline) "--offline" else null, "--script", script.path, "--model", model, "--device", device, "--threads", threads.toString())
        }

        private fun hubCache(model: String): File {
            val home = System.getenv("HF_HUB_CACHE") ?: (System.getenv("HF_HOME")?.let { "$it/hub" }) ?: "${System.getProperty("user.home")}/.cache/huggingface/hub"
            return File(home, "models--" + model.replace("/", "--"))
        }

        /** Log file the workers' stderr goes to. */
        fun logFile(): File = File(System.getProperty("java.io.tmpdir"), "field-timestral-worker.log")

        /**
         * Whether [model] can start without a download: `uv` and the worker script present, and the
         * checkpoint in the Hugging Face cache. Cheap; starts nothing.
         */
        fun status(model: String = "mistralai/Timestral-28M"): ProviderStatus {
            uv() ?: return ProviderStatus.Missing("no `uv` executable", "install uv, or set AFIELD_UV")
            val script = workerScript() ?: return ProviderStatus.Missing("worker script not found", "run Field from its checkout")
            val snaps = File(hubCache(model), "snapshots").listFiles().orEmpty().filter { File(it, "model.safetensors").exists() }
            if (snaps.isEmpty()) return ProviderStatus.Missing("checkpoint $model not downloaded", "TimestralSession.install(\"$model\")")
            return ProviderStatus.Installed("$model (snapshot ${snaps.first().name.take(10)}), worker ${script.name}")
        }

        /**
         * Builds the worker environment and downloads [model] (5M: 21 MB, 28M: 111 MB, 123M: 490 MB,
         * 209M: 837 MB). Blocks until done; returns what the worker reported (versions, load time).
         */
        fun install(model: String = "mistralai/Timestral-28M"): String {
            val uv = uv() ?: error("Timestral needs `uv`; set AFIELD_UV to its path")
            val script = workerScript() ?: error("python/fieldtemporal/timestral_worker.py not found")
            val p = ProcessBuilder(uv, "run", "--script", script.path, "--install", "--model", model).redirectError(ProcessBuilder.Redirect.appendTo(logFile())).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "installing $model failed (exit ${p.exitValue()}); see ${logFile()}" }
            return out.trim()
        }
    }
}
