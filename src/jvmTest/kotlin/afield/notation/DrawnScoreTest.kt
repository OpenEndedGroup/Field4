package afield

import afield.four.synth.DrawnScoreDocs
import afield.four.synth.PatArc
import afield.four.synth.PatQ
import afield.four.synth.notes
import afield.four.synth.toPattern
import afield.stdlib.vec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A drawn score read as notes and as a pattern — `NotationPatterns.kt` and `chain_drawn_score.md` —
 * and two things about `notation.space` that the chain depends on.
 */
class DrawnScoreTest {
    private lateinit var session: SessionInformation
    private lateinit var box: Box

    @BeforeTest
    fun setUp() {
        val root = Box("root")
        session = SessionInformation(filename = "<score>", documentName = "score", root = root, ephemeral = true)
        root.inside = session
        session.loading = false
        thisSessionInfo.set(session)
        box = Box("writer").also { it.inside = session; session.boxes[it.id] = it; it.frame = Rect(-500.0, -500.0, 10.0, 10.0) }
    }

    private val n get() = box.notation

    /**
     * Excalidraw keeps a frame's title in the element's own `name` field. A frame drawn with the
     * frame tool and renamed "score" by hand has no `customData.fieldName`, no text and no bound text.
     */
    @Test
    fun aHandDrawnFrameIsFoundByTheNameExcalidrawGaveIt() {
        val frame = Json.parseToJsonElement(
            """{"id":"hand-frame","type":"frame","x":0,"y":0,"width":400,"height":200,"angle":0,
               "name":"score","isDeleted":false,"version":1,"versionNonce":1}""",
        ).jsonObject
        NotationStore.update(session) { it.withElement(frame) }
        n { line(listOf(vec(0, 100), vec(400, 100)), name = "drone") }
        assertNotNull(n.named("score"), "a hand-named frame should be found by its name")
        val score = n.space("score", x = 0.0..4.0, y = 72.0..60.0)
        assertEquals(66.0, score.vertical(2.0).single().value, 1e-9)
    }

    /** `space("score")` over a rectangle named "score": the region should not be one of its own notes. */
    @Test
    fun theMarkThatDefinesASpaceIsNotOneOfItsEvents() {
        n {
            rect(0, 0, 400, 240, name = "roll")
            rect(100, 100, 100, 20, name = "c")
        }
        val roll = n.space("roll", x = 0.0..4.0, y = 72.0..60.0)
        assertEquals(listOf("c"), roll.events.map { it.mark.name })
    }

    @Test
    fun rectanglesAreNotesAndLabelsAreNot() {
        n {
            frame("roll", 0, 0, 400, 240)                 // 4 beats, 12 semitones: 20px each
            rect(100, 100, 100, 20, name = "c")           // beat 1..2, centre pitch 66.5
            text("a label", 250, 40)
        }
        val notes = n.space("roll", x = 0.0..4.0, y = 72.0..60.0).notes()
        assertEquals(1, notes.size)
        assertEquals(1.0, notes[0].start, 1e-9)
        assertEquals(2.0, notes[0].end, 1e-9)
        assertEquals(66.5, notes[0].pitch, 1e-9)
    }

    @Test
    fun aSlopedStrokeIsCutIntoStepsThatFollowIt() {
        n {
            frame("roll", 0, 0, 400, 240)
            stroke(listOf(vec(0, 240), vec(400, 0)), name = "rise")      // 60 → 72 over 4 beats
        }
        val notes = n.space("roll", x = 0.0..4.0, y = 72.0..60.0).notes(glideStep = 0.5)
        assertEquals(8, notes.size)
        notes.forEachIndexed { i, note ->
            assertEquals(i * 0.5, note.start, 1e-9)
            assertEquals(60.0 + i * 1.5, note.pitch, 1e-6)
        }
    }

    @Test
    fun theWholeXRangeIsOneCycleAndTheCycleRepeats() {
        n {
            frame("roll", 0, 0, 400, 240)
            rect(100, 100, 100, 20, name = "c")
        }
        val p = n.space("roll", x = 0.0..4.0, y = 72.0..60.0).toPattern()
        val ev = p.events(PatArc(PatQ.ZERO, PatQ.of(2)))
        assertEquals(listOf(PatQ.of(1, 4), PatQ.of(5, 4)), ev.map { it.arc.start })
        assertEquals(listOf(PatQ.of(1, 2), PatQ.of(3, 2)), ev.map { it.arc.end })
        assertEquals("66.500", ev[0].value)
    }

    @Test
    fun theChainScoreReadsBackAsDrawn() {
        val notes = DrawnScoreDocs.headless { DrawnScoreDocs.space(it).notes() }
        assertEquals(DrawnScoreDocs.ROLL.size + 16 + 1, notes.size)
        assertTrue(notes.zipWithNext().all { (a, b) -> b.start >= a.start })
    }
}
