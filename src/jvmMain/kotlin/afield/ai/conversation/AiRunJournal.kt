package afield.ai.conversation

import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Append-only diagnostics, independent of rendered chat. Never used to replay side effects. */
class AiRunJournal(private val file: File, val run: String = UUID.randomUUID().toString()) {
    private val parent = ThreadLocal<String?>()

    fun event(type: String, fields: Map<String, String> = emptyMap()) {
        val line = buildJsonObject {
            put("version", 1); put("run", run); put("type", type)
            put("time", System.currentTimeMillis())
            parent.get()?.let { put("call", it) }
            fields.forEach { (key, value) -> put(key, value) }
        }.toString() + "\n"
        synchronized(lock) {
            file.parentFile.mkdirs()
            FileOutputStream(file, true).use { stream ->
                stream.write(line.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
        }
    }

    /** Whole context snapshots are local evidence, not injected back into any conversation. */
    fun snapshot(text: String): String = synchronized(lock) {
        val hash = sha256(text)
        val target = File(File(file.parentFile, "blobs"), "$hash.gz")
        if (!target.exists()) {
            target.parentFile.mkdirs()
            val temporary = File.createTempFile("snapshot-", ".tmp", target.parentFile)
            try {
                java.util.zip.GZIPOutputStream(temporary.outputStream()).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                java.io.RandomAccessFile(temporary, "rw").use { it.fd.sync() }
                java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
        hash
    }

    fun context(context: TurnContext?, placement: String, prompt: String, images: List<String>) {
        event("context.prepared", mapOf("placement" to placement, "prompt" to snapshot(prompt),
            "assembly" to (context?.let { snapshot(it.legacyPrompt()) } ?: "unavailable"),
            "images" to images.size.toString(), "imageReferences" to images.joinToString(",") { snapshot(it) },
            "visibility" to "Prepared input; earlier hosted-provider context is not available locally."))
        context?.let { event("context.report", mapOf("markdown" to it.report(placement).toMarkdown())) }
    }

    fun <T> tool(name: String, arguments: String, wireName: String = name, body: () -> T): T {
        val id = UUID.randomUUID().toString()
        val previous = parent.get()
        event("tool.started", mapOf("call" to id, "parent" to previous.orEmpty(), "tool" to name, "wireName" to wireName,
            "argumentsHash" to snapshot(arguments), "argumentsChars" to arguments.length.toString()))
        parent.set(id)
        val started = System.nanoTime()
        try {
            val result = body()
            // Structured verdicts are supplied by dispatch; completing is not necessarily succeeding.
            event("tool.completed", mapOf("call" to id, "elapsedMs" to ((System.nanoTime() - started) / 1_000_000).toString()))
            return result
        } catch (failure: Throwable) {
            try { event("tool.failed", mapOf("call" to id, "error" to failure.javaClass.simpleName)) }
            catch (recording: Throwable) { failure.addSuppressed(recording) }
            throw failure
        } finally { parent.set(previous) }
    }

    companion object {
        private val lock = Any()
        fun file(document: String, box: String): File = File(File(document, "ai4_events"), "${sha256(box)}.jsonl")
        fun readSnapshot(document: String, hash: String): String {
            require(hash.matches(Regex("[a-f0-9]{64}"))) { "A snapshot reference is a SHA-256 hash." }
            val source = File(File(document, "ai4_events/blobs"), "$hash.gz")
            if (!source.exists()) return "_Snapshot not found._"
            val text = java.util.zip.GZIPInputStream(source.inputStream()).bufferedReader().use { reader ->
                val chars = CharArray(32_001)
                var count = 0
                while (count < chars.size) {
                    val n = reader.read(chars, count, chars.size - count)
                    if (n < 0) break
                    count += n
                }
                String(chars, 0, count)
            }
            val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0) + 1))
            return "**Context snapshot** `$hash` (read-only; no tools are replayed)\n\n$fence\n${text.take(32_000)}\n$fence" +
                if (text.length > 32_000) "\n\n_First 32,000 characters shown; the full snapshot remains on disk._" else ""
        }

        /** A bounded tail; malformed/interrupted final writes are displayed, never repaired silently. */
        fun inspect(document: String, box: String): String = synchronized(lock) {
            val source = file(document, box)
            if (!source.exists()) return "_No durable run events yet._"
            val tail = ArrayDeque<String>()
            source.bufferedReader().useLines { lines -> lines.forEach { line ->
                if (tail.size == 100) tail.removeFirst()
                tail.addLast(line)
            } }
            buildString {
                appendLine("**Recent durable run events** (last ${tail.size}; not a replay log)")
                appendLine("\n| run | event | detail |\n| --- | --- | --- |")
                val parsed = tail.mapNotNull { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                parsed.forEach { row ->
                    fun value(key: String) = row[key]?.jsonPrimitive?.content.orEmpty()
                    val detail = listOf("tool", "success", "outcome", "reason", "mode", "error", "elapsedMs", "title", "phase")
                        .mapNotNull { key -> value(key).takeIf { it.isNotBlank() }?.let { "$key=$it" } }
                        .joinToString(", ").replace("|", "&#124;").replace("\n", " ")
                    appendLine("| ${value("run").take(8)} | `${value("type")}` | $detail |")
                    for (key in listOf("prompt", "assembly", "before", "after", "argumentsHash", "resultHash")) {
                        value(key).takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let {
                            appendLine("| | $key | `/context $it` |")
                        }
                    }
                }
                parsed.lastOrNull { it["type"]?.jsonPrimitive?.content == "context.report" }
                    ?.get("markdown")?.jsonPrimitive?.content?.let { appendLine("\n**Last recorded context report**\n\n$it") }
                if (parsed.size != tail.size) appendLine("\n_Some event lines are incomplete or unreadable._")
            }
        }
    }
}
