package afield.graphics.scene.reading

import afield.graphics.text.*
import afield.math.linalg.Vector3f
import kotlin.test.*

class ReadingDriveRopeTest {
    private val font = Typefaces.named("Serif", 100)

    private fun player(graph: ReadingDiagram) =
        ReadingDiagramPlayer(graph, font, emptyMap(), diagramScore(graph, 10.0) {}, emptyMap())
            .apply { gravity = 0f; wind = 0f }

    private fun ReadingDiagramPlayer.at(p: DiagramParticle) = currentFrame().particle(p)

    @Test
    fun aPinnedDrivenParticleFollowsTheScoreExactly() {
        lateinit var p: DiagramParticle
        val graph = readingDiagram {
            p = point("slider", 0, 0, pinned = true)
            drive(p) { t -> Vector3f(t.toFloat() * .1f, 0f, 0f) }
        }
        val pl = player(graph)
        repeat(60) { pl.update(1 / 60.0) }
        assertEquals(.1f, pl.at(p).x, 1e-3f)
        pl.playback.seek(5.0)
        pl.update(1 / 60.0)
        assertEquals(.5f + .1f / 60, pl.at(p).x, 2e-3f)
    }

    @Test
    fun aFreeDrivenParticleSpringsAfterItsRest() {
        lateinit var p: DiagramParticle
        val graph = readingDiagram {
            p = point("lagging", 0, 0)
            drive(p) { t -> Vector3f(if (t < 1) 0f else .2f, 0f, 0f) }
        }
        val pl = player(graph)
        pl.playback.seek(1.0)
        pl.update(.05)
        val early = pl.at(p).x
        assertTrue(early > 0f && early < .15f, "lags behind its rest: $early")
        repeat(300) { pl.update(1 / 60.0) }
        assertEquals(.2f, pl.at(p).x, 2e-3f)
    }

    @Test
    fun aRopeIsALinkAndALine() {
        lateinit var a: DiagramParticle
        lateinit var b: DiagramParticle
        val graph = readingDiagram {
            a = point("hook", 0, 0, pinned = true)
            b = point("weight", 0, -.2f)
            rope("rope", a, b)
        }
        assertEquals(1, graph.links.size)
        val pl = player(graph)
        pl.update(.016)
        val line = graph.path("rope").make(pl.currentFrame())
        assertEquals(2, line.nodes.size)
        // A rope is a link, so a second one between the same two particles is refused like a second link.
        assertFailsWith<IllegalArgumentException> {
            readingDiagram {
                val x = point("x", 0, 0)
                val y = point("y", 1, 0)
                link(x, y)
                rope("r", x, y)
            }
        }
    }

    @Test
    fun anAnchorCanBeComputedFromTheFrame() {
        lateinit var mid: DiagramAnchor
        val graph = readingDiagram {
            val a = point("a", 0, 0, pinned = true)
            val b = point("b", .2f, 0, pinned = true)
            mid = anchor { Vector3f(position(a.anchor)).add(position(b.anchor)).mul(.5f).add(0f, time.toFloat(), 0f) }
        }
        val pl = player(graph)
        pl.playback.seek(.25)
        pl.update(.0)
        val at = pl.currentFrame().position(mid)
        assertEquals(.1f, at.x, 1e-5f)
        assertEquals(.25f, at.y, 1e-5f)
    }
}
