package afield.ai.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * #### Literal-text search over saved files, for AI agents
 *
 * `WorkspaceSearchRequest` is the agent tool that finds literal text in exactly one
 * [WorkspaceSearchScope]: the current document's files, its history, the whole workspace, or Field's
 * own Kotlin source. It reads one bounded file at a time under byte, match and time limits, and the
 * [WorkspaceSearchResult] says whether it is complete and, if not, why it stopped and what it skipped.
 * There is no regex or shell syntax, and unsaved editor state is not searched.
 */

/** The one tree a search covers. */
@JsonDeserialize(using = WorkspaceSearchScopeDeserializer::class)
enum class WorkspaceSearchScope {
    DOCUMENT_FILES, DOCUMENT_HISTORY, WORKSPACE_FILES, FIELD_KOTLIN_SOURCE
}

/** Jackson otherwise accepts enum ordinals, which could silently widen a search. */
class WorkspaceSearchScopeDeserializer : JsonDeserializer<WorkspaceSearchScope>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): WorkspaceSearchScope {
        if (parser.currentToken != JsonToken.VALUE_STRING)
            throw JsonMappingException.from(parser, "scope must be a named WorkspaceSearchScope")
        return WorkspaceSearchScope.entries.firstOrNull { it.name == parser.text }
            ?: throw JsonMappingException.from(parser, "Unknown WorkspaceSearchScope")
    }
}

@JsonClassDescription("Search saved UTF-8 files for literal text in exactly one scope. Returns the matches (path, line, text) plus complete, stopReason and skipped; results can be partial, so check them. Use it when you need where text occurs in saved files or Field's Kotlin source; for unsaved editor state read the box instead. No regex or shell syntax. An invalid scope, query or pathPrefix fails with success=false and the reason. e.g. scope = `FIELD_KOTLIN_SOURCE`, query = `fun fromContext`.")
@AgentToolMeta("search", effects = [ToolEffect.FILESYSTEM_READ])
data class WorkspaceSearchRequest(
    @param:JsonPropertyDescription("Required: DOCUMENT_FILES, DOCUMENT_HISTORY, WORKSPACE_FILES, or FIELD_KOTLIN_SOURCE. Never combines roots. Workspace/document files exclude backups.")
    val scope: WorkspaceSearchScope,
    @param:JsonPropertyDescription("Literal single-line text, 1..256 UTF-16 units. Whitespace is preserved.")
    val query: String,
    @param:JsonPropertyDescription("Optional relative file/directory path under the scope root, using / separators. No absolute paths, .., or symlinks.")
    val pathPrefix: String = "",
    @param:JsonPropertyDescription("Optional file extensions without dots, e.g. [kt, glsl]. Empty searches all eligible text files; source scope always requires kt.")
    val extensions: List<String> = emptyList(),
    val ignoreCase: Boolean = false,
    @param:JsonPropertyDescription("Global matching-line limit, 1..500. Each line is returned once; reaching the cap reports partial results.")
    val maxMatches: Int = 80
)

enum class WorkspaceSearchStop {
    MATCH_LIMIT, RESPONSE_LIMIT, ENTRY_LIMIT, DEPTH_LIMIT, INPUT_LIMIT, DEADLINE, CANCELLED
}

data class WorkspaceSearchMatch(
    val path: String,
    val line: Int,
    val column: Int,
    val excerpt: String,
    val excerptStartColumn: Int,
    val excerptClipped: Boolean
)

data class WorkspaceSearchSkips(
    val oversized: Int = 0,
    val nonText: Int = 0,
    val unreadable: Int = 0,
    val changed: Int = 0
)

data class WorkspaceSearchResult(
    val success: Boolean,
    val scope: WorkspaceSearchScope,
    val root: String?,
    val matches: List<WorkspaceSearchMatch>,
    val complete: Boolean,
    val stopReason: WorkspaceSearchStop?,
    val filesSearched: Int,
    val bytesRead: Long,
    val skipped: WorkspaceSearchSkips,
    val message: String
)

internal data class WorkspaceSearchRoots(val document: Path, val workspace: Path, val fieldSource: Path)

/** Internal test seams, never request parameters. No caller can raise the production budgets. */
internal data class WorkspaceSearchLimits(
    val fileBytes: Int = 2 * 1024 * 1024,
    val inputBytes: Long = 32L * 1024 * 1024,
    val entries: Int = 10_000,
    val depth: Int = 32,
    val responseBytes: Int = 256 * 1024,
    val durationNanos: Long = 2_000_000_000L
)

/**
 * One bounded file at a time; no subprocess, index, temporary output or whole-tree collection.
 * Filesystem order is deliberately unspecified. The deadline is cooperative: a blocked OS read
 * cannot be interrupted by checking a clock. This is not an atomic filesystem snapshot or sandbox.
 */
internal class WorkspaceSearch(
    private val roots: WorkspaceSearchRoots,
    private val limits: WorkspaceSearchLimits = WorkspaceSearchLimits(),
    private val nanoTime: () -> Long = System::nanoTime,
    private val cancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    private val openFile: (Path) -> InputStream = { Files.newInputStream(it, NOFOLLOW_LINKS) }
) {
    fun search(request: WorkspaceSearchRequest): WorkspaceSearchResult {
        fun failure(message: String) = WorkspaceSearchResult(false, request.scope, null, emptyList(),
            false, null, 0, 0, WorkspaceSearchSkips(), message)
        if (request.query.isEmpty() || request.query.length > 256 || request.query.any { it == '\r' || it == '\n' })
            return failure("query must contain 1..256 UTF-16 units on one line; whitespace is preserved")
        if (request.maxMatches !in 1..500) return failure("maxMatches must be in 1..500")
        if (request.extensions.size > 16 || request.extensions.any { !it.matches(Regex("[A-Za-z0-9]{1,16}")) })
            return failure("extensions must contain at most 16 alphanumeric suffixes without dots")
        val prefix = request.pathPrefix
        if (prefix.length > 1024 || prefix.startsWith('/') || prefix.any { it == '\\' || it == ':' || it == '\u0000' } ||
            prefix.split('/').any { it == ".." }) return failure("pathPrefix must be a relative path without .., drive letters or backslashes")
        try {
            val root = when (request.scope) {
                WorkspaceSearchScope.DOCUMENT_FILES -> roots.document.toRealPath()
                WorkspaceSearchScope.DOCUMENT_HISTORY -> {
                    val history = roots.document.toRealPath().resolve("backups")
                    if (Files.isSymbolicLink(history)) return failure("History root must not be a symlink")
                    history.toRealPath()
                }
                WorkspaceSearchScope.WORKSPACE_FILES -> roots.workspace.toRealPath()
                WorkspaceSearchScope.FIELD_KOTLIN_SOURCE -> roots.fieldSource.toRealPath()
            }
            if (!Files.isDirectory(root, NOFOLLOW_LINKS) || root.toString().length > MAX_PATH)
                return failure("Scope root must be an existing directory with a path of at most 4096 units")
            val start = root.resolve(prefix).normalize()
            if (!start.startsWith(root) || !safeDescendant(root, start))
                return failure("pathPrefix escapes the root or contains a symlink")
            if (root.relativize(start).any { excluded(it.toString(), request.scope) })
                return failure("pathPrefix is excluded by the scope's file policy")
            if (!Files.exists(start, NOFOLLOW_LINKS)) return failure("pathPrefix does not exist")
            return Scan(request, root).run(start)
        } catch (_: InvalidPathException) {
            return failure("Invalid pathPrefix or configured root")
        } catch (_: IOException) {
            return failure("Scope root or requested path is unavailable or unreadable")
        } catch (_: SecurityException) {
            return failure("Scope root or requested path is not accessible")
        }
    }

    private inner class Scan(val request: WorkspaceSearchRequest, val root: Path) {
        val started = nanoTime()
        val matches = ArrayList<WorkspaceSearchMatch>()
        val extensions = request.extensions.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
        var stop: WorkspaceSearchStop? = null
        var skipped = WorkspaceSearchSkips()
        var visited = 0
        var files = 0
        var bytes = 0L
        var matchBytes = 2 // JSON array brackets, plus conservatively one comma per retained row.
        var rootFailed = false

        fun running(): Boolean {
            if (stop == null) {
                if (cancelled()) stop = WorkspaceSearchStop.CANCELLED
                else if (nanoTime() - started >= limits.durationNanos) stop = WorkspaceSearchStop.DEADLINE
            }
            return stop == null
        }

        fun run(start: Path): WorkspaceSearchResult {
            visit(start, 0, true)
            return WorkspaceSearchResult(
                !rootFailed, request.scope, root.toString(), matches,
                !rootFailed && stop == null && skipped == WorkspaceSearchSkips(), stop, files, bytes, skipped,
                if (rootFailed) "Requested path could not be read; any returned matches are partial."
                else if (stop != null || skipped != WorkspaceSearchSkips()) "Partial saved-file search; narrow the scope or filters."
                else "Searched eligible saved files; no live editor state was read."
            ).also {
                // Reserve covers escaped root, counters, enums and fixed messages, even at MAX_PATH.
                check(json.writeValueAsBytes(it).size <= limits.responseBytes) { "Workspace search response budget violated" }
            }
        }

        fun visit(path: Path, depth: Int, top: Boolean = false) {
            if (!running()) return
            if (visited >= limits.entries) { stop = WorkspaceSearchStop.ENTRY_LIMIT; return }
            visited++
            if (!top && excluded(path.fileName.toString(), request.scope)) return
            if (depth > limits.depth) { stop = WorkspaceSearchStop.DEPTH_LIMIT; return }
            try {
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (attrs.isSymbolicLink) return
                if (!safeDescendant(root, path)) { skipped = skipped.copy(changed = skipped.changed + 1); return }
                if (attrs.isDirectory) {
                    Files.newDirectoryStream(path).use { stream ->
                        val iterator = stream.iterator()
                        while (running() && iterator.hasNext()) visit(iterator.next(), depth + 1)
                    }
                } else if (attrs.isRegularFile) {
                    val ext = path.fileName.toString().substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                    if (request.scope == WorkspaceSearchScope.FIELD_KOTLIN_SOURCE && ext != "kt") return
                    if (extensions.isNotEmpty() && ext !in extensions) return
                    val relative = root.relativize(path).joinToString("/")
                    if (relative.length > MAX_PATH) { skipped = skipped.copy(unreadable = skipped.unreadable + 1); return }
                    read(path, relative, attrs)
                }
            } catch (_: java.nio.file.DirectoryIteratorException) {
                unreadable(top)
            } catch (_: IOException) {
                if (cancelled()) stop = WorkspaceSearchStop.CANCELLED else unreadable(top)
            } catch (_: SecurityException) {
                unreadable(top)
            }
        }

        fun unreadable(top: Boolean) {
            skipped = skipped.copy(unreadable = skipped.unreadable + 1)
            if (top) rootFailed = true
        }

        fun read(path: Path, relative: String, before: BasicFileAttributes) {
            if (before.size() > limits.fileBytes) { skipped = skipped.copy(oversized = skipped.oversized + 1); return }
            val output = ByteArrayOutputStream(minOf(limits.fileBytes, 8192))
            openFile(path).use { input ->
                val buffer = ByteArray(8192)
                while (running()) {
                    if (bytes >= limits.inputBytes) { stop = WorkspaceSearchStop.INPUT_LIMIT; return }
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limits.inputBytes - bytes,
                        (limits.fileBytes + 1L - output.size())).toInt())
                    if (count < 0) break
                    bytes += count
                    if (output.size().toLong() + count > limits.fileBytes) {
                        skipped = skipped.copy(oversized = skipped.oversized + 1); return
                    }
                    output.write(buffer, 0, count)
                }
            }
            if (!running()) return
            val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!safeDescendant(root, path) || !after.isRegularFile || before.fileKey() != after.fileKey() ||
                before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() ||
                output.size().toLong() != after.size()) {
                skipped = skipped.copy(changed = skipped.changed + 1); return
            }
            val text = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString() }
            catch (_: CharacterCodingException) { skipped = skipped.copy(nonText = skipped.nonText + 1); return }
            if ('\u0000' in text) { skipped = skipped.copy(nonText = skipped.nonText + 1); return }
            files++
            scanText(relative, text)
        }

        fun scanText(path: String, text: String) {
            var start = 0
            var line = 1
            var i = 0
            var first = -1
            while (i <= text.length && running()) {
                if (i == text.length || text[i] == '\n' || text[i] == '\r') {
                    if (first >= 0) addMatch(path, text, start, i, first, line)
                    if (i < text.length && text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    i++
                    start = i
                    first = -1
                    line++
                } else {
                    if (first < 0 && text.regionMatches(i, request.query, 0, request.query.length, request.ignoreCase)) first = i
                    i++
                }
            }
        }

        fun addMatch(path: String, text: String, start: Int, end: Int, first: Int, line: Int) {
            var left = maxOf(start, first - 128)
            var right = minOf(end, left + 512)
            if (left > start && Character.isLowSurrogate(text[left]) && Character.isHighSurrogate(text[left - 1])) left++
            if (right < end && right > left && Character.isHighSurrogate(text[right - 1]) && Character.isLowSurrogate(text[right])) right--
            val match = WorkspaceSearchMatch(path, line, first - start + 1, text.substring(left, right),
                left - start + 1, left != start || right != end)
            val size = json.writeValueAsBytes(match).size + 1
            if (matchBytes + size > limits.responseBytes - METADATA_RESERVE) { stop = WorkspaceSearchStop.RESPONSE_LIMIT; return }
            matches += match
            matchBytes += size
            if (matches.size >= request.maxMatches) stop = WorkspaceSearchStop.MATCH_LIMIT
        }
    }

    companion object {
        private const val MAX_PATH = 4096
        private const val METADATA_RESERVE = 32 * 1024
        private val json = ObjectMapper()

        private fun excluded(name: String, scope: WorkspaceSearchScope): Boolean =
            name.startsWith('.') || name in setOf("build", "node_modules", "history_screenshots") ||
                (scope != WorkspaceSearchScope.DOCUMENT_HISTORY && name == "backups")

        private fun safeDescendant(root: Path, path: Path): Boolean {
            if (!path.startsWith(root)) return false
            var cursor = root
            for (part in root.relativize(path)) {
                cursor = cursor.resolve(part)
                if (Files.isSymbolicLink(cursor)) return false
            }
            return true
        }
    }
}
