package afield.ai.learning.pen

import afield.geometry.path.FLine
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PenCorporaTest {
    @Test
    fun everyHersheyFontHasPrintableAscii() {
        for (name in HersheyFont.names) {
            val font = HersheyFont.load(name)
            assertTrue(font.strokes('A')!!.isNotEmpty(), "$name has an A")
            assertTrue(font.strokes(' ')!!.isEmpty(), "$name's space draws nothing")
            assertTrue(font.text("Field").pieces().size >= 4, "$name writes a word in several strokes")
        }
    }

    @Test
    fun capitalsAreAsTallAsAsked() {
        val b = HersheyFont.load("futural").text("H", size = 21.0).fast2DBounds()
        assertEquals(21.0, b.h.toDouble(), 1.5)
    }

    @Test
    fun generatorsAreDeterministicAndOrdered() {
        val makers = listOf<(Long) -> FLine>(
            { PenDrawings.hershey(it) }, { PenDrawings.harmonograph(it) }, { PenDrawings.plant(it) },
            { PenDrawings.truchet(it) }, { PenDrawings.boids(it) }, { PenDrawings.twoHands(it) },
        )
        for (make in makers) {
            val a = make(3).nodes.map { it.to }
            assertTrue(a.size > 50)
            assertEquals(a, make(3).nodes.map { it.to })
            assertTrue(a != make(4).nodes.map { it.to })
        }
    }

    @Test
    fun plantsAreOneStroke() {
        // branches are walked back down to their forks rather than jumped from, so a plant never lifts
        for (seed in 0L until 5L) assertEquals(1, PenDrawings.plant(seed).pieces().size)
    }

    @Test
    fun twoHandsLabelsEverySubpath() {
        val hands = mutableListOf<Int>()
        val line = PenDrawings.twoHands(7, words = 8, hands = hands)
        assertEquals(line.pieces().size, hands.size)
        assertEquals(setOf(0, 1), hands.toSet())
        assertEquals(hands.size, PenCorpus.strokes(line).size)
    }

    @Test
    fun corpusIsResampledAtThePensSpacing() {
        val dir = Files.createTempDirectory("pen-corpus").toFile()
        try {
            val drawings = (0 until 20).map { PenDrawings.harmonograph(it.toLong()) }
            PenCorpus.write(dir.path, drawings)
            assertEquals(18, dir.resolve("train").listFiles()!!.size)
            assertEquals(2, dir.resolve("valid").listFiles()!!.size)
            val strokes = PenCorpus.strokes(drawings[0])
            val steps = strokes[0].zipWithNext { a, b -> a.distance(b).toDouble() }.dropLast(1)
            // chords between samples spaced 2.8 along the curve: never longer, shorter on the bends
            assertTrue(steps.all { it <= 2.81 }, "longest step ${steps.max()}")
            val median = steps.sorted()[steps.size / 2]
            assertTrue(median in 2.6..2.81, "median step $median")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun probeFindsACellThatTracksALabel() {
        // with the handwriting model, no cell should track an arbitrary label perfectly, but the
        // probe must rank by |r| and return every cell
        val hands = mutableListOf<Int>()
        val drawing = PenDrawings.twoHands(2, words = 6, hands = hands)
        val ranked = PenModel.distill.probe(listOf(drawing), listOf(hands))
        assertEquals(500, ranked.size)
        assertTrue(ranked.zipWithNext().all { (a, b) -> abs(a.second) >= abs(b.second) })
        assertTrue(abs(ranked[0].second) <= 1.0)
    }
}
