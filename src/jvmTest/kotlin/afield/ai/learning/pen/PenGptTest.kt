package afield.ai.learning.pen

import afield.geometry.path.FLine
import org.junit.Assume
import java.io.File
import java.util.SplittableRandom
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The PenGPT port: the `.npz` reader, the tokenizer, the decoder and the [StrokeModel] adapter, on
 * the two-layer random model from PenGPT's own tests (`tiny.model.npz`, Apache 2.0, with merges
 * `(0,0)->10, (10,6)->11, (2,2)->12, (12,0)->13`). Where the released handwriting model is already
 * in the cache, also that it writes words of a sensible size and carries a hand on from examples.
 */
class PenGptTest {
    private val tiny by lazy { PenGpt.load(File(javaClass.getResource("/afield/pen/pengpt/tiny.model.npz")!!.toURI())) }

    @Test
    fun readsTheModelFile() {
        assertEquals(listOf("", "ann", "bo"), tiny.writers)
        assertEquals(1, tiny.format)
        assertTrue(tiny.writesText)
        assertEquals(0.0125, tiny.grid)
        assertEquals(40, tiny.contextLength)
        assertEquals(2, tiny.nLayer)
        // 10 base symbols, 4 merges, then PAD END WORD BOS
        assertEquals(18, tiny.vocab)
        assertEquals(17, tiny.bos)
    }

    @Test
    fun mergesApplyInOrderAndLeftToRight() {
        assertContentEquals(intArrayOf(11), tiny.merge(intArrayOf(0, 0, 6)))
        assertContentEquals(intArrayOf(10, 0, 6), tiny.merge(intArrayOf(0, 0, 0, 6)))
        assertContentEquals(intArrayOf(10, 10), tiny.merge(intArrayOf(0, 0, 0, 0)))
        assertContentEquals(intArrayOf(13, 2), tiny.merge(intArrayOf(2, 2, 0, 2)))
        // a merged token stands for its moves: 10 is two steps in +x, 13 two in +y then one in +x
        assertEquals(2, tiny.deltas[2 * 10]); assertEquals(0, tiny.deltas[2 * 10 + 1])
        assertContentEquals(intArrayOf(0, 1, 0, 2, 1, 2), tiny.paths[13])
    }

    @Test
    fun everyMoveBetweenCellsIsAWalkInEightDirections() {
        val random = SplittableRandom(1)
        repeat(500) {
            val dx = random.nextInt(-20, 21); val dy = random.nextInt(-20, 21)
            val g = tiny.grid
            // two down points dx, dy cells apart: DOWN, the walk, UP
            val base = tiny.encodeBase(listOf(doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(dx * g, dy * g, 1.0)))
            assertEquals(PenGpt.DOWN, base.first())
            assertEquals(PenGpt.UP, base.last())
            val walk = base.copyOfRange(1, base.size - 1)
            assertEquals(max(abs(dx), abs(dy)), walk.size, "($dx, $dy)")
            assertTrue(walk.all { it in 0 until 8 })
            assertEquals(dx, walk.sumOf { PenGpt.DIRECTIONS[2 * it] })
            assertEquals(dy, walk.sumOf { PenGpt.DIRECTIONS[2 * it + 1] })
        }
    }

    @Test
    fun decodingEncodedInkGivesItBackOnTheGrid() {
        val g = tiny.grid
        // a straight stroke (which smoothing leaves alone), a lift, and a dot
        val ink = (0..8).map { doubleArrayOf(it * g, 3 * g, 1.0) } + listOf(doubleArrayOf(8 * g, 3 * g, 0.0)) +
            listOf(doubleArrayOf(4 * g, 6 * g, 1.0), doubleArrayOf(4 * g, 6 * g, 0.0))
        val tokens = tiny.encode(ink)
        val back = tiny.decode(tokens)
        val downs = back.filter { it[2] == 1.0 }
        assertEquals(10, downs.size)
        for ((a, b) in ink.filter { it[2] == 1.0 }.zip(downs)) {
            assertEquals(a[0], b[0], 1e-9); assertEquals(a[1], b[1], 1e-9)
        }
        assertEquals(2, back.count { it[2] == 0.0 }, "one pen-up after each stroke")
    }

    @Test
    fun generationIsDeterministicAndBounded() {
        val prompt = PenGptPrompt("ab", "ann")
        val greedy = tiny.generate(prompt)
        assertContentEquals(greedy, tiny.generate(prompt))
        assertTrue(greedy.size <= tiny.contextLength - 1)
        val warm = PenGptSampling(temperature = 1.0)
        assertContentEquals(tiny.generate(prompt, sampling = warm, seed = 3), tiny.generate(prompt, sampling = warm, seed = 3))
        assertNotEquals(tiny.generate(prompt, sampling = warm, seed = 3).toList(), tiny.generate(prompt, sampling = warm, seed = 4).toList())
        // the writer is part of what the model is told
        assertNotEquals(greedy.toList(), tiny.generate(PenGptPrompt("ab", "bo")).toList())
    }

    @Test
    fun aPrefixIsReadBeforeTheModelCarriesOn() {
        val prompt = PenGptPrompt("ab")
        val alone = tiny.generate(prompt)
        val prefix = intArrayOf(PenGpt.DOWN, 0, 0, 6, PenGpt.UP)
        val after = tiny.generate(prompt, prefix)
        assertTrue(after.size <= tiny.contextLength - 1 - prefix.size)
        assertNotEquals(alone.toList(), after.toList())
    }

    @Test
    fun theStrokeModelDrivesAPenLineAndForksAlike() {
        val model = tiny.strokes(PenGptPrompt("ab", "ann"), PenGptSampling(temperature = 1.0))
        val primer = FLine().moveTo(0.0, 0.0).lineTo(5.0, -3.0).lineTo(9.0, 0.0)
        val line = PenLine(primer, model, scale = 100.0, seed = 2)
        line.grow(30)
        assertEquals(30, line.history.size)
        val a = line.pen.fork(9); val b = line.pen.fork(9)
        repeat(20) { assertEquals(a.next(), b.next()) }
        // a finished pen keeps answering, with the pen up
        val done = model.pen(0)
        repeat(200) { done.next() }
        assertTrue(done.next().lift)
    }

    @Test
    fun aStyleMixedFromOneWriterIsThatWriter() {
        val alone = tiny.generate(PenGptPrompt("ab", "ann"))
        assertContentEquals(alone, tiny.generate(PenGptPrompt("ab", style = tiny.style("ann" to 1.0))))
        assertNotEquals(alone.toList(), tiny.generate(PenGptPrompt("ab", style = tiny.style("ann" to 0.5, "bo" to 0.5))).toList())
    }

    @Test
    fun theBundledCorporaModelDrawsWhatItIsToldTo() {
        val m = PenGpt.corpora
        // told labels, as handwriting is, but a drawing model: its meta says so
        assertEquals(1, m.format)
        assertEquals(false, m.writesText)
        assertEquals(listOf("", "boids", "cracks", "echoes", "harmonograph", "hershey", "michaux", "mixed", "plants", "truchet"), m.writers)
        fun aspect(d: FLine): Double {
            val p = d.pieces().flatMap { resampleForPen(it, 1.0) }
            return (p.maxOf { it.x } - p.minOf { it.x }).toDouble() / (p.maxOf { it.y } - p.minOf { it.y })
        }
        // a Truchet tiling is a grid of arcs nine cells wide and five high; a Michaux field is columns
        // of lines longer than the columns are wide
        val truchet = (0L until 3L).map { aspect(m.draw("truchet", temperature = 0.8, seed = it)) }
        val michaux = (0L until 3L).map { aspect(m.draw("michaux", temperature = 0.8, seed = it)) }
        assertTrue(truchet.all { it > 1.4 }, "truchet width / height $truchet")
        assertTrue(michaux.average() < 1.2, "michaux width / height $michaux")
    }

    @Test
    fun aNamedModelThatIsNotThereSaysWhereItLooked() {
        val e = assertFailsWith<IllegalArgumentException> { PenGpt.named("no-such-pengpt-model") }
        assertTrue(File(PenGpt.folder, "no-such-pengpt-model.npz").path in e.message!!, e.message)
    }

    @Test
    fun theReleasedHandWritesWordsOfASensibleSize() {
        Assume.assumeTrue("the PenGPT handwriting weights are not in the cache", PenGpt.HAND_WEIGHTS.isPresent)
        val hand = PenGpt.hand
        assertEquals(1193, hand.writers.size)
        val words = hand.write("hello there", writer = hand.writers[40], scale = 100.0)
        assertEquals(2, words.size)
        for (w in words) {
            val ys = w.pieces().flatMap { resampleForPen(it, 1.0) }.map { it.y }
            val height = ys.max() - ys.min()
            assertTrue(height in 10f..60f, "a word ${height} units high at scale 100")
        }
        val shown = hand.write("garden", writer = hand.writers[300])
        val like = hand.writeLike(listOf("garden" to shown[0]), "river bank")
        assertEquals(2, like.size)
        assertTrue(like.all { it.nodes.size > 10 })
    }
}
