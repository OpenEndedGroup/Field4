package afield.study.search

import afield.app.protocol.Set_output
import afield.document.graph.Box
import afield.lang.rpn.ColorRpn
import afield.lang.rpn.colorRpnEvolution
import afield.ui.output.getStringCallback
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [EyeEvolution] without a browser: the grid it puts in the box's output panel, and the click path,
 * driven by posting the same JSON the grid's script posts to the `string-update` callback it registers.
 */
class EyeEvolutionTest {

    private fun panel(box: Box, channel: String): String =
        Json.decodeFromJsonElement(Set_output.serializer(), box.output.retainedAreas.snapshot(box.id).first { it.name == "set-output" }.payload!!).value

    private fun actionId(html: String) = Regex("action:\"(eye-evolution-[0-9a-f-]+)\"").find(html)!!.groupValues[1]

    /** A genome of one integer, drawn as a 1×1 PNG, so the test is about the loop and not the pictures. */
    private fun intGrid(box: Box) = box.evolveByEye(
        parents = listOf(10, 20),
        breed = { ps, rng -> Offspring(ps[0] + ps[1] + rng.nextInt(3), "sum") },
        picture = { _ -> ColorRpn.compile("0.5").png(1) },
        fresh = { rng -> rng.nextInt(100) },
        seed = 4,
    )

    @Test
    fun theGridShowsParentsThenChildren() {
        val box = Box("eye-evolution-grid")
        val e = intGrid(box)
        assertEquals(9, e.tiles.size)
        assertEquals(listOf(10, 20), e.tiles.take(2).map { it.genome })
        assertTrue(e.tiles.drop(2).all { it.genome in 30..32 && it.note == "sum" })
        val html = panel(box, "evolve")
        assertEquals(9, Regex("<article class=\"ee-tile").findAll(html).count())
        assertEquals(2, Regex("<article class=\"ee-tile ee-parent\"").findAll(html).count())
    }

    @Test
    fun aClickedPairBecomesTheNextParents() {
        val box = Box("eye-evolution-pick")
        val e = intGrid(box)
        val picked = listOf(e.tiles[3].genome, e.tiles[7].genome)
        var told: List<Int> = emptyList()
        e.onPick = { told = it }
        val callback = getStringCallback(actionId(panel(box, "evolve")))!!
        callback("""{"act":"pick","tiles":[3,7]}""")
        val deadline = System.currentTimeMillis() + 10_000
        while (e.generation < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        while (told.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(picked, e.parents)
        assertEquals(picked, told)
        assertEquals(listOf(listOf(10, 20), picked), e.history)
        e.restart()
        assertEquals(listOf(10, 20), e.parents)
    }

    @Test
    fun breedFromUsesCrossoverAndFallsBackToAMutation() {
        val withCrossover = object : GenomeOps<Int> {
            override fun random(rng: Random) = 0
            override val mutations = listOf(Variation<Int>("inc") { g, _ -> g + 1 })
            override fun crossover(a: Int, b: Int, rng: Random) = a * 100 + b
        }
        val c = breedFrom(withCrossover, listOf(1, 2), Random(1))
        assertTrue(c.genome == 102 || c.genome == 201, "crossover of the two distinct parents: ${c.genome}")
        assertEquals("cross", c.note)
        val without = object : GenomeOps<Int> {
            override fun random(rng: Random) = 0
            override val mutations = listOf(Variation<Int>("inc") { g, _ -> g + 1 })
        }
        assertEquals("inc", breedFrom(without, listOf(5, 5), Random(1)).note)
    }

    @Test
    fun theGaGridHasTheDemosToolbarAndTiles() {
        val box = Box("eye-evolution-ga")
        val ga = box.colorRpnEvolution("x 9 * sin 0.18 +\ny 0.55 +\n0.62\nhsl", "a 14 * cos 0.14 +\nr 0.78 * 0.12 +\n0.48\nhsl", resolution = 64, seed = 2)
        val html = panel(box, "ga")
        for (word in listOf("Reroll", "Fresh Parents", "Start over", "data-setting=\"mut\"", "data-setting=\"len\"", "data-setting=\"res\"", "parent a", "child 7"))
            assertTrue(word in html, "the grid has $word")
        assertEquals("x 9 * sin 0.18 + y 0.55 + 0.62 hsl", ga.parents[0].joinToString(" "))
        assertTrue(ga.tiles.drop(2).all { it.genome.size in 1..24 })
    }
}
