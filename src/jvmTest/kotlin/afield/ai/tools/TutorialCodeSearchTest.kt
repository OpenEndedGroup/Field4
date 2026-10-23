package afield.ai.tools

import afield.ai.retrieval.SemanticRetrievalHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TutorialCodeSearchRequest: semantic hits in the `tutorials` corpus, grouped into tutorials. */
class TutorialCodeSearchTest {
    private fun hit(document: String, box: String, path: String) = SemanticRetrievalHit(
        id = path, scale = "container", corpus = "tutorials", kind = "sidecar-code", symbol = box, title = box, path = path,
        line = 1, endLine = 10, parentId = "", documentName = document, boxName = box, boxId = path.substringAfterLast('/').substringBefore('_'),
        property = "code", language = "kotlin", score = 1.0)

    @Test fun hitsGroupByDocumentAndTheGraphicsCurriculumByLesson() {
        val about = mapOf("fline_measure" to TutorialCode.About("Measure FLines.", listOf("fline_examples.md: drawing FLines")))
        val matches = TutorialCode.group(listOf(
            hit("fline_measure", "Measure", "public/docs/assets/agent_transcripts/fline_measure/measure_code_.kt"),
            hit("canonical_graphics", "3. compute shader", "public/docs/assets/canonical_graphics/c0ffee_code_.kt"),
            hit("fline_measure", "Lengths", "public/docs/assets/agent_transcripts/fline_measure/lengths_code_.kt"),
            hit("canonical_graphics", "4. texturing", "public/docs/assets/canonical_graphics/beef_code_.kt"),
        ), limit = 5, about = about)
        assertEquals(listOf("fline_measure", "canonical_graphics: 3. compute shader", "canonical_graphics: 4. texturing"), matches.map { it.name })
        assertEquals(listOf("Measure", "Lengths"), matches.first().boxes)
        assertEquals("Measure FLines.", matches.first().description)
        assertTrue(matches.first().codeFiles.all { it.startsWith("public/docs/assets/agent_transcripts/fline_measure/") })
    }

    @Test fun everyShippedTutorialHasADescriptionToShow() {
        val about = TutorialCode.about()
        if (about.isEmpty()) return // not in a checkout
        assertTrue(about.size > 200, "${about.size} transcripts")
        assertTrue("canonical_graphics" in about)
    }
}
