package afield.stacksl

import afield.rectsl.RectLangVm
import afield.rectsl.RectSLVocabulary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * StackSL and RectSL completion, answered by Field through the `complete` request: the compiler's own
 * words, each with the cheatsheet's stack effect and description, and the program's own names. Before
 * it, completion came only from the editors' hand-written tables — no `palette`, `fbm3`, `min` or `max`
 * in StackSL, no `material` or `line` in RectSL, and no alias such as `saturate` in either.
 */
class LanguageVocabularyTest {
    private val stackSL = StackSLVocabulary.words.associateBy { it.word }
    private val rectSL = RectSLVocabulary.words.associateBy { it.word }

    @Test
    fun everyStackSLWordTheCompilerAcceptsIsOffered() {
        assertEquals(stackSLDocumentableWords, stackSL.keys, "the vocabulary is the compiler's, word for word")
        assertEquals("t a b c d -- color", stackSL.getValue("palette").effect)
        assertTrue("cosine palette" in stackSL.getValue("palette").detail, stackSL.getValue("palette").detail)
        assertEquals(stackSL.getValue("palette"), stackSL.getValue("cos-palette").copy(word = "palette"), "an alias row describes each of its words")
        listOf("fbm3", "fbm4", "min", "max", "saturate", "warp3", "worley3").forEach { word ->
            val entry = stackSL.getValue(word)
            assertTrue(entry.effect.isNotBlank() && entry.detail.isNotBlank(), "$word should come with its effect and description: $entry")
        }
    }

    @Test
    fun everyRectSLWordTheEvaluatorKnowsIsOffered() {
        assertEquals(RectLangVm().wordMetadata().map { it.spelling }.toSet(), rectSL.keys)
        listOf("material", "line", "scatter", "spiral", "->xy", "array").forEach { word ->
            assertTrue(rectSL.getValue(word).detail.isNotBlank(), "$word should come with its description: ${rectSL.getValue(word)}")
        }
    }

    private fun offered(completion: afield.Completion) = completion.metadata.map { it.text }

    @Test
    fun stackSLCompletionOffersTheWholeVocabularyAndTheProgramsOwnNames() {
        val program = ": warp\n  uv 6 * sin\n;\nwarp fbm >field\n<field pale"
        val completion = StackSLVocabulary.complete(program, program.length)
        assertEquals(listOf("palette"), offered(completion))
        assertEquals(program.length - 4, completion.boundsStart, "the word being typed is replaced")
        assertEquals("t a b c d -- color", completion.metadata.single().tail)
        assertTrue("saturate" in offered(StackSLVocabulary.complete("uv satu", 7)), "an alias is offered as itself")
        assertTrue(listOf("fbm", "fbm3", "fbm4", "fbmd3").all { it in offered(StackSLVocabulary.complete("uv fbm", 6)) })
        assertEquals(listOf("warp", "warp3"), offered(StackSLVocabulary.complete(program + "\nwar", program.length + 4)),
            "the program's own definition comes first, then the vocabulary's")
        assertEquals(listOf("<field"), offered(StackSLVocabulary.complete(program + "\n<fi", program.length + 4)), "a stored mark can be read back")
        assertEquals(emptyList(), offered(StackSLVocabulary.complete("# pale", 6)), "nothing inside a comment")
    }

    @Test
    fun rectSLCompletionOffersTheEvaluatorsWordsAndTheScenesDefinitions() {
        assertTrue("material" in offered(RectSLVocabulary.complete("mate", 4)))
        val scene = ": lantern \"l\" rect ;\nlant"
        assertEquals(listOf("lantern"), offered(RectSLVocabulary.complete(scene, scene.length)))
        assertEquals(emptyList(), offered(RectSLVocabulary.complete("\"mate", 5)), "nothing inside a string")
    }

    @Test
    fun aGuideTableGivesEachWordItsEffectAndItsDescription() {
        val guide = """
            | Word | Stack | Effect |
            | --- | --- | --- |
            | `sat`, `saturate` | `color -- color` | Clamps all four channels to `[0,1]`. |

            | Word | Meaning |
            | --- | --- |
            | `red` | A named colour. |
        """.trimIndent()
        val words = vocabularyFromGuide(listOf("saturate", "red", "undocumented"), guide).associateBy { it.word }
        assertEquals(VocabularyWord("saturate", "color -- color", "Clamps all four channels to `[0,1]`."), words["saturate"],
            "the description keeps its code spans: it is Markdown")
        assertEquals(VocabularyWord("red", "", "A named colour."), words["red"])
        assertEquals(VocabularyWord("undocumented", "", ""), words["undocumented"], "a word the guide does not describe is still offered")
    }
}
