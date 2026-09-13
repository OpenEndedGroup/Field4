package afield.server

import afield.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** One bound for every Markdown the viewer carries, whether it comes from a file or a box property. */
private const val MAX_BYTES = 1024 * 1024

private fun revision(bytes: ByteArray) = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** The title shown in the viewer's list: the first level-one heading, if the Markdown offers one. */
private fun headingTitle(lines: Sequence<String>): String? =
    lines.take(128).firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()?.take(160)?.ifBlank { null }

/** Files remain authoritative: no copied Markdown property or hidden box is needed to read a guide. */
internal class DocumentGuideFiles(root: File) {
    private val root = root.canonicalFile

    fun resolve(path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute) { "Expected a document-relative path" }
        val file = File(root, path).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file.isFile) { "File is missing or outside this document" }
        return file
    }

    fun list(): List<DocumentGuideEntry> {
        // Keep this bounded and predictable. Don't descend into histories, dependencies or nested documents.
        val files = root.listFiles().orEmpty().filter { it.isFile }.toMutableList()
        for (name in listOf("docs", "guides")) {
            val directory = File(root, name)
            if (directory.isDirectory) files += directory.walkTopDown().maxDepth(4)
                .onEnter { !it.name.startsWith('.') && !File(it, "metadata.edn").exists() && it.canonicalFile.toPath().startsWith(root.toPath()) }
                .filter { it.isFile }.take(256).toList()
        }
        return files.asSequence().filter { isGuideName(it.name) }.mapNotNull { file ->
            runCatching {
                val path = root.toPath().relativize(file.toPath()).toString()
                val target = resolve(path)
                require(target.length() <= MAX_BYTES)
                val title = target.bufferedReader().use { headingTitle(it.lineSequence()) }
                    ?: file.nameWithoutExtension.replace('_', ' ')
                DocumentGuideEntry(path, title)
            }.getOrNull()
        }.sortedWith(compareBy<DocumentGuideEntry> { !it.path.equals("README.md", true) }.thenBy { it.path.lowercase() })
            .take(128).toList()
    }

    fun read(path: String): DocumentGuideFile {
        val file = guideTarget(path)
        require(file.isFile) { "Guide is missing: $path" }
        require(file.length() <= MAX_BYTES) { "Guide exceeds the 1 MiB limit" }
        val bytes = file.readBytes()
        require(bytes.size <= MAX_BYTES) { "Guide exceeds the 1 MiB limit" }
        return DocumentGuideFile(path, bytes.toString(Charsets.UTF_8), revision(bytes))
    }

    fun save(path: String, expected: String, contents: String, readOnly: Boolean): DocumentGuideFile = synchronized(writeLock) {
        check(!readOnly) { "This document is read-only" }
        val bytes = contents.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Guide exceeds the 1 MiB limit" }
        val current = read(path)
        if (current.revision != expected) throw GuideConflict()
        val target = resolve(path).toPath()
        val temporary = Files.createTempFile(target.parent, ".guide-", ".tmp")
        try {
            Files.write(temporary, bytes)
            // Recheck after writing the temporary file: an external editor may have saved meanwhile.
            if (read(path).revision != expected) throw GuideConflict()
            runCatching { Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(target)) }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        DocumentGuideFile(path, contents, revision(bytes))
    }

    /** The same namespace for discovery, reader and tools; assets use the broader [resolve]. */
    private fun guideTarget(path: String): File {
        val parts = path.split('/')
        require(path.length <= 512 && !path.contains('\\') && parts.all { it.isNotBlank() && it != "." && it != ".." && !it.startsWith('.') }) {
            "Expected a relative guide path without hidden or parent directories"
        }
        require(isGuideName(parts.last()) && (parts.size == 1 || (parts.first() in setOf("docs", "guides") && parts.size <= 5))) {
            "Use a Markdown guide in the document root, docs/ or guides/; instruction files and property sidecars are excluded"
        }
        val file = File(root, path).canonicalFile
        require(file.toPath().startsWith(root.toPath())) { "Guide is outside this document" }
        var parent = File(root, path).parentFile
        while (parent != root && parent != null) {
            require(!File(parent, "metadata.edn").exists()) { "Nested documents have their own guides" }
            parent = parent.parentFile
        }
        return file
    }

    /** Publish a complete new file, atomically, only if the name is still absent. */
    fun create(path: String, contents: String, readOnly: Boolean): DocumentGuideFile = synchronized(writeLock) {
        check(!readOnly) { "This document is read-only" }
        val target = guideTarget(path).toPath()
        val bytes = contents.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Guide exceeds the 1 MiB limit" }
        if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw GuideConflict()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".guide-", ".tmp")
        try {
            Files.write(temporary, bytes)
            // A hard link gives no-clobber publication; ATOMIC_MOVE may replace an existing target.
            try { Files.createLink(target, temporary) }
            catch (_: java.nio.file.FileAlreadyExistsException) { throw GuideConflict() }
        } finally { Files.deleteIfExists(temporary) }
        DocumentGuideFile(path, contents, revision(bytes))
    }

    fun patch(path: String, expected: String, oldText: String, newText: String, readOnly: Boolean): DocumentGuideFile = synchronized(writeLock) {
        check(!readOnly) { "This document is read-only" }
        val current = read(path)
        if (current.revision != expected) throw GuideConflict()
        require(oldText.isNotEmpty()) { "oldText must identify one existing passage" }
        val at = current.contents.indexOf(oldText)
        require(at >= 0 && current.contents.indexOf(oldText, at + 1) < 0) { "oldText must match exactly once; read more context and retry" }
        save(path, expected, current.contents.replaceRange(at, at + oldText.length, newText), readOnly)
    }

    fun append(path: String, expected: String, text: String, readOnly: Boolean): DocumentGuideFile = synchronized(writeLock) {
        check(!readOnly) { "This document is read-only" }
        val current = read(path)
        if (current.revision != expected) throw GuideConflict()
        save(path, expected, current.contents + text, readOnly)
    }

    companion object {
        private val writeLock = Any()
        internal fun isGuideName(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown") &&
                !name.startsWith('.') && !name.substringBeforeLast('.').endsWith('_') &&
                name.lowercase() !in setOf("agents.md", "claude.md", "gemini.md", "document.md")
    }
}

internal class GuideConflict : IllegalStateException("The file changed on disk. Your draft is preserved; reload the disk version or copy your draft before merging.")

/**
 * A document's `notes` properties, collected as read-only companions to the Markdown files.
 *
 * `notes` is already Markdown — it persists as a `_notes_.md` sidecar and has its own preview in
 * the box editor — and it is where humans and agents leave intent in prose, so the viewer is the
 * natural place to read all of a document's notes at once. Writing them stays with the box, which
 * keeps one write path (and one transcript entry) for the property.
 */
internal class DocumentGuideNotes(private val boxes: Collection<Box>) {
    fun list(): List<DocumentGuideEntry> = boxes.asSequence()
        .mapNotNull { box -> text(box)?.let { box to it } }
        .map { (box, notes) ->
            DocumentGuideEntry(documentGuideNotePath(box.id), title(box, notes), kind = "note", uid = box.id)
        }
        .sortedBy { it.title.lowercase() }
        .take(128).toList()

    fun read(path: String): DocumentGuideFile {
        val uid = documentGuideNoteUid(path) ?: error("Expected a box note path")
        val box = boxes.firstOrNull { it.id == uid } ?: error("Box '$uid' is not in this document")
        val notes = text(box) ?: error("Box '${box.name.ifBlank { uid }}' has no notes")
        return DocumentGuideFile(path, notes, revision(notes.toByteArray(Charsets.UTF_8)), kind = "note", uid = uid)
    }

    private fun text(box: Box): String? {
        if (box.getPropertyRaw("_tombstone").isTrue()) return null
        val notes = box.getPropertyRaw("notes") as? String ?: return null
        // The bound the files honour, for the same reason: past a megabyte this is a document, not a note.
        return notes.takeIf { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES }
    }

    private fun title(box: Box, notes: String): String =
        headingTitle(notes.lineSequence()) ?: box.name.ifBlank { null } ?: box.id
}

@RunsAt(installHandlers)
fun documentGuideHandlers() {
    handlers["document-guide"] = mutableSetOf({ message, reply ->
        val info = thisSessionInfo.get()
        val readOnly = ReadOnlyDocuments.blocksWritesFromCurrentSession()
        val result = try {
            val request = Json.decodeFromJsonElement<DocumentGuideRequest>(message.payload!!)
            val files = DocumentGuideFiles(File(info.filename))
            val notes = DocumentGuideNotes(info.boxes.values.toList())
            // Notes are read here and written on their box, so every writing action refuses one.
            val isNote = documentGuideNoteUid(request.path) != null
            val onTheBox = "Box notes are edited on the box itself"
            when (request.action) {
                "list" -> DocumentGuideResponse(entries = files.list() + notes.list())
                "read" -> DocumentGuideResponse(file = if (isNote) notes.read(request.path) else files.read(request.path))
                "save" -> {
                    check(!isNote) { onTheBox }
                    val saved = files.save(request.path, request.revision, request.contents, readOnly)
                    refreshFileProxyBoxes(info)
                    DocumentGuideResponse(file = saved)
                }
                "create" -> {
                    check(!isNote) { onTheBox }
                    val created = files.create(request.path, request.contents, readOnly)
                    DocumentGuideResponse(file = created, entries = files.list() + notes.list())
                }
                "pin" -> {
                    check(!readOnly) { "This document is read-only" }
                    check(!isNote) { "$onTheBox; it is already on the canvas" }
                    files.read(request.path)
                    val target = files.resolve(request.path)
                    val existing = info.boxes.values.firstOrNull {
                        it.getPropertyRaw("file_proxy_mode") == "document" &&
                            it.getPropertyRaw("file_proxy_path") == request.path && it.getPropertyRaw("_tombstone") != true
                    }
                    val uid = existing?.id ?: createFileProxyBox(CreateFileProxyBox(
                        mode = "document", path = target.absolutePath, fileName = target.name, x = request.x, y = request.y
                    )).uid
                    DocumentGuideResponse(uid = uid)
                }
                else -> error("Unknown guide operation")
            }
        } catch (e: Exception) {
            DocumentGuideResponse(ok = false, error = e.message ?: "Could not open guide", conflict = e is GuideConflict)
        }
        reply(Message(message.replyTo ?: "document-guide").payload(result.copy(document = info.filename, readOnly = readOnly)))
        Handled.yes
    })
}

/** Assets are served only from an already-open document, with the same canonical-path boundary as reads. */
fun Route.installDocumentGuideRoutes() {
    get("/api/document-guide-asset") {
        val key = call.request.queryParameters["document"]
        val info = documents.values.firstOrNull { it.filename == key }
        val path = call.request.queryParameters["path"].orEmpty()
        val file = info?.let { runCatching { DocumentGuideFiles(File(it.filename)).resolve(path) }.getOrNull() }
        if (file == null || file.extension.lowercase() !in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "avif") || file.length() > 20 * 1024 * 1024) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.response.headers.append("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.respondFile(file)
        }
    }
}
