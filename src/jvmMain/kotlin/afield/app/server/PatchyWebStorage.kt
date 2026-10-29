package afield.app.server

import afield.data.table.div
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.box
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem

import afield.app.protocol.computeIfAbsent
import afield.app.protocol.put
import afield.audio.synth.*
import afield.data.table.get
import afield.document.workspace.Entry
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.tools.docs.format
import afield.ui.notation.with
import afield.ui.output.OutputRequestBinding
import afield.app.server.SEC_FETCH_SITE_HEADER
import afield.app.server.isSameSiteRequest
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The HTTP side of a Patchy editor that edits a `.psd` on disk: one route pair, addressed by an
 * opaque output-binding id rather than by a path.
 *
 * [patchyWeb] registers a binding when a box opens a document file and drops it again when the run
 * stops, so the browser never names a path and a stale id resolves to nothing. Registration refuses
 * anything that is not a `.psd` inside that document's own `files` directory, both requests are
 * refused unless they are same-site, and a write to a read-only document is refused as well.
 *
 * Writes are compare-and-swap on the file's content hash. A GET returns the hash with the bytes, a
 * PUT has to send the hash it is replacing, and a PUT whose hash does not match the file on disk is
 * answered with a conflict rather than overwriting whatever arrived in the meantime. The new bytes
 * have to open with a PSD signature and fit the size limit, and they land through a temporary file
 * moved into place rather than by writing over the original.
 */

/** Opaque output-binding IDs are the only browser address for a box's PSD file. */
internal object PatchyWebStorage {
    private data class Entry(val binding: OutputRequestBinding, val document: File, val file: File)
    private const val maxBytes = 512L * 1024 * 1024
    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Any>()

    fun register(binding: OutputRequestBinding, document: File, file: File) {
        val root = File(document, "files").canonicalFile.toPath()
        val target = file.canonicalFile.toPath()
        require(target.startsWith(root) && target != root && target.fileName.toString().endsWith(".psd", true)) {
            "Patchy documentFile must be a .psd inside this document's files/ directory (try \"%document/patchy.psd\".file)"
        }
        entries[binding.id] = Entry(binding, document.canonicalFile, target.toFile())
    }

    fun unregister(id: String) { entries.remove(id) }

    private fun entry(id: String?): Entry? = entries[id]?.takeIf { runCatching { it.binding.requireLive() }.isSuccess }
    private fun revision(file: File): String = if (!file.isFile) "missing" else
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun patchySameSite(secFetchSite: String?, origin: String?, host: String?): Boolean =
        isSameSiteRequest(secFetchSite, origin, host)

    fun routes(route: Route) = with(route) {
        route("/api/patchy-document/{binding}") {
            get {
                if (!patchySameSite(call.request.headers[SEC_FETCH_SITE_HEADER], call.request.headers[HttpHeaders.Origin], call.request.headers[HttpHeaders.Host])) {
                    call.respond(HttpStatusCode.Forbidden); return@get
                }
                val entry = entry(call.parameters["binding"])
                    ?: run { call.respond(HttpStatusCode.NotFound); return@get }
                val data = withContext(Dispatchers.IO) {
                    synchronized(locks.computeIfAbsent(entry.file.path) { Any() }) {
                        if (!entry.file.isFile || entry.file.length() > maxBytes) null
                        else entry.file.readBytes().let { it to revision(entry.file) }
                    }
                }
                if (data == null) {
                    call.response.header("X-Patchy-Revision", "missing")
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.response.header("X-Patchy-Revision", data.second)
                    call.respondBytes(data.first, ContentType.Application.OctetStream)
                }
            }
            put {
                if (!patchySameSite(call.request.headers[SEC_FETCH_SITE_HEADER], call.request.headers[HttpHeaders.Origin], call.request.headers[HttpHeaders.Host])) {
                    call.respond(HttpStatusCode.Forbidden); return@put
                }
                val entry = entry(call.parameters["binding"])
                    ?: run { call.respond(HttpStatusCode.NotFound); return@put }
                if (ReadOnlyDocuments.blocksWritesTo(entry.document.path)) {
                    call.respond(HttpStatusCode.Forbidden, "Document is read-only"); return@put
                }
                val expected = call.request.headers["X-Patchy-Revision"]
                    ?: run { call.respond(HttpStatusCode(428, "Precondition Required")); return@put }
                val bytes = withContext(Dispatchers.IO) {
                    call.receiveStream().use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size().toLong() + count > maxBytes) return@withContext null
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                } ?: run { call.respond(HttpStatusCode.PayloadTooLarge); return@put }
                if (bytes.size < 4 || String(bytes, 0, 4, Charsets.US_ASCII) != "8BPS") {
                    call.respond(HttpStatusCode.BadRequest, "Expected a PSD file"); return@put
                }
                val result = withContext(Dispatchers.IO) {
                    synchronized(locks.computeIfAbsent(entry.file.path) { Any() }) {
                        if (revision(entry.file) != expected) null else {
                            entry.file.parentFile.mkdirs()
                            val temp = Files.createTempFile(entry.file.parentFile.toPath(), ".patchy-", ".psd.tmp")
                            try {
                                Files.write(temp, bytes)
                                try { Files.move(temp, entry.file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                                catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                                    Files.move(temp, entry.file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                }
                                revision(entry.file)
                            } finally { Files.deleteIfExists(temp) }
                        }
                    }
                }
                if (result == null) call.respond(HttpStatusCode.Conflict, "The PSD changed on disk")
                else {
                    call.response.header("X-Patchy-Revision", result)
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
    }
}
