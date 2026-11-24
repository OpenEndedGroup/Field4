package afield.geometry.mesh

import afield.math.linalg.Vec2
import afield.math.linalg.vec
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [relaxPath] on the field double spiral of an L-shaped room with a bay window. */
class MeshRelaxTest {

    private val plan = listOf(Vec2(-1f, -0.7f), Vec2(1f, -0.7f), Vec2(1f, 0.1f), Vec2(0.1f, 0.1f), Vec2(0.1f, 0.7f), Vec2(-1f, 0.7f)) +
        (0..16).map { i ->
            val a = PI * i / 16
            Vec2((-1.0 - 0.3 * kotlin.math.sin(a)).toFloat(), (0.25 + 0.25 * kotlin.math.cos(a)).toFloat())
        }

    /**
     * Relaxing keeps the ends where they were, never crosses, keeps laps about a spacing apart, and
     * shrinks the largest bare circle on the floor.
     */
    @Test
    fun relaxingTheFieldSpiralFillsTheRoomWithoutCrossing() {
        val spacing = 0.07
        val floor = outlineMesh(plan, 0.0125)
        val (supply, back) = floor.windingAngle(floor.geodesicDistanceFromBoundary()).fieldDoubleSpiral(spacing)
        val wall = outlineWallDistance(plan)
        val before = largestBareCircle(supply + back, wall, -1.35, 1.05, -0.75, 0.75, 0.01)

        val relaxed = relaxPath(supply, back, spacing, wall)
        val after = largestBareCircle(relaxed.points, wall, -1.35, 1.05, -0.75, 0.75, 0.01)
        println(
            "relaxed: ${relaxed.points.size} points, length ${relaxed.length}, closest ${relaxed.closest}, " +
                "tightest bend ${relaxed.tightestBend}, crossings ${relaxed.crossings}, bare circle $before -> $after (spacing $spacing)",
        )

        assertEquals(supply.first(), relaxed.points.first(), "the supply port does not move")
        assertEquals(back.last(), relaxed.points.last(), "the return port does not move")
        assertEquals(0, relaxed.crossings, "the pipe crosses itself")
        assertTrue(relaxed.closest > 0.7 * spacing, "laps come within ${relaxed.closest}")
        assertTrue(after < before, "relaxing did not shrink the largest bare circle ($before -> $after)")
    }

    /**
     * Aiming the cut at a port puts both ends of the spiral there, and the field spiral stays
     * uncrossed even where the cut is not monotone in distance from the walls (the port at
     * (0.79, -0.7) breaks two contours in two; chaining pieces end to end keeps them in order).
     */
    @Test
    fun portsAnywhereOnTheWallGiveUncrossedSpirals() {
        val spacing = 0.07
        val floor = outlineMesh(plan, 0.0125)
        val fromWalls = floor.geodesicDistanceFromBoundary()
        val wall = outlineWallDistance(plan)
        for (port in listOf(vec(0.79, 0.0, -0.7), vec(1.0, 0.0, -0.01), vec(-0.08, 0.0, 0.7))) {
            val (supply, back) = floor.windingAngle(fromWalls, port = port).fieldDoubleSpiral(spacing)
            val asLaid = relaxPath(supply, back, spacing, wall, steps = 0, finish = 0)
            assertEquals(0, asLaid.crossings, "the field spiral for the port at $port crosses itself")
            assertTrue(supply.first().distance(port) < 3 * spacing, "supply starts ${supply.first().distance(port)} from the port at $port")
            assertTrue(back.last().distance(port) < 3 * spacing, "return ends ${back.last().distance(port)} from the port at $port")
        }
    }
}
