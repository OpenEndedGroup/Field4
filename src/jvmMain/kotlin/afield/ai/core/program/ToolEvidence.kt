package afield.ai.core.program

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Complete tool results that are too large for a transcript, kept whole outside the model's context.
 *
 * A program's nested calls are recorded in the turn's transcript and the tool I/O log, but a result of
 * tens of kilobytes would make both unreadable, and cutting it at a fixed length leaves invalid JSON
 * that hides exactly the part of a failure the next agent needs. So a nested result over
 * [ToolProgramRunner.MAX_NESTED_RESULT_CHARS] is stored here whole, content-addressed, and the transcript
 * carries a small, valid stub with its id, SHA-256 and size; [ReadToolEvidenceRequest] reads it back.
 *
 * [store] is replaceable: a host with its own evidence store (a study's build records, say) can put
 * these there instead. The default keeps them gzipped beside the tool I/O log,
 * `~/.afield/logs/ai4_tool_io/evidence/` (`AFIELD_AI4_TOOL_EVIDENCE_PATH` or
 * `-Dafield.ai4.toolEvidence.path` to move it). Nothing deletes them yet.
 */
object ToolEvidence {

    /** Where a result was put: `id` is what [ReadToolEvidenceRequest] takes; [sha256] and [bytes] are of the UTF-8 text. */
    data class Ref(val id: String, val sha256: String, val bytes: Int)

    interface Store {
        fun put(text: String): Ref
        fun get(id: String): String?
    }

    /** gzip files named by content hash in [dir]. */
    class FileStore(private val dir: File) : Store {
        override fun put(text: String): Ref {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val sha = sha256(bytes)
            val id = ID_PREFIX + sha.take(24)
            val target = File(dir.apply { mkdirs() }, "${sha.take(24)}.json.gz")
            if (!target.exists()) {
                val tmp = File.createTempFile("evidence-", ".tmp", dir)
                try {
                    GZIPOutputStream(tmp.outputStream().buffered()).use { it.write(bytes) }
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally { tmp.delete() }
            }
            return Ref(id, sha, bytes.size)
        }

        override fun get(id: String): String? {
            val key = id.removePrefix(ID_PREFIX).takeIf { it.matches(Regex("[0-9a-f]{24}")) } ?: return null
            val file = File(dir, "$key.json.gz").takeIf { it.isFile } ?: return null
            return GZIPInputStream(file.inputStream().buffered()).use { String(it.readBytes(), Charsets.UTF_8) }
        }
    }

    const val ID_PREFIX = "ai4ev:"

    private fun defaultDir(): File {
        val raw = System.getProperty("afield.ai4.toolEvidence.path") ?: System.getenv("AFIELD_AI4_TOOL_EVIDENCE_PATH")
        return raw?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }
            ?: Paths.get(System.getProperty("user.home"), ".afield", "logs", "ai4_tool_io", "evidence").toFile()
    }

    @Volatile
    var store: Store = FileStore(defaultDir())

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

@JsonClassDescription(
    "Read back a complete tool result that was too large to keep in the conversation: a tool program's call " +
        "record names it as `evidence` (an id starting ai4ev:). Returns the text from `offset`, at most `limit` " +
        "characters, and whether there is more. Use it only when a call record carries an evidence id and you need the full result. " +
        "An id not in the evidence store fails with success=false. e.g. id = `ai4ev:3f2a`, offset = 16000."
)
@afield.ai.core.AgentToolMeta("program", exposure = afield.ai.core.ToolExposure.DEFERRED, effects = [afield.ai.core.ToolEffect.FILESYSTEM_READ])
data class ReadToolEvidenceRequest(
    @JsonPropertyDescription("The evidence id, ai4ev:…")
    val id: String,
    @JsonPropertyDescription("Character offset to start at; default 0.")
    val offset: Int = 0,
    @JsonPropertyDescription("Most characters to return; default 16000, at most 64000.")
    val limit: Int = 16_000,
)

data class ReadToolEvidenceResult(
    val success: Boolean,
    val id: String,
    val bytes: Int = 0,
    val sha256: String = "",
    val offset: Int = 0,
    val text: String = "",
    val more: Boolean = false,
    val message: String = "",
)

internal fun readToolEvidence(request: ReadToolEvidenceRequest): ReadToolEvidenceResult {
    val text = ToolEvidence.store.get(request.id.trim())
        ?: return ReadToolEvidenceResult(false, request.id, message = "No evidence with id ${request.id}.")
    val from = request.offset.coerceIn(0, text.length)
    val limit = (if (request.limit <= 0) 16_000 else request.limit).coerceAtMost(64_000)
    val to = (from + limit).coerceAtMost(text.length)
    val bytes = text.toByteArray(Charsets.UTF_8)
    return ReadToolEvidenceResult(true, request.id, bytes.size, ToolEvidence.sha256(bytes), from, text.substring(from, to), to < text.length)
}
