package afield.logger

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.format.DateTimeFormatter

object SaveReloadLog {
    private val logPath: Path = Paths.get(System.getProperty("user.home"), ".afield", "logs", "save_reload.log")
    private val lock = Any()
    private val formatter = DateTimeFormatter.ISO_INSTANT

    fun elapsedMillis(startedAtNs: Long): Long =
        (System.nanoTime() - startedAtNs) / 1_000_000L

    fun logTiming(event: String, startedAtNs: Long, message: String, thresholdMs: Long = 0L): Long {
        val elapsedMs = elapsedMillis(startedAtNs)
        if (elapsedMs >= thresholdMs) {
            log(event, "ms=$elapsedMs $message")
        }
        return elapsedMs
    }

    fun log(event: String, message: String) {
        val line = buildString {
            append(formatter.format(Instant.now()))
            append(" [")
            append(event)
            append("] ")
            append(message)
            append('\n')
        }
        synchronized(lock) {
            Files.createDirectories(logPath.parent)
            Files.writeString(
                logPath,
                line,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        }
    }
}
