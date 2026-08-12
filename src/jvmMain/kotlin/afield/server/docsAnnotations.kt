package afield.server

import afield.logger.lprintln
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import kotlin.concurrent.thread

private const val DOCS_ORIGIN = "https://openendedgroup.com"
private const val DOCS_ROOT = "/field4/"
private const val PROXY_ROOT = "/field-docs/"

// ─────────────────────────────────────────────────────────────────────────────
// Access control
//
// `POST /api/docs-improvement` spawns `codex exec --sandbox workspace-write` — an
// autonomous coding agent with write access to a source checkout — and the prompt it
// is handed is assembled out of annotation notes that arrive over `POST
// /api/docs-annotations`. Both routes are installed into the same Ktor application as
// an SSL connector that binds `0.0.0.0` (see `Server.kt`, `httpsHost`), because Field
// genuinely does serve browsers and the visionOS shell on other devices. Until this
// file grew the block below, that added up to: anything on the LAN could POST its own
// instructions and then POST a button that ran them against the human's source tree,
// with no credential of any kind.
//
// So the capability is now opt-in, token-authenticated, loopback-only, and attributed
// to a directory that is written down rather than inherited from wherever the JVM
// happened to be launched.
//
// What this deliberately does *not* claim to defend against: another process running
// as the same user on the same machine. It can reach loopback, read the token out of
// the environment or the log, and in any case already has whatever access the coding
// agent would be given. The boundary drawn here is "this machine's own user", not
// "this machine".
// ─────────────────────────────────────────────────────────────────────────────

/** System property that turns the agent-spawning route on. Env fallback: [DOCS_IMPROVEMENT_ENABLE_ENV]. */
internal const val DOCS_IMPROVEMENT_ENABLE_PROPERTY = "afield.docsImprovement.enabled"

/** Environment variable that turns the agent-spawning route on. Property equivalent: [DOCS_IMPROVEMENT_ENABLE_PROPERTY]. */
internal const val DOCS_IMPROVEMENT_ENABLE_ENV = "AFIELD_DOCS_IMPROVEMENT"

/** Shared secret for the agent-spawning route. Unset means "mint one at startup and print it once". */
internal const val DOCS_IMPROVEMENT_TOKEN_ENV = "AFIELD_DOCS_IMPROVEMENT_TOKEN"

/** The checkout the spawned agent is allowed to edit. Unset means the server's own working directory. */
internal const val DOCS_IMPROVEMENT_DIRECTORY_ENV = "AFIELD_DOCS_IMPROVEMENT_DIR"

/** Escape hatch that re-opens the *annotation* routes to non-loopback clients. Never re-opens the spawn. */
internal const val DOCS_ANNOTATIONS_ALLOW_REMOTE_ENV = "AFIELD_DOCS_ANNOTATIONS_ALLOW_REMOTE"

/** Header carrying [DOCS_IMPROVEMENT_TOKEN_ENV] on a launch request. */
internal const val DOCS_IMPROVEMENT_TOKEN_HEADER = "X-Field-Docs-Improvement-Token"

/**
 * The resolved configuration the two guards below are written against.
 *
 * It is a value rather than a set of `System.getenv` calls precisely so that the
 * *pre-change* posture — everything permitted, no token — is expressible as an
 * instance and can be pointed at the same tests, which is the only way to know the
 * tests would have failed before.
 */
internal data class DocsAccessPolicy(
    val improvementEnabled: Boolean = false,
    val allowRemoteAnnotations: Boolean = false,
    val improvementToken: String = "",
)

/** A refusal, as the status and the body the route should send. `null` from a guard means "allowed". */
internal data class DocsAccessRefusal(val status: HttpStatusCode, val reason: String)

/**
 * True only for a peer address that is a *literal* loopback IP.
 *
 * Two things matter here and both are easy to get wrong.
 *
 * First, the address must come from `call.request.local`, not `call.request.origin`.
 * `origin` is the connection point as rewritten by `XForwardedHeaders`/`ForwardedHeaders`
 * — i.e. out of `X-Forwarded-For`, a header any client can simply send. Field does not
 * install those plugins today, but a guard that would silently become bypassable if
 * someone did is not a guard. `local` is the socket.
 *
 * Second, nothing here may perform a name lookup. [InetAddress.getByName] resolves a
 * non-literal through DNS, which would hand a remote caller a way to make the guard's
 * verdict depend on a record they control ("dns rebinding, but for an if-statement").
 * [InetAddress.ofLiteral] (Java 22+) parses literals only and throws otherwise, so an
 * unparseable or unexpected address form fails closed.
 *
 * IPv4-mapped forms (`::ffff:127.0.0.1`) parse to an `Inet4Address` and are therefore
 * covered; so is the whole of `127.0.0.0/8`, which is loopback by RFC 1122 whatever
 * the last three octets say.
 */
internal fun isLoopbackDocsClient(remoteAddress: String?): Boolean {
    val trimmed = remoteAddress?.trim()?.removeSurrounding("[", "]").orEmpty()
    if (trimmed.isEmpty()) return false
    val candidates = listOf(trimmed, trimmed.substringBefore('%')).distinct()
    return candidates.any { candidate ->
        runCatching { InetAddress.ofLiteral(candidate) }.getOrNull()?.isLoopbackAddress == true
    }
}

/**
 * Reads a boolean the way a human types one at a shell prompt.
 *
 * The property wins over the environment when both are set, matching how the rest of
 * `Server.kt` treats `-D` flags: a flag on this specific JVM's command line is a more
 * specific statement of intent than a variable inherited from a login shell. Anything
 * that is not one of the affirmatives — including the empty string that
 * `AFIELD_DOCS_IMPROVEMENT=` produces — is off, because the failure this whole file is
 * about is the one where you *think* something is off.
 */
internal fun docsFlagEnabled(property: String?, environment: String?): Boolean {
    val chosen = property?.takeIf { it.isNotBlank() } ?: environment
    return chosen?.trim()?.lowercase() in setOf("1", "true", "yes", "on")
}

/**
 * The guard for reading and writing annotations.
 *
 * Annotation text is the *input* to the coding agent's prompt, so it is not merely a
 * private notepad: whoever can write it can steer a run the human later launches. That
 * is why it is loopback-only by default even though nothing about storing a string is
 * itself dangerous.
 *
 * [DOCS_ANNOTATIONS_ALLOW_REMOTE_ENV] exists because reading the documentation mirror
 * on a tablet is a real use of this codebase, and a hardening pass that quietly breaks
 * it would be traded away the first time it got in the way. Note what the hatch does
 * *not* do: [refuseDocsImprovementLaunch] never consults it.
 */
internal fun refuseDocsAnnotationRequest(policy: DocsAccessPolicy, remoteAddress: String?): DocsAccessRefusal? {
    if (policy.allowRemoteAnnotations || isLoopbackDocsClient(remoteAddress)) return null
    return DocsAccessRefusal(
        HttpStatusCode.Forbidden,
        "Field documentation annotations are loopback-only. Open Field at https://localhost:<port>/field-docs/… " +
            "from this machine, or set $DOCS_ANNOTATIONS_ALLOW_REMOTE_ENV=1 before starting Field to allow " +
            "annotations from other devices.",
    )
}

/**
 * The guard for spawning the coding agent. Three independent conditions, checked in
 * order of how little they reveal to a caller who should not be here at all.
 *
 * Loopback first: a caller off this machine learns only that the route is loopback-only,
 * not whether the feature is configured or what the header is called. Then the enable
 * flag, whose refusal is a *help message* because by then the caller is local and is
 * almost certainly the human wondering why the button did nothing. Then the token.
 *
 * A blank `improvementToken` refuses. There is no path in [resolveDocsAccessPolicy]
 * that produces one — an enabled server without a configured token mints a random one —
 * but "misconfigured" must not mean "unauthenticated", so it is checked anyway.
 */
internal fun refuseDocsImprovementLaunch(
    policy: DocsAccessPolicy,
    remoteAddress: String?,
    presentedToken: String?,
): DocsAccessRefusal? {
    if (!isLoopbackDocsClient(remoteAddress)) {
        return DocsAccessRefusal(
            HttpStatusCode.Forbidden,
            "The documentation improvement agent can only be launched from this machine.",
        )
    }
    if (!policy.improvementEnabled) {
        return DocsAccessRefusal(
            HttpStatusCode.Forbidden,
            "The documentation improvement agent is disabled. It spawns a coding agent with write access to a " +
                "source checkout, so it is off unless you ask for it: restart Field with " +
                "-D$DOCS_IMPROVEMENT_ENABLE_PROPERTY=true (or $DOCS_IMPROVEMENT_ENABLE_ENV=1).",
        )
    }
    if (!docsTokensMatch(policy.improvementToken, presentedToken.orEmpty())) {
        return DocsAccessRefusal(
            HttpStatusCode.Unauthorized,
            "Missing or incorrect $DOCS_IMPROVEMENT_TOKEN_HEADER. The expected value is " +
                "$DOCS_IMPROVEMENT_TOKEN_ENV, or the token Field printed to the log at startup.",
        )
    }
    return null
}

/**
 * Compares in time independent of *content*, via [MessageDigest.isEqual] — deliberately,
 * though constant-time comparison turns out not to be load-bearing here.
 *
 * Worth working out rather than cargo-culting, because the answer is "probably doesn't
 * matter". A timing oracle needs someone who can submit guesses and observe the outcome,
 * and neither caller who can reach this line can:
 *
 *  - a **cross-site page** — the case the token actually exists for — never arrives.
 *    `X-Field-Docs-Improvement-Token` is not CORS-safelisted, so the browser preflights,
 *    and Field's CORS config allows only `Content-Type`. Measured 2026-08-11: the
 *    preflight gets `403 Forbidden` and the page gets `TypeError: Failed to fetch`. No
 *    request reaches the handler, so there is no timing to measure.
 *  - a **local process running as this user** can reach loopback, but it can equally read
 *    the token out of the environment or the startup log, so it has no reason to guess.
 *
 * So: the cheap correct default, not a defence anything rests on. The residual weakness
 * is stated rather than papered over — `isEqual` returns early on a length mismatch, so
 * token *length* leaks to anyone positioned to time it. Nobody so positioned needs it.
 */
private fun docsTokensMatch(expected: String, presented: String): Boolean =
    expected.isNotEmpty() && MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.UTF_8),
        presented.toByteArray(StandardCharsets.UTF_8),
    )

/**
 * The checkout the agent is pointed at, decided once and written down.
 *
 * The old code passed `Paths.get(".")` straight to `ProcessBuilder.directory`, so the
 * blast radius of a run was "whatever directory someone happened to `cd` into before
 * launching `field.sh`", and nothing anywhere recorded which one that was. After a run
 * you could not answer *which tree did it edit* from the logs.
 *
 * A [problem] rather than an exception because a bad `AFIELD_DOCS_IMPROVEMENT_DIR`
 * should refuse the run with a readable sentence, not take down route installation.
 */
internal data class DocsImprovementDirectory(val path: Path?, val problem: String = "") {
    val display: String get() = path?.toString() ?: "<unresolved>"
}

/** Canonicalises [configured] (or [serverDirectory] when it is unset) and insists it is a real directory. */
internal fun resolveDocsImprovementDirectory(configured: String?, serverDirectory: Path): DocsImprovementDirectory {
    val requested = configured?.trim()?.takeIf { it.isNotEmpty() }?.let(Paths::get) ?: serverDirectory
    val resolved = runCatching { requested.toAbsolutePath().normalize().toRealPath() }
        .getOrElse { return DocsImprovementDirectory(null, "cannot resolve $requested: ${it.message}") }
    if (!Files.isDirectory(resolved)) return DocsImprovementDirectory(null, "$resolved is not a directory")
    return DocsImprovementDirectory(resolved)
}

/** Reads the ambient configuration into a [DocsAccessPolicy], minting a token if the feature is on without one. */
private fun resolveDocsAccessPolicy(): DocsAccessPolicy {
    val enabled = docsFlagEnabled(
        System.getProperty(DOCS_IMPROVEMENT_ENABLE_PROPERTY),
        System.getenv(DOCS_IMPROVEMENT_ENABLE_ENV),
    )
    val configuredToken = System.getenv(DOCS_IMPROVEMENT_TOKEN_ENV)?.trim().orEmpty()
    val token = when {
        !enabled -> ""
        configuredToken.isNotEmpty() -> configuredToken
        else -> HexFormat.of().formatHex(ByteArray(24).also(SecureRandom()::nextBytes))
    }
    return DocsAccessPolicy(
        improvementEnabled = enabled,
        allowRemoteAnnotations = docsFlagEnabled(null, System.getenv(DOCS_ANNOTATIONS_ALLOW_REMOTE_ENV)),
        improvementToken = token,
    )
}

@Serializable
data class DocsAnnotationAnchor(
    val exact: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val startOffset: Int = 0,
    val endOffset: Int = 0,
    val xRatio: Double? = null,
    val yRatio: Double? = null,
)

@Serializable
data class DocsAnnotation(
    val id: String = "",
    val pageUrl: String,
    val note: String,
    val tags: List<String> = emptyList(),
    val anchor: DocsAnnotationAnchor = DocsAnnotationAnchor(),
    val createdAtEpochMs: Long = 0,
    val updatedAtEpochMs: Long = 0,
    val resolvedAtEpochMs: Long? = null,
    val resolution: String = "",
    /**
     * The checkout whose Field recorded this note.
     *
     * The store is the single global `~/.afield/docs-annotations.json`, shared by every
     * Field on the machine, so before this field there was no way to tell a note written
     * from the human's main tree apart from one written by a nightly worktree's server —
     * which matters now that a note is an instruction to a coding agent. Empty for notes
     * written before this field existed, and never used as a filter: it is a fact for a
     * reader, not a security boundary.
     */
    val recordedInCheckout: String = "",
)

@Serializable private data class DocsAnnotationFile(val version: Int = 1, val annotations: List<DocsAnnotation> = emptyList())
@Serializable data class DocsAnnotationUpsert(val annotation: DocsAnnotation)
@Serializable data class DocsAnnotationResolve(val resolved: Boolean = true, val resolution: String = "")
@Serializable data class DocsImprovementLaunchRequest(val tags: List<String> = emptyList())
@Serializable data class DocsImprovementStatus(
    val running: Boolean = false,
    val status: String = "idle",
    val runId: String = "",
    val message: String = "",
    val startedAtEpochMs: Long = 0,
    val finishedAtEpochMs: Long = 0,
    val exitCode: Int? = null,
    val logTail: String = "",
    /**
     * Whether a launch would even be considered. Reported on `GET` so a UI can grey the
     * control out and say why, instead of offering a button that always 403s.
     */
    val enabled: Boolean = false,
    /**
     * The canonical directory the agent is (or would be) run in — the single fact that
     * makes a run attributable to a checkout. Reported on `GET` as well as after a run,
     * so you can check it *before* pressing the button rather than reading it out of a
     * postmortem.
     */
    val workingDirectory: String = "",
)

private object DocsAnnotationStore {
    private val lock = Any()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    val path: Path = Paths.get(System.getProperty("user.home"), ".afield", "docs-annotations.json")

    fun list(): List<DocsAnnotation> = synchronized(lock) { read().annotations }

    fun upsert(input: DocsAnnotation): DocsAnnotation = synchronized(lock) {
        require(input.pageUrl.startsWith(DOCS_ORIGIN + DOCS_ROOT)) { "annotation page must be in Field docs" }
        require(input.note.isNotBlank()) { "annotation note is empty" }
        val now = System.currentTimeMillis()
        val old = read().annotations
        val id = input.id.ifBlank { UUID.randomUUID().toString() }
        val existing = old.firstOrNull { it.id == id }
        val saved = input.copy(
            id = id,
            tags = docsAnnotationTags(input.note, input.tags),
            createdAtEpochMs = existing?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now,
            // An edit does not relocate a note: whoever first recorded it stays recorded.
            recordedInCheckout = existing?.recordedInCheckout?.ifBlank { null } ?: docsImprovementDirectory.display,
        )
        write(old.filterNot { it.id == id } + saved)
        saved
    }

    fun resolve(id: String, request: DocsAnnotationResolve): DocsAnnotation? = synchronized(lock) {
        var result: DocsAnnotation? = null
        val now = System.currentTimeMillis()
        val updated = read().annotations.map {
            if (it.id != id) it else it.copy(
                resolvedAtEpochMs = if (request.resolved) now else null,
                resolution = if (request.resolved) request.resolution else "",
                updatedAtEpochMs = now,
            ).also { value -> result = value }
        }
        if (result != null) write(updated)
        result
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val current = read().annotations
        val updated = current.filterNot { it.id == id }
        if (updated.size == current.size) false else true.also { write(updated) }
    }

    private fun read(): DocsAnnotationFile = runCatching {
        if (!Files.exists(path)) DocsAnnotationFile()
        else json.decodeFromString<DocsAnnotationFile>(Files.readString(path))
    }.getOrElse {
        lprintln("warning,docs-annotations", "Could not read $path: ${it.message}")
        DocsAnnotationFile()
    }

    private fun write(annotations: List<DocsAnnotation>) =
        writeDocsAnnotationsAtomically(path, json, annotations)
}

/**
 * The `@tag` vocabulary a note carries, which is also the filter the improvement agent
 * selects notes by — so what counts as a tag decides what a run is handed.
 *
 * The lookbehind is the interesting part: `(?<![\w@])` means an `@` that follows a word
 * character or another `@` is not a tag opener, so `user@example.com` contributes no
 * `example`, and `@@shout` contributes nothing at all. Tags must begin with a letter,
 * which keeps a bare `@2` out, and are lowercased and de-duplicated so that filtering
 * is case-insensitive without every caller having to remember that.
 *
 * Explicitly supplied tags are merged rather than trusted alone; the client already
 * computes its own list in `docsAnnotations.js` and the two are allowed to disagree.
 */
internal fun docsAnnotationTags(note: String, supplied: List<String>): List<String> =
    (supplied + Regex("(?<![\\w@])@([A-Za-z][A-Za-z0-9_-]{0,63})").findAll(note).map { it.groupValues[1] })
        .map { it.removePrefix("@").lowercase() }.filter { it.isNotBlank() }.distinct().sorted()

/**
 * Replaces the annotation file in one step, or not at all.
 *
 * The temporary file is created in the *destination's own directory* on purpose:
 * `ATOMIC_MOVE` is only guaranteed within a filesystem, and a temp file under
 * `java.io.tmpdir` can easily be on a different one, which turns the move into a
 * copy-then-delete with a window where the file is half-written. This matters more than
 * it looks — the improvement agent is told to edit this very file while a human may be
 * adding notes through the panel, so a torn write loses annotations.
 *
 * Extracted from the store object so a test can point it at a temporary directory. What
 * it does not do is protect against two *processes* interleaving: the store is one
 * global file shared by every Field on the machine, and the in-JVM lock around it says
 * nothing about the other JVM. `recordedInCheckout` exists so that at least the result
 * is legible after the fact.
 */
internal fun writeDocsAnnotationsAtomically(path: Path, json: Json, annotations: List<DocsAnnotation>) {
    Files.createDirectories(path.parent)
    val temp = Files.createTempFile(path.parent, "docs-annotations-", ".json")
    Files.writeString(temp, json.encodeToString(DocsAnnotationFile(annotations = annotations)), StandardCharsets.UTF_8)
    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

private object DocsImprovementTask {
    private val lock = Any()
    private var state = DocsImprovementStatus(enabled = docsAccessPolicy.improvementEnabled, workingDirectory = docsImprovementDirectory.display)
    fun status() = synchronized(lock) { state }
    fun start(tags: List<String>): DocsImprovementStatus = synchronized(lock) {
        if (state.running) return state
        // The directory is resolved before anything else, because an unusable one means
        // the run has no defined blast radius and must not begin at all.
        val directory = docsImprovementDirectory.path
            ?: return idle("Cannot run: ${docsImprovementDirectory.problem}. Set $DOCS_IMPROVEMENT_DIRECTORY_ENV to a checkout.")
        val selected = DocsAnnotationStore.list().filter { it.resolvedAtEpochMs == null && (tags.isEmpty() || it.tags.any(tags::contains)) }
        if (selected.isEmpty()) return idle("No unresolved annotations match this filter.")
        val runId = UUID.randomUUID().toString()
        // Loud, and on the server's own log rather than only in an HTTP response: a run
        // that edits a source tree should be visible to whoever is watching the terminal,
        // not only to whoever launched it.
        lprintln(
            "docs-annotations",
            "Launching documentation improvement agent: run=$runId directory=$directory notes=${selected.size} " +
                "tags=${tags.ifEmpty { listOf("<all>") }}",
        )
        state = DocsImprovementStatus(
            running = true, status = "running", runId = runId,
            message = "Starting documentation improvement task in $directory.",
            startedAtEpochMs = System.currentTimeMillis(),
            enabled = true, workingDirectory = directory.toString(),
        )
        thread(name = "docs-annotation-improvement", isDaemon = true) { run(runId, selected, directory) }
        state
    }

    /** A refusal that leaves no run behind, while still reporting the posture the caller asked about. */
    private fun idle(message: String) = DocsImprovementStatus(
        message = message,
        enabled = docsAccessPolicy.improvementEnabled,
        workingDirectory = docsImprovementDirectory.display,
    )

    private fun run(runId: String, notes: List<DocsAnnotation>, directory: Path) {
        val prompt = buildString {
            appendLine("Improve Field4 documentation in this checkout using the user annotations below.")
            appendLine("Make narrow source-grounded edits. After fixing a note, update ${DocsAnnotationStore.path} by setting resolvedAtEpochMs and a short resolution. Preserve unresolved notes and unrelated work.")
            notes.forEach { appendLine("- ${it.id} ${it.pageUrl} tags=${it.tags}: ${it.note} quote=${it.anchor.exact}") }
        }
        val log = StringBuilder()
        var code = -1
        try {
            val process = ProcessBuilder("codex", "exec", "--sandbox", "workspace-write", prompt)
                .directory(directory.toFile()).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { if (log.length < 120_000) log.appendLine(it) } }
            code = process.waitFor()
            finish(runId, code, if (code == 0) "Documentation improvement task finished in $directory." else "Documentation improvement task in $directory exited with $code.", log.toString())
        } catch (error: Exception) {
            finish(runId, code, "Could not run Codex: ${error.message}", log.appendLine(error.toString()).toString())
        }
    }
    private fun finish(runId: String, code: Int, message: String, log: String) = synchronized(lock) {
        if (state.runId == runId) state = state.copy(running = false, status = if (code == 0) "succeeded" else "failed", message = message, finishedAtEpochMs = System.currentTimeMillis(), exitCode = code, logTail = log.takeLast(120_000))
    }
}

private val docsProxyClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
    .followRedirects(HttpClient.Redirect.NORMAL).build()

/** Resolved once per JVM: re-reading the environment per request would make the posture unauditable. */
private val docsAccessPolicy: DocsAccessPolicy by lazy { resolveDocsAccessPolicy() }

/** Likewise once: the directory a run edits must not be able to change between the log line and the run. */
private val docsImprovementDirectory: DocsImprovementDirectory by lazy {
    resolveDocsImprovementDirectory(System.getenv(DOCS_IMPROVEMENT_DIRECTORY_ENV), Paths.get("."))
}

/**
 * Refuses and returns true when this call may not touch annotations.
 *
 * Written as an explicit call at the head of each handler rather than a pipeline
 * interceptor so that a reader of any one route can see, in that route, that it is
 * guarded — and so that grepping `refuseUnlessLocalDocsClient` enumerates the whole
 * protected surface.
 */
private suspend fun ApplicationCall.refuseUnlessLocalDocsClient(): Boolean {
    val refusal = refuseDocsAnnotationRequest(docsAccessPolicy, request.local.remoteAddress) ?: return false
    respond(refusal.status, refusal.reason)
    return true
}

fun Route.installDocsAnnotationRoutes() {
    // One line at startup that says what this server will and will not do, because the
    // failure mode being defended against is silent capability.
    if (docsAccessPolicy.improvementEnabled) {
        lprintln(
            "docs-annotations",
            "Documentation improvement agent ENABLED for ${docsImprovementDirectory.display}. " +
                "POST /api/docs-improvement requires header $DOCS_IMPROVEMENT_TOKEN_HEADER: " +
                "${docsAccessPolicy.improvementToken} (from loopback only).",
        )
    } else {
        lprintln(
            "docs-annotations",
            "Documentation improvement agent disabled. It spawns a coding agent with write access to a checkout; " +
                "enable with -D$DOCS_IMPROVEMENT_ENABLE_PROPERTY=true or $DOCS_IMPROVEMENT_ENABLE_ENV=1.",
        )
    }
    if (docsAccessPolicy.allowRemoteAnnotations) {
        lprintln(
            "warning,docs-annotations",
            "$DOCS_ANNOTATIONS_ALLOW_REMOTE_ENV is set: documentation annotations accept writes from any host " +
                "that can reach this port. The improvement agent stays loopback-only regardless.",
        )
    }

    get("/api/docs-annotations") {
        if (call.refuseUnlessLocalDocsClient()) return@get
        val page = call.request.queryParameters["page"]
        val tags = call.request.queryParameters.getAll("tag").orEmpty().map(String::lowercase)
        val from = call.request.queryParameters["from"]?.toLongOrNull()
        val to = call.request.queryParameters["to"]?.toLongOrNull()
        val includeResolved = call.request.queryParameters["resolved"] == "true"
        call.respond(DocsAnnotationStore.list().filter { item ->
            (page == null || item.pageUrl == page) && (tags.isEmpty() || item.tags.any(tags::contains)) &&
                (from == null || item.createdAtEpochMs >= from) && (to == null || item.createdAtEpochMs <= to) &&
                (includeResolved || item.resolvedAtEpochMs == null)
        })
    }
    post("/api/docs-annotations") {
        if (call.refuseUnlessLocalDocsClient()) return@post
        call.respond(DocsAnnotationStore.upsert(call.receive<DocsAnnotationUpsert>().annotation))
    }
    put("/api/docs-annotations/{id}/resolve") {
        if (call.refuseUnlessLocalDocsClient()) return@put
        DocsAnnotationStore.resolve(call.parameters["id"].orEmpty(), call.receive<DocsAnnotationResolve>())?.let { call.respond(it) }
            ?: call.respond(HttpStatusCode.NotFound, "annotation not found")
    }
    delete("/api/docs-annotations/{id}") {
        if (call.refuseUnlessLocalDocsClient()) return@delete
        if (DocsAnnotationStore.delete(call.parameters["id"].orEmpty())) call.respond(mapOf("ok" to true))
        else call.respond(HttpStatusCode.NotFound, "annotation not found")
    }
    get("/api/docs-improvement") {
        if (call.refuseUnlessLocalDocsClient()) return@get
        call.respond(DocsImprovementTask.status())
    }
    post("/api/docs-improvement") {
        val refusal = refuseDocsImprovementLaunch(
            docsAccessPolicy,
            call.request.local.remoteAddress,
            call.request.headers[DOCS_IMPROVEMENT_TOKEN_HEADER],
        )
        if (refusal != null) {
            lprintln(
                "warning,docs-annotations",
                "Refused a documentation improvement launch from ${call.request.local.remoteAddress}: ${refusal.status}",
            )
            return@post call.respond(refusal.status, refusal.reason)
        }
        call.respond(DocsImprovementTask.start(call.receive<DocsImprovementLaunchRequest>().tags.map(String::lowercase)))
    }
    // Gated like the annotations, and the reason is not the one you would guess.
    //
    // The content is a public website, so nothing here is *secret*. The problem is where
    // it runs. This route fetches openendedgroup.com's HTML and serves it back from
    // Field's own origin — that is the entire trick that lets the injected panel talk to
    // `/api/docs-annotations` without CORS. The cost of the trick is that every script in
    // the mirrored page is same-origin with Field, and Field has ~50 other unauthenticated
    // `/api/…` routes. A compromised or hostile documentation page would be able to call
    // all of them.
    //
    // A remote reader loses nothing by this: the only thing the proxy adds over visiting
    // the real site is the annotation panel, and that panel is loopback-only anyway. So
    // the mirror is now loopback-only too, which costs a tablet one redirect of effort and
    // takes the whole same-origin surface off the local network. The same
    // AFIELD_DOCS_ANNOTATIONS_ALLOW_REMOTE hatch re-opens both together, which is the
    // honest coupling — they were never independent.
    route(PROXY_ROOT.removeSuffix("/")) {
        get("{...}") {
            if (call.refuseUnlessLocalDocsClient()) return@get
            call.proxyFieldDocs()
        }
    }
}

/**
 * The upstream URL a `/field-docs/…` request may be turned into, or `null` to refuse.
 *
 * The guard this replaces was `relative.contains("..")` applied to `URI.rawPath` — the
 * *percent-encoded* path. `..` written as `%2e%2e` therefore contains no `..`, sailed
 * past the check, and was handed to `HttpClient`, which normalises it: the proxy would
 * cheerfully fetch anything on `openendedgroup.com` outside `/field4/` and serve it
 * back through Field's own origin, with the annotation script injected into it. The
 * damage is bounded — that origin is a public website — but "our proxy will fetch
 * whatever you ask it to" is not a property to leave lying around.
 *
 * Two independent checks now, because encoding bugs are exactly the class of bug where
 * one clever check is wrong:
 *
 *  - textual, on the *decoded* path: no `..` segment, no absolute path, no backslash
 *    (a Windows-flavoured separator some servers still honour), no control characters;
 *  - structural, on the assembled URI: after [URI.normalize] the origin must still be
 *    the documentation origin and the path must still start with `/field4/`.
 *
 * Decoding once is the right depth: the upstream server also decodes once, so a
 * double-encoded `%252e%252e` reaches it as the literal filename `%2e%2e` and never
 * becomes a traversal.
 */
internal fun docsProxyTarget(rawPath: String, rawQuery: String?): URI? {
    // Both spellings of the root: Ktor routes `/field-docs` as well as `/field-docs/`,
    // and the old prefix-strip only matched the latter, so a request without the
    // trailing slash was assembled into `/field4//field-docs` and 404'd upstream.
    val relative = rawPath.removePrefix(PROXY_ROOT.removeSuffix("/")).removePrefix("/").ifBlank { "index.html" }
    val decoded = runCatching { URLDecoder.decode(relative, StandardCharsets.UTF_8) }.getOrNull() ?: return null
    if (decoded.split('/').any { it == ".." }) return null
    if (decoded.startsWith("/") || decoded.contains('\\') || decoded.any { it.code < 0x20 }) return null
    val target = runCatching {
        URI.create("$DOCS_ORIGIN$DOCS_ROOT$relative" + rawQuery?.let { "?$it" }.orEmpty()).normalize()
    }.getOrNull() ?: return null
    if (!isWithinFieldDocs(target)) return null
    return target
}

/**
 * Whether a URI is still a page of the Field documentation.
 *
 * Applied twice: once to the URL we are about to request, and once to the URL the
 * response actually came back from. The second application is the interesting one —
 * `docsProxyClient` follows redirects, so a redirect is a way for the upstream to move
 * the proxy somewhere the path checks never saw. That is only reachable if the
 * documentation origin itself starts redirecting somewhere strange, which is unlikely
 * and is also exactly the situation in which you want the proxy to stop rather than to
 * faithfully mirror wherever it has been sent.
 */
private fun isWithinFieldDocs(uri: URI): Boolean =
    "${uri.scheme}://${uri.authority}" == DOCS_ORIGIN && uri.rawPath.orEmpty().startsWith(DOCS_ROOT)

/**
 * Minimal attribute escaping for the injected `<base href>`.
 *
 * Belt and braces, honestly: [URI.create] already rejects a raw `"` or `<`, so no
 * request that reaches here can carry one, and the encoded `%22` form renders as inert
 * text. It costs one function to stop that argument from being load-bearing.
 */
private fun escapeDocsHtmlAttribute(value: String): String =
    value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

private suspend fun ApplicationCall.proxyFieldDocs() {
    val incoming = URI(request.uri)
    val target = docsProxyTarget(incoming.rawPath, incoming.rawQuery)
        ?: return respond(HttpStatusCode.BadRequest, "invalid docs path")
    val upstream = try {
        withContext(Dispatchers.IO) {
            docsProxyClient.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        }
    } catch (error: Exception) { return respond(HttpStatusCode.BadGateway, "documentation unavailable: ${error.message}") }
    // Where the bytes actually came from, after any redirects the client followed. See
    // [isWithinFieldDocs]: the path checks above only constrain where we *asked*.
    if (!isWithinFieldDocs(upstream.uri())) {
        lprintln("warning,docs-annotations", "Docs proxy refused a redirect off ${DOCS_ORIGIN}${DOCS_ROOT}: ${upstream.uri()}")
        return respond(HttpStatusCode.BadGateway, "documentation redirected outside $DOCS_ORIGIN$DOCS_ROOT")
    }
    val contentType = upstream.headers().firstValue(HttpHeaders.ContentType).orElse("application/octet-stream")
    if (contentType.lowercase().contains("text/html")) {
        var html = upstream.body().toString(StandardCharsets.UTF_8)
        val pageBase = escapeDocsHtmlAttribute(target.resolve(".").toString())
        val sauce = """<script>window.__FIELD_DOCS_UPSTREAM__=${Json.encodeToString(target.toString())};window.__FIELD_DOCS_PROXY__='$PROXY_ROOT';const fieldDocsAnnotationsScript=document.createElement('script');fieldDocsAnnotationsScript.src=window.location.origin+'/docsAnnotations.js';document.head.appendChild(fieldDocsAnnotationsScript);</script><base href="$pageBase">"""
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
        val headMatch = head.find(html)
        html = if (headMatch != null) html.substring(0, headMatch.range.last + 1) + sauce + html.substring(headMatch.range.last + 1) else sauce + html
        respondText(html, ContentType.Text.Html, HttpStatusCode.fromValue(upstream.statusCode()))
    } else {
        respondBytes(upstream.body(), runCatching { ContentType.parse(contentType) }.getOrDefault(ContentType.Application.OctetStream), HttpStatusCode.fromValue(upstream.statusCode()))
    }
}
