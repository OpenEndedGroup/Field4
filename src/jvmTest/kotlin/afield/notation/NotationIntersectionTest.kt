package afield

import afield.stdlib.vec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the drawing by cutting it: a vertical line is an instant, a horizontal line a pitch.
 * See `dev_docs/NOTATION.md`, "Reading by intersection".
 */
class NotationIntersectionTest {
    private lateinit var session: SessionInformation
    private lateinit var box: Box

    @BeforeTest
    fun setUp() {
        val root = Box("root")
        session = SessionInformation(filename = "<cut>", documentName = "cut", root = root, ephemeral = true)
        root.inside = session
        session.loading = false
        thisSessionInfo.set(session)
        box = Box("writer").also { it.inside = session; session.boxes[it.id] = it; it.frame = Rect(-500.0, -500.0, 10.0, 10.0) }
    }

    private val n get() = box.notation

    @Test
    fun aVerticalCutReadsAStrokeAsAValueAtThatInstant() {
        n { stroke((0..100).map { vec(it * 4.0, 200 + 50 * sin(it / 100.0 * 2 * PI)) }, name = "wave") }
        val hits = n.vertical(100.0)
        assertEquals(1, hits.size)
        assertEquals(250.0, hits[0].value, 0.5)                 // a quarter of the way through: the crest (y grows down)
        assertNull(hits[0].span)
        assertEquals(0.25, n.named("wave")!!.let { hits[0].along }, 0.03)
    }

    @Test
    fun aStrokeThatDoublesBackIsTwoVoicesAtOnce() {
        n { stroke(listOf(vec(0, 100), vec(200, 100), vec(0, 150)), name = "loop") }
        assertEquals(2, n.vertical(100.0).size)
        assertEquals(listOf(100.0, 125.0), n.named("loop")!!.yAt(100.0))
    }

    @Test
    fun aShapeIsASpanAndARotatedOneIsCutWhereItReallyIs() {
        n { rect(100, 100, 100, 40, name = "note") }
        val h = n.vertical(150.0).single()
        assertEquals(100.0..140.0, h.span)
        assertEquals(120.0, h.value)

        n { rect(100, 100, 100, 100, name = "note") }
        n.edit(n.named("note")!!) { angle = PI / 4 }
        val d = n.vertical(150.0).single().span!!      // through the centre of a 45° square: its diagonal
        assertEquals(100 * sqrt(2.0), d.endInclusive - d.start, 0.5)
        // Its left corner is at 150 - 50√2 ≈ 79.3; 25.7 in from a 45° corner the cut is twice that.
        val near = n.vertical(105.0).single().span!!
        assertEquals(2 * (105 - (150 - 50 * sqrt(2.0))), near.endInclusive - near.start, 0.5)
    }

    @Test
    fun aScoreMapsTheRegionToBeatsAndPitches() {
        n {
            frame("score", 100, 100, 800, 400)
            line(listOf(vec(100, 300), vec(900, 300)), name = "drone")          // the middle: pitch 66
            line(listOf(vec(100, 500), vec(500, 100)), name = "rise")           // 48 → 84 over beats 0..4
        }
        val score = n.space("score", x = 0.0..8.0, y = 84.0..48.0)
        val at2 = score.vertical(2.0).associateBy { it.mark.name }
        assertEquals(66.0, at2["drone"]!!.value, 1e-6)
        assertEquals(66.0, at2["rise"]!!.value, 1e-6)
        assertEquals(9.0, at2["rise"]!!.slope, 1e-6, "an octave and a half over four beats: 9 semitones a beat")
        assertEquals(0.0, at2["drone"]!!.slope, 1e-9)

        val when66 = score.horizontal(66.0).mapNotNull { it.mark.name to it.value }.toMap()
        assertEquals(2.0, when66["rise"]!!, 1e-6)
    }

    @Test
    fun shapesInAScoreAreNotesWithOnsetOffsetAndPitch() {
        n {
            frame("roll", 0, 0, 400, 240)                                        // 4 beats, 12 semitones: 20px each
            rect(100, 100, 100, 20, name = "c")                                  // beat 1..2
            rect(250, 40, 50, 20, name = "e")                                    // beat 2.5..3
        }
        val roll = n.space("roll", x = 0.0..4.0, y = 72.0..60.0)
        val ev = roll.events
        assertEquals(listOf("c", "e"), ev.map { it.mark.name })
        assertEquals(1.0, ev[0].start, 1e-9); assertEquals(2.0, ev[0].end, 1e-9)
        assertEquals(66.5, ev[0].value, 1e-9)                               // centre of y 100..120
        assertEquals(listOf("e"), roll.between(2.0, 3.0).map { it.mark.name })
        assertEquals(listOf("c"), roll.sounding(1.5).map { it.mark.name })
    }

    @Test
    fun theScoreFollowsTheBoxItIs() {
        val stage = Box("stage").also { it.inside = session; session.boxes[it.id] = it; it.frame = Rect(0.0, 0.0, 100.0, 100.0) }
        n { line(listOf(vec(0, 50), vec(1000, 50)), name = "h") }
        val space = n.space(stage, x = 0.0..1.0, y = 0.0..1.0)
        assertEquals(0.5, space.vertical(0.5).single().value, 1e-9)
        stage.frame = Rect(0.0, 0.0, 100.0, 200.0)
        assertEquals(0.25, space.vertical(0.5).single().value, 1e-9)
    }

    @Test
    fun pressureIsReadAtTheCrossing() {
        n { stroke(listOf(vec(0, 0), vec(100, 0)), name = "p") }
        val m = n.named("p")!!
        n.edit(m) { }  // no-op edit keeps it; now give it pressures as a pen would
        NotationStore.update(session) { s ->
            s.withElement(s[m.id]!!.with("pressures" to JsonArray(listOf(JsonPrimitive(0.2), JsonPrimitive(1.0)))))
        }
        assertEquals(0.6, n.vertical(50.0).single().pressure!!, 1e-6)
    }

    @Test
    fun horizontalCutsReadWhereMarksPassAHeight() {
        n { line(listOf(vec(0, 0), vec(100, 100)), name = "d") }
        assertEquals(listOf(40.0), n.named("d")!!.xAt(40.0).map { Math.round(it * 1e6) / 1e6 })
        assertEquals(40.0, n.horizontal(40.0).single().value, 1e-9)
    }

    @Test
    fun aSegmentCutComesBackInOrderAlongIt() {
        n {
            line(listOf(vec(100, 0), vec(100, 100)), name = "first")
            line(listOf(vec(300, 0), vec(300, 100)), name = "second")
        }
        val hits = n.intersect(vec(400, 50), vec(0, 50))
        assertEquals(listOf("second", "first"), hits.map { it.mark.name })
        assertEquals(0.25, hits[0].value, 1e-9)
    }
}
