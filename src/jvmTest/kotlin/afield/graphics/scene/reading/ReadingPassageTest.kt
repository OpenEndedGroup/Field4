package afield.graphics.scene.reading

import afield.math.linalg.Vector3f
import afield.geometry.path.FLine
import afield.graphics.text.Typefaces
import kotlin.test.*

class ReadingPassageTest {
    @Test fun endpointDotsRemainOnFullCurvesWhileTheirGroupFades() {
        val graph = readingDiagram {
            group("arcs") {
                val a = point("a", 0, 0, pinned = true)
                val b = point("b", 1, 0, pinned = true)
                endpointDots(arc("curve", a.anchor, b.anchor, rise = .2f))
                endpointDots(path("closed", shape = { FLine().moveTo(2, 0).lineTo(3, 0).lineTo(3, 1).close() }))
            }
        }
        val score = diagramScore(graph, 4.0) {
            fadeIn(graph.group("arcs"), at = 0.0, seconds = 2.0)
        }
        val player = ReadingDiagramPlayer(graph, Typefaces.named("Serif", 100), emptyMap(), score)
        player.playback.seek(1.0)
        val frame = player.frame()
        assertTrue(frame.position(graph.dot("curve-endpoint-0").at).distance(Vector3f()) < 1e-6f)
        assertTrue(frame.position(graph.dot("curve-endpoint-1").at).distance(Vector3f(1f, 0f, 0f)) < 1e-6f)
        assertTrue(frame.position(graph.dot("closed-endpoint-1").at).distance(Vector3f(2f, 0f, 0f)) < 1e-6f)
        assertTrue(score.pose(graph.path("curve"), 1.0).opacity in .1f.. .9f)
    }
    @Test fun retainedParsesComeFromThePoemAndHaveConsistentTopology() {
        for (i in 2..4) {
            val sentence = ReadingPassage.sentence(i)
            assertTrue(ReadingPassage.text.contains(sentence.text))
            assertTrue(sentence.problems().isEmpty(), sentence.problems().joinToString())
            assertTrue(sentence.tokens.size >= 8)
        }
    }

    @Test fun walkingUsesYawAndAnalogInputWithoutDiagonalSpeedupOrReconnectJumps() {
        val forward = readingWalk(0f, 1f, Vector3f(0f, -.7f, -1f), .01)
        assertTrue(forward.distance(Vector3f(0f, 0f, -.0065f)) < 1e-6f)
        val strafe = readingWalk(1f, 0f, Vector3f(1f, .4f, 0f), .01)
        assertTrue(strafe.distance(Vector3f(0f, 0f, .0065f)) < 1e-6f)
        assertEquals(0f, readingWalk(.1f, .1f, Vector3f(0f, 0f, -1f), .01).length())
        assertTrue(readingWalk(1f, 1f, Vector3f(0f, 0f, -1f), .01).length() <= .006501f)
        assertTrue(readingWalk(0f, 1f, Vector3f(0f, 0f, -1f), 10.0).length() <= .032501f)
        assertEquals(0f, readingWalk(0f, 1f, Vector3f(0f, 1f, 0f), .01).length())
    }
}
