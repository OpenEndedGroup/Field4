@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package afield.ui.output

import afield.app.diagnostics.logging.lprintln
import afield.app.protocol.ControllerControl
import afield.app.protocol.FileCompletionEntry
import afield.app.protocol.FileCompletionReply
import afield.app.protocol.FileCompletionRequest
import afield.app.protocol.Handled
import afield.app.protocol.Message
import afield.app.protocol.handlers
import afield.app.protocol.payload
import afield.document.graph.Box
import afield.document.workspace.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.util.UUID

/**
 * Which file names a [fileChooser] offers: one or more globs, matched against the name alone and
 * ignoring case.
 *
 * `"*.png"`, `"*.{png,jpg}"`, `"*.wav, *.aif"` (commas, semicolons or spaces separate globs outside
 * braces), and a bare extension such as `"png"` or `".png"` meaning `"*.png"`. Blank or `"*"` offers
 * every file. Directories are always offered, because they are how you get to a file.
 */
class FileMask(val pattern: String) {
    private val matchers: List<PathMatcher> = splitGlobs(pattern).map { glob ->
        FileSystems.getDefault().getPathMatcher("glob:" + normalizeGlob(glob).lowercase())
    }

    /** True when [name] (a file name, not a path) is one this mask offers. */
    fun matches(name: String): Boolean {
        if (matchers.isEmpty()) return true
        val candidate = runCatching { Paths.get(name.lowercase()) }.getOrNull() ?: return false
        return matchers.any { it.matches(candidate) }
    }

    override fun toString(): String = pattern

    private companion object {
        fun splitGlobs(pattern: String): List<String> {
            val globs = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            for (c in pattern) {
                when {
                    c == '{' -> { depth++; current.append(c) }
                    c == '}' -> { depth = maxOf(0, depth - 1); current.append(c) }
                    depth == 0 && (c == ',' || c == ';' || c == '|' || c.isWhitespace()) -> {
                        if (current.isNotBlank()) globs += current.toString().trim()
                        current.clear()
                    }
                    else -> current.append(c)
                }
            }
            if (current.isNotBlank()) globs += current.toString().trim()
            // Any match-everything glob makes the whole mask match everything ("*.*" too: a name
            // with no extension is still a file someone may want).
            return if (globs.any { it == "*" || it == "*.*" }) emptyList() else globs
        }

        fun normalizeGlob(glob: String): String {
            val hasGlob = glob.any { it == '*' || it == '?' || it == '[' || it == '{' }
            return when {
                hasGlob -> glob
                glob.startsWith(".") -> "*$glob"
                !glob.contains('.') -> "*.$glob"
                else -> glob
            }
        }
    }
}

/**
 * Everything a [fileChooser] knows apart from its value: where relative paths start, which names
 * it offers, and what it accepts.
 *
 * @param root the directory a relative path is read against, and where browsing starts
 * @param mask the files offered; see [FileMask]
 * @param chooseDirectory choose a directory rather than a file; files are then not listed
 * @param mustExist accept only a path that exists; turn off to name a file you are about to write
 * @param showHidden list dot-files without the person having to type the dot
 */
class FileChooserSpec(
    val root: Path,
    val mask: FileMask = FileMask(""),
    val chooseDirectory: Boolean = false,
    val mustExist: Boolean = true,
    val showHidden: Boolean = false
) {
    private val home: Path = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize()

    /** [text] as the absolute path it names: `~/…` from home, absolute as is, otherwise from [root]. */
    fun resolve(text: String): Path {
        val t = text.trim()
        val path = when {
            t.isEmpty() -> root
            t == "~" -> home
            t.startsWith("~/") -> home.resolve(t.removePrefix("~/"))
            else -> runCatching { Paths.get(t) }.getOrNull()?.takeIf { it.isAbsolute } ?: root.resolve(t)
        }
        return path.toAbsolutePath().normalize()
    }

    /** How the chooser writes [path] in its field: relative to [root] inside it, `~/…` under home, else absolute. */
    fun display(path: Path): String {
        val p = path.toAbsolutePath().normalize()
        val r = root.toAbsolutePath().normalize()
        return when {
            p == r -> ""
            p.startsWith(r) -> r.relativize(p).toString()
            p == home -> "~"
            p.startsWith(home) -> "~/" + home.relativize(p)
            else -> p.toString()
        }
    }

    /** [path] for a heading, where relative would say nothing: `~/…` under home, else absolute. */
    fun describe(path: Path): String {
        val p = path.toAbsolutePath().normalize()
        return when {
            p == home -> "~"
            p.startsWith(home) -> "~/" + home.relativize(p)
            else -> p.toString()
        }
    }

    /** Whether [path] is something this chooser hands to its callback. */
    fun accepts(path: Path): Boolean {
        val name = path.fileName?.toString() ?: return chooseDirectory && Files.isDirectory(path)
        if (Files.exists(path)) {
            return if (chooseDirectory) Files.isDirectory(path) else Files.isRegularFile(path) && mask.matches(name)
        }
        if (mustExist) return false
        val parent = path.parent ?: return false
        return Files.isDirectory(parent) && (chooseDirectory || mask.matches(name))
    }
}

private const val maxCompletionScan = 20_000

/**
 * What [text] completes to, for the chooser [spec] — the server half of the chooser's drop-down.
 *
 * Text ending in `/` (or blank) lists that directory; otherwise the last segment is a prefix, and
 * the listing is its parent's children that match it: names starting with it (exact case first),
 * then names containing it, then names holding its letters in order. Directories come before files
 * within each rank and always match the mask; files must match it. Dot-files appear only when the
 * typed segment starts with a dot, unless [FileChooserSpec.showHidden].
 */
internal fun completeFilePath(
    spec: FileChooserSpec,
    controlId: String,
    text: String,
    seq: Int = 0,
    limit: Int = 200
): FileCompletionReply {
    val slash = text.lastIndexOf('/')
    val listsDirectory = text.isEmpty() || text.endsWith("/") || text == "~"
    val textDir = when {
        text == "~" -> "~/"
        listsDirectory -> text
        slash >= 0 -> text.substring(0, slash + 1)
        else -> ""
    }
    val prefix = if (listsDirectory) "" else text.substring(slash + 1)
    val directory = if (textDir.isEmpty()) spec.root.toAbsolutePath().normalize() else spec.resolve(textDir)
    val typed = spec.resolve(text)
    fun reply(entries: List<FileCompletionEntry> = emptyList(), truncated: Boolean = false, error: String? = null) =
        FileCompletionReply(
            controlId = controlId,
            seq = seq,
            text = text,
            directory = spec.describe(directory).let { if (it.endsWith("/")) it else "$it/" },
            entries = entries,
            truncated = truncated,
            exists = Files.exists(typed),
            choosable = text.isNotBlank() && spec.accepts(typed),
            mask = spec.mask.pattern,
            error = error
        )
    if (!Files.isDirectory(directory)) {
        return reply(error = if (Files.exists(directory)) "${spec.display(directory)} is not a directory" else "no directory ${spec.display(directory).ifEmpty { directory.toString() }}")
    }
    val showHidden = spec.showHidden || prefix.startsWith(".")
    val lower = prefix.lowercase()

    data class Candidate(val name: String, val directory: Boolean, val rank: Int, val path: Path?)

    fun rank(name: String): Int? {
        if (prefix.isEmpty()) return 0
        val n = name.lowercase()
        return when {
            name.startsWith(prefix) -> 0
            n.startsWith(lower) -> 1
            n.contains(lower) -> 2
            isSubsequence(lower, n) -> 3
            else -> null
        }
    }

    val candidates = mutableListOf<Candidate>()
    if (directory.parent != null && (prefix.isEmpty() || "..".startsWith(prefix))) {
        candidates += Candidate("..", true, 0, null)
    }
    val scanned = try {
        Files.list(directory).use { stream ->
            var count = 0
            stream.limit(maxCompletionScan.toLong()).forEach { child ->
                count++
                val name = child.fileName?.toString() ?: return@forEach
                if (name.startsWith(".") && !showHidden) return@forEach
                val isDirectory = Files.isDirectory(child)
                if (!isDirectory && (spec.chooseDirectory || !spec.mask.matches(name))) return@forEach
                val r = rank(name) ?: return@forEach
                candidates += Candidate(name, isDirectory, r, child)
            }
            count
        }
    } catch (e: IOException) {
        return reply(error = "cannot read ${spec.display(directory).ifEmpty { directory.toString() }}: ${e.message}")
    } catch (e: SecurityException) {
        return reply(error = "not allowed to read ${spec.display(directory)}")
    }

    val sorted = candidates.sortedWith(
        compareBy<Candidate> { it.rank }
            .thenBy { if (it.name == "..") 0 else 1 }
            .thenBy { if (it.directory) 0 else 1 }
            .thenComparator { a, b -> naturalCompare(a.name, b.name) }
    )
    val entries = sorted.take(limit).map { c ->
        val attributes = c.path?.takeUnless { c.directory }?.let { p ->
            runCatching { Files.size(p) to Files.getLastModifiedTime(p).toMillis() }.getOrNull()
        }
        FileCompletionEntry(
            name = c.name,
            insert = textDir + c.name + if (c.directory) "/" else "",
            directory = c.directory,
            size = attributes?.first ?: 0,
            modified = attributes?.second ?: 0
        )
    }
    return reply(entries, truncated = sorted.size > limit || scanned >= maxCompletionScan)
}

private fun isSubsequence(needle: String, haystack: String): Boolean {
    var i = 0
    for (c in haystack) if (i < needle.length && c == needle[i]) i++
    return i == needle.length
}

/** `take2` before `take10`: runs of digits compare as numbers, everything else ignoring case. */
internal fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val si = i
            val sj = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val na = a.substring(si, i).trimStart('0')
            val nb = b.substring(sj, j).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i).compareTo(b.length - j).takeIf { it != 0 } ?: a.compareTo(b)
}

// The chooser [spec] for each control id the page may ask about. Group ids are stable, so a rerun
// replaces its entry; a standalone chooser gets a fresh id per run, so the oldest are let go.
private val fileChooserSpecs = object : LinkedHashMap<String, FileChooserSpec>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FileChooserSpec>?) = size > 2048
}

private fun registerFileChooserSpec(controlId: String, spec: FileChooserSpec) {
    synchronized(fileChooserSpecs) { fileChooserSpecs[controlId] = spec }
}

internal fun fileChooserSpec(controlId: String): FileChooserSpec? = synchronized(fileChooserSpecs) { fileChooserSpecs[controlId] }

/** Registers the `file-complete` websocket request; called once from `Server.kt`. */
internal fun registerFileChooserHandler() {
    handlers["file-complete"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement(FileCompletionRequest.serializer(), message.payload!!)
        val spec = fileChooserSpec(request.controlId)
        if (spec == null) {
            reply(Message("file-complete").payload(FileCompletionReply(request.controlId, request.seq, request.text, error = "this chooser is gone; run the box again")))
        } else {
            // Off the socket's thread: a directory on a sleeping network volume can take seconds.
            GlobalScope.launch(Dispatchers.IO) {
                val answer = runCatching { completeFilePath(spec, request.controlId, request.text, request.seq) }
                    .getOrElse { FileCompletionReply(request.controlId, request.seq, request.text, error = it.message ?: it.toString()) }
                reply(Message("file-complete").payload(answer))
            }
        }
        Handled.yes
    })
}

/** The chooser's root: [directory] as a FileManager spec, read in [box]'s document. */
private fun Box.fileChooserRoot(directory: String): Path {
    val spec = directory.trim()
    val home = Paths.get(System.getProperty("user.home"))
    if (spec == "~") return home
    if (spec.startsWith("~/")) return home.resolve(spec.removePrefix("~/")).toAbsolutePath().normalize()
    runCatching { Paths.get(spec) }.getOrNull()?.takeIf { it.isAbsolute }?.let { return it.normalize() }
    return try {
        val manager = if (isInsideInitialized()) FileManager.forSession(inside) else FileManager.current()
        if (spec.isEmpty()) manager.documentFilesDirectory else manager.path(spec)
    } catch (e: Exception) {
        lprintln("warning,controls", "fileChooser: cannot resolve directory '$directory' (${e.message}); browsing from home instead")
        home
    }.toAbsolutePath().normalize()
}

private fun escapeAttribute(text: String) =
    text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

internal fun buildFileChooserHtml(controlId: String, label: String, value: String, spec: FileChooserSpec): String {
    val placeholder = when {
        spec.chooseDirectory -> "a directory"
        spec.mask.pattern.isBlank() -> "a file"
        else -> spec.mask.pattern
    }
    return """
        <div class="field-control field-text field-file" data-control-id="$controlId" data-root="${escapeAttribute(spec.display(spec.root).ifEmpty { spec.root.toString() })}" data-mask="${escapeAttribute(spec.mask.pattern)}">
            <label class="field-label" for="$controlId" style="flex:0 0 auto;">${escapeAttribute(label)}</label>
            <div class="field-file-field">
                <input type="text" class="field-text-input field-file-input" id="$controlId" value="${escapeAttribute(value)}" placeholder="${escapeAttribute(placeholder)}" spellcheck="false" autocomplete="off" autocapitalize="off" role="combobox" aria-autocomplete="list" aria-expanded="false" />
                <button type="button" class="field-file-browse" tabindex="-1" aria-label="Browse ${escapeAttribute(spec.display(spec.root).ifEmpty { "the folder" })}" title="Browse">&#x25BE;</button>
            </div>
        </div>
    """.trimIndent()
}

/**
 * The callback the page's commits reach: [text] as a file, given to [onUpdate] only when [spec]
 * accepts it. A path it does not accept is reported once on `warning,controls` and goes no further.
 */
private fun fileChooserCallback(label: String, spec: FileChooserSpec, onUpdate: (File) -> Unit): (String) -> Unit = { text ->
    if (text.isNotBlank()) {
        val path = spec.resolve(text)
        if (spec.accepts(path)) onUpdate(path.toFile())
        else lprintln("warning,controls", "fileChooser '$label': ${spec.display(path).ifEmpty { path.toString() }} is not ${chooserNoun(spec)}")
    }
}

private fun chooserNoun(spec: FileChooserSpec) = when {
    spec.chooseDirectory -> if (spec.mustExist) "an existing directory" else "a directory"
    spec.mask.pattern.isBlank() -> if (spec.mustExist) "an existing file" else "a file in an existing directory"
    else -> (if (spec.mustExist) "an existing file matching " else "a file matching ") + spec.mask.pattern
}

/**
 * Adds a file chooser to this control group: a text field that completes against the filesystem as
 * you type, instead of a native dialog.
 *
 * ```kotlin
 * var image: File? = null
 * controlGroup("input") {
 *     fileChooser("image", directory = "~/Pictures", mask = "*.{png,jpg}") { image = it }
 * }
 * ```
 *
 * Type a path, relative to [directory] or as `~/…` or absolute, and a list of what it could
 * complete to drops down. The arrow keys move through it; Tab or Enter takes an entry (a directory
 * opens, a file is chosen); Escape closes it; the ▾ button, or Alt+↓, lists the current folder.
 * Enter on a typed path, or leaving the field, commits it. [onUpdate] receives an absolute [File],
 * and only for a path the chooser accepts — one matching [mask], existing unless [mustExist] is
 * off — so the box never sees a half-typed name.
 *
 * Unlike a slider, which leaves the first value to your own variable, [onUpdate] runs on *every*
 * run in which the control holds an acceptable path: on the first run with [initialValue], and on
 * a rerun with whatever was chosen last, which the group remembers like any other control's value.
 *
 * @param label text shown beside the field
 * @param directory where relative paths start and browsing begins, as a FileManager spec:
 *   blank for this document's `files/`, `"%document/…"`, `"%name/…"` for a registered directory,
 *   `"~/…"`, or an absolute path
 * @param mask the files offered, such as `"*.png"`, `"*.{wav,aif}"` or `"csv"`; see [FileMask]
 * @param initialValue the path shown when nothing has been chosen yet, relative to [directory] or absolute
 * @param chooseDirectory choose a directory instead of a file
 * @param mustExist accept only existing paths; turn off to name a file you are about to write
 * @param showHidden list dot-files without typing the dot
 * @param tooltip what the file is for, shown on hover
 * @param onUpdate called with the chosen file
 */
fun ControlGroupBuilder.fileChooser(
    label: String,
    directory: String = "",
    mask: String = "",
    initialValue: String = "",
    chooseDirectory: Boolean = false,
    mustExist: Boolean = true,
    showHidden: Boolean = false,
    tooltip: String? = null,
    onUpdate: (File) -> Unit
): ControlValueHandle<String> {
    val spec = FileChooserSpec(ownerBox.fileChooserRoot(directory), FileMask(mask), chooseDirectory, mustExist, showHidden)
    val callback = fileChooserCallback(label, spec, onUpdate)
    return addControl(
        "file",
        label,
        build = { id, previous ->
            val value = previous?.text ?: initialValue
            registerFileChooserSpec(id, spec)
            registerStringCallback(id, callback)
            callback(value)
            buildFileChooserHtml(id, label, value, spec) to ControllerControl(
                id = id, type = "file", label = label, channel = ownerChannel, text = value
            )
        },
        read = { it.text },
        write = { old, next -> old.copy(text = next) },
        tooltip = tooltip
    )
}

/**
 * Displays a [ControlGroupBuilder.fileChooser] on its own in [channel]. Its value is not remembered
 * across runs; put it in a [controlGroup] for that.
 *
 * @param label text shown beside the field
 * @param directory where relative paths start and browsing begins, as a FileManager spec
 * @param mask the files offered; see [FileMask]
 * @param initialValue the path shown at first
 * @param chooseDirectory choose a directory instead of a file
 * @param mustExist accept only existing paths
 * @param channel output disclosure that receives the control
 * @param tooltip what the file is for, shown on hover
 * @param onUpdate called with the chosen file
 */
fun Box.fileChooser(
    label: String,
    directory: String = "",
    mask: String = "",
    initialValue: String = "",
    chooseDirectory: Boolean = false,
    mustExist: Boolean = true,
    channel: String = "controls",
    tooltip: String? = null,
    onUpdate: (File) -> Unit
) {
    val controlId = "file-${UUID.randomUUID()}"
    val spec = FileChooserSpec(fileChooserRoot(directory), FileMask(mask), chooseDirectory, mustExist)
    val callback = fileChooserCallback(label, spec, onUpdate)
    registerFileChooserSpec(controlId, spec)
    registerStringCallback(controlId, callback)
    callback(initialValue)
    display(channel, withControlTooltip(buildFileChooserHtml(controlId, label, initialValue, spec), tooltip))
}
