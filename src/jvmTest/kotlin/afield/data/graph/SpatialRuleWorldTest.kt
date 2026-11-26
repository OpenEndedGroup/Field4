package afield.data.graph

import afield.geometry.path.FLine
import afield.graphics.render.label
import afield.graphics.render.onClick
import afield.math.linalg.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpatialRuleWorldTest {
    @Test
    fun matchRetractionKeepsDrawingAndCanOverlapReentry() {
        SpatialRuleWorld().use { world ->
            var separation = 0.5f
            lateinit var right: SpatialPoint
            var drop = false
            var starts = 0
            var exits = 0
            world.source("tips") {
                point("tip", Vec3())
                right = point("tip", Vec3(separation, 0f, 0f))
                while (!drop) {
                    move(right, Vec3(separation, 0f, 0f))
                    nextFrame()
                }
            }
            world.rules("stitches") {
                onEnter("stitch", points("tip").pairsWithin(1.0, 2.0), condition = "nearby tips") { (a, b) ->
                    starts++
                    edge("thread", a, b)
                    edge("thread", a, b)
                    point("knot", a.pos, lifetime = SpatialLifetime.MATCH)
                    try {
                        while (match.live) {
                            draw(FLine())
                            nextFrame()
                        }
                        repeat(4) {
                            draw(FLine())
                            nextFrame()
                        }
                    } finally {
                        exits++
                    }
                }
            }

            world.step(0.0)
            assertTrue(world.points("tip").isEmpty())
            assertEquals(1, world.step(1.0).size)
            assertEquals(0, world.edges("thread").size)
            separation = 1.5f
            world.step(2.0)
            assertEquals(2, world.edges("thread").map { it.id }.distinct().size)
            world.step(3.0)
            assertEquals(1, starts)
            val knot = world.points("knot").single()
            val why = assertNotNull(world.explain(knot))
            assertEquals("stitches/stitch", why.origin.process)
            assertEquals("nearby tips", why.origin.condition)
            assertEquals(1.0, why.ageSeconds)
            assertEquals(2, why.inputs.count { it.kind == "tip" && it.live })

            separation = 2.5f
            world.step(4.0)
            separation = 0.5f
            assertEquals(1, world.step(5.0).size)
            assertEquals(false, assertNotNull(world.explain(knot)).matchLive)
            assertEquals(2, world.step(6.0).size)
            assertEquals(2, starts)
            assertFalse(knot.alive)

            drop = true
            world.step(7.0)
            assertEquals(2, world.step(8.0).size)
            assertFalse(right.alive)
            assertTrue(world.edges("thread").isEmpty())
            world.stop("stitches")
            assertEquals(2, exits)
            assertTrue(world.step(9.0).isEmpty())
        }
    }

    @Test
    fun thousandMovingPointsAgreeAcrossSerialAndParallelIndexes() {
        fun simulate(parallel: Boolean, factory: SpatialIndexFactory): List<Pair<Long, List<Long>>> =
            SpatialRuleWorld(parallelSearch = parallel, indexFactory = factory).use { world ->
                val starts = mutableListOf<Pair<Long, List<Long>>>()
                world.source("cloud") {
                    val cloud = List(1000) { i ->
                        point("tip", Vec3((i / 2) * 4f, (i % 2).toFloat(), 0f))
                    }
                    while (true) {
                        cloud.forEachIndexed { i, point ->
                            val z = if (i % 2 == 1 && frame % 3 == 2L) 2f else 0f
                            move(point, Vec3((i / 2) * 4f, (i % 2).toFloat(), z))
                        }
                        nextFrame()
                    }
                }
                world.rules("links") {
                    onEnter("pair", points("tip").pairsWithin(1.1)) { (a, b) ->
                        starts.add(frame to listOf(a.id, b.id))
                        while (match.live) nextFrame()
                    }
                }
                repeat(30) { world.step(it / 60.0) }
                starts
            }

        val factory = SpatialIndexFactory { positions, radius ->
            val grid = Vec3SpatialHash(radius)
            grid.rebuild(positions)
            SpatialNeighborIndex(grid::radiusNeighbors)
        }
        val serial = simulate(false, factory)
        assertEquals(5000, serial.size)
        assertEquals(simulate(true, factory), serial)
    }

    @Test
    fun replacingRulesLeavesPublisherAliveAndEdgesHaveIndependentMatches() {
        SpatialRuleWorld().use { world ->
            var enters = 0
            world.source("publisher") {
                val a = point("tip", Vec3())
                val b = point("tip", Vec3(1f, 0f, 0f))
                edge("line", a, b, mapOf("ready" to true))
                edge("line", a, b, mapOf("ready" to true))
                while (true) nextFrame()
            }

            fun install() = world.rules("reader") {
                onEnter("read", edges("line").where { it["ready"] == true }) {
                    enters++
                    while (match.live) nextFrame()
                }
            }

            install()
            world.step(0.0)
            world.step(1.0)
            val original = world.points("tip").map { it.id }
            assertEquals(2, enters)
            install()
            world.step(2.0)
            assertEquals(4, enters)
            assertEquals(original, world.points("tip").map { it.id })
        }
    }

    @Test
    fun timedPhasesFollowTheCallersClock() {
        SpatialRuleWorld().use { world ->
            val seen = mutableListOf<String>()
            var near = true
            world.source("pair") {
                val left = point("tip", Vec3())
                val right = point("tip", Vec3(1f, 0f, 0f))
                while (true) {
                    move(right, Vec3(if (near) 1f else 9f, 0f, 0f))
                    nextFrame()
                }
            }
            world.rules("phases") {
                onEnter("link", points("tip").pairsWithin(2.0)) { _ ->
                    whileLive { seen += "live ${age}" }
                    during(2.0) { progress -> seen += "fade $progress" }
                    wait(1.0)
                    seen += "done ${age}"
                }
            }
            world.step(10.0)
            world.step(11.0)
            world.step(12.0)
            near = false
            world.step(13.0)
            world.step(14.0)
            world.step(15.0)
            world.step(16.0)
            world.step(17.0)
            world.step(18.0)
            assertEquals(
                listOf("live 0.0", "live 1.0", "live 2.0", "fade 0.0", "fade 0.5", "done 6.0"),
                seen,
            )
        }
    }

    @Test
    fun edgesKnowTheirGeometry() {
        SpatialRuleWorld().use { world ->
            lateinit var bond: SpatialEdge
            lateinit var left: SpatialPoint
            world.source("bond") {
                left = point("seed", Vec3())
                val right = point("seed", Vec3(3f, 4f, 0f))
                nextFrame()
                bond = edge("bond", left, right)
                while (true) nextFrame()
            }
            world.step(0.0)
            world.step(1.0)
            world.step(2.0)
            assertEquals(5.0, bond.length, 1e-6)
            assertEquals(Vec3(1.5f, 2f, 0f), bond.at(0.5))
            assertEquals(bond.to, bond.other(left))
            assertEquals(5.0, left.distance(bond.to), 1e-6)
        }
    }

    @Test
    fun trianglesCloseOnceAndEndWithAnEdge() {
        SpatialRuleWorld().use { world ->
            var cut = false
            world.source("triangle") {
                val a = point("seed", Vec3())
                val b = point("seed", Vec3(1f, 0f, 0f))
                val c = point("seed", Vec3(0f, 1f, 0f))
                val d = point("seed", Vec3(5f, 5f, 0f))
                nextFrame()
                edge("bond", a, b)
                edge("bond", b, a)
                edge("bond", c, b)
                val ca = edge("bond", c, a)
                edge("bond", c, d)
                while (!cut) nextFrame()
                retract(ca)
                while (true) nextFrame()
            }
            val starts = mutableListOf<List<Long>>()
            val ends = mutableListOf<List<Long>>()
            world.rules("membranes") {
                onEnter("fill", edges("bond").triangles()) { triangle ->
                    starts += triangle.points.map { it.id }
                    assertEquals(triangle.a, triangle.ab.from.takeIf { it === triangle.a } ?: triangle.ab.to)
                    whileLive()
                    ends += triangle.points.map { it.id }
                }
            }
            repeat(4) { world.step(it.toDouble()) }
            assertEquals(listOf(listOf(1L, 2L, 3L)), starts)
            cut = true
            repeat(3) { world.step(4.0 + it) }
            assertEquals(listOf(listOf(1L, 2L, 3L)), ends)
        }
    }

    @Test
    fun lineageWalksBackThroughLiveProducers() {
        SpatialRuleWorld().use { world ->
            world.source("seeds") {
                point("seed", Vec3())
                point("seed", Vec3(10f, 0f, 0f))
                while (true) nextFrame()
            }
            world.rules("bonds") {
                onEnter("bond", points("seed").pairsWithin(20.0)) { (a, b) ->
                    edge("bond", a, b)
                    whileLive()
                }
            }
            world.rules("beads") {
                onEnter("bead", edges("bond")) { bond ->
                    point("bead", bond.at(0.5))
                    whileLive()
                }
            }
            repeat(5) { world.step(it.toDouble()) }
            val bead = assertNotNull(world.nearest(Vec3(5f, 1f, 0f), within = 2.0, kinds = setOf("bead")))
            assertEquals(listOf("bead", "bond", "seed", "seed"), world.lineage(bead).map { it.kind })
            assertEquals("bond", world.nearest(Vec3(2f, 0.5f, 0f), within = 1.0)?.kind)
            assertEquals(null, world.nearest(Vec3(2f, 5f, 0f), within = 1.0))
            assertEquals(bead, world.fact(bead.id))
        }
    }

    @Test
    fun aLinkLastsUntilItsLastEndGoes() {
        SpatialRuleWorld().use { world ->
            var drop = 0
            world.source("anchors") {
                val anchors = List(3) { point("anchor", Vec3(it.toFloat(), 0f, 0f)) }
                link("rope", anchors)
                var released = 0
                for (i in listOf(1, 0, 2)) {
                    while (drop == released) nextFrame()
                    released++
                    retract(anchors[i])
                }
                while (true) nextFrame()
            }
            val attached = mutableListOf<Int>()
            var ended = false
            world.rules("ropes") {
                onEnter("hang", links("rope")) { rope ->
                    whileLive { attached += rope.attached.size }
                    ended = true
                }
            }
            repeat(3) { world.step(it.toDouble()) }
            assertEquals(3, world.links("rope").single().attached.size)
            for (step in 1..3) {
                drop = step
                repeat(3) { world.step(2.0 + step * 3 + it) }
            }
            assertTrue(world.links("rope").isEmpty())
            assertTrue(ended)
            assertEquals(listOf(3, 2, 1), attached.distinct())
        }
    }

    @Test
    fun pointsCarryTheirVelocity() {
        SpatialRuleWorld().use { world ->
            lateinit var walker: SpatialPoint
            lateinit var sitter: SpatialPoint
            world.source("walk") {
                walker = point("tip", Vec3())
                sitter = point("tip", Vec3(5f, 0f, 0f))
                var x = 0f
                while (true) {
                    nextFrame()
                    x += 2f
                    move(walker, Vec3(x, 0f, 0f))
                }
            }
            world.step(0.0)
            world.step(0.5)
            assertEquals(Vec3(), walker.velocity)
            world.step(1.0)
            world.step(1.5)
            assertEquals(Vec3(4f, 0f, 0f), walker.velocity)
            assertEquals(Vec3(), sitter.velocity)
        }
    }

    @Test
    fun chanceDecidesOncePerBinding() {
        SpatialRuleWorld().use { world ->
            var apart = false
            world.source("cloud") {
                val tips = List(40) { point("tip", Vec3(it * 0.02f, 0f, 0f)) }
                while (true) {
                    tips.forEachIndexed { i, tip -> move(tip, Vec3(i * if (apart) 10f else 0.02f, 0f, 0f)) }
                    nextFrame()
                }
            }
            val kept = mutableListOf<Set<List<Long>>>()
            world.rules("coin") {
                val heads = points("tip").pairsWithin(1.0).chance(0.5)
                onEnter("count", heads) { _ -> whileLive() }
            }
            fun matched(): Set<List<Long>> {
                val now = mutableSetOf<List<Long>>()
                world.rules("probe") {
                    onEnter("probe", points("tip").pairsWithin(1.0).chance(0.5)) { (a, b) -> now += listOf(a.id, b.id) }
                }
                world.step(world.now + 1)
                world.step(world.now + 1)
                return now
            }
            world.step(0.0)
            world.step(1.0)
            kept += matched()
            apart = true
            world.step(world.now + 1)
            apart = false
            world.step(world.now + 1)
            kept += matched()
            assertTrue(kept[0].size in 300..480, "about half of 780 pairs, got ${kept[0].size}")
            assertEquals(kept[0], kept[1])
        }
    }

    @Test
    fun aComputedTreeKeepsTheProcessesOfEdgesItKeeps() {
        SpatialRuleWorld().use { world ->
            var spread = 1f
            world.source("seeds") {
                val seeds = List(4) { point("seed", Vec3(it.toFloat(), 0f, 0f)) }
                while (true) {
                    seeds.forEachIndexed { i, s -> move(s, Vec3(if (i == 3) 3f * spread else i.toFloat(), 0f, 0f)) }
                    nextFrame()
                }
            }
            val started = mutableListOf<List<Long>>()
            val ended = mutableListOf<List<Long>>()
            world.rules("tree") {
                val tree = pairs { _ ->
                    val seeds = points("seed")
                    val candidates = buildList {
                        for (i in seeds.indices) for (j in i + 1 until seeds.size) add(GraphEdge(i, j, seeds[i].distance(seeds[j])))
                    }
                    minimumSpanningForest(seeds.size, candidates).map { seeds[it.i] to seeds[it.j] }
                }
                onEnter("branch", tree) { (a, b) ->
                    started += listOf(a.id, b.id)
                    whileLive()
                    ended += listOf(a.id, b.id)
                }
            }
            repeat(3) { world.step(it.toDouble()) }
            assertEquals(listOf(listOf(1L, 2L), listOf(2L, 3L), listOf(3L, 4L)), started.sortedBy { it[0] })
            spread = -1f
            repeat(3) { world.step(3.0 + it) }
            assertEquals(listOf(listOf(3L, 4L)), ended)
            assertEquals(listOf(1L, 4L), started.last())
            assertEquals(4, started.size)
        }
    }

    @Test
    fun aClickedLineExplainsItsFact() {
        SpatialRuleWorld().use { world ->
            lateinit var tip: SpatialPoint
            world.source("tips") {
                tip = point("tip", Vec3())
                while (true) nextFrame()
            }
            world.step(0.0)
            world.step(1.0)
            var shown: SpatialExplanation? = null
            val line = world.inspectOnClick(tip, FLine()) { shown = it }
            assertEquals("spatial-fact-${tip.id}", line.label)
            assertNotNull(line.onClick)
            assertEquals(null, shown)
        }
    }
}
