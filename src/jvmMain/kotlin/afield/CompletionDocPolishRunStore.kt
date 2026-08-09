package afield

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Serializable
internal data class PersistedCompletionDocPolishRun(
    val schemaVersion: Int = 1,
    val running: Boolean = false,
    val status: String = "idle",
    val runId: String = "",
    val taskName: String = "",
    val dryRun: Boolean = false,
    val startedAtEpochMs: Long = 0,
    val finishedAtEpochMs: Long = 0,
    val exitCode: Int? = null,
    val message: String = "",
    val logPath: String = ""
)

internal data class RestoredCompletionDocPolishRun(
    val state: PersistedCompletionDocPolishRun,
    val logTail: String
)

internal class CompletionDocPolishRunStore(
    logDir: Path,
    private val maxLogChars: Int
) {
    private val root = logDir.toAbsolutePath().normalize()
    private val latestStatePath = root.resolve("latest.json")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun newLogPath(startedAtEpochMs: Long, runId: String): Path {
        val stamp = LOG_STAMP.format(Instant.ofEpochMilli(startedAtEpochMs))
        val id = runId.take(8).ifBlank { "unknown" }
        return root.resolve("$stamp-$id.log")
    }

    fun append(logPath: String, text: String) {
        if (logPath.isBlank() || text.isEmpty()) return
        val path = safeLogPath(logPath) ?: error("Completion-doc polish log path is outside $root")
        Files.createDirectories(root)
        Files.writeString(
            path,
            text,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )
    }

    fun save(state: PersistedCompletionDocPolishRun) {
        Files.createDirectories(root)
        val temp = Files.createTempFile(root, "latest-", ".tmp")
        try {
            Files.writeString(temp, json.encodeToString(state), StandardCharsets.UTF_8)
            try {
                Files.move(
                    temp,
                    latestStatePath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                Files.move(temp, latestStatePath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun restore(): RestoredCompletionDocPolishRun? = runCatching {
        if (!Files.isRegularFile(latestStatePath)) return null
        val state = json.decodeFromString<PersistedCompletionDocPolishRun>(
            Files.readString(latestStatePath, StandardCharsets.UTF_8)
        )
        val tail = safeLogPath(state.logPath)
            ?.takeIf(Files::isRegularFile)
            ?.let(::readTail)
            .orEmpty()
        RestoredCompletionDocPolishRun(state, tail)
    }.getOrNull()

    private fun safeLogPath(value: String): Path? {
        if (value.isBlank()) return null
        val path = Path.of(value).toAbsolutePath().normalize()
        return path.takeIf { it.startsWith(root) }
    }

    private fun readTail(path: Path): String {
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            val wanted = minOf(size, maxLogChars.toLong() * UTF8_MAX_BYTES_PER_CHAR)
            if (wanted <= 0) return ""
            val buffer = ByteBuffer.allocate(wanted.toInt())
            channel.position(size - wanted)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) <= 0) break
            }
            buffer.flip()
            return StandardCharsets.UTF_8.decode(buffer).toString().takeLast(maxLogChars)
        }
    }

    private companion object {
        const val UTF8_MAX_BYTES_PER_CHAR = 4L
        val LOG_STAMP: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss.SSS'Z'")
            .withZone(ZoneOffset.UTC)
    }
}
