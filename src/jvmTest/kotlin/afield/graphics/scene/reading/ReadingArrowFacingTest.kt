package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.graphics.text.*
import afield.math.linalg.Vector3f
import kotlin.math.abs
import kotlin.test.*

class ReadingArrowFacingTest {
    private val font = Typefaces.named("Serif", 100)

    private fun player(halfLife: Float): ReadingDiagramPlayer {
        val graph = readingDiagram {
            point("anchor", 0, 0, pinned = true)
            path("arrow", { FLine().moveTo(Vector3f(0f, 0f, 0f)).lineTo(Vector3f(.1f, 0f, 0f)) }) {
                arrow = .02f
                arrowFacingHalfLife = halfLife
            }
        }
        return ReadingDiagramPlayer(graph, font, emptyMap(), diagramScore(graph, 10.0) {}, emptyMap())
            .apply { gravity = 0f; wind = 0f }
    }

    /** The barb tails' offset from the tip, sideways from the line (x is along it). */
    private fun barb(p: ReadingDiagramPlayer): Vector3f {
        val tail = p.geometry.segments.last().a.p
        return Vector3f(tail).sub(.1f, 0f, 0f).apply { x = 0f }.normalize()
    }

    @Test
    fun withoutAReaderTheHeadStaysInThePlaneOfTheDiagram() {
        val p = player(0f)
        p.update(.016)
        assertTrue(abs(barb(p).z) < 1e-5f, "${barb(p)}")
    }

    @Test
    fun theHeadTurnsToFaceAReaderAbove() {
        val p = player(0f)
        p.setReaderPosition(Vector3f(.1f, 2f, 0f))
        p.update(.016)
        // Facing a reader overhead, the barbs spread in the floor plane: along z, not y.
        assertTrue(abs(barb(p).z) > .999f, "${barb(p)}")
    }

    @Test
    fun theTurnIsGradual() {
        val p = player(.6f)
        p.setReaderPosition(Vector3f(.1f, 2f, 0f))
        p.update(.05)
        val early = abs(barb(p).z)
        assertTrue(early > .01f && early < .5f, "after one short frame: $early")
        repeat(200) { p.update(.05) }
        assertTrue(abs(barb(p).z) > .999f, "settled: ${barb(p)}")
    }

    @Test
    fun rateScalesScoreTimeButNotPhysics() {
        val p = player(0f)
        p.playback.rate = .5
        p.update(.1)
        assertEquals(.05, p.playback.time, 1e-9)
        assertFailsWith<IllegalArgumentException> { p.playback.rate = -1.0 }
    }
}
