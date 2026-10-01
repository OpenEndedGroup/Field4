package afield.server

import afield.logger.lprintln
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// The documentation revision pass, as a page: /docs-pass.html
//
// Everything a pass does — make a worktree, drain the notes queue with one agent per page, show
// the handoffs and the diff, rebuild the docs, commit, land on main, remove the worktree — is
// `tools/docs_notes.py pass …`, and works the same from a terminal. This file only runs those
// commands for the page: one long job at a time, its complete output kept on disk, the last part
// of it in memory for the page to poll.
//
// Every route here is loopback + same-site and nothing else: a browser on this machine, on one of
// Field's own pages. That is the boundary drawn in DOCS_IMPROVEMENT_AGENT.md ("this machine's own
// user"), and it is what stops the two real attacks — another device on the network, and a web
// page open in your browser. Unlike `POST /api/docs-improvement` there is no feature switch and no
// token: both were dropped here on 2026-09-30, because the token scrolls out of the terminal it is
// printed in, and the page is used from inside Field itself. What that gives up is the
// backstop for a *future* change to the CORS or same-site code; the checks themselves are the
// ones `DocsAnnotationRouteSecurityTest` pins.
//
// Both annotation hatches are forced shut for these routes (see [docsPassPolicy]). With only two
// checks left, `AFIELD_DOCS_ANNOTATIONS_ALLOW_REMOTE` — set for taking notes from a tablet — must
// not turn out to let the tablet start agents, commit, and move main.
// ─────────────────────────────────────────────────────────────────────────────

/** One request from the page. Only the fields its [action] uses are read, and all of them are checked. */
@Serializable
data class DocsPassAction(
    val action: String,
    val jobs: Int = 4,
    val agent: String = "claude",
    val tags: List<String> = emptyList(),
    val pages: List<String> = emptyList(),
    val mode: String = "never",
    val path: String = "",
    val id: String = "",
    val state: String = "done",
    val text: String = "",
    val abandon: Boolean = false,
    /** A handoff batch: `decisions`, `navigation`, `captures`, `site`, `code` or `elsewhere`. */
    val batch: String = "",
)

@Serializable
data class DocsPassJob(
    val name: String = "",
    val running: Boolean = false,
    val startedAtEpochMs: Long = 0,
    val finishedAtEpochMs: Long = 0,
    val exitCode: Int? = null,
    val logTail: String = "",
    val logFile: String = "",
)

/** [policy] with both annotation hatches shut: loopback and same-site, whatever the environment says. */
internal fun docsPassPolicy(policy: DocsAccessPolicy): DocsAccessPolicy =
    policy.copy(allowRemoteAnnotations = false, allowCrossSiteAnnotations = false)

/**
 * What `GET /api/docs-improvement/pass` answers. Classes rather than `buildJsonObject`, whose
 * `put("…")` calls read as route declarations to `ServerSurfaceLedgerTest`'s discovery.
 */
@Serializable
data class DocsPassStatusResponse(
    val workingDirectory: String,
    val job: DocsPassJob,
    /** `docs_notes.py pass status` as it printed it, or `null` when it failed. */
    val status: JsonElement,
    val statusError: String? = null,
)

/** A quick action's outcome: the command's exit code and everything it printed. */
@Serializable
data class DocsPassQuickResult(val exitCode: Int, val output: String)

/** Defaults included, so the page sees `running: false` and `exitCode: null` rather than nothing. */
private val docsPassJson = Json { encodeDefaults = true; explicitNulls = true }

/** The actions that answer at once, and may run while a job does (stopping one, most of all). */
private val quickDocsPassActions = setOf("stop", "stop-now", "discard", "close-handoff", "cancel-job", "group-handoffs", "set-batch")

/** The batches `tools/docs_notes.py` knows, in the order they are worked through. */
private val docsPassBatches = setOf("decisions", "navigation", "captures", "site", "code", "elsewhere")

private val docsPassTag = Regex("[a-z][a-z0-9_-]{0,63}")
private val docsPassPage = Regex("[A-Za-z0-9_./-]{1,200}")
private val docsPassHandoffId = Regex("[0-9a-fA-F-]{8,36}")

/**
 * The `docs_notes.py` arguments for [request], or `null` when anything in it is not what the page
 * would send. Arguments go to the process as an argv, never through a shell, so this is about
 * refusing nonsense early rather than about quoting.
 */
internal fun docsPassArguments(request: DocsPassAction): List<String>? {
    fun safePath(value: String) = value.isNotBlank() && docsPassPage.matches(value) && value.first().isLetterOrDigit() &&
        value.split('/').none { it == ".." || it.isEmpty() }
    return when (request.action) {
        "create" -> listOf("pass", "create")
        "revise" -> {
            if (request.jobs !in 1..12 || request.agent !in setOf("claude", "codex")) return null
            val tags = request.tags.map { it.trim().removePrefix("@").lowercase() }.filter { it.isNotEmpty() }
            if (tags.any { !docsPassTag.matches(it) } || request.pages.any { !safePath(it) }) return null
            listOf("pass", "revise", "--jobs", request.jobs.toString(), "--agent", request.agent) +
                tags.flatMap { listOf("--tag", it) } + request.pages.flatMap { listOf("--page", it) }
        }
        "stop" -> listOf("pass", "stop")
        "stop-now" -> listOf("pass", "stop", "--now")
        "rebuild" -> if (request.mode in setOf("never", "incremental", "strict")) listOf("pass", "rebuild", "--mode", request.mode) else null
        "commit" -> listOf("pass", "commit")
        "land" -> listOf("pass", "land")
        "finish" -> listOf("pass", "finish") + if (request.abandon) listOf("--abandon") else emptyList()
        // `--` before the positional values, so a text that begins with a dash stays text.
        "discard" -> if (safePath(request.path)) listOf("pass", "discard", "--", request.path) else null
        "close-handoff" -> {
            val text = request.text.trim()
            if (!docsPassHandoffId.matches(request.id) || request.state !in setOf("done", "taken", "dropped")) return null
            if (text.length > 2000 || text.any { it.code < 0x20 && it != '\n' }) return null
            if (request.state == "done" && text.isEmpty()) return null
            listOf("handoff-close", "--state", request.state, "--", request.id, text)
        }
        "cancel-job" -> emptyList()
        "group-handoffs" -> listOf("handoff-group")
        "set-batch" -> if (docsPassHandoffId.matches(request.id) && request.batch in docsPassBatches) {
            listOf("handoff-set-batch", "--", request.id, request.batch)
        } else null
        "start-session" -> if (request.batch in docsPassBatches) listOf("handoff-session", request.batch) else null
        "end-session" -> if (request.batch in docsPassBatches) {
            listOf("handoff-session-end", request.batch) + if (request.abandon) listOf("--abandon") else emptyList()
        } else null
        else -> null
    }
}

/**
 * Runs `docs_notes.py` for the page. [start] runs one long job at a time; [query] runs a short
 * command and returns its output, for status, diffs and the quick actions.
 */
private object DocsPassJobs {
    private val lock = Any()
    private var job = DocsPassJob()
    private var process: Process? = null
    private val tail = StringBuilder()

    fun snapshot(): DocsPassJob = synchronized(lock) { job.copy(logTail = tail.toString()) }

    fun start(name: String, arguments: List<String>, directory: Path): DocsPassJob = synchronized(lock) {
        if (job.running) return job.copy(logTail = tail.toString())
        val logDirectory = directory.resolve(".field-backdoor/docs-pass/jobs").also { Files.createDirectories(it) }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val logFile = logDirectory.resolve("$stamp-$name.log")
        val command = docsNotesCommand(directory) + arguments
        lprintln("docs-annotations", "Documentation pass job '$name' in $directory: ${command.joinToString(" ")}")
        tail.setLength(0)
        job = DocsPassJob(name = name, running = true, startedAtEpochMs = System.currentTimeMillis(), logFile = logFile.toString())
        val started = try {
            ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start()
        } catch (error: Exception) {
            append("could not start: $error\n")
            job = job.copy(running = false, finishedAtEpochMs = System.currentTimeMillis(), exitCode = 127)
            return job.copy(logTail = tail.toString())
        }
        process = started
        thread(name = "docs-pass-$name", isDaemon = true) {
            // The complete output goes to the file; only the tail is kept in memory for the page.
            Files.newBufferedWriter(logFile, StandardCharsets.UTF_8).use { file ->
                file.write("$ ${command.joinToString(" ")}\n")
                started.inputStream.bufferedReader().forEachLine { line ->
                    file.write(line); file.write("\n"); file.flush()
                    synchronized(lock) { append(line + "\n") }
                }
            }
            val code = started.waitFor()
            synchronized(lock) {
                job = job.copy(running = false, finishedAtEpochMs = System.currentTimeMillis(), exitCode = code)
                process = null
            }
        }
        job.copy(logTail = tail.toString())
    }

    /** Terminates the job's whole process tree: `docs_notes.py` and anything it started. */
    fun cancel(): Boolean = synchronized(lock) {
        val running = process ?: return false
        running.descendants().forEach { it.destroy() }
        running.destroy()
        append("\n[docs-pass] cancelled from the page\n")
        true
    }

    fun query(arguments: List<String>, directory: Path, timeoutSeconds: Long = 60): Pair<Int, String> {
        val process = ProcessBuilder(docsNotesCommand(directory) + arguments).directory(directory.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = StringBuilder()
        val reader = thread(isDaemon = true) { output.append(process.inputStream.bufferedReader().readText()) }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return 124 to "timed out after ${timeoutSeconds}s"
        }
        reader.join(2000)
        return process.exitValue() to output.toString()
    }

    private fun append(text: String) {
        tail.append(text)
        if (tail.length > 200_000) tail.delete(0, tail.length - 150_000)
    }

    private fun docsNotesCommand(directory: Path): List<String> =
        docsNotesPython() + listOf("tools/docs_notes.py", "--store", docsAnnotationStorePath().toString(), "--repo", directory.toString())
}

/** The open pass's worktree, read from the state file `docs_notes.py pass create` writes. */
private fun docsPassWorktree(checkout: Path): Path? = runCatching {
    val state = Json.parseToJsonElement(Files.readString(checkout.resolve(".field-backdoor/docs-pass/current.json")))
    (state as kotlinx.serialization.json.JsonObject)["worktree"]?.let { (it as JsonPrimitive).content }?.let { Path.of(it) }
}.getOrNull()?.takeIf { Files.isDirectory(it) }

private fun parsedOrNull(output: String): JsonElement =
    runCatching { Json.parseToJsonElement(output) }.getOrElse { JsonNull }

/**
 * The pass's routes, every one of them loopback + same-site with the annotation hatches shut:
 * the reads show source text and the checkout's path, and the write starts agents and moves main.
 */
internal fun Route.installDocsPassRoutes(policy: DocsAccessPolicy) {
    get("/api/docs-improvement/pass") {
        if (call.refuseUnlessSameSiteDocsClient(docsPassPolicy(policy))) return@get
        val directory = docsImprovementCheckout()
        val (code, output) = if (directory == null) 1 to "" else
            withContext(Dispatchers.IO) { DocsPassJobs.query(listOf("pass", "status"), directory) }
        val body = DocsPassStatusResponse(
            workingDirectory = directory?.toString() ?: "",
            job = DocsPassJobs.snapshot(),
            status = if (code == 0) parsedOrNull(output) else JsonNull,
            statusError = if (code == 0) null else output.ifBlank { "docs_notes.py pass status exited $code" },
        )
        call.respondText(docsPassJson.encodeToString(DocsPassStatusResponse.serializer(), body), ContentType.Application.Json)
    }
    get("/api/docs-improvement/pass/diff") {
        if (call.refuseUnlessSameSiteDocsClient(docsPassPolicy(policy))) return@get
        val directory = docsImprovementCheckout() ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, "no checkout")
        val path = call.request.queryParameters["path"].orEmpty()
        if (path.isNotEmpty() && docsPassArguments(DocsPassAction("discard", path = path)) == null) {
            return@get call.respond(HttpStatusCode.BadRequest, "bad path")
        }
        val (code, output) = withContext(Dispatchers.IO) {
            DocsPassJobs.query(listOf("pass", "diff") + if (path.isEmpty()) emptyList() else listOf("--path", path), directory)
        }
        if (code != 0) return@get call.respond(HttpStatusCode.Conflict, output.ifBlank { "no pass" })
        call.respondText(output, ContentType.Application.Json)
    }
    get("/api/docs-improvement/pass/handoff") {
        if (call.refuseUnlessSameSiteDocsClient(docsPassPolicy(policy))) return@get
        val directory = docsImprovementCheckout() ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, "no checkout")
        val id = call.request.queryParameters["id"].orEmpty()
        if (!docsPassHandoffId.matches(id)) return@get call.respond(HttpStatusCode.BadRequest, "bad id")
        val (code, output) = withContext(Dispatchers.IO) { DocsPassJobs.query(listOf("pass", "handoff-text", id), directory) }
        if (code != 0) return@get call.respond(HttpStatusCode.NotFound, output.ifBlank { "no such handoff" })
        call.respondText(output, ContentType.Application.Json)
    }
    // The pass worktree's own built site, so a rebuilt page can be read before it is committed.
    // Files only, through the same traversal checks as the /field-docs/ mirror, and no annotation
    // panel: notes belong on the page as it is, not on a draft.
    route("/docs-pass-site") {
        get("{...}") {
            if (call.refuseUnlessSameSiteDocsClient(docsPassPolicy(policy))) return@get
            val worktree = docsImprovementCheckout()?.let(::docsPassWorktree)
                ?: return@get call.respond(HttpStatusCode.NotFound, "No revision pass is open.")
            val site = worktree.resolve("public/field4").toAbsolutePath().normalize()
            val relative = call.request.path().removePrefix("/docs-pass-site")
            val file = docsMirrorFile(site, "/field-docs$relative")
                ?: return@get call.respond(HttpStatusCode.NotFound, "Not in the pass's build: rebuild the docs, or check the path.")
            val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(file) }
            call.respondBytes(bytes, ContentType.defaultForFilePath(file.fileName.toString()))
        }
    }
    post("/api/docs-improvement/pass") {
        if (call.refuseUnlessSameSiteDocsClient(docsPassPolicy(policy))) return@post
        val directory = docsImprovementCheckout()
            ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, "The checkout is not usable; set $DOCS_IMPROVEMENT_DIRECTORY_ENV.")
        val request = call.receive<DocsPassAction>()
        val arguments = docsPassArguments(request) ?: return@post call.respond(HttpStatusCode.BadRequest, "not a valid ${request.action} request")
        if (request.action == "cancel-job") {
            return@post call.respondText(
                docsPassJson.encodeToString(DocsPassQuickResult.serializer(), DocsPassQuickResult(if (DocsPassJobs.cancel()) 0 else 1, "")),
                ContentType.Application.Json,
            )
        }
        if (request.action in quickDocsPassActions) {
            if (request.action == "discard" && DocsPassJobs.snapshot().running) {
                return@post call.respond(HttpStatusCode.Conflict, "a job is running; discard once it has finished")
            }
            val (code, output) = withContext(Dispatchers.IO) { DocsPassJobs.query(arguments, directory) }
            return@post call.respondText(
                docsPassJson.encodeToString(DocsPassQuickResult.serializer(), DocsPassQuickResult(code, output)),
                ContentType.Application.Json,
                if (code == 0) HttpStatusCode.OK else HttpStatusCode.Conflict,
            )
        }
        val job = DocsPassJobs.start(request.action, arguments, directory)
        if (job.name != request.action || !job.running && job.exitCode == null) {
            return@post call.respond(HttpStatusCode.Conflict, "'${job.name}' is still running")
        }
        call.respondText(docsPassJson.encodeToString(DocsPassJob.serializer(), job), ContentType.Application.Json)
    }
}
