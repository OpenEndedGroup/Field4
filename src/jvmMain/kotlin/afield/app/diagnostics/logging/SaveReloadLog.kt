/**
 * A timestamped append-only log of document save and reload events, written to
 * `~/.afield/logs/save_reload.log`.
 *
 * Each line is an ISO instant, the event name in brackets and a message. `logTiming` adds the
 * elapsed milliseconds since a `System.nanoTime()` start and skips events faster than its threshold.
 * The file rolls over to `save_reload.log.1` once it reaches 50 MB. Every line also goes to
 * [LogDispatcher] on `info,persist,savereload`, so the log widget and other sinks see it; the
 * console mutes `info,persist` by default.
 */

package afield.app.diagnostics.logging

import afield.ai.diagnostics.LogDispatcher
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.format.DateTimeFormatter

object SaveReloadLog {
    private val logPath: Path = Paths.get(System.getProperty("user.home"), ".afield", "logs", "save_reload.log")
    private val rolledPath: Path = logPath.resolveSibling("save_reload.log.1")
    private const val maxBytes = 50L * 1024 * 1024
    private val lock = Any()
    private val formatter = DateTimeFormatter.ISO_INSTANT
    const val CHANNEL = "info,persist,savereload"

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
        LogDispatcher.log(CHANNEL, "[$event] $message")
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
            // Roll to save_reload.log.1 past the cap; this file once grew to 2 GB unbounded.
            if (Files.exists(logPath) && Files.size(logPath) >= maxBytes) {
                Files.move(logPath, rolledPath, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.writeString(
                logPath,
                line,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        }
    }
}
