package afield.graphics.scene.reading

import afield.graphics.text.*
import afield.graphics.text.slug.SlugPath
import afield.math.linalg.*
import kotlin.math.*
import kotlin.test.*

class ReadingTextFacingTest {
    private val font = Typefaces.named("Serif", 100)
    private fun player(graph: ReadingDiagram): ReadingDiagramPlayer {
        val atlas = SlugPath.decode(requireNotNull(javaClass.getResource("/afield/slug/curved-hole.bin")).readBytes())
        val runs = graph.texts.associateWith { run ->
            val layout = layoutText(run.text, font)
            ReadingText(run.text, layout.advanceWidth.toFloat(), layout.toFLine(), atlas, layout)
        }
        return ReadingDiagramPlayer(graph, font, runs.mapKeys { it.key.id },
            diagramScore(graph, 10.0) {}, runs).apply {
            wind = 0f; gravity = 0f; playback.playing = false
        }
    }

    @Test fun planeFacesTheReaderWithGravityUpAndSafePoles() {
        for (reader in listOf(Vector3f(1f,.4f,2f), Vector3f(-2f,-.2f,-1f), Vector3f(0f,2f,0f))) {
            val q = readingFacingTarget(Vector3f(), reader, null)
            assertTrue(q.transform(Vector3f(0f,0f,1f)).distance(Vector3f(reader).normalize()) < 1e-5f)
            assertTrue(q.transform(Vector3f(0f,1f,0f)).y >= -1e-5f)
        }
        val previous = Quaternionf().rotateY(.7f)
        assertTrue(abs(previous.dot(readingFacingTarget(Vector3f(), Vector3f(.001f,0f,0f), previous))) > .999999f)
    }

    @Test fun anchorsAndLabelsShareFullOrientationIncludingLocalRoll() {
        val graph = readingDiagram {
            text("a", "turning words", point("p", .3, -.2, .1, pinned = true)) {
                faceReader = true
                rotation = .2f
                orientation.rotateY(.1f)
            }
        }
        val p = player(graph)
        p.setReaderPosition(Vector3f(1f,.5f,2f))
        p.update(.02)
        val f = p.frame()
        val run = graph.text("a")
        val label = p.labels.single()
        val x = f.measure(run).ink.right
        val y = f.measure(run).ink.top
        val gpu = Matrix4f().translation(label.position).rotate(label.orientation).rotateZ(label.rotation).scale(label.scale)
            .transformPosition(Vector3f(x - p.shape(run).advance * .5f, y, 0f))
        assertTrue(f.textPoint(run,x,y).distance(gpu) < 1e-5f)
    }

    @Test fun turningIsRateLimitedAndZeroTimeReadsDoNotAdvanceIt() {
        val graph = readingDiagram {
            text("a", "still", point("p", 0, 0, pinned = true)) { faceReader = true }
        }
        val p = player(graph)
        p.update(.02)
        val before = Quaternionf(p.labels.single().orientation)
        p.setReaderPosition(Vector3f(2f,0f,-1f))
        p.update(0.0)
        assertTrue(abs(before.dot(p.labels.single().orientation)) > .999999f)
        p.update(.1)
        val after = p.labels.single().orientation
        val angle = 2 * acos(abs(before.dot(after)).coerceIn(0f,1f))
        assertTrue(angle > .01f && angle <= .182f)
    }

    @Test fun endpointsFollowEachSeparatelyRotatingRun() {
        val graph = readingDiagram {
            val a = text("a", "here", point("pa", -.4, 0, .3, pinned = true)) { faceReader = true }
            val b = text("b", "elsewhere", point("pb", .5, .1, -.5, pinned = true)) { faceReader = true }
            connection("thread", a.right, b.left)
        }
        val p = player(graph)
        p.update(.02)
        p.setReaderPosition(Vector3f(-1f,.3f,-.2f))
        repeat(20) { p.update(.01) }
        val f = p.frame()
        val nodes = graph.connection("thread").nodes(f)
        assertTrue(nodes.first().distance(f.position(graph.text("a").right)) < 1e-6f)
        assertTrue(nodes.last().distance(f.position(graph.text("b").left)) < 1e-6f)
        assertTrue(p.individualTextFacing)
    }
}
