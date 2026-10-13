package afield.server

import afield.logger.lprintln
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// A real terminal in the browser: /terminal.html (xterm.js) over the /pty/<id> WebSocket, with a
// pseudo-terminal (pty4j) on this side. Its first job is running an interactive agent ([fieldAgent]:
// `claude` or `codex`) on one
// handoff batch of the documentation revision pass, started from that batch on /docs-pass.html
// with one sentence of direction — so the agent works through the batch's handoffs and the human
// answers it in place, instead of copying a command into a terminal of their own.
//
// This hands out a process on this machine, so the boundary is the revision pass's and nothing
// looser: loopback and same-site, annotation hatches shut ([docsPassPolicy]), on the launch and
// list routes and on the WebSocket upgrade. And the client never names a command or a directory:
// it picks one of the [TerminalLaunch] kinds, and the server builds the argv and finds the
// directory. There is no "run this shell line".
//
// Protocol on /pty/<id>: server → client binary frames are terminal output (the scrollback first,
// on every connect, so a reload re-attaches); a text frame `{"type":"exit","code":N}` ends it.
// Client → server binary or text frames are keystrokes, except a text frame that is a JSON object
// with `"type":"resize"`, which carries `cols` and `rows`. Closing the page detaches; the process
// keeps running until it exits or is killed through POST /api/terminal/<id>/kill.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * What the page may start. [kind] is `claude` (in the checkout, [text] as an optional first
 * message), `handoff-batch` (in [batch]'s open session worktree, [text] as the human's direction),
 * or `land-watch` (`tools/land.py status --watch` in the checkout, for /land.html; [text] is ignored;
 * plain glyphs, since xterm.js has no Nerd Font).
 */
@Serializable
data class TerminalLaunch(val kind: String, val text: String = "", val batch: String = "", val cols: Int = 120, val rows: Int = 36)

@Serializable
data class TerminalInfo(
    val id: String,
    val kind: String,
    val title: String,
    val directory: String,
    val startedAtEpochMs: Long,
    val running: Boolean,
    val exitCode: Int? = null,
    /** The handoff batch a `handoff-batch` terminal works on, so the page can show it there. */
    val batch: String = "",
)

/** What a launch runs, and where. */
internal data class TerminalPlan(val argv: List<String>, val directory: Path, val title: String)

private val terminalJson = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true }

/**
 * The argv and directory for [launch], or a readable refusal. Pure, so the tests can pin it:
 * [sessionWorktree] answers "where is this batch's open session", and nothing else is consulted.
 */
internal fun terminalPlan(launch: TerminalLaunch, checkout: Path, agent: String, uv: String = "uv", sessionWorktree: (String) -> Path?): Result<TerminalPlan> {
    val text = launch.text.trim()
    if (text.length > 4000 || text.any { it.code < 0x20 && it != '\n' && it != '\t' }) {
        return Result.failure(IllegalArgumentException("the text must be at most 4000 characters, without control characters"))
    }
    return when (launch.kind) {
        // The kind keeps its old name on the wire; it starts whichever agent [fieldAgent] names.
        "claude" -> Result.success(TerminalPlan(listOf(agent) + if (text.isEmpty()) emptyList() else listOf(text), checkout, Path.of(agent).fileName.toString()))
        "handoff-batch" -> {
            if (launch.batch !in docsPassBatches) return Result.failure(IllegalArgumentException("unknown batch '${launch.batch}'"))
            val worktree = sessionWorktree(launch.batch)
                ?: return Result.failure(IllegalStateException("the ${launch.batch} batch has no open session; start one first"))
            val title = "${launch.batch}" + if (text.isEmpty()) "" else ": ${text.take(60)}"
            Result.success(TerminalPlan(listOf(agent, handoffBatchBrief(text)), worktree, title))
        }
        "land-watch" -> Result.success(TerminalPlan(listOf("/usr/bin/env", "FIELD_LAND_PLAIN=1", uv, "run", "python", "tools/land.py", "status", "--watch"), checkout, "landing queue"))
        else -> Result.failure(IllegalArgumentException("unknown terminal kind '${launch.kind}'"))
    }
}

/** The first message for a `handoff-batch` terminal: the session brief, then the human's sentence. */
internal fun handoffBatchBrief(direction: String): String {
    val said = if (direction.isEmpty()) "" else "\n\nThe human's direction for this batch:\n\n> ${direction.replace("\n", "\n> ")}"
    return """
        |Read HANDOFF_SESSION.md, then work through its handoffs in order.$said
        |
        |Do not stop between handoffs to ask for confirmation: the human is watching this terminal and
        |has said what they want. Close each handoff as the brief describes when it is done, and land
        |the branch through the queue as it says. Stop only on a real failure (a build error, a capture
        |showing Field's error marker, a lander bounce) or a decision the direction does not cover, and
        |then say exactly what it is.
    """.trimMargin()
}

/** The open session worktree for [batch], from the file `docs_notes.py handoff-session` writes. */
internal fun handoffSessionWorktree(checkout: Path, batch: String): Path? = runCatching {
    val sessions = Json.parseToJsonElement(Files.readString(checkout.resolve(".field-backdoor/docs-pass/sessions.json")))
    (sessions as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
        .lastOrNull { it["batch"]?.jsonPrimitive?.content == batch }
        ?.get("worktree")?.jsonPrimitive?.content?.let { Path.of(it) }
}.getOrNull()?.takeIf { Files.isDirectory(it) }

/**
 * The coding agent Field starts: `claude` or `codex`. `FIELD_AGENT` in the environment, else the first
 * word of `~/.afield/agent`, else `claude`; `tools/field_agent.py` reads the same two places, so the
 * landing queue and the docs revision pass agree with the terminals here.
 */
internal fun fieldAgent(env: Map<String, String> = System.getenv(), file: Path = Paths.get(System.getProperty("user.home"), ".afield", "agent")): String {
    val agents = setOf("claude", "codex")
    val fromFile = runCatching { Files.readString(file).trim().split(Regex("\\s+")).first() }.getOrDefault("")
    return listOf(env["FIELD_AGENT"].orEmpty(), fromFile).map { it.trim().lowercase() }.firstOrNull { it in agents } ?: "claude"
}

/** [name] on PATH or in the usual install places: a Field started from the Dock has a thin PATH. */
internal fun terminalExecutable(name: String): String {
    val home = System.getProperty("user.home")
    val candidates = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).filter { it.isNotBlank() }.map { Paths.get(it, name) } +
        listOf(Paths.get(home, ".local", "bin", name), Paths.get(home, ".claude", "local", name), Paths.get("/opt/homebrew/bin", name), Paths.get("/usr/local/bin", name))
    return candidates.firstOrNull { Files.isExecutable(it) }?.toString() ?: name
}

/** The child's environment: this JVM's, with a real TERM, a PATH that reaches the tools, and no nesting markers. */
internal fun terminalEnvironment(base: Map<String, String>): Map<String, String> {
    val home = System.getProperty("user.home")
    val env = base.toMutableMap()
    // A Field launched by an agent carries these, and `claude` refuses to start "inside" one.
    listOf("CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_SSE_PORT", "CLAUDE_CODE_CHILD_SESSION").forEach(env::remove)
    env["TERM"] = "xterm-256color"
    env["COLORTERM"] = "truecolor"
    env["TERM_PROGRAM"] = "field-terminal"
    env.putIfAbsent("LANG", "en_US.UTF-8")
    val extra = listOf("$home/.local/bin", "/opt/homebrew/bin", "/usr/local/bin")
    val path = env["PATH"].orEmpty().split(java.io.File.pathSeparator).filter { it.isNotBlank() }
    env["PATH"] = (path + extra.filter { it !in path }).joinToString(java.io.File.pathSeparator)
    return env
}

private class TerminalSession(val info: TerminalInfo, val process: PtyProcess) {
    private val lock = Any()
    private val scrollback = ByteArrayOutputStream()
    private val subscribers = mutableSetOf<Channel<ByteArray>>()
    @Volatile var exitCode: Int? = null

    /** Subscribes, returning the scrollback so far; the channel then gets everything after it. */
    fun attach(channel: Channel<ByteArray>): ByteArray = synchronized(lock) {
        if (exitCode == null) subscribers += channel else channel.close()
        scrollback.toByteArray()
    }

    fun detach(channel: Channel<ByteArray>) = synchronized(lock) { subscribers -= channel }

    fun publish(bytes: ByteArray) = synchronized(lock) {
        scrollback.write(bytes)
        if (scrollback.size() > SCROLLBACK_LIMIT) {
            val kept = scrollback.toByteArray().let { it.copyOfRange(it.size - SCROLLBACK_LIMIT / 2, it.size) }
            scrollback.reset(); scrollback.write(kept)
        }
        // A subscriber that cannot keep up is dropped rather than waited for; reconnecting replays the scrollback.
        subscribers.removeAll { channel ->
            val dropped = !channel.trySend(bytes).isSuccess
            if (dropped) channel.close()
            dropped
        }
    }

    fun finish(code: Int) = synchronized(lock) {
        exitCode = code
        subscribers.forEach { it.close() }
        subscribers.clear()
    }

    fun snapshot() = info.copy(running = exitCode == null, exitCode = exitCode)

    companion object { const val SCROLLBACK_LIMIT = 2 * 1024 * 1024 }
}

private object TerminalSessions {
    private const val MAX_RUNNING = 4
    private const val MAX_KEPT = 12
    private val sessions = LinkedHashMap<String, TerminalSession>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread { synchronized(this) { sessions.values.toList() }.forEach { kill(it) } })
    }

    @Synchronized fun get(id: String) = sessions[id]

    @Synchronized fun list() = sessions.values.map { it.snapshot() }

    fun start(launch: TerminalLaunch, plan: TerminalPlan): Result<TerminalInfo> {
        val command = plan.argv
        val directory = plan.directory
        synchronized(this) {
            if (sessions.values.count { it.exitCode == null } >= MAX_RUNNING) {
                return Result.failure(IllegalStateException("$MAX_RUNNING terminals are already running; close one first"))
            }
        }
        val process = try {
            PtyProcessBuilder(command.toTypedArray())
                .setDirectory(directory.toString())
                .setEnvironment(terminalEnvironment(System.getenv()))
                .setInitialColumns(launch.cols.coerceIn(20, 500))
                .setInitialRows(launch.rows.coerceIn(5, 200))
                .setConsole(false)
                .start()
        } catch (error: Exception) {
            return Result.failure(IllegalStateException("could not start ${command.first()}: $error"))
        }
        val id = UUID.randomUUID().toString()
        val info = TerminalInfo(id, launch.kind, plan.title, directory.toString(), System.currentTimeMillis(), true, batch = launch.batch)
        val session = TerminalSession(info, process)
        lprintln("server", "Terminal $id (${launch.kind}) in $directory: ${command.first()}")
        synchronized(this) {
            sessions[id] = session
            sessions.entries.filter { it.value.exitCode != null }.dropLast(MAX_KEPT).forEach { sessions.remove(it.key) }
        }
        thread(name = "terminal-$id", isDaemon = true) {
            val buffer = ByteArray(16 * 1024)
            val input = process.inputStream
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) session.publish(buffer.copyOf(n))
                }
            } catch (_: java.io.IOException) {
                // The pty closes under the reader when the process exits.
            }
            val code = process.waitFor()
            session.publish("\r\n[process exited with code $code]\r\n".toByteArray())
            session.finish(code)
            lprintln("server", "Terminal $id exited with $code")
        }
        return Result.success(session.snapshot())
    }

    fun kill(session: TerminalSession) {
        if (session.exitCode != null) return
        // pty4j's process has no ProcessHandle of its own (`toHandle()` throws), so look it up by pid.
        runCatching { ProcessHandle.of(session.process.pid()).ifPresent { it.descendants().forEach(ProcessHandle::destroy) } }
        session.process.destroy()
    }
}

private suspend fun DefaultWebSocketServerSession.refuseUnlessTerminalClient(policy: DocsAccessPolicy): Boolean {
    val pass = docsPassPolicy(policy)
    val refusal = refuseDocsAnnotationRequest(pass, call.request.local.remoteAddress)
        ?: refuseCrossSiteDocsRequest(pass, call.request.headers[SEC_FETCH_SITE_HEADER], call.request.headers[HttpHeaders.Origin], call.request.headers[HttpHeaders.Host])
        ?: return false
    lprintln("warning,server", "Refused a terminal WebSocket from ${call.request.local.remoteAddress} origin=${call.request.headers[HttpHeaders.Origin] ?: "<none>"}")
    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, refusal.reason.take(120)))
    return true
}

private suspend fun ApplicationCall.respondTerminal(info: TerminalInfo) =
    respondText(terminalJson.encodeToString(TerminalInfo.serializer(), info), ContentType.Application.Json)

/** The terminal's routes; every one of them is the revision pass's boundary, loopback + same-site. */
internal fun Route.installTerminalRoutes(policy: DocsAccessPolicy) {
    val pass = docsPassPolicy(policy)
    get("/api/terminal") {
        if (call.refuseUnlessSameSiteDocsClient(pass)) return@get
        call.respondText(terminalJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(TerminalInfo.serializer()), TerminalSessions.list()), ContentType.Application.Json)
    }
    post("/api/terminal") {
        if (call.refuseUnlessSameSiteDocsClient(pass)) return@post
        val checkout = docsImprovementCheckout()
            ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, "The checkout is not usable; set $DOCS_IMPROVEMENT_DIRECTORY_ENV.")
        val launch = call.receive<TerminalLaunch>()
        val plan = terminalPlan(launch, checkout, terminalExecutable(fieldAgent()), terminalExecutable("uv")) { handoffSessionWorktree(checkout, it) }
            .getOrElse { return@post call.respond(HttpStatusCode.BadRequest, it.message ?: "bad request") }
        TerminalSessions.start(launch, plan)
            .onSuccess { call.respondTerminal(it) }
            .onFailure { call.respond(HttpStatusCode.Conflict, it.message ?: "could not start") }
    }
    post("/api/terminal/{id}/kill") {
        if (call.refuseUnlessSameSiteDocsClient(pass)) return@post
        val session = TerminalSessions.get(call.parameters["id"].orEmpty()) ?: return@post call.respond(HttpStatusCode.NotFound, "no such terminal")
        TerminalSessions.kill(session)
        call.respondTerminal(session.snapshot())
    }
    webSocket("/pty/{id}") {
        if (refuseUnlessTerminalClient(policy)) return@webSocket
        val session = TerminalSessions.get(call.parameters["id"].orEmpty())
            ?: return@webSocket close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such terminal"))
        val output = Channel<ByteArray>(capacity = 1024)
        val scrollback = session.attach(output)
        if (scrollback.isNotEmpty()) send(Frame.Binary(true, scrollback))
        val writer = launch {
            for (bytes in output) send(Frame.Binary(true, bytes))
            session.exitCode?.let { send(Frame.Text("""{"type":"exit","code":$it}""")) }
        }
        val stdin = session.process.outputStream
        try {
            for (frame in incoming) {
                val bytes = when (frame) {
                    is Frame.Binary -> frame.readBytes()
                    is Frame.Text -> {
                        val text = frame.readText()
                        if (text.startsWith("{\"type\":\"resize\"")) {
                            runCatching {
                                val o = Json.parseToJsonElement(text).jsonObject
                                session.process.winSize = WinSize(o["cols"]!!.jsonPrimitive.int.coerceIn(20, 500), o["rows"]!!.jsonPrimitive.int.coerceIn(5, 200))
                            }
                            continue
                        }
                        text.toByteArray()
                    }
                    else -> continue
                }
                if (session.exitCode == null) withContext(Dispatchers.IO) { runCatching { stdin.write(bytes); stdin.flush() } }
            }
        } finally {
            session.detach(output)
            output.close()
            writer.join()
        }
    }
}
