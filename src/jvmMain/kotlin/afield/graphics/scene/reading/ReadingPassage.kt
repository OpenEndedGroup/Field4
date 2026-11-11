package afield.graphics.scene.reading

import afield.graphics.text.sentences.Sentence
import afield.graphics.text.sentences.Sentences

/** The supplied poem and retained, genuine CPU Stanza parses used by the reading examples. */
object ReadingPassage {
    val text: String = requireNotNull(javaClass.getResource("/afield/reading/source-passage.txt")).readText()

    fun sentence(example: Int): Sentence {
        require(example in 2..4)
        return Sentences.parse(requireNotNull(javaClass.getResource("/afield/reading/passages/$example.json")).readText())
    }
}
