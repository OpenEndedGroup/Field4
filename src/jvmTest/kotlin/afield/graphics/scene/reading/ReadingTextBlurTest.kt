package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.graphics.text.*
import afield.graphics.text.slug.SlugPath
import kotlin.math.*
import kotlin.test.*

class ReadingTextBlurTest {
    @Test
    fun interpolationPreservesVarianceAndReturnsToAnalyticSlug() {
        val capacity = 1.6f
        assertEquals(0f, ReadingBlurLevels.mix(0f, capacity).cached)
        for (i in 1..1000) {
            val radius = capacity * i / 1000f
            val mix = ReadingBlurLevels.mix(radius, capacity)
            val a = ReadingBlurLevels.sigma(mix.low).pow(2)
            val b = ReadingBlurLevels.sigma(mix.high).pow(2)
            val variance = (a + (b - a) * mix.fraction) * mix.cached
            assertEquals((radius / capacity).pow(2), variance, 2e-6f)
            assertTrue(mix.fraction in 0f..1f && mix.cached in 0f..1f)
        }
        assertEquals(15, ReadingBlurLevels.mix(capacity, capacity).high)
        assertFailsWith<IllegalArgumentException> { ReadingBlurLevels.mix(2f, 1f) }
        assertFailsWith<IllegalArgumentException> { ReadingBlurLevels.mix(Float.NaN, 1f) }
    }

    @Test
    fun incrementalGaussiansAccumulateToTheirDeclaredWidthsAndDoNotBecomeTinyTextures() {
        var variance = 0f
        for (level in 1 until ReadingBlurLevels.count) {
            variance += ReadingBlurLevels.increment(level).pow(2)
            assertEquals(ReadingBlurLevels.sigma(level).pow(2), variance, 1e-6f)
            assertTrue(ReadingBlurLevels.size(2048, level, 128) >= 128)
            assertTrue(ReadingBlurLevels.size(1024, level, 128) >= 128)
        }
    }

    private fun graph() = readingDiagram {
        val p = point("p", 0, 0, pinned = true)
        text("word", "weather", p) { blur = .1f }
    }

    @Test
    fun slowBlurInAndOutIsSeekableAndIndependentOfOpacity() {
        val graph = graph()
        val word = graph.text("word")
        val score =
            diagramScore(graph, 24.0) {
                blurIn(word, at = 0.0, seconds = 6.0, radius = .8f)
                blurOut(word, at = 14.0, seconds = 8.0, radius = 1.2f)
            }
        assertEquals(.8f, score.pose(word, 0.0).blur)
        assertEquals(0f, score.pose(word, 6.0).blur)
        assertEquals(.6f, score.pose(word, 18.0).blur, 1e-6f)
        assertEquals(1.2f, score.pose(word, 22.0).blur)
        assertEquals(.8f, score.pose(word, 0.0).blur)
        for (t in listOf(0.0, 3.0, 6.0, 18.0, 22.0)) assertEquals(1f, score.pose(word, t).opacity)
        assertEquals(2f, score.blurCapacities.getValue(word))
        assertEquals(.1f, word.blur)
        assertEquals(0f, word.blurCapacity) // rescoring never mutates the graph's reservation
    }

    @Test
    fun labelsCarryBlurAndPreparedCapacityAccountsForBaseAndScore() {
        val graph = graph()
        val word = graph.text("word")
        val font = Typefaces.named("Serif", 100)
        val layout = layoutText(word.text, font)
        val atlas =
            SlugPath.decode(
                requireNotNull(javaClass.getResource("/afield/slug/curved-hole.bin")).readBytes(),
            )
        val shape = ReadingText(word.text, layout.advanceWidth.toFloat(), FLine(), atlas, layout)
        val score =
            diagramScore(graph, 20.0) { blurIn(word, at = 0.0, seconds = 5.0, radius = .8f) }
        val player = ReadingDiagramPlayer(graph, font, mapOf(word.text to shape), score)
        player.playback.playing = false
        player.update(0.0)
        assertEquals(.9f, player.labels.single().blur, 1e-6f)
        assertEquals(.9f, player.blurRequests.getValue(shape), 1e-6f)
        player.playback.seek(5.0)
        player.update(0.0)
        assertEquals(.1f, player.labels.single().blur, 1e-6f)
        word.blurCapacity = 2f
        assertEquals(2f, player.blurRequests.getValue(shape))
        assertFailsWith<IllegalArgumentException> { word.blur = -1f }
        assertFailsWith<IllegalArgumentException> { word.blurCapacity = Float.POSITIVE_INFINITY }
    }
}
