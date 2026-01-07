package afield.ai.core.completion

import afield.ai.core.bridge.OpenAiIdeCodexBridge
import afield.ai.core.lang.KOTLIN_SCRIPT
import afield.ai.core.logging.SilentLog
import com.openai.models.ReasoningEffort
import java.util.concurrent.ConcurrentHashMap

private fun kotlinCompletionThreadInstructions(): String = """
You are an IDE autocomplete engine for Kotlin.

Rules:
- Output 3 candidate completions separated by a line containing only: ---
- Each candidate is ONLY the text to insert at <cursor>.
- After the candidates, output a notes block starting with the line: <<<notes>>>
- In the notes block, provide brief notes for candidates 1, 2, and 3 (one line each).
- Do not include markdown or backticks.
- Do not repeat existing code around the cursor.
- Keep each candidate short (a few lines max) and syntactically valid.
- Prefer helpful completions over empty output.
- Do not call tools; output plain text only.
""".trimIndent()

private fun kotlinCompletionPrompt(
    line: Int,
    column: Int,
    codeWithCursor: String
): String = """
Return 3 completion candidates separated by a line containing only: ---
Then include a notes block that starts with: <<<notes>>>
Format the notes as:
1) ...
2) ...
3) ...
Cursor: line $line, column $column

Kotlin script:
$codeWithCursor
""".trimIndent()

private fun cleanCompletionText(raw: String): String {
    var text = raw.trim()
    if (text.startsWith("```")) {
        val lines = text.lines()
        val fenceEnd = lines.indexOfLast { it.trim().startsWith("```") }
        if (fenceEnd > 0) {
            text = lines.subList(1, fenceEnd).joinToString("\n").trim()
        }
    }
    return text.replace("<cursor>", "")
        .replace("<rewrite>", "")
        .replace("</rewrite>", "")
        .replace("<help>", "")
        .replace("<error>", "")
        .replace("</error>", "")
        .trimEnd()
}

private fun splitCompletionCandidates(text: String): List<String> {
    val results = mutableListOf<String>()
    val current = StringBuilder()
    val lines = text.split("\n")
    for (line in lines) {
        if (line.trim() == "---") {
            val candidate = current.toString().trim()
            if (candidate.isNotEmpty()) results += candidate
            current.clear()
            continue
        }
        if (current.isNotEmpty()) current.append("\n")
        current.append(line)
    }
    val candidate = current.toString().trim()
    if (candidate.isNotEmpty()) results += candidate
    return results
}

private fun splitNotesBlock(text: String): Pair<String, String> {
    val marker = Regex("(?i)<<<notes>>>")
    val match = marker.find(text) ?: return text.trimEnd() to ""
    val candidatesText = text.substring(0, match.range.first).trimEnd()
    val notesText = text.substring(match.range.last + 1).trim()
    return candidatesText to notesText
}

data class KotlinCompletionCandidate(
    val text: String,
    val documentation: String
)

private fun parseCandidateDocs(commentary: String, count: Int): List<String> {
    if (count <= 0) return emptyList()
    val docs = MutableList(count) { "" }
    if (commentary.isBlank()) return docs
    val regex = Regex("^\\s*(\\d+)[).:-]?\\s*(.*)$")
    var current = -1
    commentary.lines().forEach { line ->
        val match = regex.matchEntire(line)
        if (match != null) {
            val idx = match.groupValues[1].toIntOrNull()?.minus(1) ?: -1
            if (idx in 0 until count) {
                current = idx
                docs[idx] = match.groupValues[2].trim()
                return@forEach
            }
        }
        if (current >= 0) {
            val existing = docs[current]
            docs[current] = if (existing.isBlank()) line.trim() else (existing + "\n" + line.trim()).trim()
        }
    }
    if (docs.any { it.isNotBlank() }) return docs
    val fallback = commentary.trim()
    return List(count) { fallback }
}

object Ai4KotlinAutocomplete {
    private val bridge: OpenAiIdeCodexBridge by lazy {
        val fastModel = System.getenv("AFIELD_KOTLIN_AUTOCOMPLETE_MODEL")
            ?: "gpt-5.1-codex-mini"
        OpenAiIdeCodexBridge(
            model = fastModel,
            log = SilentLog,
            reasoningEffort = ReasoningEffort.LOW
        )
    }
    private val threadByKey = ConcurrentHashMap<String, OpenAiIdeCodexBridge.ThreadState>()

    fun complete(
        fileKey: String,
        code: String,
        line: Int,
        column: Int,
        position: Int,
        maxOutputTokens: Long = 320
    ): List<KotlinCompletionCandidate> {
        val lang = KOTLIN_SCRIPT
        val safePos = position.coerceIn(0, code.length)
        val codeWithCursor = code.substring(0, safePos) + "<cursor>" + code.substring(safePos)
        val wrapped = lang.wrap(codeWithCursor)

        val state = threadByKey[fileKey] ?: bridge.startThread(
            fileKey,
            lang.wrap(code),
            instructions = kotlinCompletionThreadInstructions()
        ).also { threadByKey[fileKey] = it }

        val prompt = kotlinCompletionPrompt(line, column, wrapped)
        val result = bridge.send(state, prompt, maxOutputTokens = maxOutputTokens)
        val cleaned = cleanCompletionText(result.text)
        val (candidatesText, notesText) = splitNotesBlock(cleaned)
        val candidates = splitCompletionCandidates(candidatesText)
        val fallbackNotes = result.commentary.joinToString("\n").trim()
        val docs = parseCandidateDocs(if (notesText.isNotBlank()) notesText else fallbackNotes, candidates.size)
        return candidates.mapIndexed { idx, text ->
            KotlinCompletionCandidate(text, docs.getOrElse(idx) { "" })
        }
    }
}
