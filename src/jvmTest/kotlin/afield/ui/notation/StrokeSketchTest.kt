package afield.ui.notation

import afield.app.protocol.Rect
import afield.app.server.SessionInformation
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import afield.geometry.path.FLine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [eachStroke] end to end on the server: a stroke drawn by hand reaches the pen's box through
 * `notation.onStroke`, comes alive with the code the pen had then, steps its physics at 60 a second,
 * and sees its neighbours. The sketch's loop is driven by hand with [StrokeSketch.tick].
 */
class StrokeSketchTest {
    private lateinit var session: SessionInformation
    private lateinit var a: Box
    private lateinit var b: Box
    private val sketch get() = StrokeSketch.of(session)
    private var clock = 100.0

    @BeforeTest
    fun setUp() {
        val root = Box("root")
        session = SessionInformation(filename = "<strokes>", documentName = "strokes", root = root, ephemeral = true)
        root.inside = session
        session.loading = false
        thisSessionInfo.set(session)
        a = box("a")
        b = box("b")
    }

    private fun box(id: String): Box = Box(id).also {
        it.inside = session
        session.boxes[id] = it
        it.frame = Rect(0.0, 0.0, 100.0, 50.0)
    }

    private fun stroke(id: String, vararg points: Pair<Double, Double>): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id)); put("type", JsonPrimitive("freedraw"))
        put("x", JsonPrimitive(0)); put("y", JsonPrimitive(0))
        put("width", JsonPrimitive(10)); put("height", JsonPrimitive(10))
        put("version", JsonPrimitive(1)); put("versionNonce", JsonPrimitive(1))
        put("isDeleted", JsonPrimitive(false))
        put("points", JsonArray(points.map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) }))
    }

    /** What a hand drawing [element] does: the scene changes, and the reactions see it. */
    private fun draw(element: JsonObject) {
        val (before, after) = NotationStore.update(session) { it.withElements(listOf(element)) }
        dispatchNotationChange(session, NotationChange.between(before, after))
        sketch.tick(clock)
    }

    private fun run(seconds: Double) {
        val steps = Math.round(seconds * 60).toInt()
        repeat(steps) {
            clock += 1.0 / 60
            sketch.tick(clock)
        }
    }

    @Test
    fun aStrokeComesAliveWithThePensCodeAndLeavesTheDrawing() {
        var started = 0
        a.eachStroke(trail = 0.4) {
            started++
            draw { _ -> listOf(polyline(curve)) }
        }
        draw(stroke("s1", 0.0 to 0.0, 10.0 to 0.0, 20.0 to 0.0, 30.0 to 0.0))
        assertEquals(1, started, "the block runs once, when the stroke is drawn")
        val s = a.livingStrokes.single()
        assertEquals(4, s.points.size)
        assertEquals(0.4, sketch.trail)
        assertTrue(NotationStore.scene(session)["s1"]!!["isDeleted"]!!.jsonPrimitive.content == "true", "the hand mark is taken off the scene")
        run(0.5)
        assertEquals(0.5, s.t, 1e-6)
        assertEquals(4, s.output.single().nodes.size)
    }

    @Test
    fun strokesKeepTheCodeTheyWereDrawnWithAndThePenFollowsTheLastBoxRun() {
        a.eachStroke { draw { _ -> listOf(FLine().moveTo(0, 0).lineTo(1, 1)) } }
        draw(stroke("s1", 0.0 to 0.0, 10.0 to 0.0))
        a.eachStroke { draw { _ -> emptyList() } }          // edited and run again
        draw(stroke("s2", 0.0 to 50.0, 10.0 to 50.0))
        run(0.1)
        val (first, second) = a.livingStrokes
        assertEquals(1, first.output.size, "the first stroke still runs the code it was drawn with")
        assertEquals(0, second.output.size)

        b.eachStroke { draw { _ -> emptyList() } }
        draw(stroke("s3", 0.0 to 90.0, 10.0 to 90.0))
        assertEquals(b, a.livingStrokes.last().box, "the box run last holds the pen")

        a.clearStrokes()
        sketch.tick(clock)
        assertTrue(a.livingStrokes.isEmpty())
    }

    @Test
    fun pointsIntegrateAtSixtyStepsASecondWithTheDemosDamping() {
        a.eachStroke {
            draw { _ ->
                points.forEach { it.ax = 60.0 }
                emptyList()
            }
        }
        draw(stroke("s1", 0.0 to 0.0, 10.0 to 0.0))
        run(1.0 / 60)
        val p = a.livingStrokes.single().points[0]
        assertEquals(0.98, p.vx, 1e-9, "v = (v + a dt) * 0.98")
        assertEquals(0.98 / 60, p.x, 1e-9)
        clock += 5.0
        sketch.tick(clock)
        val v2 = ((0.98 + 1) * 0.98 + 1) * 0.98
        assertEquals(v2, p.vx, 1e-9, "a five-second stall is caught up with two steps, not three hundred")
    }

    @Test
    fun aStrokeFindsItsNeighboursPointsAndLastFramesOutput() {
        var near: StrokeHit? = null
        var seen: StrokeHit? = null
        a.eachStroke {
            draw { _ ->
                if (index == 1) {
                    near = closestPoint(points[0].x, points[0].y)
                    seen = closestOutput(points[0].x, points[0].y)
                }
                listOf(polyline(curve), polyline(curve).also { it.ignore = true })
            }
        }
        draw(stroke("s1", 0.0 to 0.0, 100.0 to 0.0))
        draw(stroke("s2", 0.0 to 30.0, 100.0 to 30.0))
        run(2.0 / 60)
        assertNotNull(near)
        assertEquals(30.0, near!!.dist, 1e-6)
        assertEquals(0, near!!.strokeIndex)
        assertEquals(30.0, seen!!.dist, 1e-6)
    }
}
