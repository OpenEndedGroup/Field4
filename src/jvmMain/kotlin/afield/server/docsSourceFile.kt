package afield.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// ─────────────────────────────────────────────────────────────────────────────
// A repo source file, from this checkout, for the docs pane.
//
// The docs build links a code span that is exactly a tracked repo path under src/, tools/,
// server/ or public/ to GitHub (`link_repo_source_paths_html` in public/docs_hooks.py; Marc,
// 2026-10-01, handoff 9b508085). Inside Field's own docs pane that is the wrong copy: the file is
// on this machine, and may be ahead of main. So `docsAnnotations.js`, which the /field-docs/
// mirror injects into each page, intercepts the click and shows the file this route returns.
//
// It is a read of source text, which is exactly the exposure `GET /api/source` has without a
// guard (see `installFieldApiSameSiteGuard`). This one is guarded like the revision pass's routes:
// loopback + same-site with both annotation hatches shut ([docsPassPolicy]), so it answers only a
// browser on this machine, on one of Field's own pages. It serves the four roots the docs link
// and nothing else: no dev_docs/, no config/, no build output, no path that leaves its root
// through `..` or a symlink.
// ─────────────────────────────────────────────────────────────────────────────

/** The repo roots whose files the docs link, and so the only ones this route serves. */
internal val DOCS_SOURCE_ROOTS = setOf("src", "tools", "server", "public")

/** Larger files are cut to this many bytes, at a line boundary, and say so. */
internal const val DOCS_SOURCE_MAX_BYTES = 1_500_000

@Serializable
data class DocsSourceFileResponse(
    val path: String,
    val text: String,
    val truncated: Boolean = false,
)

private val docsSourceJson = Json { encodeDefaults = true }

/**
 * The file `requested` names under [checkout], or null to refuse. `requested` is a repo-relative
 * path with `/` separators whose first segment is one of [DOCS_SOURCE_ROOTS]. Checked twice, as
 * the docs mirror's paths are: textually (no empty, `.` or `..` segment, no backslash, no control
 * character, not absolute), then structurally (the real path, symlinks resolved, is still a
 * regular file inside that root).
 */
internal fun docsSourceFile(checkout: Path, requested: String): Path? {
    if (requested.isEmpty() || requested.length > 1024) return null
    if (requested.startsWith("/") || requested.any { it == '\\' || it.isISOControl() }) return null
    val segments = requested.split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
    val rootName = segments.first()
    if (rootName !in DOCS_SOURCE_ROOTS || segments.size < 2) return null
    return runCatching {
        val root = checkout.resolve(rootName).toRealPath()
        val real = checkout.resolve(requested).toRealPath()
        real.takeIf { it.startsWith(root) && Files.isRegularFile(it) }
    }.getOrNull()
}

/** The file's text, or null when it is not text: a NUL byte, or bytes that are not UTF-8. */
internal fun docsSourceText(bytes: ByteArray): String? {
    if (bytes.any { it == 0.toByte() }) return null
    return runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
}

internal fun Route.installDocsSourceFileRoute(
    policy: DocsAccessPolicy,
    checkout: Path = Paths.get(System.getProperty("user.dir")),
) {
    get("/api/docs-source-file") {
        if (call.refuseUnlessSameSiteDocsClient(docsPassPolicy(policy))) return@get
        val requested = call.request.queryParameters["path"].orEmpty()
        val file = docsSourceFile(checkout, requested)
            ?: return@get call.respond(HttpStatusCode.NotFound, "not a source file under src/, tools/, server/ or public/ in this checkout")
        val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(file) }
        val truncated = bytes.size > DOCS_SOURCE_MAX_BYTES
        val kept = if (!truncated) bytes else {
            val cut = (DOCS_SOURCE_MAX_BYTES downTo 1).firstOrNull { bytes[it - 1] == '\n'.code.toByte() } ?: DOCS_SOURCE_MAX_BYTES
            bytes.copyOf(cut)
        }
        val text = docsSourceText(kept)
            ?: return@get call.respond(HttpStatusCode.UnsupportedMediaType, "not a text file")
        val body = DocsSourceFileResponse(path = requested, text = text, truncated = truncated)
        call.respondText(docsSourceJson.encodeToString(DocsSourceFileResponse.serializer(), body), ContentType.Application.Json)
    }
}
