package afield.server

import afield.logger.lprintln
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
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

/** Escape hatch that re-opens the annotation *API* to other websites. A different axis from the one above. */
internal const val DOCS_ANNOTATIONS_ALLOW_CROSS_SITE_ENV = "AFIELD_DOCS_ANNOTATIONS_ALLOW_CROSS_SITE"

/** System property that selects the annotation notebook. Env fallback: [DOCS_ANNOTATIONS_STORE_ENV]. */
internal const val DOCS_ANNOTATIONS_STORE_PROPERTY = "afield.docsAnnotations.store"

/** Annotation notebook path. Unset preserves the shared `~/.afield/docs-annotations.json` default. */
internal const val DOCS_ANNOTATIONS_STORE_ENV = "AFIELD_DOCS_ANNOTATIONS_STORE"

/** Header carrying [DOCS_IMPROVEMENT_TOKEN_ENV] on a launch request. */
internal const val DOCS_IMPROVEMENT_TOKEN_HEADER = "X-Field-Docs-Improvement-Token"

/** System property choosing where `/field-docs/…` reads from. Env fallback: [DOCS_MIRROR_SOURCE_ENV]. */
internal const val DOCS_MIRROR_SOURCE_PROPERTY = "afield.docsMirror.source"

/** `local`, `upstream`, or unset for [DocsMirrorSource.AUTO]. Property equivalent: [DOCS_MIRROR_SOURCE_PROPERTY]. */
internal const val DOCS_MIRROR_SOURCE_ENV = "AFIELD_DOCS_MIRROR_SOURCE"

/** System property naming the built documentation site to serve. Env fallback: [DOCS_MIRROR_DIRECTORY_ENV]. */
internal const val DOCS_MIRROR_DIRECTORY_PROPERTY = "afield.docsMirror.dir"

/** The built documentation site to serve. Unset means [DEFAULT_DOCS_MIRROR_DIRECTORY] under the server's own directory. */
internal const val DOCS_MIRROR_DIRECTORY_ENV = "AFIELD_DOCS_MIRROR_DIR"

/** Where `tools/build_zensical_docs.py` writes the site, relative to a checkout. */
internal const val DEFAULT_DOCS_MIRROR_DIRECTORY = "public/field4"

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
    val allowCrossSiteAnnotations: Boolean = false,
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
 * Whether the request came from *this website*, as opposed to some other one. The question
 * `refuseCrossSiteDocsRequest` and `Server.kt`'s opt-in API guard are both really asking;
 * *which machine* is a separate one, and [isLoopbackDocsClient] answers that.
 *
 * This is the hole the branch that wrote [refuseDocsAnnotationRequest] reasoned its way to
 * and then did not close. Its own KDoc, and two dev_docs, say the sentence correctly:
 * **loopback does not stop a cross-site POST, because your own browser is on loopback.**
 * It was applied to `POST /api/docs-improvement`, whose token header is not CORS-safelisted
 * so the preflight fails — and not to the annotation routes one screen above it, whose only
 * header is `Content-Type: application/json`. That header *is* in Field's `allowHeader`,
 * `Post` and `Delete` *are* in its `allowMethod`, and `anyHost()` covers the origin. So the
 * preflight succeeded and any page you had open could write annotation notes — which are
 * the improvement agent's prompt input, i.e. the exact asset the token exists to protect.
 *
 * The fix is not to narrow the CORS block. `POST /api/import-offline-doc` is a real
 * cross-origin consumer (`offline-viewer.js`), the plugin's configuration is
 * application-wide, and a route that must not be reachable has to refuse for itself — the
 * same conclusion `Server.kt` reaches about the bind address, for the same reason.
 *
 * Before either signal is trusted, a browser-shaped request has to name Field as
 * `localhost` or a literal IP address. Merely having `Origin == Host` is not enough: a
 * DNS-rebinding page keeps those equal while changing the address its own name resolves
 * to. As with [isLoopbackDocsClient], this check never performs a name lookup.
 *
 * Two signals, in the order of how much they are worth:
 *
 *  - **`Sec-Fetch-Site`**, which the browser sets and a page cannot: it is a forbidden
 *    header name, so no amount of `fetch` options touches it. `same-origin` is the panel
 *    talking to its own server. `none` is a human typing the URL or following a bookmark —
 *    a request with no initiator at all, which is not a CSRF. Everything else is refused,
 *    and note that includes **`same-site`**: a page on `http://localhost:9493` is a
 *    different *origin* from `https://localhost:9092` but the same *site*, and it is
 *    precisely the origin from which last night's branch measured a successful attack.
 *    An unrecognised value is refused too, because a value we do not understand is not
 *    permission.
 *  - **`Origin` against `Host`**, for a client too old to send `Sec-Fetch-Site`. Both are
 *    set by the browser from the same URL, so a same-origin request has them agreeing on
 *    the authority and a cross-origin one does not. `Origin: null` — a sandboxed iframe,
 *    or some redirect chains — parses to no authority and is therefore refused.
 *
 * No `Origin` at all means no browser: `curl`, the visionOS shell's own fetches, a script.
 * That counts as same-site here, because it is not a CSRF — nobody's ambient credentials are
 * being borrowed — and because on the annotation routes the caller still has to be on
 * loopback to get this far. An `Origin` with no `Host` is a shape no real client produces,
 * so it fails closed.
 */
internal fun isSameSiteRequest(secFetchSite: String?, origin: String?, hostHeader: String?): Boolean {
    val fetchSite = secFetchSite?.trim()?.lowercase().orEmpty()
    val presented = origin?.trim().orEmpty()
    if ((fetchSite.isNotEmpty() || presented.isNotEmpty()) && !isAllowedFieldHost(hostHeader)) return false
    when (fetchSite) {
        "same-origin", "none" -> return true
        "" -> Unit
        else -> return false
    }
    if (presented.isEmpty()) return true
    val host = hostHeader?.trim().orEmpty()
    if (host.isEmpty()) return false
    val authority = presented.substringAfter("://", missingDelimiterValue = "")
    return authority.isNotEmpty() && authority.equals(host, ignoreCase = true)
}

/**
 * The authorities Field can safely recognise without asking DNS: its conventional
 * `localhost` name and literal IPv4/IPv6 addresses, each optionally carrying a port.
 */
internal fun isAllowedFieldHost(hostHeader: String?): Boolean {
    val authority = hostHeader?.trim().orEmpty()
    if (authority.isEmpty()) return false
    val host = when {
        authority.startsWith('[') && ']' in authority -> {
            val close = authority.indexOf(']')
            if (!isValidAuthorityPort(authority.substring(close + 1))) return false
            authority.substring(1, close)
        }
        authority.count { it == ':' } <= 1 -> {
            val colon = authority.indexOf(':')
            if (colon >= 0 && !isValidAuthorityPort(authority.substring(colon))) return false
            authority.substringBefore(':')
        }
        else -> return false // Browsers bracket an IPv6 Host; an unbracketed one is not an authority.
    }
    if (host.equals("localhost", ignoreCase = true)) return true
    return runCatching { InetAddress.ofLiteral(host) }.isSuccess
}

private fun isValidAuthorityPort(suffix: String): Boolean =
    suffix.isEmpty() ||
        (suffix.startsWith(':') && suffix.drop(1).toIntOrNull()?.let { it in 1..65535 } == true)

/**
 * [isSameSiteRequest] as a refusal, with the sentence the annotation panel renders.
 *
 * The decision and the message are separated because `Server.kt`'s opt-in guard over the
 * *whole* mutating API asks the same question and has to give a different answer. One
 * implementation of "is this another website", two things to say about it.
 */
internal fun refuseCrossSiteDocsRequest(
    policy: DocsAccessPolicy,
    secFetchSite: String?,
    origin: String?,
    hostHeader: String?,
): DocsAccessRefusal? {
    if (policy.allowCrossSiteAnnotations) return null
    if (isSameSiteRequest(secFetchSite, origin, hostHeader)) return null
    return DocsAccessRefusal(
        HttpStatusCode.Forbidden,
        "Field documentation annotations are same-site only: this request came from another website, and " +
            "annotation text is the prompt the documentation improvement agent is handed. Use the panel Field " +
            "injects into https://localhost:<port>/field-docs/…, or set " +
            "$DOCS_ANNOTATIONS_ALLOW_CROSS_SITE_ENV=1 before starting Field if you meant this.",
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
        allowCrossSiteAnnotations = docsFlagEnabled(null, System.getenv(DOCS_ANNOTATIONS_ALLOW_CROSS_SITE_ENV)),
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
     * The store defaults to the global `~/.afield/docs-annotations.json`, shared by every
     * Field on the machine, so before this field there was no way to tell a note written
     * from the human's main tree apart from one written by a nightly worktree's server.
     * [DOCS_ANNOTATIONS_STORE_ENV] can now isolate those servers, but old and default
     * notes still need attribution. Empty for notes written before this field existed,
     * and never used as a filter: it is a fact for a reader, not a security boundary.
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

/**
 * Resolves the notebook once at startup without requiring it to exist yet.
 *
 * The property wins over the environment, as it does for the enable switch. Relative
 * overrides are relative to the server's working directory, which makes
 * `AFIELD_DOCS_ANNOTATIONS_STORE=.field-backdoor/docs-annotations.json` naturally
 * checkout-local. Unset deliberately preserves the existing shared notebook rather
 * than silently orphaning a human's notes during a security fix.
 */
internal fun resolveDocsAnnotationStorePath(
    property: String?,
    environment: String?,
    userHome: Path,
    serverDirectory: Path,
): Path {
    val configured = property?.trim()?.takeIf { it.isNotEmpty() }
        ?: environment?.trim()?.takeIf { it.isNotEmpty() }
    val requested = configured?.let(Paths::get) ?: userHome.resolve(".afield").resolve("docs-annotations.json")
    return (if (requested.isAbsolute) requested else serverDirectory.resolve(requested))
        .toAbsolutePath().normalize()
}

private fun configuredDocsAnnotationStorePath(): Path = resolveDocsAnnotationStorePath(
    System.getProperty(DOCS_ANNOTATIONS_STORE_PROPERTY),
    System.getenv(DOCS_ANNOTATIONS_STORE_ENV),
    Paths.get(System.getProperty("user.home")),
    Paths.get("."),
)

private object DocsAnnotationStore {
    private val lock = Any()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * A `var` for exactly one reason: [withDocsAnnotationStoreAt]. Production resolves
     * its value once from [DOCS_ANNOTATIONS_STORE_PROPERTY]/[DOCS_ANNOTATIONS_STORE_ENV].
     *
     * A route-level test that POSTs an annotation would otherwise write into the human's
     * real `~/.afield/docs-annotations.json`, which is not a temp file and not the test's
     * to touch. There is no production path that assigns to this.
     */
    @Volatile
    var path: Path = configuredDocsAnnotationStorePath()

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
 * Runs [block] with the annotation store pointed at [path], then puts it back. Tests only;
 * production isolation uses [DOCS_ANNOTATIONS_STORE_ENV] or [DOCS_ANNOTATIONS_STORE_PROPERTY].
 *
 * The alternative was a test that drives `POST /api/docs-annotations` for real and lands a
 * note in the human's own notebook, which is a worse trade than a `var`. Not concurrent-safe,
 * deliberately: the store is a process-global singleton and pretending otherwise here would
 * be a more convincing lie than the `@Volatile`. Nesting *is* safe — the previous value is
 * restored, not the default — because a helper that wraps every test application in one of
 * these cannot know whether its caller already did.
 *
 * Worth reaching for more readily than it looks. Most of the route-level tests assert a
 * *refusal*, so while they pass they never reach the store at all — but the point of a
 * security test is to be run against a reverted guard, and that is exactly the state in
 * which they do reach it. Twelve notes landed in the real file that way on 2026-08-12,
 * during this function's own branch, before anybody noticed.
 */
internal fun <T> withDocsAnnotationStoreAt(path: Path, block: () -> T): T {
    val previous = DocsAnnotationStore.path
    DocsAnnotationStore.path = path
    try {
        return block()
    } finally {
        DocsAnnotationStore.path = previous
    }
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
 * it does not do is protect against two *processes* pointed at the same file: the in-JVM
 * lock says nothing about another JVM. The default remains one global file shared by
 * every Field on the machine; [DOCS_ANNOTATIONS_STORE_ENV] is how automation and
 * worktrees opt out. `recordedInCheckout` keeps the default legible after the fact.
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
 * Where `/field-docs/…` reads from, as a value for the same reason [DocsAccessPolicy] is
 * one: "serves the local build", "serves the published site" and "has no local build" are
 * then all just arguments, and a test can stand the real route up over a temporary
 * directory without a network or an environment variable in sight.
 */
internal data class DocsMirror(val source: DocsMirrorSource, val directory: Path)

/** The mirror this JVM resolved at startup, from the environment, and prints on installation. */
private val docsMirror: DocsMirror by lazy {
    DocsMirror(
        resolveDocsMirrorSource(System.getProperty(DOCS_MIRROR_SOURCE_PROPERTY), System.getenv(DOCS_MIRROR_SOURCE_ENV)),
        resolveDocsMirrorDirectory(
            System.getProperty(DOCS_MIRROR_DIRECTORY_PROPERTY) ?: System.getenv(DOCS_MIRROR_DIRECTORY_ENV),
            Paths.get("."),
        ),
    )
}

/**
 * Refuses and returns true when this call is not from *this machine*.
 *
 * Written as an explicit call at the head of each handler rather than a pipeline
 * interceptor so that a reader of any one route can see, in that route, that it is
 * guarded — and so that grepping the two `refuseUnless…` names enumerates the whole
 * protected surface, and which axis each route is protected on.
 */
private suspend fun ApplicationCall.refuseUnlessLocalDocsClient(policy: DocsAccessPolicy): Boolean {
    val refusal = refuseDocsAnnotationRequest(policy, request.local.remoteAddress) ?: return false
    respond(refusal.status, refusal.reason)
    return true
}

/**
 * Refuses and returns true when this call is not from *this site*. Loopback first, because
 * a caller who is off the machine should learn the machine rule and nothing more.
 *
 * The `Host` header rather than `request.local.serverHost`: `Host` is what the browser
 * derived the page's origin from, so it is the thing `Origin` is comparable to. Reading
 * the connection point instead would compare an origin against a socket, and on a server
 * reached by several names — `localhost`, `127.0.0.1`, the LAN address, all live here —
 * those are not the same question.
 */
private suspend fun ApplicationCall.refuseUnlessSameSiteDocsClient(policy: DocsAccessPolicy): Boolean {
    if (refuseUnlessLocalDocsClient(policy)) return true
    val refusal = refuseCrossSiteDocsRequest(
        policy,
        request.headers[SEC_FETCH_SITE_HEADER],
        request.headers[HttpHeaders.Origin],
        request.headers[HttpHeaders.Host],
    ) ?: return false
    lprintln(
        "warning,docs-annotations",
        "Refused a cross-site ${request.httpMethod.value} ${request.path()} from " +
            "origin=${request.headers[HttpHeaders.Origin] ?: "<none>"} " +
            "sec-fetch-site=${request.headers[SEC_FETCH_SITE_HEADER] ?: "<none>"}",
    )
    respond(refusal.status, refusal.reason)
    return true
}

/** The browser-set signal for "who initiated this". A page cannot set it: it is a forbidden header name. */
internal const val SEC_FETCH_SITE_HEADER = "Sec-Fetch-Site"

/**
 * @param policy the access posture to enforce. Defaults to the one this JVM resolved at
 *   startup; a test passes its own so that "the server as it shipped last week", "the
 *   hatch open" and "the hatch shut" are all just arguments, the way [DocsAccessPolicy]
 *   was designed to make them.
 * @param mirror which copy of the documentation `/field-docs/…` serves, same idea: a test
 *   hands over a temporary directory and needs no network.
 */
internal fun Route.installDocsAnnotationRoutes(
    policy: DocsAccessPolicy = docsAccessPolicy,
    mirror: DocsMirror = docsMirror,
) {
    // One line at startup that says what this server will and will not do, because the
    // failure mode being defended against is silent capability.
    lprintln("docs-annotations", "Documentation annotation store: ${DocsAnnotationStore.path}")
    lprintln(
        "docs-annotations",
        "Documentation mirror ($PROXY_ROOT) source=${mirror.source.name.lowercase()}: " +
            "local build ${mirror.directory} (${if (Files.isRegularFile(mirror.directory.resolve("index.html"))) "built" else "not built yet"}), " +
            "published site $DOCS_ORIGIN$DOCS_ROOT. Change with $DOCS_MIRROR_SOURCE_ENV / $DOCS_MIRROR_DIRECTORY_ENV.",
    )
    if (policy.improvementEnabled) {
        lprintln(
            "docs-annotations",
            "Documentation improvement agent ENABLED for ${docsImprovementDirectory.display}. " +
                "POST /api/docs-improvement requires header $DOCS_IMPROVEMENT_TOKEN_HEADER: " +
                "${policy.improvementToken} (from loopback only).",
        )
    } else {
        lprintln(
            "docs-annotations",
            "Documentation improvement agent disabled. It spawns a coding agent with write access to a checkout; " +
                "enable with -D$DOCS_IMPROVEMENT_ENABLE_PROPERTY=true or $DOCS_IMPROVEMENT_ENABLE_ENV=1.",
        )
    }
    if (policy.allowRemoteAnnotations) {
        lprintln(
            "warning,docs-annotations",
            "$DOCS_ANNOTATIONS_ALLOW_REMOTE_ENV is set: documentation annotations accept network peers that " +
                "can reach this port. Browser requests must still use localhost or a literal IP and be same-origin " +
                "unless $DOCS_ANNOTATIONS_ALLOW_CROSS_SITE_ENV is also set. The improvement agent stays " +
                "loopback-only regardless.",
        )
    }
    if (policy.allowCrossSiteAnnotations) {
        lprintln(
            "warning,docs-annotations",
            "$DOCS_ANNOTATIONS_ALLOW_CROSS_SITE_ENV is set: documentation annotations accept writes from any " +
                "website open in your browser, and annotation text is the improvement agent's prompt. The " +
                "improvement agent still needs its token regardless.",
        )
    }

    get("/api/docs-annotations") {
        if (call.refuseUnlessSameSiteDocsClient(policy)) return@get
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
        if (call.refuseUnlessSameSiteDocsClient(policy)) return@post
        call.respond(DocsAnnotationStore.upsert(call.receive<DocsAnnotationUpsert>().annotation))
    }
    put("/api/docs-annotations/{id}/resolve") {
        if (call.refuseUnlessSameSiteDocsClient(policy)) return@put
        DocsAnnotationStore.resolve(call.parameters["id"].orEmpty(), call.receive<DocsAnnotationResolve>())?.let { call.respond(it) }
            ?: call.respond(HttpStatusCode.NotFound, "annotation not found")
    }
    delete("/api/docs-annotations/{id}") {
        if (call.refuseUnlessSameSiteDocsClient(policy)) return@delete
        if (DocsAnnotationStore.delete(call.parameters["id"].orEmpty())) call.respond(mapOf("ok" to true))
        else call.respond(HttpStatusCode.NotFound, "annotation not found")
    }
    get("/api/docs-improvement") {
        if (call.refuseUnlessSameSiteDocsClient(policy)) return@get
        call.respond(DocsImprovementTask.status())
    }
    post("/api/docs-improvement") {
        // Belt and braces on the one route where the cost of being wrong is a coding agent.
        // The token already stops a web page — its header is not CORS-safelisted, so the
        // preflight fails — but that argument rests on six lines of CORS configuration in
        // another file staying exactly as they are. This check does not.
        //
        // `allowCrossSiteAnnotations` is forced shut here, for the same reason
        // [refuseDocsImprovementLaunch] never reads `allowRemoteAnnotations`: a hatch whose
        // name says "annotations" must not turn out to have opened the spawn.
        if (call.refuseUnlessSameSiteDocsClient(policy.copy(allowCrossSiteAnnotations = false))) return@post
        val refusal = refuseDocsImprovementLaunch(
            policy,
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
    // The content is a public website — or this checkout's own build of it — so nothing
    // here is *secret*. The problem is where it runs. This route serves that HTML from
    // Field's own origin — that is the entire trick that lets the injected panel talk to
    // `/api/docs-annotations` without CORS. The cost of the trick is that every script in
    // the mirrored page is same-origin with Field, and Field has ~50 other unauthenticated
    // `/api/…` routes. A compromised or hostile documentation page would be able to call
    // all of them. Serving the local build does not change that argument: a built site is
    // generated from this checkout's Markdown, so it is exactly as trusted as the checkout.
    //
    // A remote reader loses nothing by this: the only thing the proxy adds over visiting
    // the real site is the annotation panel, and that panel is loopback-only anyway. So
    // the mirror is now loopback-only too, which costs a tablet one redirect of effort and
    // takes the whole same-origin surface off the local network. The same
    // AFIELD_DOCS_ANNOTATIONS_ALLOW_REMOTE hatch re-opens both together, which is the
    // honest coupling — they were never independent.
    route(PROXY_ROOT.removeSuffix("/")) {
        get("{...}") {
            // Machine only, not site: this route is reached by *navigation*, and a link to
            // it from somewhere else arrives as `Sec-Fetch-Site: cross-site` while being an
            // entirely ordinary way to get to a page. It also serves nothing but a public
            // website's bytes. What it must not do is hand those bytes — and the
            // same-origin position they arrive in — to another device, and that is the
            // loopback check, unchanged.
            if (call.refuseUnlessLocalDocsClient(policy)) return@get
            call.serveFieldDocs(mirror)
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
    val relative = docsRequestRelativePath(rawPath)
    safeDecodedDocsPath(relative) ?: return null
    val target = runCatching {
        URI.create("$DOCS_ORIGIN$DOCS_ROOT$relative" + rawQuery?.let { "?$it" }.orEmpty()).normalize()
    }.getOrNull() ?: return null
    if (!isWithinFieldDocs(target)) return null
    return target
}

/**
 * A `/field-docs/…` request path reduced to a path relative to the documentation root,
 * still percent-encoded because the upstream URL is assembled out of it verbatim.
 *
 * Both spellings of the root: Ktor routes `/field-docs` as well as `/field-docs/`, and
 * the old prefix-strip only matched the latter, so a request without the trailing slash
 * was assembled into `/field4//field-docs` and 404'd upstream.
 */
private fun docsRequestRelativePath(rawPath: String): String =
    rawPath.removePrefix(PROXY_ROOT.removeSuffix("/")).removePrefix("/").ifBlank { "index.html" }

/**
 * [relative] decoded once and checked textually, or `null` to refuse — the first of
 * [docsProxyTarget]'s two independent checks, and the only one [docsMirrorFile] can
 * share, since a local path has no origin to re-check afterwards.
 */
private fun safeDecodedDocsPath(relative: String): String? {
    val decoded = runCatching { URLDecoder.decode(relative, StandardCharsets.UTF_8) }.getOrNull() ?: return null
    if (decoded.split('/').any { it == ".." }) return null
    if (decoded.startsWith("/") || decoded.contains('\\') || decoded.any { it.code < 0x20 }) return null
    return decoded
}

/**
 * Which copy of the documentation `/field-docs/…` serves.
 *
 * [AUTO] is the default and means "the local build if it is there, the published site if
 * it is not". That ordering is the useful one twice over: it is the only copy that exists
 * when the machine is offline — the mirror used to fail with nothing but
 * `documentation unavailable: null`, which is what an exception with no message reduces
 * to — and it is the copy that contains the paragraph you just edited, which is the whole
 * point of annotating a page in order to improve it.
 *
 * [UPSTREAM] is the old behaviour, for checking what a reader of the published site
 * actually sees. [LOCAL] refuses to fall back, so a missing page is reported as missing
 * rather than quietly answered from the internet — which is what you want while you are
 * working on the build itself.
 */
internal enum class DocsMirrorSource { AUTO, LOCAL, UPSTREAM }

/**
 * Reads [DOCS_MIRROR_SOURCE_PROPERTY]/[DOCS_MIRROR_SOURCE_ENV], property first for the
 * same reason as [docsFlagEnabled]: a `-D` on this JVM's command line is a more specific
 * statement of intent than a variable inherited from a login shell.
 *
 * Anything unrecognised — including the empty string an unset-but-exported variable
 * produces — is [DocsMirrorSource.AUTO] rather than an error, because failing to start
 * the documentation route over a typo is a worse outcome than serving the default. The
 * startup line prints the *resolved* value, so the typo is visible where it matters.
 */
internal fun resolveDocsMirrorSource(property: String?, environment: String?): DocsMirrorSource {
    val raw = (property?.trim()?.takeIf(String::isNotEmpty) ?: environment?.trim().orEmpty()).lowercase()
    return when (raw) {
        "local" -> DocsMirrorSource.LOCAL
        "upstream" -> DocsMirrorSource.UPSTREAM
        else -> DocsMirrorSource.AUTO
    }
}

/**
 * The built site [DocsMirrorSource.LOCAL] and [DocsMirrorSource.AUTO] serve, as an
 * absolute path. Not required to exist: a docs build finished an hour after Field
 * started should be picked up by the next request, not by the next restart, so existence
 * is a per-request question ([docsMirrorFile]) and only the *path* is settled once.
 */
internal fun resolveDocsMirrorDirectory(configured: String?, serverDirectory: Path): Path =
    (configured?.trim()?.takeIf(String::isNotEmpty)?.let(Paths::get) ?: serverDirectory.resolve(DEFAULT_DOCS_MIRROR_DIRECTORY))
        .toAbsolutePath().normalize()

/**
 * The file under [root] a `/field-docs/…` request names, or `null` if there isn't one.
 *
 * Three ways out of [root], all closed, because "serve a file from a directory" is the
 * other classic of this genre and the proxy's own traversal bug (see [docsProxyTarget])
 * is a reminder that one clever check is not enough:
 *
 *  - the textual check of [safeDecodedDocsPath], shared with the proxy;
 *  - `startsWith` on the normalised path, which catches anything the decode produced
 *    that the textual check did not anticipate;
 *  - `toRealPath` on the file that was found, so a **symlink** inside the built site
 *    cannot point out of it. The built site is generated, so this is not hypothetical
 *    only because nobody has yet written a docs hook that symlinks something in.
 *
 * A directory resolves to its `index.html`, matching how the published site is served,
 * so `/field-docs/` and `/field-docs/reference/` both work.
 */
internal fun docsMirrorFile(root: Path, rawPath: String): Path? {
    val decoded = safeDecodedDocsPath(docsRequestRelativePath(rawPath)) ?: return null
    val candidate = root.resolve(decoded).normalize()
    if (!candidate.startsWith(root)) return null
    val file = if (Files.isDirectory(candidate)) candidate.resolve("index.html") else candidate
    if (!Files.isRegularFile(file)) return null
    val realRoot = runCatching { root.toRealPath() }.getOrNull() ?: return null
    val realFile = runCatching { file.toRealPath() }.getOrNull() ?: return null
    return if (realFile.startsWith(realRoot)) file else null
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

/**
 * The annotation panel's bootstrap, plus the `<base href>` that decides where the rest
 * of the page's assets come from.
 *
 * `upstream` is the *canonical* URL of the page whichever copy served it, because it is
 * what `docsAnnotations.js` keys notes on (`canonicalPage`). A note taken on the local
 * build and a note taken on the published site are notes on the same page, and rebuilding
 * the site must not orphan either — so the key is the published URL even when not one
 * byte came from there.
 */
private fun docsAnnotationsSauce(upstream: URI, pageBase: String, source: String): String =
    """<script>window.__FIELD_DOCS_UPSTREAM__=${Json.encodeToString(upstream.toString())};window.__FIELD_DOCS_PROXY__='$PROXY_ROOT';window.__FIELD_DOCS_SOURCE__=${Json.encodeToString(source)};const fieldDocsAnnotationsScript=document.createElement('script');fieldDocsAnnotationsScript.src=window.location.origin+'/docsAnnotations.js';document.head.appendChild(fieldDocsAnnotationsScript);</script><base href="${escapeDocsHtmlAttribute(pageBase)}">"""

/** Puts [sauce] immediately after `<head …>`, or at the very top of a page that has no head. */
private fun withDocsAnnotationsInjected(html: String, sauce: String): String {
    val headMatch = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html) ?: return sauce + html
    return html.substring(0, headMatch.range.last + 1) + sauce + html.substring(headMatch.range.last + 1)
}

/**
 * Serves `/field-docs/…` from the local build when there is one, and from the published
 * site when there is not — see [DocsMirrorSource] for why that is the order.
 *
 * Both paths inject the same annotation panel and declare the same canonical page URL,
 * so which copy answered is invisible to the notebook and visible to you: the panel
 * labels itself from `window.__FIELD_DOCS_SOURCE__`.
 */
private suspend fun ApplicationCall.serveFieldDocs(mirror: DocsMirror) {
    val incoming = URI(request.uri)
    // Computed first and for both paths: it is the annotation key, and its refusal is the
    // traversal guard the local path shares.
    val target = docsProxyTarget(incoming.rawPath, incoming.rawQuery)
        ?: return respond(HttpStatusCode.BadRequest, "invalid docs path")
    val (source, root) = mirror
    if (source != DocsMirrorSource.UPSTREAM) {
        val file = docsMirrorFile(root, incoming.rawPath)
        if (file != null) return respondLocalDocs(file, root, target)
        if (source == DocsMirrorSource.LOCAL) return respondText(
            docsUnavailableHtml(
                target,
                "$DOCS_MIRROR_SOURCE_ENV=local, so the published site was not tried.",
                root,
                "No file for this page under the local build.",
            ),
            ContentType.Text.Html,
            HttpStatusCode.NotFound,
        )
    }
    val upstream = try {
        withContext(Dispatchers.IO) {
            docsProxyClient.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        }
    } catch (error: Exception) {
        // `error.message` alone was the whole of this response, and an exception with no
        // message — a bare `IOException`, a cancellation — rendered as the sentence
        // "documentation unavailable: null" with nothing in the log either. Name the
        // class, and say what the alternative copy would have been.
        lprintln("warning,docs-annotations", "Docs mirror could not reach $target: $error")
        return respondText(
            docsUnavailableHtml(target, describeDocsFailure(error), root, localDocsAbsence(root)),
            ContentType.Text.Html,
            HttpStatusCode.BadGateway,
        )
    }
    // Where the bytes actually came from, after any redirects the client followed. See
    // [isWithinFieldDocs]: the path checks above only constrain where we *asked*.
    if (!isWithinFieldDocs(upstream.uri())) {
        lprintln("warning,docs-annotations", "Docs proxy refused a redirect off ${DOCS_ORIGIN}${DOCS_ROOT}: ${upstream.uri()}")
        return respond(HttpStatusCode.BadGateway, "documentation redirected outside $DOCS_ORIGIN$DOCS_ROOT")
    }
    val contentType = upstream.headers().firstValue(HttpHeaders.ContentType).orElse("application/octet-stream")
    if (contentType.lowercase().contains("text/html")) {
        val html = upstream.body().toString(StandardCharsets.UTF_8)
        val sauce = docsAnnotationsSauce(target, target.resolve(".").toString(), "published")
        respondText(withDocsAnnotationsInjected(html, sauce), ContentType.Text.Html, HttpStatusCode.fromValue(upstream.statusCode()))
    } else {
        respondBytes(upstream.body(), runCatching { ContentType.parse(contentType) }.getOrDefault(ContentType.Application.OctetStream), HttpStatusCode.fromValue(upstream.statusCode()))
    }
}

/**
 * Serves one file of the local build, annotated exactly as the proxied copy is.
 *
 * The `<base href>` is the *local* directory rather than the upstream one, which is what
 * keeps the page offline: with an upstream base every stylesheet, script and image on the
 * page is fetched from openendedgroup.com, and a mirror whose assets all come from the
 * internet is not a mirror. Relative links then resolve to `/field-docs/…` on their own,
 * and `docsAnnotations.js`'s link rewriting — which only fires for upstream-origin links
 * — correctly does nothing.
 */
private suspend fun ApplicationCall.respondLocalDocs(file: Path, root: Path, canonical: URI) {
    val bytes = withContext(Dispatchers.IO) { runCatching { Files.readAllBytes(file) }.getOrNull() }
        ?: return respondText(
            docsUnavailableHtml(canonical, "The local build has this page but it could not be read.", root, "$file is not readable."),
            ContentType.Text.Html,
            HttpStatusCode.InternalServerError,
        )
    val declared = ContentType.defaultForFilePath(file.fileName.toString())
    if (declared.match(ContentType.Text.Html)) {
        val relativeDirectory = root.relativize(file.parent).toString().replace('\\', '/')
        val pageBase = PROXY_ROOT + if (relativeDirectory.isEmpty()) "" else "$relativeDirectory/"
        val sauce = docsAnnotationsSauce(canonical, pageBase, "local")
        return respondText(withDocsAnnotationsInjected(bytes.toString(StandardCharsets.UTF_8), sauce), ContentType.Text.Html)
    }
    // [defaultForFilePath] appends `charset=UTF-8` to text, SVG and JavaScript itself,
    // which matters: a CSS or JS file served without one is decoded as the *document's*
    // encoding, and the built site is full of characters that do not survive that.
    respondBytes(bytes, declared)
}

/** An exception's class as well as its message, because [Exception.message] is so often null. */
private fun describeDocsFailure(error: Exception): String =
    error.message?.takeIf(String::isNotBlank)?.let { "${error.javaClass.simpleName}: $it" } ?: error.javaClass.name

/** Why the local build did not answer, for the page that has to explain itself. */
private fun localDocsAbsence(root: Path): String = when {
    !Files.isDirectory(root) -> "There is no local build at $root."
    !Files.isRegularFile(root.resolve("index.html")) -> "$root exists but has no index.html — an unbuilt or half-built site."
    else -> "The local build at $root has no file for this page."
}

/**
 * The page you get when neither copy of the documentation can answer.
 *
 * HTML rather than a plain-text status body because this is read inside the editor's
 * documentation pane, where a bare string is indistinguishable from a broken feature —
 * which is exactly how "documentation unavailable: null" read. It says which two places
 * were tried, what went wrong with each, and the one command that fixes the common case.
 */
private fun docsUnavailableHtml(page: URI, upstreamFailure: String, root: Path, localFailure: String): String {
    val e = ::escapeDocsHtmlAttribute
    return """<!doctype html><html><head><meta charset="utf-8"><title>Documentation unavailable</title>
<style>body{background:#090909;color:#e8e8e8;font:13px/1.6 Iosevka,ui-monospace,monospace;margin:0;padding:40px;max-width:46em}
h1{font-size:15px;letter-spacing:.06em;font-weight:600;margin:0 0 1.5em}dt{color:#8b8b8b;margin-top:1.2em}dd{margin:.3em 0 0}
pre{background:#111;border:1px solid #333;padding:10px;overflow:auto;white-space:pre-wrap}code{color:rgb(72,201,255)}</style></head>
<body><h1>DOCUMENTATION UNAVAILABLE</h1>
<p>Nothing could answer <code>${e(page.toString())}</code>.</p>
<dl>
<dt>the published site</dt><dd>${e(upstreamFailure)}</dd>
<dt>the local build</dt><dd>${e(localFailure)}</dd>
</dl>
<p>Build the site and this pane serves it from disk, offline, including whatever you have just written:</p>
<pre>FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean</pre>
<p><code>$DOCS_MIRROR_DIRECTORY_ENV</code> points this pane at another build directory; <code>$DOCS_MIRROR_SOURCE_ENV=upstream</code> pins it to the published site, <code>=local</code> to the build at <code>${e(root.toString())}</code>.</p>
</body></html>"""
}
