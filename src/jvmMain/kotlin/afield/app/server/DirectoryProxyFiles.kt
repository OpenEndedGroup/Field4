package afield.app.server

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** A fresh snapshot of eligible paths, not of file contents. Intentional exclusions count as skips. */
data class DirectoryProxyScan(
    val files: List<File>,
    val complete: Boolean,
    val stopReason: String?,
    val skipped: Int
)

/**
 * Shared directory-proxy policy. No subprocess, cache, or file-content reads during enumeration.
 *
 * There is no JGit dependency here. Basic .gitignore rules are supported per directory: comments,
 * negation, rooted/slash patterns, directory-only patterns, *, ?, ** and character classes. As in
 * Git, an excluded directory is not traversed (a child negation cannot resurrect it). Global Git
 * excludes and .git/info/exclude are deliberately not consulted. Backslash escapes and trailing
 * space escaping are not interpreted; rules containing backslashes conservatively exclude the
 * directory rather than risk exposing an ignored file. Ignore files are capped at 64 KiB.
 *
 * A File returned here is not an open handle: callers must resolve again immediately before reads.
 * These checks do not provide an atomic sandbox against concurrent filesystem replacement.
 */
object DirectoryProxyFiles {
    internal data class Limits(
        val entries: Int = 10_000,
        val files: Int = 2_000,
        val depth: Int = 32,
        val millis: Long = 2_000
    )

    private const val SCAN_BYTES = 2L * 1024 * 1024
    private const val READ_BYTES = 10L * 1024 * 1024
    private const val IGNORE_BYTES = 64L * 1024
    private val excluded = setOf(
        "build", "node_modules", "target", "dist", "vendor", "generated", "out", "coverage",
        "__pycache__", "venv", "bower_components"
    )

    fun scan(root: File): DirectoryProxyScan = scan(root, Limits())

    /** Scans only this root-relative directory or file; an empty prefix means the root. */
    fun scan(root: File, prefix: String): DirectoryProxyScan = scan(root, prefix, Limits())

    internal fun scan(root: File, limits: Limits): DirectoryProxyScan = scan(root, "", limits)

    /** Invalid, excluded, missing or symlink prefixes fail just like explicit [resolve] requests. */
    internal fun scan(root: File, prefix: String, limits: Limits): DirectoryProxyScan {
        require(limits.entries >= 0 && limits.files >= 0 && limits.depth >= 0 && limits.millis >= 0)
        val base = rootPath(root)
        val found = mutableListOf<File>()
        var entries = 0
        var skipped = 0
        var reason: String? = null
        var halted = false
        val start = System.nanoTime()
        fun partial(message: String) { if (reason == null) reason = message }
        fun expired(): Boolean {
            if ((System.nanoTime() - start) / 1_000_000 >= limits.millis) {
                partial("time limit (${limits.millis} ms)"); halted = true
            }
            return halted
        }
        fun visit(directory: Path, depth: Int, inherited: List<Rule>) {
            if (expired()) return
            val rules = try { inherited + rulesAt(directory) } catch (_: IOException) {
                skipped++; partial("unreadable or unsupported .gitignore"); return
            } catch (_: IllegalArgumentException) {
                skipped++; partial("unreadable or unsupported .gitignore"); return
            }
            try {
                Files.newDirectoryStream(directory).use { children ->
                    for (child in children) {
                        if (expired()) break
                        if (entries >= limits.entries) {
                            partial("entry limit (${limits.entries})"); halted = true; break
                        }
                        entries++
                        val isDirectory = Files.isDirectory(child, NOFOLLOW_LINKS)
                        if (!permittedChild(base, child, isDirectory, rules)) { skipped++; continue }
                        if (isDirectory) {
                            if (depth + 1 >= limits.depth) {
                                skipped++; partial("depth limit (${limits.depth})")
                            } else visit(child, depth + 1, rules)
                        } else if (Files.isRegularFile(child, NOFOLLOW_LINKS)) {
                            if (depth + 1 > limits.depth) {
                                skipped++; partial("depth limit (${limits.depth})"); continue
                            }
                            if (Files.size(child) > SCAN_BYTES) { skipped++; continue }
                            if (found.size >= limits.files) {
                                partial("file limit (${limits.files})"); halted = true; break
                            }
                            found += child.toFile()
                        } else skipped++
                        if (halted) break
                    }
                }
            } catch (_: IOException) { skipped++; partial("unreadable or changing directory") }
            catch (_: java.nio.file.DirectoryIteratorException) {
                skipped++; partial("unreadable or changing directory")
            }
        }
        if (prefix.isEmpty()) {
            visit(base, 0, emptyList())
        } else {
            val selected = checkedPath(base, prefix.removeSuffix("/"), limits.depth) { !expired() }
            if (!expired()) {
                if (Files.isDirectory(selected.path, NOFOLLOW_LINKS)) {
                    visit(selected.path, selected.depth, selected.rules)
                } else if (limits.entries == 0) {
                    partial("entry limit (${limits.entries})")
                } else if (!Files.isRegularFile(selected.path, NOFOLLOW_LINKS) || Files.size(selected.path) > SCAN_BYTES) {
                    skipped++
                } else if (limits.files == 0) {
                    partial("file limit (${limits.files})")
                } else {
                    found += selected.path.toFile()
                }
            }
        }
        return DirectoryProxyScan(found.sortedBy { it.relativeTo(base.toFile()).invariantSeparatorsPath }, reason == null, reason, skipped)
    }

    /** Rejects hidden/generated/ignored children as well as escapes; permits attachments up to 10 MiB. */
    fun resolve(root: File, relative: String): File {
        val current = checkedPath(rootPath(root), relative, 32).path
        require(Files.isRegularFile(current, NOFOLLOW_LINKS)) { "Not a regular file" }
        require(Files.size(current) <= READ_BYTES) { "Directory proxy file exceeds 10 MiB" }
        return current.toFile()
    }

    fun reference(boxId: String, relative: String): String {
        require(boxId.isNotBlank() && boxId.none { it == '/' || it == '\\' || it.isISOControl() }) { "Invalid proxy box id" }
        return "proxy:$boxId/${relativeParts(relative).joinToString("/")}"
    }

    fun catalogue(root: File, boxId: String): String = catalogue(root, boxId, Limits())

    internal fun catalogue(root: File, boxId: String, limits: Limits): String {
        val scan = scan(root, limits)
        val base = rootPath(root).toFile()
        return buildString {
            append("Directory proxy catalogue: ${scan.files.size} files; ")
            append(if (scan.complete) "complete" else "PARTIAL: ${scan.stopReason}")
            append("; skipped ${scan.skipped}.\n")
            append("Limits: ${limits.entries} entries, ${limits.files} files, depth ${limits.depth}, ${limits.millis} ms; candidates <=2 MiB; explicit reads <=10 MiB. Hidden, generated, ignored, symlink and oversized entries are skipped.\n")
            for (file in scan.files) {
                // JSON-style quoting preserves whitespace/newlines without inventing unusable paths.
                append('"')
                append(reference(boxId, file.relativeTo(base).invariantSeparatorsPath)
                    .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"))
                append("\"\n")
            }
        }
    }

    private fun relativeParts(relative: String): List<String> {
        require(relative.isNotBlank() && !relative.startsWith('/') && '\\' !in relative && ':' !in relative && relative.none { it.isISOControl() }) { "Unsafe directory proxy path" }
        val parts = relative.split('/')
        require(parts.all { it.isNotEmpty() && it != "." && it != ".." }) { "Unsafe directory proxy path" }
        return parts
    }

    private fun rootPath(root: File): Path = root.canonicalFile.toPath().also {
        require(Files.isDirectory(it, NOFOLLOW_LINKS)) { "Directory proxy root is not a directory" }
    }

    private data class CheckedPath(val path: Path, val depth: Int, val rules: List<Rule>)

    /** Returned rules cover ancestors only; traversal adds the selected directory's own rules. */
    private fun checkedPath(
        base: Path,
        relative: String,
        maxDepth: Int,
        keepGoing: () -> Boolean = { true }
    ): CheckedPath {
        val parts = relativeParts(relative)
        require(parts.size <= maxDepth) { "Directory proxy depth limit ($maxDepth)" }
        var current = base
        var rules = emptyList<Rule>()
        for ((index, part) in parts.withIndex()) {
            // A timed-out scan must not inspect further ancestors or open the prefix directory.
            if (!keepGoing()) return CheckedPath(current, index, rules)
            rules = rules + rulesAt(current)
            current = current.resolve(part)
            val directory = Files.isDirectory(current, NOFOLLOW_LINKS)
            require(permittedChild(base, current, directory, rules)) { "Unsafe, missing or excluded directory proxy child" }
            if (index < parts.lastIndex) require(directory) { "Not a directory" }
        }
        return CheckedPath(current, parts.size, rules)
    }

    private fun permittedChild(base: Path, child: Path, directory: Boolean, rules: List<Rule>): Boolean =
        safeChild(base, child) && !excluded(child) && !ignored(child, directory, rules)

    private fun safeChild(base: Path, child: Path): Boolean =
        !Files.isSymbolicLink(child) && Files.exists(child, NOFOLLOW_LINKS) &&
            child.toFile().canonicalFile.toPath().startsWith(base)

    private fun excluded(path: Path): Boolean {
        val name = path.fileName.toString()
        return name.startsWith('.') || name in excluded || name.any { it.isISOControl() || it == '\\' || it == ':' }
    }

    private data class Rule(val base: Path, val regex: Regex, val directoryOnly: Boolean, val negate: Boolean)

    private fun ignored(path: Path, directory: Boolean, rules: List<Rule>): Boolean {
        var ignored = false
        for (rule in rules) {
            if ((!rule.directoryOnly || directory) && rule.regex.matches(rule.base.relativize(path).toString().replace(File.separatorChar, '/'))) {
                ignored = !rule.negate
            }
        }
        return ignored
    }

    private fun rulesAt(directory: Path): List<Rule> {
        val file = directory.resolve(".gitignore")
        if (!Files.exists(file, NOFOLLOW_LINKS)) return emptyList()
        require(!Files.isSymbolicLink(file) && Files.isRegularFile(file, NOFOLLOW_LINKS))
        require(Files.size(file) <= IGNORE_BYTES)
        val text = Files.newInputStream(file).use { input ->
            val bytes = input.readNBytes(IGNORE_BYTES.toInt() + 1)
            require(bytes.size <= IGNORE_BYTES)
            bytes.toString(Charsets.UTF_8)
        }
        return text.lineSequence().mapNotNull { raw ->
            var pattern = raw.trimEnd()
            if (pattern.isEmpty() || pattern.startsWith('#')) return@mapNotNull null
            require('\\' !in pattern) { "Unsupported escaped gitignore rule" }
            val negate = pattern.startsWith('!')
            if (negate) pattern = pattern.drop(1)
            val directoryOnly = pattern.endsWith('/')
            pattern = pattern.removeSuffix("/")
            val rooted = pattern.startsWith('/') || '/' in pattern
            pattern = pattern.removePrefix("/")
            if (pattern.isEmpty()) return@mapNotNull null
            Rule(directory, Regex((if (rooted) "^" else "^(?:.*/)?") + glob(pattern) + "$"), directoryOnly, negate)
        }.toList()
    }

    private fun glob(pattern: String): String = buildString {
        var i = 0
        while (i < pattern.length) {
            when (val c = pattern[i]) {
                '*' -> if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                    i++
                    if (i + 1 < pattern.length && pattern[i + 1] == '/') { append("(?:.*/)?"); i++ }
                    else append(".*")
                } else append("[^/]*")
                '?' -> append("[^/]")
                '[' -> {
                    val end = pattern.indexOf(']', i + 1)
                    require(end > i + 1) { "Unsupported gitignore character class" }
                    val content = pattern.substring(i + 1, end)
                    require(content.none { it == '[' || it == '&' || it == '/' })
                    append('[')
                    append(if (content.startsWith('!')) "^" + content.drop(1) else content)
                    append(']'); i = end
                }
                else -> append(Regex.escape(c.toString()))
            }
            i++
        }
    }
}
