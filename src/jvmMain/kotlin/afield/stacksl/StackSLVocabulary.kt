package afield.stacksl

import afield.Completion
import afield.Metadata

/**
 * One word of a small language as an editor offers it: [effect] as its cheatsheet writes it
 * (`t a b c d -- color`), and [detail] the cheatsheet's prose, as Markdown. Either may be empty.
 */
data class VocabularyWord(val word: String, val effect: String, val detail: String)

/**
 * [words], each with what [guide] — a words guide generated from a language's cheatsheet — says it
 * does, alphabetically. A table whose header has a `Stack`, `Pushes`, `Reads` or `Stack / reads`
 * column gives the effect there, and its last column is the description; a row naming several words
 * (`` `sat`, `saturate` ``) describes each of them. A word the guide does not describe is kept, with
 * nothing said about it.
 */
fun vocabularyFromGuide(words: Collection<String>, guide: String): List<VocabularyWord> {
    val described = HashMap<String, Pair<String, String>>()
    var effectColumn = -1
    for (line in guide.lines()) {
        if (!line.startsWith("|")) { effectColumn = -1; continue }
        val cells = line.trim().removePrefix("|").removeSuffix("|").split(" | ").map { it.trim() }
        if (cells.size < 2 || cells.all { it.matches(Regex("-+")) }) continue
        if (cells[0] in HEADER_FIRST_CELLS) {
            effectColumn = cells.indexOfFirst { it in EFFECT_HEADERS }
            continue
        }
        val effect = if (effectColumn in 1 until cells.size) cells[effectColumn].replace("`", "") else ""
        // Backticks stay: the description is Markdown, and `b*cos(t*c)` outside them is emphasis.
        val detail = if (effectColumn == cells.lastIndex) "" else cells.last()
        Regex("`([^`]+)`").findAll(cells[0]).forEach { described.putIfAbsent(it.groupValues[1], effect to detail) }
    }
    return words.toSortedSet().map { word ->
        val (effect, detail) = described[word] ?: ("" to "")
        VocabularyWord(word, effect, detail)
    }
}

/**
 * What Field's editor offers at [position] in [source], a program in [language]: every word of
 * [vocabulary], and every name the program gives itself ([ownWords]), that begins with the word being
 * typed — which runs back from [position] to whitespace or a bracket. Nothing inside a `#` comment or
 * a string. Each row carries the word's stack effect as its tail and the cheatsheet's description.
 */
fun vocabularyCompletion(source: String, position: Int, language: String, vocabulary: List<VocabularyWord>, ownWords: List<Metadata>): Completion {
    val end = position.coerceIn(0, source.length)
    var start = end
    while (start > 0 && !source[start - 1].isWhitespace() && source[start - 1] != '(' && source[start - 1] != ')') start--
    val prefix = source.substring(start, end)
    val lineSoFar = source.substring(source.lastIndexOf('\n', end - 1) + 1, end)
    if ('#' in lineSoFar || prefix.startsWith("\"") || lineSoFar.count { it == '"' } % 2 == 1) return Completion(start, end, metadata = emptyList())
    val lower = prefix.lowercase()
    val own = ownWords.filter { it.text.lowercase().startsWith(lower) }
    val words = vocabulary.filter { it.word.lowercase().startsWith(lower) && own.none { o -> o.text == it.word } }.map { w ->
        Metadata(icon = "keyword", tail = w.effect, text = w.word, displayText = w.word, category = "words",
            documentation = "**${w.word}**" + (if (w.effect.isNotBlank()) " `${w.effect}`" else "") +
                (if (w.detail.isNotBlank()) "\n\n${w.detail}" else ""),
            documentationSourceName = "$language cheatsheet")
    }
    return Completion(start, end, metadata = own + words)
}

private val HEADER_FIRST_CELLS = setOf("Word", "Words", "Syntax")
private val EFFECT_HEADERS = setOf("Stack", "Pushes", "Reads", "Stack / reads")

/**
 * Every word the StackSL compiler accepts that a person would type, with what the cheatsheet says it
 * does — the vocabulary the editor offers in completion and explains beside the cursor.
 *
 * The editor's own tables (`stacksl.js`) are written by hand and had drifted: on 2026-09-27 they
 * documented 159 of the compiler's 314 words and offered no others, so `palette`, `fbm3`, `min` and
 * `max` never appeared in completion. The words here come from the compiler itself
 * ([stackSLDocumentableWords]); their stack effect and description from the StackSL words guide,
 * which `tools/stacksl_conversation_documents.py` generates from `public/docs/stacksl_cheatsheet.md`
 * and holds equal to it. The editor asks for completion through Field's `complete` request, which a
 * StackSL program answers here ([StackSLExecution][afield.stacksl.study.StackSLExecution]), as does a
 * Kotlin box for its `stackSL` property.
 */
object StackSLVocabulary {
    /** Every documentable word, alphabetically. */
    val words: List<VocabularyWord> by lazy {
        vocabularyFromGuide(stackSLDocumentableWords,
            StackSLVocabulary::class.java.getResource("/afield/stacksl/study/stacksl_words.md")?.readText().orEmpty())
    }

    /**
     * Completion at [position] in the StackSL program [source]: the vocabulary, the program's own
     * `: name` definitions, and `<name` for every mark it stores with `>name`.
     */
    fun complete(source: String, position: Int): Completion {
        val lines = source.lines()
        fun lineOf(offset: Int) = source.substring(0, offset).count { it == '\n' } + 1
        val own = ArrayList<Metadata>()
        Regex("""(?:^|\s):\s+(\S+)""").findAll(source).forEach { m ->
            val line = lineOf(m.groups[1]!!.range.first)
            own += Metadata(icon = "function", tail = "definition", text = m.groupValues[1], displayText = m.groupValues[1],
                documentation = "**${m.groupValues[1]}**, defined on line $line:\n\n`${lines.getOrElse(line - 1) { "" }.trim().take(120)}`")
        }
        Regex("""(?:^|\s)>([A-Za-z_][A-Za-z0-9_-]*)""").findAll(source).map { it.groupValues[1] }.distinct()
            .filter { it !in setOf("x", "y", "z", "w") }.forEach { mark ->
                own += Metadata(icon = "variable", tail = "mark", text = "<$mark", displayText = "<$mark",
                    documentation = "**<$mark** `-- value`\n\nThe value this program stores in the mark `$mark` with `>$mark`.")
            }
        return vocabularyCompletion(source, position, "StackSL", words, own.distinctBy { it.text })
    }
}
