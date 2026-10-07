package afield.stdlib

import afield.stacksl.cpu.StackSLCpu
import afield.stacksl.cpu.StackSLWires
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [StackSLEvolution] without a live model: rewinding, the watchmaker's plumbing through a scripted
 * proposer, the gradient step and crossbreeding. Pixel error at 16 px throughout, so nothing here
 * needs the LPIPS model and the whole file runs in seconds.
 */
class StackSLEvolutionTest {

    private val settings = StackSLEvolveSettings(
        mu = 6, lambda = 10, maxTokens = 24,
        stages = listOf(StackSLStage(dim = 16, metric = StackSLMetric.PIXEL, lpipsGrid = 0))
    )

    private fun evolution(target: String = "spot", s: StackSLEvolveSettings = settings) =
        StackSLEvolution({ dim -> StackSLTargets.byName(target, dim)!! }, s, seed = 3, parallel = false)

    /** The disc `spot` is, written by hand: what a watchmaker that understood the target would say. */
    private val disc = "[1,0.82,0.25] [0.03,0.04,0.12] p length 0.28 threshold mix"

    @Test
    fun `uv y runs up the picture, as the watchmaker is told`() {
        val r = StackSLCpu.renderStill("uv y", dim = 8, parallel = false)
        assertTrue(r.rgba[0] > r.rgba[(7 * 8) * 4], "row 0 is the top of the picture, so it must be the brighter")
    }

    @Test
    fun `the hand-written disc draws spot, so a test can lean on it`() {
        val e = evolution()
        assertTrue(e.layoutOf(StackSLProgram.of(disc)) > 0.9, "the disc program should draw the target")
    }

    @Test
    fun `restoring a snapshot and stepping on gives the same population as never stopping`() {
        val straight = evolution().step(6)
        val first = evolution().step(3)
        val json = StackSLEvolutionSnapshot.toJson(first.snapshot("half"))
        val resumed = evolution().restore(StackSLEvolutionSnapshot.fromJson(json)).step(3)
        assertEquals(straight.ranked.map { it.genome.text }, resumed.ranked.map { it.genome.text })
        assertEquals(straight.renders, resumed.renders)
        assertEquals(straight.generation, resumed.generation)
    }

    @Test
    fun `a rewind discards what came after it`() {
        val e = evolution().step(2)
        val before = e.snapshot()
        e.inject(listOf(disc), "proposed")
        assertEquals(disc, e.best!!.genome.text)
        e.restore(before)
        assertTrue(e.ranked.none { it.genome.text == disc })
    }

    @Test
    fun `a watchmaker round puts its programs in the population and is credited when it leads`() {
        val e = evolution().step(2)
        var seen: StackSLProposalRequest? = null
        e.watchmaker = StackSLProposer { request ->
            seen = request
            StackSLProposalReply(
                listOf(StackSLProposal(null, disc, "a disc"), StackSLProposal(1, "uv : bad ;", "broken")),
                StackSLModelUsage("scripted", 1, 100, 80, 20, 5)
            )
        }
        e.configure { it.copy(watchmakerEvery = 1) }.step(1)
        val request = assertNotNull(seen)
        assertEquals(settings.parents, request.parents.size)
        assertTrue(request.sheetPng.size > 100, "the model is shown the renders")
        assertTrue(request.targetColours.contains("[1.0,0.82,0.25]"), request.targetColours)
        assertEquals(disc, e.best!!.genome.text)
        assertEquals("watchmaker:new", e.best!!.origin)
        assertEquals(1, e.credit.getValue("watchmaker:new").leads)
        assertEquals(1, e.usage.calls)
        val refused = e.snapshot().lastRound.single { it.program.contains(":") }
        assertNotNull(refused.problem)
        // The next round is told what the last one did.
        assertTrue(StackSLWatchmakerPrompt.round(e.request()).contains("definitions are not allowed"))
    }

    @Test
    fun `the watchmaker is never shown the held-out layout check or the target's name`() {
        val e = evolution().step(1)
        val text = StackSLWatchmakerPrompt.round(e.request())
        assertTrue("layout" !in text.lowercase() && "kappa" !in text.lowercase(), text)
        assertTrue("spot" !in text.lowercase(), "the target's name says what to draw: $text")
    }

    @Test
    fun `replies are read line by line and nothing is repaired`() {
        val (ok, unread) = StackSLWatchmakerPrompt.parse(
            """
            1 | p length 0.3 threshold | tighter disc
            0 | uv x 0.5 threshold
            #2 | uv y sin | stripes
            here are my ideas:
            """.trimIndent(), parents = 4
        )
        assertEquals(listOf(1, null, 2), ok.map { it.parent })
        assertEquals("uv x 0.5 threshold", ok[1].program)
        assertEquals(1, unread.size)
    }

    @Test
    fun `a gradient step moves the radius that is asked for and nothing else`() {
        val e = evolution().step(1)
        val seed = StackSLProgram.of("[1,0.82,0.25] [0.03,0.04,0.12] p length 0.4 threshold mix")
        val out = e.fit(seed, literals = listOf(4), iterations = 40, learningRate = 0.02)
        val fitted = assertNotNull(out.fitted, out.problem)
        val tokens = StackSLProgram.of(fitted).tokens
        assertEquals(seed.tokens.take(4), tokens.take(4), "the colours were not asked for and must not move")
        val radius = tokens[4].toDouble()
        assertTrue(radius in 0.22..0.34, "the radius should move toward 0.28, got $radius from 0.4")
        assertTrue(out.skillAfter!! > out.skillBefore!!)
    }

    @Test
    fun `token columns name the same literals the compiler lifts`() {
        val text = "[1,0.82,0.25] [0.03,0.04,0.12] p length 0.4 threshold mix"
        val columns = StackSLProposals.tokenColumns(text)
        val wire = StackSLWires.lift(text).literalWires.single { it.token == "0.4" }
        assertEquals(columns[4], wire.column)
    }

    @Test
    fun `crossbreeding brings another population's leader in`() {
        val other = evolution().step(1).also { it.inject(listOf(disc)) }.snapshot("other branch")
        val e = evolution().step(1)
        e.crossbreed(other, count = 2)
        assertEquals(disc, e.best!!.genome.text)
        assertTrue(e.notes.last().contains("other branch"))
    }

    @Test
    fun `the archive keeps every population it is given`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "stacksl-archive-${System.nanoTime()}")
        try {
            val archive = StackSLPopulationArchive(dir)
            val a = archive.add(evolution().step(1).snapshot())
            val b = archive.add(evolution().step(2).snapshot("two"))
            assertEquals(listOf(a, b), archive.list().map { it.first })
            assertEquals(2, archive.get(b)!!.generation)
            assertNull(archive.get("p999"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a schedule re-scores the population when its stage begins`() {
        val e = evolution(s = settings.copy(stages = listOf(
            StackSLStage(generations = 2, dim = 8, metric = StackSLMetric.PIXEL, lpipsGrid = 0),
            StackSLStage(dim = 16, metric = StackSLMetric.PIXEL, lpipsGrid = 0)
        ))).step(4)
        assertEquals(16, e.target.raster.width)
        assertTrue(e.notes.any { it.contains("stage PIXEL at 16px") }, e.notes.toString())
        assertTrue(e.improvements.any { it.origin.startsWith("stage") })
    }
}
