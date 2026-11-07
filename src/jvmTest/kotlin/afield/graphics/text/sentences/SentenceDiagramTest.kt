package afield.graphics.text.sentences

import java.io.File
import kotlin.test.*

class SentenceDiagramTest {
    private fun sentence(index: Int) = Sentences.parse(File("templates/text_one/parser/$index.json").readText())

    @Test
    fun cachedParsesPreserveTokenTreeAndDependencyReferences() {
        for (index in 1..4) {
            val sentence = sentence(index)
            assertTrue(sentence.problems().isEmpty(), sentence.problems().joinToString())
            assertEquals(sentence.tokens.map { it.id }, sentence.constituency.leaves().map { it.token })
            assertTrue(sentence.dependencies.sdCollapsed.any { it.isRoot })
            for (edge in sentence.dependencies.sdCollapsed) {
                assertNotNull(sentence.tokenOrNull(edge.dep))
                if (!edge.isRoot) assertNotNull(sentence.tokenOrNull(edge.gov))
            }
        }
    }

    @Test
    fun bothLayoutsKeepTheRealWordsAndFiniteGeometry() {
        for (index in 1..4) {
            val sentence = sentence(index)
            val layout = SentenceDiagram().use(sentence).diagram()
            assertEquals(sentence.tokens.map { it.word }, layout.words.map { it.word })
            assertTrue(layout.words.all { it.left.isFinite() && it.baseline.isFinite() && it.width > 0 })
            assertTrue(layout.nodes.all { it.at.x.isFinite() && it.at.y.isFinite() })
            assertTrue(layout.arcs.all { it.curve.a.x.isFinite() && it.curve.b.y.isFinite() })
            val furniture = Kellogg().layout(sentence)
            assertTrue(furniture.problems().isEmpty(), furniture.problems().joinToString())
            assertTrue(furniture.words.all { it.width.isFinite() && it.width > 0 })
            assertTrue(furniture.lines.all { it.a.x.isFinite() && it.b.y.isFinite() })
        }
    }
}
