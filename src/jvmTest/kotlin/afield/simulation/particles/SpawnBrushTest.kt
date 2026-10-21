package afield.simulation.particles

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SpawnBrush] is what a drag in the renderer window becomes, and the window itself cannot be
 * dragged in a test: posting mouse events needs an Accessibility grant the build does not have.
 * So the pointer-to-paint rule, which is all of the painting that is not a GLFW read, is checked
 * here frame by frame against madp's mousedown / mousemove / mouseup handlers.
 */
class SpawnBrushTest {

    private fun run(vararg frames: Triple<Pair<Double, Double>, Boolean, Boolean>): List<Pair<Double, Double>> {
        val painted = mutableListOf<Pair<Double, Double>>()
        val brush = SpawnBrush(spacing = 8.0)
        frames.forEach { (at, down, inside) -> brush.step(at.first, at.second, down, inside) { x, y -> painted += x to y } }
        return painted
    }

    private fun frame(x: Double, y: Double, down: Boolean, inside: Boolean = true) = Triple(x to y, down, inside)

    @Test
    fun aPressLaysOneParticleWhereItIs() {
        assertEquals(listOf(100.0 to 50.0), run(frame(100.0, 50.0, down = true)))
    }

    @Test
    fun aDragLaysOneParticleEverySpacingAlongTheWay() {
        val painted = run(frame(0.0, 0.0, true), frame(80.0, 0.0, true))
        // the press, then ten more every 8 pixels up to and including the new position
        assertEquals(11, painted.size)
        assertEquals(0.0 to 0.0, painted.first())
        assertEquals(80.0 to 0.0, painted.last())
        painted.zipWithNext().forEach { (a, b) -> assertEquals(8.0, hypot(b.first - a.first, b.second - a.second), 1e-9) }
    }

    @Test
    fun aShortMoveStillLaysOneAsMadpsMousemoveDoes() {
        assertEquals(2, run(frame(0.0, 0.0, true), frame(3.0, 0.0, true)).size)
    }

    @Test
    fun holdingStillOrMovingWithoutTheButtonPaintsNothingMore() {
        val painted = run(
            frame(0.0, 0.0, true), frame(0.0, 0.0, true), frame(0.0, 0.0, true),
            frame(0.0, 0.0, false), frame(50.0, 50.0, false),
        )
        assertEquals(1, painted.size)
    }

    @Test
    fun aDragThatStartsOutsideTheWindowPaintsNothing() {
        assertTrue(run(frame(-10.0, 5.0, true, inside = false), frame(40.0, 5.0, true, inside = true)).isEmpty())
    }

    @Test
    fun aDragThatLeavesTheWindowKeepsPainting() {
        val painted = run(frame(10.0, 10.0, true), frame(-30.0, 10.0, true, inside = false))
        assertEquals(6, painted.size)
        assertEquals(-30.0 to 10.0, painted.last())
    }

    @Test
    fun releasingAndPressingAgainStartsANewStroke() {
        val painted = run(frame(0.0, 0.0, true), frame(0.0, 0.0, false), frame(200.0, 0.0, true))
        assertEquals(listOf(0.0 to 0.0, 200.0 to 0.0), painted)
    }
}
