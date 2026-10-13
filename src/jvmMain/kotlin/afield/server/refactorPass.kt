package afield.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** CLI-backed private refactor review; the browser never supplies a shell command. */
@Serializable
internal data class RefactorPassAction(
    val action: String,
    val plan: String = "",
    val item: String = "",
    val state: String = "",
    val reason: String = "",
)

@Serializable
private data class RefactorPassResponse(val job: DocsPassJob, val data: JsonElement = JsonNull, val error: String = "")

internal val refactorPassBatches = setOf("prose", "display-text", "dynamic-names", "languages", "syntax", "generated", "source-layout")
private val refactorItemId = Regex("[0-9a-f]{24}")
private val refactorJson = Json { encodeDefaults = true }

internal fun refactorPassArguments(request: RefactorPassAction): List<String>? = when (request.action) {
    "create" -> if (request.plan.length in 1..4096 && request.plan.none { it.code < 32 } &&
        runCatching { Path.of(request.plan).isAbsolute }.getOrDefault(false)) listOf("create", "--plan", request.plan) else null
    "replan", "rehearse" -> listOf(request.action)
    "decide" -> if (refactorItemId.matches(request.item) && request.state in setOf("patch", "preserve", "adapter", "question", "open") &&
        request.reason.isNotBlank() && request.reason.length <= 8000 && request.reason.none { it.code < 32 && it != '\n' && it != '\t' })
        listOf("decide", "--item", request.item, "--state", request.state, "--reason", request.reason) else null
    else -> null
}

/** An explicit terminal kind uses the pass CLI's writer lease and its own non-landing brief. */
internal fun refactorTerminalPlan(launch: TerminalLaunch, checkout: Path, uv: String): Result<TerminalPlan> {
    if (launch.batch !in refactorPassBatches || launch.text.length > 4000 || launch.text.any { it.code < 32 && it != '\n' && it != '\t' })
        return Result.failure(IllegalArgumentException("invalid refactor batch or direction"))
    if (!Files.isRegularFile(checkout.resolve(".field-backdoor/refactor-pass/current.json")))
        return Result.failure(IllegalStateException("Import a refactor plan first"))
    return Result.success(TerminalPlan(refactorCommand(checkout, uv) +
        listOf("agent", "--batch", launch.batch, "--direction", launch.text), checkout, "Refactor: ${launch.batch}"))
}

private fun refactorCommand(checkout: Path, uv: String = terminalExecutable("uv")): List<String> =
    listOf(uv, "run", "--project", checkout.toString(), "--group", "backdoor", "python", "tools/refactor_pass.py", "--repo", checkout.toString())

private object RefactorPassJobs {
    private val monitor = Any()
    private var job = DocsPassJob()
    fun snapshot(): DocsPassJob = synchronized(monitor) { job }

    fun start(directory: Path, arguments: List<String>): DocsPassJob = synchronized(monitor) {
        check(!job.running) { "A refactor pass operation is already running" }
        val logDirectory = directory.resolve(".field-backdoor/refactor-pass/jobs")
        Files.createDirectories(logDirectory)
        val logFile = logDirectory.resolve("${System.currentTimeMillis()}-${arguments.first()}.log")
        val child = ProcessBuilder(refactorCommand(directory) + arguments).directory(directory.toFile()).redirectErrorStream(true).start()
        job = DocsPassJob(name = arguments.first(), running = true, startedAtEpochMs = System.currentTimeMillis(), logFile = logFile.toString())
        thread(name = "refactor-pass-${arguments.first()}", isDaemon = true) {
            try {
                Files.newBufferedWriter(logFile).use { file ->
                    child.inputStream.bufferedReader().forEachLine { line ->
                        file.write(line); file.newLine(); file.flush()
                        synchronized(monitor) { job = job.copy(logTail = (job.logTail + line + "\n").takeLast(24_000)) }
                    }
                }
                val exit = child.waitFor()
                synchronized(monitor) { job = job.copy(running = false, finishedAtEpochMs = System.currentTimeMillis(), exitCode = exit) }
            } catch (error: Exception) {
                child.destroy()
                synchronized(monitor) { job = job.copy(running = false, exitCode = 1, logTail = error.toString()) }
            }
        }
        job
    }

    fun query(directory: Path, arguments: List<String>): Pair<Int, String> {
        val process = ProcessBuilder(refactorCommand(directory) + arguments).directory(directory.toFile()).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = thread(isDaemon = true) { process.inputStream.bufferedReader().use { output.append(it.readText()) } }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroy() }; process.destroyForcibly()
            reader.join(2000)
            return 124 to "Refactor query timed out"
        }
        reader.join()
        return process.exitValue() to output.toString()
    }
}

/** Reads contain private workspace source, so they share the terminal's loopback/same-site policy. */
internal fun Route.installRefactorPassRoutes(policy: DocsAccessPolicy) {
    get("/api/refactor-pass") {
        if (call.refuseUnlessSameSiteDocsClient(docsPassPolicy(policy))) return@get
        val checkout = docsImprovementCheckout() ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, "No checkout")
        val item = call.request.queryParameters["item"]
        if (item != null && !refactorItemId.matches(item)) return@get call.respond(HttpStatusCode.BadRequest, "Invalid review item")
        val change = call.request.queryParameters["change"]
        if (change != null && !refactorItemId.matches(change)) return@get call.respond(HttpStatusCode.BadRequest, "Invalid change")
        val args = when {
            item != null -> listOf("item", "--item", item)
            change != null -> listOf("changes", "--change", change)
            call.request.queryParameters["changes"] != null -> listOf("changes")
            else -> listOf("status")
        }
        val (code, output) = withContext(Dispatchers.IO) { RefactorPassJobs.query(checkout, args) }
        val data = if (code == 0) runCatching { refactorJson.parseToJsonElement(output) }.getOrDefault(JsonNull) else JsonNull
        call.respondText(refactorJson.encodeToString(RefactorPassResponse.serializer(),
            RefactorPassResponse(RefactorPassJobs.snapshot(), data, if (code == 0) "" else output)), ContentType.Application.Json)
    }
    post("/api/refactor-pass") {
        if (call.refuseUnlessSameSiteDocsClient(docsPassPolicy(policy))) return@post
        val checkout = docsImprovementCheckout() ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, "No checkout")
        val arguments = refactorPassArguments(call.receive<RefactorPassAction>())
            ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid refactor request")
        if (arguments.first() == "decide") {
            val (code, output) = withContext(Dispatchers.IO) { RefactorPassJobs.query(checkout, arguments) }
            return@post call.respondText(output, ContentType.Application.Json,
                if (code == 0) HttpStatusCode.OK else HttpStatusCode.Conflict)
        }
        val result = runCatching { withContext(Dispatchers.IO) { RefactorPassJobs.start(checkout, arguments) } }
        result.onSuccess { call.respondText(refactorJson.encodeToString(DocsPassJob.serializer(), it), ContentType.Application.Json) }
            .onFailure { call.respond(HttpStatusCode.Conflict, it.message ?: "Could not start operation") }
    }
}
