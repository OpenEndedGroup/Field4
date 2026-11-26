package afield.data.graph

import afield.geometry.path.FLine
import afield.graphics.render.FLineIntersection
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
    fun inspectionClickRetainsTheDrawnSnapshotAndIgnoresAnotherFactsLabel() {
        SpatialRuleWorld().use { world ->
            lateinit var tip: SpatialPoint
            world.source("tip") {
                tip = point("tip", Vec3())
                while (true) nextFrame()
            }
            world.step(0.0)
            world.step(1.0) // Commit the source's newly published fact.
            val drawn = assertNotNull(world.explain(tip))
            var received: SpatialExplanation? = null
            val line = world.inspectOnClick(tip, FLine()) { received = it }
            val click = assertNotNull(line.onClick)
            click(FLineIntersection(-1, Vec3(), label = "another-fact"))
            assertEquals(null, received)
            world.step(3.0)
            click(FLineIntersection(-1, Vec3(), label = line.label))
            val explanation = assertNotNull(received)
            assertEquals(tip.id, explanation.id)
            assertEquals("tip", explanation.kind)
            assertEquals(drawn, explanation)
            assertTrue(assertNotNull(world.explain(tip)).ageSeconds > explanation.ageSeconds)
        }
    }

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
}
