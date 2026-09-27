package afield

import afield.logger.lprintln
import afield.macros.gatherPreambleUpwards
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Durable, append-only observations of what happens at Field's autocomplete UI boundary.
 *
 * The server cannot reconstruct this later: the browser merges several candidate sources and owns
 * the final ranking. Conversely the browser does not have the inherited preamble or the other
 * code-bearing properties that shape completion. [record] joins those two views without placing
 * logging on the latency-sensitive completion reply path.
 */
object CompletionInteractionLogger {
    private val lock = Any()
    private val day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    private val json = Json { encodeDefaults = true }

    private val codeProperties = linkedSetOf(
        "code", "preamble", "introduction", "attic", "metadata",
        "vertex", "fragment", "vertex_fragment", "compute", "task", "mesh",
        "raygen", "miss", "closesthit", "anyhit", "intersection", "callable",
        "vertex_s", "fragment_s", "compute_s", "task_s", "mesh_s",
        "raygen_s", "miss_s", "closesthit_s", "anyhit_s", "intersection_s", "callable_s",
        "stackSL", "rectSL", "msl", "customization", "remote_js"
    )

    @Serializable
    internal data class Record(
        val timestamp: String,
        val documentName: String,
        val documentPath: String,
        val boxName: String,
        val executionType: String,
        val relevantProperties: Map<String, String>,
        val inheritedPreamble: String,
        val interaction: CompletionInteractionEvent
    )

    fun record(
        event: CompletionInteractionEvent,
        box: Box,
        documentName: String,
        documentPath: String
    ) {
        runCatching {
            recordTo(defaultRoot(), event, box, documentName, documentPath)
        }.onFailure {
            lprintln(
                "warning,completion-observability",
                "could not append completion interaction ${event.interactionId}/${event.event}: ${it.message}"
            )
        }
    }

    internal fun recordTo(
        root: Path,
        event: CompletionInteractionEvent,
        box: Box,
        documentName: String,
        documentPath: String,
        now: Instant = Instant.now()
    ): Path {
        val properties = relevantProperties(box, event)
        val record = Record(
            timestamp = now.toString(),
            documentName = documentName,
            documentPath = documentPath,
            boxName = box.name,
            executionType = box.getPropertyRaw("executionType")?.toString().orEmpty(),
            relevantProperties = properties,
            inheritedPreamble = runCatching { gatherPreambleUpwards(box) }.getOrDefault(""),
            interaction = event
        )
        val path = root.resolve("${day.format(now)}.jsonl")
        val line = json.encodeToString(record) + "\n"
        synchronized(lock) {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            ).use { it.write(line) }
        }
        return path
    }

    internal fun relevantProperties(
        box: Box,
        event: CompletionInteractionEvent
    ): Map<String, String> {
        val names = LinkedHashSet(codeProperties).apply { add(event.property) }
        val result = linkedMapOf<String, String>()
        for (name in names) {
            val value = if (name == event.property && event.event == "requested") {
                event.allCode
            } else {
                box.getPropertyRaw(name) as? String
            }
            if (value != null && (value.isNotBlank() || name == event.property)) result[name] = value
        }
        // The editor is ahead of box persistence while the user is typing. Preserve its exact current
        // source on every request even when the backing PropertyBag has not caught up yet.
        if (event.event == "requested") result[event.property] = event.allCode
        return result
    }

    private fun defaultRoot(): Path {
        val configured = System.getProperty("afield.completion.interactionLog.path")
            ?: System.getenv("AFIELD_COMPLETION_INTERACTION_LOG_PATH")
        if (!configured.isNullOrBlank()) {
            val path = Paths.get(configured.trim())
            return if (path.isAbsolute) path else Paths.get(System.getProperty("user.dir")).resolve(path)
        }
        return Paths.get(System.getProperty("user.home"), ".afield", "logs", "completion_interactions")
    }
}
