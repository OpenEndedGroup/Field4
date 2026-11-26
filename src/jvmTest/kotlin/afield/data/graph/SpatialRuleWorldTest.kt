package afield.data.graph

import afield.geometry.path.FLine
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
}
