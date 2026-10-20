package afield.animation.motion

import afield.app.protocol.Rect
import afield.app.server.SessionInformation
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import afield.runtime.lifecycle.AnimationClock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The score runner stepped frame by frame on a frame-indexed clock: who is called, when, with what
 * progress, and what a box moving itself and a box that throws do to the rest of the score.
 */
class ScoreTest {
    private lateinit var session: SessionInformation
    private lateinit var lead: Box
    private lateinit var token: AnimationClock.FrameToken

    private fun box(id: String, frame: Rect) = Box(id).also {
        it.inside = session
        session.boxes[it.id] = it
        it.frame = frame
        session.root.children.add(it)
    }

    @BeforeTest
    fun setUp() {
        val root = Box("root")
        session = SessionInformation(filename = "<score>", documentName = "score", root = root, ephemeral = true)
        root.inside = session
        session.loading = false
        thisSessionInfo.set(session)
        lead = box("lead", Rect(-400.0, 0.0, 100.0, 50.0))
        // 20 frames a second and dt's 0.05 s ceiling agree, so each step is exactly 1/20 s of score.
        token = AnimationClock.beginFrameIndexed(20)
    }

    @AfterTest
    fun tearDown() = AnimationClock.endFrameIndexed(token)

    /** Loop 4 s over x 0..1000: the playhead moves 12.5 canvas units a frame. */
    private fun transport(playhead: String? = null) = ScoreTransport(lead, 4.0, 0.0..1000.0, 0.0..500.0, 500, playhead)

    private fun ScoreTransport.run(frames: Int) = repeat(frames) {
        step()
        AnimationClock.advance(token)
    }

    @Test
    fun aBoxIsCalledOnlyWhileThePlayheadIsOverItAndHearsItsEntryAndExit() {
        val a = box("a", Rect(95.0, 100.0, 110.0, 100.0))
        val calls = mutableListOf<Triple<Double, Boolean, Boolean>>()
        val beats = mutableListOf<Double>()
        a.score {
            calls += Triple(localProgress, justEntered, justExited)
            if (onSubdivision(4)) beats += localProgress
        }
        transport().run(40) // 0 .. 487.5
        val active = calls.filter { !it.third }
        assertEquals(9, active.size, "x 100, 112.5 ... 200: nine frames over x 95..205")
        assertEquals(listOf(true) + List(8) { false }, active.map { it.second })
        assertEquals(5.0 / 110.0, active.first().first, 1e-6)
        assertEquals(105.0 / 110.0, active.last().first, 1e-6)
        assertEquals(1, calls.count { it.third }, "one call with justExited")
        assertEquals(4, beats.size, "onSubdivision(4) fires once per quarter: $beats")
    }

    @Test
    fun localTIsSecondsIntoTheBoxAndTheLoopComesRound() {
        val a = box("a", Rect(0.0, 0.0, 250.0, 100.0)) // a quarter of the span: one second of a 4 s loop
        val entries = mutableListOf<Long>()
        var lastLocalT = 0.0
        var duration = 0.0
        a.score {
            if (justEntered) entries += loopIndex
            if (!justExited) lastLocalT = localT
            duration = boxDuration
        }
        transport().run(20 * 9) // nine seconds: loops 0, 1 and 2
        assertEquals(listOf(0L, 1L, 2L), entries)
        assertEquals(1.0, duration, 1e-9)
        assertTrue(lastLocalT in 0.9..1.0, "localT runs to the box's duration, got $lastLocalT")
    }

    @Test
    fun thePlayheadBoxFollowsTheClockAndABoxCanMoveItself() {
        val playhead = box("ph", Rect(0.0, 0.0, 12.0, 10.0)).also { it.name = "playhead" }
        val hopper = box("hop", Rect(0.0, 300.0, 50.0, 50.0))
        hopper.score { if (justEntered) box.move(0.0, -200.0) }
        val transport = transport("playhead")
        transport.run(5)
        assertEquals(transport.playheadX, playhead.frame.x, 1e-9)
        assertEquals(Rect(transport.playheadX, 0.0, 12.0, 500.0), playhead.frame)
        assertEquals(100.0, hopper.frame.y)
    }

    @Test
    fun aBoxThatThrowsDoesNotStopTheOthersFromDrawing() {
        box("bad", Rect(0.0, 0.0, 400.0, 100.0)).score { throw IllegalStateException("nope") }
        box("good", Rect(0.0, 200.0, 400.0, 100.0)).score {
            val c = boxPoint(0.5, 0.5)
            draw.ring(c, 10.0, color = "rgba(255, 238, 205, 0.96)", width = 2.5)
            draw.rect(0.0, 0.0, 5.0, 5.0, fill = "hsla(28, 90%, 68%, 0.84)")
        }
        val transport = transport()
        transport.run(3)
        val json = transport.lastFrameJson
        assertTrue(json.contains("[\"c\",100.0,125.0,10.0,null,\"rgba(255, 238, 205, 0.96)\",2.5,1.0]"), json)
        assertTrue(json.contains("\"hsla(28, 90%, 68%, 0.84)\""), json)
    }
}
