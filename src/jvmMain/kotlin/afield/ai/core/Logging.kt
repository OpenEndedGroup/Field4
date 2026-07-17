package afield.ai.core

import afield.notify
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.UUID

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

    fun removeSink(sink: LogSink) {
        sinks.remove(sink)
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
    private const val ANSI_RESET = "\u001B[0m"
    private const val ANSI_DARK_GRAY = "\u001B[90m"
    private const val ANSI_ITALIC = "\u001B[3m"

    /**
     * Channels suppressed from stdout. These events still reach every other sink (e.g. the
     * LogStream widget), so nothing is lost — they're just too chatty for the console. Override
     * with `-Dafield.log.mute=...` or `AFIELD_LOG_MUTE=...` (semicolon-separated exact channel
     * strings); set to a blank/empty value to mute nothing.
     */
    private val muted: Set<String> = run {
        val raw = System.getProperty("afield.log.mute") ?: System.getenv("AFIELD_LOG_MUTE")
        if (raw != null) {
            raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        } else {
            setOf("info,boxes")
        }
    }

    override fun log(event: LogEvent) {
        if (event.channel in muted) return
        val prefix = ""
        val channelPart = if (event.channel.isNotBlank()) {
            // Render channel tags as dark gray + italic for easier skimming in stdout
            "$ANSI_DARK_GRAY$ANSI_ITALIC[${event.channel}]$ANSI_RESET "
        } else ""
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
            val raw = System.getProperty("afield.ai4.trace")
                ?: System.getProperty("afield.ai4.trace.path")
                ?: System.getenv("AFIELD_AI4_TRACE")
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
            val max = (
                System.getProperty("afield.ai4.trace.max")
                    ?: System.getenv("AFIELD_AI4_TRACE_MAX")
            )?.toIntOrNull()?.coerceAtLeast(1000) ?: 20000
            return TraceLogger(path, max).also {
                it.log(
                    "TRACE_INIT",
                    buildString {
                        appendLine("path=$path")
                        appendLine("maxChars=$max")
                    }
                )
            }
        }
    }
}

class AiToolIoLogger private constructor(
    private val root: Path,
    private val maxChars: Int,
    private val log: LogSink
) {
    data class Session(
        val id: String,
        val path: Path
    )

    private val lock = Any()

    fun begin(fields: Map<String, Any?>): Session {
        val now = Instant.now()
        val id = UUID.randomUUID().toString()
        val fileStamp = now.toString()
            .replace(":", "")
            .replace(".", "-")
        val path = root.resolve("$fileStamp-${id.take(8)}.jsonl")
        val session = Session(id = id, path = path)
        event(session, "session_start", fields + ("logPath" to path.toString()))
        log.log("info,ai.toolio", "log=${path.toAbsolutePath()}")
        return session
    }

    fun event(session: Session, event: String, fields: Map<String, Any?> = emptyMap()) {
        val record = linkedMapOf<String, Any?>(
            "timestamp" to Instant.now().toString(),
            "sessionId" to session.id,
            "event" to event
        )
        record.putAll(fields)
        append(session.path, jsonValue(record) + "\n")
    }

    private fun append(path: Path, text: String) {
        synchronized(lock) {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            ).use { it.write(text) }
        }
    }

    private fun clipped(text: String): String =
        if (text.length <= maxChars) text else text.take(maxChars) + "\n... [truncated ${text.length - maxChars} chars]"

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Byte, is Short, is Int, is Long -> value.toString()
        is Float -> if (value.isFinite()) value.toString() else jsonString(value.toString())
        is Double -> if (value.isFinite()) value.toString() else jsonString(value.toString())
        is String -> jsonString(clipped(value))
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "${jsonString(k?.toString() ?: "null")}:${jsonValue(v)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { jsonValue(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { jsonValue(it) }
        else -> jsonString(clipped(value.toString()))
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }

    companion object {
        fun fromEnvOrDefault(log: LogSink): AiToolIoLogger? {
            val enabledRaw = System.getProperty("afield.ai4.toolIoLog")
                ?: System.getenv("AFIELD_AI4_TOOL_IO_LOG")
            if (enabledRaw != null) {
                val normalized = enabledRaw.trim()
                if (normalized.equals("0", ignoreCase = true) ||
                    normalized.equals("false", ignoreCase = true) ||
                    normalized.equals("off", ignoreCase = true)
                ) {
                    return null
                }
            }

            val rootRaw = System.getProperty("afield.ai4.toolIoLog.path")
                ?: System.getenv("AFIELD_AI4_TOOL_IO_LOG_PATH")
            val defaultRoot = Paths.get(System.getProperty("user.home"), ".afield", "logs", "ai4_tool_io")
            val root = rootRaw
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    val path = Paths.get(it)
                    if (path.isAbsolute) path else Paths.get(System.getProperty("user.dir")).resolve(path)
                }
                ?: defaultRoot
            val max = (
                System.getProperty("afield.ai4.toolIoLog.max")
                    ?: System.getenv("AFIELD_AI4_TOOL_IO_LOG_MAX")
            )?.toIntOrNull()?.coerceAtLeast(1000) ?: 200_000
            return AiToolIoLogger(root, max, log)
        }
    }
}
