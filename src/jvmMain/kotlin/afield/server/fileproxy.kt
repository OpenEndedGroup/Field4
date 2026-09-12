package afield.server

import afield.Box
import afield.CreateFileProxyBox
import afield.CreateFileProxyBoxResponse
import afield.Handled
import afield.Message
import afield.ReadOnlyDocuments
import afield.Rect
import afield.RunsAt
import afield.Set_string
import afield.box
import afield.doResync
import afield.handlers
import afield.home
import afield.installHandlers
import afield.needsResync
import afield.notify
import afield.payload
import afield.thisSessionInfo
import afield.logger.lprintln
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import kotlin.io.path.pathString

private const val FILE_PROXY_DEFAULT_WIDTH = 240.0
private const val FILE_PROXY_DEFAULT_HEIGHT = 90.0
private const val MAX_TEXT_PROXY_BYTES = 10L * 1024L * 1024L
private const val FILE_PROXY_CUSTOMIZATION = "this.classList.add(\"file-proxy-box\"); this.style.backgroundColor = \"var(--field-bg)\""

private val languageByExtension = mapOf(
    "kt" to "kotlin",
    "kts" to "kotlin",
    "java" to "java",
    "js" to "javascript",
    "mjs" to "javascript",
    "cjs" to "javascript",
    "ts" to "typescript",
    "tsx" to "typescript",
    "jsx" to "javascript",
    "json" to "json",
    "py" to "python",
    "clj" to "clojure",
    "edn" to "clojure",
    "html" to "html",
    "css" to "css",
    "md" to "markdown",
    "markdown" to "markdown",
    "yml" to "yaml",
    "yaml" to "yaml",
    "xml" to "xml",
    "sh" to "shell",
    "bash" to "shell",
    "zsh" to "shell",
    "glsl" to "glsl",
    "frag" to "glsl",
    "vert" to "glsl",
    "comp" to "glsl",
    "slang" to "slang",
    "metal" to "msl",
    "msl" to "msl",
    "txt" to "plaintext",
    "csv" to "plaintext",
    "tsv" to "plaintext",
    "log" to "plaintext"
)

private fun sanitizeDroppedFileName(name: String): String {
    val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\u0000/\\\\]"), "_")
        .trim()
    return cleaned.ifBlank { "dropped-file" }
}

private fun expandHome(path: String): String =
    if (path == "~") System.getProperty("user.home") else path.replaceFirst("~/", System.getProperty("user.home") + "/")

private fun canonicalStartsWith(file: File, parent: File): Boolean =
    runCatching { file.canonicalFile.toPath().startsWith(parent.canonicalFile.toPath()) }.getOrDefault(false)

private fun relativePath(parent: File, child: File): String =
    parent.canonicalFile.toPath().relativize(child.canonicalFile.toPath()).pathString

private fun sameBytes(file: File, bytes: ByteArray): Boolean =
    file.isFile && file.length() == bytes.size.toLong() && runCatching { file.readBytes().contentEquals(bytes) }.getOrDefault(false)

private fun uniqueDestination(directory: File, fileName: String, bytes: ByteArray): File {
    val cleanName = sanitizeDroppedFileName(fileName)
    val dot = cleanName.lastIndexOf('.')
    val stem = if (dot > 0) cleanName.substring(0, dot) else cleanName
    val extension = if (dot > 0) cleanName.substring(dot) else ""
    var candidate = File(directory, cleanName)
    var index = 2
    while (candidate.exists() && !sameBytes(candidate, bytes)) {
        candidate = File(directory, "$stem $index$extension")
        index++
    }
    return candidate
}

private fun languageForFile(fileName: String): String {
    val ext = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return languageByExtension[ext] ?: "plaintext"
}

private fun looksBinary(bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return false
    val sample = bytes.take(4096)
    if (sample.any { it.toInt() == 0 }) return true
    val suspicious = sample.count { byte ->
        val c = byte.toInt() and 0xff
        c < 0x09 || (c > 0x0d && c < 0x20)
    }
    return suspicious > sample.size / 20
}

private fun decodeText(bytes: ByteArray): String? {
    if (looksBinary(bytes)) return null
    return runCatching {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrElse {
        runCatching { bytes.toString(StandardCharsets.ISO_8859_1) }.getOrNull()
    }
}

private fun readProxyText(file: File): Pair<String, String>? {
    if (!file.isFile || file.length() > MAX_TEXT_PROXY_BYTES) return null
    val text = decodeText(file.readBytes()) ?: return null
    return text to languageForFile(file.name)
}

private fun Box.isFileProxy(): Boolean =
    getPropertyRaw("file_proxy_mode") is String && getPropertyRaw("file_proxy_path") is String

private fun resolveFileProxyTarget(box: Box): File? {
    val mode = box.getPropertyRaw("file_proxy_mode") as? String ?: return null
    val path = box.getPropertyRaw("file_proxy_path") as? String ?: return null
    val info = if (box.isInsideInitialized()) box.inside else thisSessionInfo.get()
    return when (mode) {
        "absolute" -> File(expandHome(path))
        "workspace" -> File(home, path).also {
            require(canonicalStartsWith(it, File(home))) { "workspace proxy escapes workspace: $path" }
        }
        "document" -> File(info.filename, path).also {
            require(canonicalStartsWith(it, File(info.filename))) { "document proxy escapes document folder: $path" }
        }
        else -> null
    }
}

fun refreshFileProxyBox(box: Box, notifyMissing: Boolean = false) {
    if (!box.isFileProxy()) return
    val target = try {
        resolveFileProxyTarget(box)
    } catch (e: Exception) {
        box.setPropertyRaw("file_proxy_error", e.message ?: "invalid file proxy")
        return
    } ?: return

    if (!target.exists()) {
        val msg = "File proxy target is missing: ${target.path}"
        box.setPropertyRaw("file_proxy_error", msg)
        if (notifyMissing) notify(msg, "error")
        return
    }

    if ((box.getPropertyRaw("file_proxy_error") as? String).orEmpty().isNotBlank()) {
        box.setPropertyRaw("file_proxy_error", "")
    }
    val text = readProxyText(target)
    if (text != null) {
        val (contents, language) = text
        box.setPropertyRaw("contents", contents)
        box.setPropertyRaw("contents_language", language)
        box.setPropertyRaw("default_property", "contents")
    }
}

fun refreshFileProxyBoxes(info: afield.SessionInformation, notifyMissing: Boolean = false) {
    info.boxes.values.forEach { refreshFileProxyBox(it, notifyMissing = notifyMissing) }
}

/**
 * What [writeFileProxyContents] did, for a caller that has to tell the cases apart.
 *
 * This used to be a `Boolean`, and its one caller — the `set-string` handler in `Server.kt` —
 * ignored it. That was survivable while `false` had a single meaning, but a read-only refusal
 * cannot be reported as `false`: the handler calls this for *every* box whose edited property
 * happens to be named `contents`, so "this box is not a file proxy" is the common, expected answer
 * and a refusal returned the same way would be invisible.
 */
enum class FileProxyWriteResult {
    /** Not a file-proxy box; the caller should carry on with the ordinary property write. */
    NotAFileProxy,

    /** A file-proxy box, but the session is read-only. Nothing was written. */
    Refused,

    /** The target file was rewritten. */
    Written
}

/**
 * Write [contents] through to the file a proxy box stands for.
 *
 * Refuses in a read-only session. All three proxy modes are refused, not only `document`: an
 * `absolute` or `workspace` proxy writes somewhere the user did not open, which is *worse* than
 * writing inside the document, and the read-only promise the client now displays is "nothing you
 * change here will be saved" rather than "nothing inside this folder will be saved".
 */
fun writeFileProxyContents(box: Box, contents: String): FileProxyWriteResult {
    if (!box.isFileProxy()) return FileProxyWriteResult.NotAFileProxy
    val target = resolveFileProxyTarget(box) ?: return FileProxyWriteResult.NotAFileProxy
    if (ReadOnlyDocuments.blocksWritesFrom(box)) {
        ReadOnlyDocuments.refuseWrite("file-proxy-contents", target.path)
        return FileProxyWriteResult.Refused
    }
    if (!target.exists()) {
        throw IllegalStateException("file proxy target is missing: ${target.path}")
    }
    target.writeText(contents)
    box.setPropertyRaw("contents_language", languageForFile(target.name))
    return FileProxyWriteResult.Written
}

internal data class CreatedProxy(
    val mode: String,
    val storedPath: String,
    val target: File,
    val originalPath: String?
)

/**
 * `internal` rather than `private` so `ReadOnlyWriteSurfaceTest` can drive it directly.
 *
 * The alternative was to test it through `handlers["create-file-proxy-box"]`, which drags in the
 * `box()` factory, `doResync`, and a live session's socket set to assert one `writeBytes`.
 */
internal fun createProxyTarget(request: CreateFileProxyBox): CreatedProxy {
    val info = thisSessionInfo.get()
    val documentDir = File(info.filename)
    // Refused by throwing, and refused for every mode. Throwing because the contract is to return a
    // proxy that exists: handing back a plausible-looking `CreatedProxy` would put a box on the
    // canvas pointing at a file that was never written, which is a worse lie than an error. Every
    // mode because `absolute` and `workspace` create nothing but do bind the document to a path
    // outside it, and that binding is a document edit the session is not allowed to make.
    if (ReadOnlyDocuments.blocksWritesFromCurrentSession()) {
        ReadOnlyDocuments.refuseWrite("file-proxy-create", File(documentDir, request.fileName).path)
        throw IllegalStateException(
            "this document is open read-only, so dropped files are not saved into it: ${request.fileName}"
        )
    }
    val workspaceDir = File(home)
    val sourcePath = request.path?.trim()?.takeIf { it.isNotEmpty() }?.let { File(expandHome(it)) }
    val bytes = request.contentsBase64?.let { Base64.getDecoder().decode(it) }

    return when (request.mode) {
        "absolute" -> {
            val target = sourcePath ?: throw IllegalArgumentException("absolute proxy needs a filesystem path")
            require(target.isAbsolute) { "absolute proxy path must be absolute" }
            require(target.exists()) { "file does not exist: ${target.path}" }
            CreatedProxy("absolute", target.absolutePath, target, target.absolutePath)
        }

        "workspace" -> {
            val path = request.path?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("workspace proxy needs a relative path")
            val target = File(workspaceDir, path)
            require(canonicalStartsWith(target, workspaceDir)) { "workspace proxy escapes workspace" }
            require(target.exists()) { "file does not exist: ${target.path}" }
            CreatedProxy("workspace", path, target, sourcePath?.absolutePath)
        }

        "document", "document-copy" -> {
            documentDir.mkdirs()
            if (sourcePath != null && sourcePath.exists() && canonicalStartsWith(sourcePath, documentDir)) {
                CreatedProxy("document", relativePath(documentDir, sourcePath), sourcePath, sourcePath.absolutePath)
            } else {
                val data = bytes ?: sourcePath?.takeIf { it.exists() }?.readBytes()
                    ?: throw IllegalArgumentException("copying into the document needs dropped file bytes")
                val destination = uniqueDestination(documentDir, request.fileName, data)
                if (!destination.exists()) {
                    destination.writeBytes(data)
                }
                CreatedProxy("document", relativePath(documentDir, destination), destination, sourcePath?.absolutePath)
            }
        }

        else -> throw IllegalArgumentException("unknown file proxy mode: ${request.mode}")
    }
}

internal fun createFileProxyBox(request: CreateFileProxyBox): CreateFileProxyBoxResponse {
    val createdProxy = createProxyTarget(request)
    val info = thisSessionInfo.get()
    val uid = UUID.randomUUID().toString()
    val created = box(uid, createIfMissing = true, initIfCreated = true, root = info)

    created.name = sanitizeDroppedFileName(request.fileName)
    created.frame = Rect(request.x, request.y, FILE_PROXY_DEFAULT_WIDTH, FILE_PROXY_DEFAULT_HEIGHT)
    created.setPropertyRaw("file_proxy_mode", createdProxy.mode)
    created.setPropertyRaw("file_proxy_path", createdProxy.storedPath)
    createdProxy.originalPath?.let { created.setPropertyRaw("file_proxy_original_path", it) }
    request.mimeType?.takeIf { it.isNotBlank() }?.let { created.setPropertyRaw("file_proxy_mime", it) }
    created.setPropertyRaw("default_property", "contents")
    created.setPropertyRaw("customization", FILE_PROXY_CUSTOMIZATION)
    refreshFileProxyBox(created, notifyMissing = true)

    needsResync.add(info.root to "children")
    needsResync.add(created to "parents")
    needsResync.add(created to "actions")
    created.resyncAll()
    info.markSidecarPending(uid, "file_proxy_path")
    info.topologyGeneration++
    doResync()
    return CreateFileProxyBoxResponse(ok = true, uid = uid, message = "Created file proxy for ${createdProxy.target.path}", mode = createdProxy.mode, path = createdProxy.storedPath)
}

@RunsAt(installHandlers)
fun fileProxyHandlers() {
    handlers["create-file-proxy-box"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<CreateFileProxyBox>(message.payload!!)
        try {
            val response = createFileProxyBox(request)
            reply(
                Message(message.replyTo ?: "create-file-proxy-box").payload(
                    response
                )
            )
            Handled.yes
        } catch (e: Exception) {
            lprintln("error,file-proxy", "failed to create file proxy: ${e.message}")
            notify(e.message ?: "Failed to create file proxy", "error")
            reply(
                Message(message.replyTo ?: "create-file-proxy-box").payload(
                    CreateFileProxyBoxResponse(ok = false, message = e.message ?: "Failed to create file proxy")
                )
            )
            Handled.yes
        }
    })
}
