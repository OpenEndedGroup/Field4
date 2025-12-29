package afield.ai.core.logging

import afield.notify
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class LogEvent(
    val channel: String,
    val message: String,
    val timestamp: Instant = Instant.now(),
    val indent: String = "",
    val blockId: String? = null,
    val boundary: BlockBoundary? = null
)

enum class BlockBoundary { BEGIN, END }

fun interface LogSink {
    fun log(event: LogEvent)
}

fun LogSink.log(
    channel: String,
    message: String,
    indent: String = "",
    blockId: String? = null,
    boundary: BlockBoundary? = null
) = log(LogEvent(channel = channel, message = message, indent = indent, blockId = blockId, boundary = boundary))

object LogDispatcher {
    private val sinks = CopyOnWriteArrayList<LogSink>().apply { add(StdoutLog) }

    fun setSinks(newSinks: List<LogSink>) {
        sinks.clear()
        sinks.addAll(newSinks)
    }

    fun addSink(sink: LogSink) {
        sinks.add(sink)
    }

    fun log(event: LogEvent) {
        sinks.forEach { sink ->
            runCatching { sink.log(event) }
        }
    }

    fun log(
        channel: String,
        message: String,
        indent: String = "",
        blockId: String? = null,
        boundary: BlockBoundary? = null
    ) = log(LogEvent(channel = channel, message = message, indent = indent, blockId = blockId, boundary = boundary))
}

object StdoutLog : LogSink {
    override fun log(event: LogEvent) {
        val prefix = ""
        val channelPart = if (event.channel.isNotBlank()) "[${event.channel}] " else ""
        val boundaryMarker = when (event.boundary) {
            BlockBoundary.BEGIN -> ">|> "
            BlockBoundary.END -> "<|< "
            null -> ""
        }
        val full = prefix + channelPart + event.indent + boundaryMarker + event.message
        println(full)
    }
}

class NotifyLog(
    private val label: String = "AI",
    private val prefix: String = "AI4",
    private val maxLen: Int = 200,
    private val dedupeWindowMs: Long = 1000
) : LogSink {
    override fun log(event: LogEvent) {
        StdoutLog.log(event)
        val cleaned = event.message.replace(Regex("\\s+"), " ").trim()
        val clipped = if (cleaned.length <= maxLen) cleaned else cleaned.substring(0, maxLen) + "..."
        val key = "$label|$prefix|${event.channel}|$clipped"
        val now = System.currentTimeMillis()
        val prev = lastNotification.put(key, now)
        if (prev != null && now - prev < dedupeWindowMs) return
        notify("$prefix: [${event.channel}] $clipped", label)
    }

    companion object {
        private val lastNotification = ConcurrentHashMap<String, Long>()
    }
}

object SilentLog : LogSink {
    override fun log(event: LogEvent) {
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
