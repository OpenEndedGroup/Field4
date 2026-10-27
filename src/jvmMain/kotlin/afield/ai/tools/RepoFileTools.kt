package afield.ai.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.StandardOpenOption

/**
 * #### Repository files for a headless AI4 job
 *
 * The `repo` tools read, list, search, patch and create files in one checkout, for a job that hands
 * AI4 work a coding agent would otherwise do (a docs-notes revision, a landing question). A job
 * supplies a [RepoScope]: the root, the globs it may read and the globs it may write. Every path is
 * relative to the root with `/` separators; an absolute path, a `..` segment, a symbolic link
 * anywhere on the way, `.git`, or a path no glob admits is refused with the reason. The tools are
 * offered only when a job wires a scope ([ToolExecutionContext.repoFiles]); a chat never sees them.
 */

/** What one job may touch. Globs are `java.nio` globs over the root-relative path: `**` crosses directories. */
data class RepoScope(val root: Path, val readable: List<String>, val writable: List<String> = emptyList())

@JsonClassDescription("Read a text file of the repository this job works in, with line numbers. Paths are relative to the job's root, e.g. `public/docs/fline.md`. Give startLine/endLine (1-based, inclusive) for a range; a whole file is returned up to 2000 lines, then truncated=true says to ask for the rest. Fails with the reason for a path outside the job's allow-list.")
@AgentToolMeta("repo", effects = [ToolEffect.FILESYSTEM_READ])
data class RepoReadFileRequest(
    val path: String,
    val startLine: Int = 1,
    @param:JsonPropertyDescription("Last line to return; 0 = up to 2000 lines from startLine.")
    val endLine: Int = 0,
)

@JsonClassDescription("List repository files matching a glob, relative to the job's root, e.g. `public/docs/*.md` or `src/jvmMain/kotlin/afield/ai/**`. Only files the job may read are listed; at most 500.")
@AgentToolMeta("repo", effects = [ToolEffect.FILESYSTEM_READ])
data class RepoListFilesRequest(val glob: String = "**")

@JsonClassDescription("Search the repository files this job may read for literal text (or a Java regex with regex=true), one match per line. Narrow with glob, e.g. `src/**/*.kt`. Returns path, line and the line's text; complete=false means the cap was reached.")
@AgentToolMeta("repo", effects = [ToolEffect.FILESYSTEM_READ])
data class RepoSearchRequest(
    val query: String,
    val glob: String = "**",
    val regex: Boolean = false,
    val ignoreCase: Boolean = false,
    @param:JsonPropertyDescription("1..300 matching lines.")
    val maxMatches: Int = 100,
)

@JsonClassDescription("Replace an exact passage in a repository file the job may write. oldText must occur exactly once (copy it from RepoReadFileRequest without the line-number prefixes), unless replaceAll=true. The file is rewritten in place; read it again to check.")
@AgentToolMeta("repo", effects = [ToolEffect.FILESYSTEM_WRITE])
data class RepoPatchFileRequest(val path: String, val oldText: String, val newText: String, val replaceAll: Boolean = false)

@JsonClassDescription("Create a repository file the job may write, with this content. Refuses an existing file unless overwrite=true; prefer RepoPatchFileRequest for changes to one.")
@AgentToolMeta("repo", effects = [ToolEffect.FILESYSTEM_WRITE])
data class RepoWriteFileRequest(val path: String, val content: String, val overwrite: Boolean = false)

data class RepoFileResult(
    val success: Boolean,
    val message: String,
    val path: String = "",
    val text: String = "",
    val totalLines: Int = 0,
    val truncated: Boolean = false,
)

data class RepoMatch(val path: String, val line: Int, val text: String)
data class RepoListResult(val success: Boolean, val message: String, val files: List<String> = emptyList(), val complete: Boolean = true)
data class RepoSearchResult(val success: Boolean, val message: String, val matches: List<RepoMatch> = emptyList(), val complete: Boolean = true, val filesSearched: Int = 0)

/** A path the scope refuses; the message is what the model sees. */
class RepoPathRefused(message: String) : IllegalArgumentException(message)

/** The `repo` tools over one [RepoScope]. Every call re-checks its path: nothing is cached between calls. */
class RepoFileTools(val scope: RepoScope) {
    private val root: Path = scope.root.toAbsolutePath().normalize()
    private val realRoot: Path = root.toRealPath()
    private val readers = scope.readable.map(::matcher)
    private val writers = scope.writable.map(::matcher)

    /** Paths this job wrote, in order, for the job's report. */
    val written: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    private fun matcher(glob: String): PathMatcher = FileSystems.getDefault().getPathMatcher("glob:" + glob.trim().removePrefix("./"))

    /** [raw] as a confined absolute path, or [RepoPathRefused]. */
    fun resolve(raw: String, write: Boolean): Path {
        val rel = raw.trim().removePrefix("./")
        if (rel.isEmpty()) throw RepoPathRefused("path is empty")
        if (rel.startsWith("/") || rel.contains('\\') || Regex("^[A-Za-z]:").containsMatchIn(rel))
            throw RepoPathRefused("'$raw': give a path relative to the job's root, with / separators")
        val parts = rel.split('/')
        if (parts.any { it == ".." || it == "." || it.isEmpty() }) throw RepoPathRefused("'$raw': no '..', '.' or empty segments")
        if (parts.first() == ".git") throw RepoPathRefused("'$raw': .git is never reachable")
        val path = try { root.resolve(rel).normalize() } catch (e: InvalidPathException) { throw RepoPathRefused("'$raw': ${e.message}") }
        if (!path.startsWith(root)) throw RepoPathRefused("'$raw' is outside the job's root")
        var cursor = root
        for (part in parts) {
            cursor = cursor.resolve(part)
            if (Files.isSymbolicLink(cursor)) throw RepoPathRefused("'$raw': ${root.relativize(cursor)} is a symbolic link")
        }
        // A symlinked ancestor of the root itself is fine; anything that resolves elsewhere is not.
        val existing = generateSequence(path) { it.parent }.firstOrNull { Files.exists(it, NOFOLLOW_LINKS) }
        if (existing != null && !existing.toRealPath().startsWith(realRoot)) throw RepoPathRefused("'$raw' resolves outside the job's root")
        val relPath = root.relativize(path)
        val allowed = if (write) writers else readers
        if (allowed.none { it.matches(relPath) })
            throw RepoPathRefused("'$rel' is not in this job's ${if (write) "writable" else "readable"} list: " +
                (if (write) scope.writable else scope.readable).joinToString(", ").ifBlank { "(empty)" })
        return path
    }

    private fun relative(path: Path) = root.relativize(path).toString().replace('\\', '/')

    private fun readText(path: Path): String? = try {
        if (Files.size(path) > MAX_FILE_BYTES) null
        else Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(path))).toString()
    } catch (_: CharacterCodingException) { null }

    private fun <T> guarded(fail: (String) -> T, body: () -> T): T = try { body() }
        catch (e: RepoPathRefused) { fail(e.message ?: "refused") }
        catch (e: java.io.IOException) { fail("${e.javaClass.simpleName}: ${e.message}") }

    fun read(req: RepoReadFileRequest): RepoFileResult = guarded({ RepoFileResult(false, it, req.path) }) {
        val path = resolve(req.path, write = false)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return@guarded RepoFileResult(false, "no such file: ${req.path}", req.path)
        val text = readText(path) ?: return@guarded RepoFileResult(false, "not a UTF-8 text file under ${MAX_FILE_BYTES / 1024} KB", req.path)
        val lines = text.split('\n').let { if (text.endsWith("\n")) it.dropLast(1) else it }
        val start = req.startLine.coerceAtLeast(1)
        val wanted = if (req.endLine > 0) req.endLine else start + MAX_LINES - 1
        val end = minOf(wanted, lines.size, start + MAX_LINES - 1)
        val body = if (start > lines.size) "" else (start..end).joinToString("\n") { "$it\t${lines[it - 1]}" }
        RepoFileResult(true, if (start > lines.size) "the file has ${lines.size} lines" else "lines $start-$end of ${lines.size}",
            relative(path), body, lines.size, truncated = end < minOf(wanted, lines.size) || (req.endLine <= 0 && end < lines.size))
    }

    /** Files under the root the job may read, in path order; [limit] caps it. Skipped directories are not entered. */
    private fun walk(glob: String, limit: Int): Pair<List<Path>, Boolean> {
        val filter = matcher(glob)
        val out = ArrayList<Path>()
        var complete = true
        Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes) =
                if (dir != root && (attrs.isSymbolicLink || dir.fileName.toString() in SKIPPED_DIRS)) java.nio.file.FileVisitResult.SKIP_SUBTREE
                else java.nio.file.FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (!attrs.isRegularFile) return java.nio.file.FileVisitResult.CONTINUE
                val rel = root.relativize(file)
                if (!filter.matches(rel) || readers.none { it.matches(rel) }) return java.nio.file.FileVisitResult.CONTINUE
                if (out.size >= limit) { complete = false; return java.nio.file.FileVisitResult.TERMINATE }
                out.add(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException) = java.nio.file.FileVisitResult.CONTINUE
        })
        return out.sortedBy { relative(it) } to complete
    }

    fun list(req: RepoListFilesRequest): RepoListResult = guarded({ RepoListResult(false, it) }) {
        val (files, complete) = walk(req.glob, MAX_LIST)
        RepoListResult(true, "${files.size} file(s)" + if (complete) "" else " (capped at $MAX_LIST; narrow the glob)",
            files.map(::relative), complete)
    }

    fun search(req: RepoSearchRequest): RepoSearchResult = guarded({ RepoSearchResult(false, it) }) {
        if (req.query.isEmpty() || req.query.contains('\n')) return@guarded RepoSearchResult(false, "query must be one non-empty line")
        val cap = req.maxMatches.coerceIn(1, 300)
        val pattern = try {
            if (req.regex) Regex(req.query, if (req.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()) else null
        } catch (e: IllegalArgumentException) { return@guarded RepoSearchResult(false, "bad regex: ${e.message}") }
        val (files, walkedAll) = walk(req.glob, MAX_SEARCH_FILES)
        val matches = ArrayList<RepoMatch>()
        var searched = 0
        for (file in files) {
            val text = readText(file) ?: continue
            searched++
            for ((i, line) in text.lineSequence().withIndex()) {
                val hit = pattern?.containsMatchIn(line) ?: line.contains(req.query, req.ignoreCase)
                if (hit) {
                    if (matches.size >= cap) return@guarded RepoSearchResult(true, "stopped at $cap matches", matches, false, searched)
                    matches += RepoMatch(relative(file), i + 1, line.take(300))
                }
            }
        }
        RepoSearchResult(true, "${matches.size} match(es) in $searched file(s)", matches, walkedAll, searched)
    }

    fun patch(req: RepoPatchFileRequest): RepoFileResult = guarded({ RepoFileResult(false, it, req.path) }) {
        val path = resolve(req.path, write = true)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return@guarded RepoFileResult(false, "no such file: ${req.path}", req.path)
        val text = readText(path) ?: return@guarded RepoFileResult(false, "not a UTF-8 text file", req.path)
        generatedBy(text)?.let { return@guarded RepoFileResult(false, "${req.path} is generated ($it): change its generator instead", req.path) }
        if (req.oldText.isEmpty()) return@guarded RepoFileResult(false, "oldText is empty", req.path)
        val count = countOccurrences(text, req.oldText)
        if (count == 0) return@guarded RepoFileResult(false, "oldText does not occur in ${req.path}; read the file again and copy the passage exactly", req.path)
        if (count > 1 && !req.replaceAll) return@guarded RepoFileResult(false, "oldText occurs $count times; give a longer passage or replaceAll=true", req.path)
        val updated = if (req.replaceAll) text.replace(req.oldText, req.newText) else text.replaceFirst(req.oldText, req.newText)
        Files.writeString(path, updated, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, NOFOLLOW_LINKS)
        written += relative(path)
        RepoFileResult(true, "replaced $count occurrence(s) in ${relative(path)}", relative(path))
    }

    fun write(req: RepoWriteFileRequest): RepoFileResult = guarded({ RepoFileResult(false, it, req.path) }) {
        val path = resolve(req.path, write = true)
        if (Files.exists(path, NOFOLLOW_LINKS) && !req.overwrite)
            return@guarded RepoFileResult(false, "${req.path} exists; patch it, or pass overwrite=true", req.path)
        if (Files.isRegularFile(path, NOFOLLOW_LINKS)) readText(path)?.let(::generatedBy)?.let {
            return@guarded RepoFileResult(false, "${req.path} is generated ($it): change its generator instead", req.path)
        }
        if (req.content.length > MAX_FILE_BYTES) return@guarded RepoFileResult(false, "content over ${MAX_FILE_BYTES / 1024} KB", req.path)
        Files.createDirectories(path.parent)
        // createDirectories may have made the parents; re-check that nothing on the way is a link.
        resolve(req.path, write = true)
        Files.writeString(path, req.content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, NOFOLLOW_LINKS)
        written += relative(path)
        RepoFileResult(true, "wrote ${relative(path)} (${req.content.length} chars)", relative(path))
    }

    companion object {
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_LINES = 2000
        const val MAX_LIST = 500
        const val MAX_SEARCH_FILES = 20_000
        private val SKIPPED_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".venv", ".kotlin")

        /**
         * The `DO NOT EDIT` line in a file's first five lines, or null. The first live docs-notes run
         * (2026-10-27) read lines 850-1035 of a generated Vectors.kt, never saw its header, and patched it.
         */
        internal fun generatedBy(text: String): String? =
            text.lineSequence().take(5).firstOrNull { it.contains("DO NOT EDIT") }?.trim()?.trimStart('/', '#', '*', ' ', '<', '!', '-')?.take(160)

        private fun countOccurrences(text: String, needle: String): Int {
            var n = 0; var at = text.indexOf(needle)
            while (at >= 0) { n++; at = text.indexOf(needle, at + needle.length) }
            return n
        }
    }
}

internal fun registerRepoFileTools(tools: ToolRegistry) {
    tools.register(RepoReadFileRequest::class.java, { it.repoFiles != null }) { ctx, req -> ctx.repoFiles!!.read(req) }
    tools.register(RepoListFilesRequest::class.java, { it.repoFiles != null }) { ctx, req -> ctx.repoFiles!!.list(req) }
    tools.register(RepoSearchRequest::class.java, { it.repoFiles != null }) { ctx, req -> ctx.repoFiles!!.search(req) }
    tools.register(RepoPatchFileRequest::class.java, { it.repoFiles != null }) { ctx, req -> ctx.repoFiles!!.patch(req) }
    tools.register(RepoWriteFileRequest::class.java, { it.repoFiles != null }) { ctx, req -> ctx.repoFiles!!.write(req) }
}
