package afield.lang.rectsl

import afield.app.protocol.Completion
import afield.app.protocol.Metadata
import afield.lang.stacksl.VocabularyWord
import afield.lang.stacksl.vocabularyCompletion
import afield.lang.stacksl.vocabularyFromGuide

/**
 * Every word the RectSL evaluator knows, with what the cheatsheet says it does — the vocabulary the
 * editor offers in completion and explains beside the cursor.
 *
 * The words are the evaluator's own ([RectLangVm.wordMetadata], the set its "did you mean"
 * searches), so a word the evaluator knows is offered even when the editor's hand-written table
 * (`rectsl.js`) lacks it. Their stack effect and description come from the RectSL words guide, which
 * `tools/rectsl_conversation_documents.py` generates from `public/docs/rectsl_cheatsheet.md` and holds
 * equal to it. The editor asks for completion through Field's `complete` request, which a RectSL
 * scene answers here ([RectSLExecution][afield.lang.rectsl.study.RectSLExecution]), as does a Kotlin box for
 * its `rectSL` property.
 */

/** The RectSL word list and the completion it answers. */
object RectSLVocabulary {
    /** Every built-in word, alphabetically. */
    val words: List<VocabularyWord> by lazy {
        vocabularyFromGuide(RectLangVm().wordMetadata().map { it.spelling },
            RectSLVocabulary::class.java.getResource("/afield/rectsl/study/rectsl_words.md")?.readText().orEmpty())
    }

    /** Completion at [position] in the RectSL scene [source]: the vocabulary and the scene's own `: name` definitions. */
    fun complete(source: String, position: Int): Completion {
        val lines = source.lines()
        val own = Regex("""(?:^|\s):\s+(\S+)""").findAll(source).map { m ->
            val line = source.substring(0, m.groups[1]!!.range.first).count { it == '\n' } + 1
            Metadata(icon = "function", tail = "definition", text = m.groupValues[1], displayText = m.groupValues[1],
                documentation = "**${m.groupValues[1]}**, defined on line $line:\n\n`${lines.getOrElse(line - 1) { "" }.trim().take(120)}`")
        }.distinctBy { it.text }.toList()
        return vocabularyCompletion(source, position, "RectSL", words, own)
    }
}
