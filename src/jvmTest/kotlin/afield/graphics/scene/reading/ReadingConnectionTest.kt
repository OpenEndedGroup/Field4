package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.graphics.text.*
import afield.graphics.text.slug.SlugPath
import afield.math.linalg.Vector3f
import kotlin.math.PI
import kotlin.test.*

class ReadingConnectionTest {
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

    private fun graph() = readingDiagram {
        val a = text("a", "slow water", point("a-point", -.6, 0, pinned = true))
        val b = text("b", "moves", point("b-point", .6, 0, pinned = true))
        connection("thread", a.right, b.left, interiorNodes = 5) {
            bow.set(0f, -.2f, .03f)
        }
    }

    @Test fun endpointsStickWhileInteriorsLagThenSettle() {
        val graph = graph()
        val player = player(graph)
        val thread = graph.connection("thread")
        player.update(1.0 / 120)
        val before = thread.nodes(player.frame())
        graph.point("b-point").rest.y += .5f
        player.update(1.0 / 120)
        val frame = player.frame()
        val after = thread.nodes(frame)
        assertEquals(7, after.size)
        assertTrue(after.first().distance(frame.position(graph.text("a").right)) < 1e-6f)
        assertTrue(after.last().distance(frame.position(graph.text("b").left)) < 1e-6f)
        assertTrue(after[3].distance(before[3]) < .03f, "Interior jumped to the moved endpoint")
        repeat(600) { player.update(1.0 / 120) }
        val settled = thread.nodes(player.frame())
        val middle = Vector3f(settled.first()).lerp(settled.last(), .5f).add(thread.bow)
        assertTrue(settled[3].distance(middle) < .005f, "Thread did not settle: ${settled[3]} / $middle")
    }

    @Test fun slidingUnderlineMarginsAndBoundaryUseRotatedMultilineMetrics() {
        val graph = readingDiagram {
            text("lines", "a much wider line\nshort", point("p", 0, 0, pinned = true)) {
                rotation = (PI / 3).toFloat()
                scale = 1.7f
            }
        }
        val player = player(graph)
        val run = graph.text("lines")
        val f = player.frame()
        val last = f.measure(run.line(1))
        val expected = f.textPoint(run, last.ink.right, last.ink.centerY)
        val toward = Vector3f(expected).add(f.textDirection(run, .05f, 0f, .01f))
        val margin = run.attach(DiagramTextSite.RIGHT_MARGIN).resolve(f, toward)
        assertTrue(margin.distance(expected) < 1e-5f)
        // A short line's margin must not use the longest line's right bound.
        assertTrue(margin.distance(f.position(run.inkBounds.right)) > .01f)
        val word = run.word("short")
        val m = f.measure(word)
        val underline = f.textPoint(run, m.advance.centerX, m.baseline)
            .add(f.textDirection(run, 0f, -.014f, 0f))
        assertTrue(word.attach(DiagramTextSite.UNDERLINE).resolve(f, underline).distance(underline) < 1e-5f)
        val center = f.position(word.inkBounds.center)
        assertTrue(word.attach(DiagramTextSite.CLOSEST).resolve(f, center).distance(center) < 1e-5f)
        assertTrue(word.attach(DiagramTextSite.BOUNDARY).resolve(f, center).distance(center) > .001f)
        // Collapsed text still gives finite anchors, rather than NaNs from zero-length edges.
        run.scale = 0f
        assertTrue(word.attach(DiagramTextSite.BOUNDARY).resolve(player.frame(), toward).isFinite())
    }

    @Test fun splineAndScoreOffsetsNeverDetachEndpointsAndSnapshotsArePure() {
        val graph = graph()
        val player = player(graph)
        val thread = graph.connection("thread")
        player.update(.02)
        player.playback.score = diagramScore(graph, 10.0) {
            move(thread.path, Vector3f(), Vector3f(0f, .3f, 0f), 0.0, 1.0)
        }
        player.playback.seek(1.0)
        graph.text("a").rotation = .2f
        val f = player.frame()
        val before = thread.nodes(f)
        val curve = f.path(thread.path)
        assertTrue(curve.nodes.first().to.distance(f.position(graph.text("a").right)) < 1e-6f)
        assertTrue(curve.nodes.last().to.distance(f.position(graph.text("b").left)) < 1e-6f)
        val control = (curve.nodes[1] as FLine.CubicTo).c1
        thread.tension = 1f
        val tight = player.frame().path(thread.path)
        assertTrue((tight.nodes[1] as FLine.CubicTo).c1.distance(tight.nodes[0].to) < 1e-6f)
        assertTrue(control.distance((tight.nodes[1] as FLine.CubicTo).c1) > .001f)
        repeat(10) { player.update(0.0); thread.nodes(player.frame()) }
        assertEquals(before, thread.nodes(player.frame()))
        assertFailsWith<IllegalArgumentException> { thread.responseSeconds = Float.NaN; player.update(.02) }
    }

    @Test fun lengthAndCurvatureMinimizationAreIndependentPhysicalControls() {
        fun build() = readingDiagram {
            val a = point("a", -1, 0, pinned = true)
            val b = point("b", 1, 0, pinned = true)
            connection("rope", a.anchor, b.anchor, 6) {
                bow.set(0f, .5f, 0f)
                layoutStrength = 0f
                lengthStiffness = 100f
                curvatureStiffness = 0f
            }
        }
        // Include no text here: a connector is equally useful between unlabelled anchors.
        val stable = player(build())
        val minimized = player(build())
        val curved = player(build())
        minimized.diagram.connection("rope").lengthMode = DiagramConnectionLength.MINIMIZE
        curved.diagram.connection("rope").apply { lengthStiffness = 0f; curvatureStiffness = 300f }
        fun length(p: ReadingDiagramPlayer) = p.diagram.connection("rope").nodes(p.frame())
            .zipWithNext { a, b -> a.distance(b) }.sum()
        repeat(1200) { stable.update(1.0 / 120); minimized.update(1.0 / 120); curved.update(1.0 / 120) }
        assertTrue(length(stable) > 2.2f)
        assertTrue(length(minimized) < length(stable) - .1f)
        assertTrue(length(curved) < length(stable) - .1f)
    }

    @Test fun curvatureForceIsTheNegativeEnergyGradient() {
        val points = List(6) { Vector3f(it.toFloat(), if (it == 2) .3f else 0f, 0f) }
        val forces = connectionCurvatureForces(points)
        fun energy() = (1 until points.lastIndex).sumOf { i ->
            (Vector3f(points[i - 1]).fma(-2f, points[i]).add(points[i + 1]).lengthSquared() * .5).toDouble()
        }
        val epsilon = .0001f
        for (i in 1 until points.lastIndex) {
            points[i].y += epsilon
            val plus = energy()
            points[i].y -= 2 * epsilon
            val minus = energy()
            points[i].y += epsilon
            assertEquals((-(plus - minus) / (2 * epsilon)).toFloat(), forces[i].y, .002f)
        }
        assertEquals(Vector3f(), forces.first())
        assertEquals(Vector3f(), forces.last())
    }

    @Test fun stateIsPlayerLocalAndForeignAnchorsAreRejected() {
        val graph = graph()
        val a = player(graph)
        val b = player(graph)
        a.update(.02); b.update(.02)
        val thread = graph.connection("thread")
        val untouched = thread.nodes(b.frame())
        graph.point("a-point").rest.y += .5f
        a.update(.05)
        assertEquals(untouched, thread.nodes(b.frame()))
        a.resetPositions()
        assertTrue(thread.nodes(a.frame()).all { it.isFinite() })
        assertFailsWith<IllegalArgumentException> {
            readingDiagram {
                val p = point("p", 0, 0)
                connection("wrong", p.anchor, graph.text("a").center)
            }
        }
    }
}
