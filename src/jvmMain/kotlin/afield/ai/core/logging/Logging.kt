package afield.ai.core.logging

import afield.notify
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

fun interface LogSink {
    fun log(msg: String)
}

object StdoutLog : LogSink {
    override fun log(msg: String) {
        val prefix = "[OpenAiIde] "
        val full = prefix + msg
        val width = System.getenv("COLUMNS")?.toIntOrNull()?.coerceAtLeast(20) ?: 100
        val padded = if (full.length < width) " ".repeat(width - full.length) + full else full
        val ansiDarkGray = "\u001b[90m"
        val ansiReset = "\u001b[0m"
        println(ansiDarkGray + padded + ansiReset)
    }
}

class NotifyLog(
    private val label: String = "AI",
    private val prefix: String = "AI4",
    private val maxLen: Int = 200,
    private val dedupeWindowMs: Long = 1000
) : LogSink {
    override fun log(msg: String) {
        StdoutLog.log(msg)
        val cleaned = msg.replace(Regex("\\s+"), " ").trim()
        val clipped = if (cleaned.length <= maxLen) cleaned else cleaned.substring(0, maxLen) + "..."
        val key = "$label|$prefix|$clipped"
        val now = System.currentTimeMillis()
        val prev = lastNotification.put(key, now)
        if (prev != null && now - prev < dedupeWindowMs) return
        notify("$prefix: $clipped", label)
    }

    companion object {
        private val lastNotification = ConcurrentHashMap<String, Long>()
    }
}

object SilentLog : LogSink {
    override fun log(msg: String) {
        // intentionally quiet
    }
}

class TraceLogger private constructor(
    private val path: Path,
    private val maxChars: Int
) {
    private val lock = Any()

    fun log(section: String, payload: String) {
        val clipped = if (payload.length > maxChars) {
            payload.take(maxChars) + "\n... [truncated ${payload.length - maxChars} chars]"
        } else {
            payload
        }
        val entry = buildString {
            append("---- ")
            append(section)
            append(" ")
            append(Instant.now().toString())
            append("\n")
            append(clipped)
            append("\n\n")
        }
        synchronized(lock) {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            ).use { it.write(entry) }
        }
    }

    companion object {
        fun fromEnv(): TraceLogger? {
            val raw = System.getenv("AFIELD_AI4_TRACE")
                ?: System.getenv("AFIELD_AI4_TRACE_PATH")
                ?: return null
            val normalized = raw.trim()
            if (normalized.isEmpty()) return null
            if (normalized.equals("0", ignoreCase = true) ||
                normalized.equals("false", ignoreCase = true) ||
                normalized.equals("off", ignoreCase = true)
            ) {
                return null
            }
            val defaultPath = Paths.get(System.getProperty("user.home"), ".afield", "logs", "ai4_trace.log")
            val path = if (
                normalized.equals("1", ignoreCase = true) ||
                normalized.equals("true", ignoreCase = true) ||
                normalized.equals("yes", ignoreCase = true) ||
                normalized.equals("on", ignoreCase = true)
            ) {
                defaultPath
            } else {
                val p = Paths.get(normalized)
                if (p.isAbsolute) p else Paths.get(System.getProperty("user.dir")).resolve(p)
            }
            val max = System.getenv("AFIELD_AI4_TRACE_MAX")?.toIntOrNull()?.coerceAtLeast(1000) ?: 20000
            return TraceLogger(path, max)
        }
    }
}
