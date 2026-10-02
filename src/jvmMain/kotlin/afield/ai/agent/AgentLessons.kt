package afield.ai.agent

import afield.ai.core.aiToolMapper
import com.fasterxml.jackson.databind.JsonNode
import java.io.File
import java.time.Instant

/**
 * What AI4 has learned from its own evals, as a file a person reviews with `git diff`.
 *
 * # The loop
 *
 * `agent-eval` runs tasks through the ordinary AI4 turn and keeps every tool call. [distil] reads those
 * calls for **misuse** — a failed call that is *not* the box's own compile or runtime error (those are
 * the task, not a mistake about the tools): a refused argument, an unresolvable file ref, an exception
 * from the tool itself, the same failing call repeated unchanged. Misuse that recurs (twice or more, in
 * one task or across several) becomes a lesson: the tool, what it answered, and — when a later call of
 * the same tool in the same task worked — the argument that changed between the two. Nothing here asks
 * a model to summarise; the lessons are mechanical, so a lesson can be traced back to the calls that
 * made it.
 *
 * `agent-eval --distil` rewrites the file's `## Distilled` section and bumps its `version:`. The
 * `## Curated` section is for people and is never rewritten. [instructionText] is what
 * [afield.ai.core.buildDynamicInstructions] adds to every AI4 turn that has the graph tools, capped at
 * [MAX_INSTRUCTION_CHARS].
 *
 * # Switches
 *
 * `AFIELD_AI4_LESSONS=off` turns the lessons off everywhere; `AgentTaskRequest.lessons` / `AgentEvalRequest.lessons`
 * (`on` / `off`) pin them for one run, which is how a before/after is measured. `AFIELD_AI4_LESSONS_FILE`
 * names another file.
 */
object AgentLessons {
    const val MAX_INSTRUCTION_CHARS = 2400
    const val MAX_DISTILLED = 8
    const val RESOURCE = "/ai4/agent_lessons.md"
    /** The tracked source of [RESOURCE], relative to a checkout (Field runs from its checkout). */
    const val CHECKOUT_PATH = "src/jvmMain/resources/ai4/agent_lessons.md"

    fun enabledByDefault(): Boolean =
        System.getenv("AFIELD_AI4_LESSONS")?.trim()?.lowercase() !in setOf("off", "0", "false", "no")

    /** `on` / `off` pin; anything else is [enabledByDefault]. */
    fun resolve(setting: String): Boolean = when (setting.trim().lowercase()) {
        "on", "true", "1", "yes" -> true
        "off", "false", "0", "no" -> false
        else -> enabledByDefault()
    }

    /** Tests point this at a temporary file (they cannot set the environment). */
    @Volatile
    internal var fileOverride: File? = null

    /** The writable file: `AFIELD_AI4_LESSONS_FILE`, else the checkout's tracked copy when it exists. */
    fun sourceFile(): File? = fileOverride ?:
        System.getenv("AFIELD_AI4_LESSONS_FILE")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(CHECKOUT_PATH).absoluteFile.takeIf { it.isFile }

    /** The file's text: the source file when there is one (so a distil is live at once), else the packaged copy. */
    fun rawText(): String? = sourceFile()?.takeIf { it.isFile }?.readText()
        ?: AgentLessons::class.java.getResource(RESOURCE)?.readText()

    data class Parsed(val version: Int, val header: String, val curated: List<String>, val distilled: List<String>, val provenance: String)

    fun parse(text: String): Parsed {
        val version = Regex("(?m)^version:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
        val provenance = Regex("(?m)^distilled:\\s*(.*)$").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        fun bullets(section: String): List<String> {
            val start = Regex("(?m)^## $section\\s*$").find(text) ?: return emptyList()
            val rest = text.substring(start.range.last + 1)
            val end = Regex("(?m)^## ").find(rest)?.range?.first ?: rest.length
            // A bullet runs until the next bullet or a blank line, so wrapped bullets stay whole.
            val items = mutableListOf<String>()
            for (line in rest.substring(0, end).lines()) {
                when {
                    line.startsWith("- ") -> items += line
                    line.isBlank() -> items += ""
                    items.isNotEmpty() && items.last().isNotEmpty() -> items[items.size - 1] = items.last() + " " + line.trim()
                }
            }
            return items.filter { it.isNotBlank() }
        }
        // The header comment itself mentions "## Curated", so it ends at the first *line* that starts
        // a field or a section (substringBefore cut the comment in half in the second distil).
        val headerEnd = Regex("(?m)^(version:|distilled:|## Curated\\s*$)").find(text)?.range?.first ?: text.length
        val header = text.substring(0, headerEnd).trimEnd()
        return Parsed(version, header, bullets("Curated"), bullets("Distilled"), provenance)
    }

    /**
     * The block added to AI4's instructions, or null when there are no lessons. Whole bullets only,
     * curated first, until [MAX_INSTRUCTION_CHARS].
     */
    fun instructionText(): String? {
        val parsed = rawText()?.let(::parse) ?: return null
        val all = parsed.curated + parsed.distilled
        if (all.isEmpty()) return null
        val out = StringBuilder("Lessons from earlier AI4 eval runs with these tools (agent_lessons.md v${parsed.version}; tool misuse that cost earlier runs rounds):\n")
        for (bullet in all) {
            if (out.length + bullet.length + 1 > MAX_INSTRUCTION_CHARS) break
            out.append(bullet).append('\n')
        }
        return out.toString().trimEnd()
    }

    // ---- distillation -----------------------------------------------------------------------

    private fun json(text: String): JsonNode? = runCatching { aiToolMapper.readTree(text) }.getOrNull()

    /**
     * A failed call that is a mistake about the tools rather than the box's own error. Run/Launch
     * failures that reached execution (a `stage`) and compiles that produced diagnostics are the
     * code's errors, which is what the task is about.
     */
    fun isMisuse(call: AgentToolCallRecord): Boolean {
        if (call.ok) return false
        val node = json(call.result)
        return when (call.tool) {
            "RunBoxRequest", "LaunchBoxRequest" -> node?.get("stage")?.asText().isNullOrBlank() && node?.get("timedOut")?.asBoolean() != true
            "CompileKotlinRequest", "EvaluateKotlinExpressionRequest" -> (node?.get("diagnostics")?.size() ?: 0) == 0
            else -> true
        }
    }

    fun misuseCount(result: AgentTaskResult): Int = result.toolCalls.count(::isMisuse)

    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)

    private val threePartRef = Regex("afield://([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/(?=[^/\\s'\"`]+/)", RegexOption.IGNORE_CASE)

    /**
     * Replace what differs between runs by placeholders: the document's name *as file refs spell it*
     * ([documentRef], the scratch directory's uuid) becomes `<document>`, other uuids `<uid>`.
     *
     * Not `documentName`: a headless task document is *named* `scratch`, and substituting that turned
     * the model's wrong guess `afield://scratch/<uid>/code` into the lesson "`afield://<document>/…`
     * fails" — the right spelling reported as the wrong one (the first distil, 2026-09-30). Without a
     * [documentRef] (reports from before it existed) the first segment of a three-part ref whose first
     * segment is a uuid is taken to be the document, which is what a three-part ref means.
     */
    fun normalize(text: String, documentRef: String): String {
        var t = text
        t = if (documentRef.isNotBlank()) t.replace(documentRef, "<document>") else threePartRef.replace(t, "afield://<document>/")
        t = uuid.replace(t, "<uid>")
        return t
    }

    private fun message(call: AgentToolCallRecord): String {
        val node = json(call.result)
        val m = node?.get("message")?.asText()?.takeIf { it.isNotBlank() } ?: node?.get("error")?.asText() ?: call.result
        return m.lineSequence().first().take(200)
    }

    private fun shape(value: JsonNode?, documentName: String): String = when {
        value == null || value.isNull -> "null"
        value.isTextual -> value.asText().let { v ->
            if (v.length > 100 || v.contains('\n')) "<text>" else "`" + normalize(v, documentName).ifEmpty { "" } + "`"
        }
        else -> normalize(value.toString(), documentName).take(100)
    }

    /** The arguments whose shape differs between a failing call and the next working one. */
    private fun argumentChange(failing: AgentToolCallRecord, working: AgentToolCallRecord, documentName: String): String {
        val a = json(failing.arguments) ?: return ""
        val b = json(working.arguments) ?: return ""
        val keys = (a.fieldNames().asSequence() + b.fieldNames().asSequence()).toSortedSet()
        return keys.mapNotNull { key ->
            val sa = shape(a.get(key), documentName)
            val sb = shape(b.get(key), documentName)
            if (sa == sb) null else "$key $sa → $sb"
        }.joinToString("; ")
    }

    data class Lesson(val tool: String, val message: String, val count: Int, val tasks: List<String>,
                      val repeatedUnchanged: Int, val change: String) {
        fun bullet(): String = buildString {
            append("- `$tool` failed $count time${if (count == 1) "" else "s"} in ${tasks.size} task${if (tasks.size == 1) "" else "s"} ")
            append("(${tasks.joinToString(", ")}) answering \"$message\".")
            if (change.isNotBlank()) append(" The call that then worked changed: $change.")
            else append(" No later call of it in those tasks worked.")
            if (repeatedUnchanged > 0) append(" It was repeated unchanged $repeatedUnchanged time${if (repeatedUnchanged == 1) "" else "s"}: after a failure, change the arguments or the approach.")
        }
    }

    fun distil(results: List<AgentTaskResult>): List<Lesson> {
        data class Hit(val task: String, val call: AgentToolCallRecord, val change: String, val repeat: Boolean)
        val byKey = LinkedHashMap<Pair<String, String>, MutableList<Hit>>()
        for (result in results) {
            val calls = result.toolCalls
            calls.forEachIndexed { i, call ->
                if (!isMisuse(call)) return@forEachIndexed
                val later = calls.drop(i + 1).firstOrNull { it.tool == call.tool && it.ok }
                val change = later?.let { argumentChange(call, it, result.documentRef) }.orEmpty()
                val repeat = i > 0 && calls[i - 1].tool == call.tool && calls[i - 1].arguments == call.arguments && !calls[i - 1].ok
                val key = call.tool to normalize(message(call), result.documentRef)
                byKey.getOrPut(key) { mutableListOf() } += Hit(result.taskId.ifBlank { "task" }, call, change, repeat)
            }
        }
        return byKey.entries
            .filter { it.value.size >= 2 }
            .map { (key, hits) ->
                Lesson(
                    tool = key.first, message = key.second, count = hits.size,
                    tasks = hits.map { it.task }.distinct(),
                    repeatedUnchanged = hits.count { it.repeat },
                    change = hits.map { it.change }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
                        .maxByOrNull { it.value }?.key.orEmpty()
                )
            }
            .sortedByDescending { it.count }
            .take(MAX_DISTILLED)
    }

    /**
     * [existing] with its `## Distilled` section replaced by [lessons], its version bumped, and a
     * provenance line. The curated section and the header comment are kept as they are.
     */
    fun render(existing: String?, lessons: List<Lesson>, provenance: String): String {
        val parsed = existing?.let(::parse)
        val version = (parsed?.version ?: 0) + 1
        val header = parsed?.header?.lines()
            ?.filterNot { it.startsWith("version:") || it.startsWith("distilled:") }
            ?.joinToString("\n")?.trimEnd()
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_HEADER
        return buildString {
            appendLine(header)
            appendLine()
            appendLine("version: $version")
            appendLine("distilled: $provenance")
            appendLine()
            appendLine("## Curated")
            appendLine()
            parsed?.curated?.forEach { appendLine(it) }
            if (parsed?.curated?.isNotEmpty() == true) appendLine()
            appendLine("## Distilled")
            appendLine()
            if (lessons.isEmpty()) appendLine("(none: no tool misuse recurred in the distilled run)")
            lessons.forEach { appendLine(it.bullet()) }
        }
    }

    const val DEFAULT_HEADER = """<!--
AI4 lessons: read into AI4's instructions whenever the box-graph tools are wired (every chat path and
every agent task), capped at 2400 chars, curated bullets first. Kill switch: AFIELD_AI4_LESSONS=off.
"## Curated" is for people. "## Distilled" is rewritten by `agent-eval --distil` from the misuse in
that run's tool calls (AgentLessons.distil); review it with git diff like any other change.
-->"""

    data class DistilOutcome(val written: Boolean, val path: String, val version: Int, val lessons: List<Lesson>, val message: String)

    private val reportJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** A saved [AgentEvalReport] (the JSON `agent-eval` returned), for re-distilling without re-running. */
    fun readReport(path: String): AgentEvalReport =
        reportJson.decodeFromString(AgentEvalReport.serializer(), File(path).readText())

    /** Distil [report] and write the file. Refuses when there is no writable source file. */
    fun distilAndWrite(report: AgentEvalReport): DistilOutcome {
        val lessons = distil(report.results)
        val target = sourceFile() ?: return DistilOutcome(false, "", 0, lessons,
            "No writable lessons file: run Field from its checkout ($CHECKOUT_PATH) or set AFIELD_AI4_LESSONS_FILE.")
        val provenance = "${Instant.now()} from agent-eval mode=${report.mode} ${report.provider} ${report.model}, " +
            "${report.population} task(s): ${report.results.joinToString(",") { it.taskId }}; " +
            "${report.results.sumOf { misuseCount(it) }} misuse call(s)"
        val text = render(target.takeIf { it.isFile }?.readText(), lessons, provenance)
        target.parentFile?.mkdirs()
        target.writeText(text)
        return DistilOutcome(true, target.path, parse(text).version, lessons, "Wrote ${lessons.size} lesson(s) to ${target.path}.")
    }
}
