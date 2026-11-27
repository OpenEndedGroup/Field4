package afield.ui.notation

import afield.math.linalg.vec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [StrokePlayheads] on geometry whose answers are known by hand: two straight strokes crossing in
 * the middle, playheads at known speeds, so the meeting beats and counts can be stated exactly.
 */
class StrokePlayheadsTest {

    /** A horizontal stroke 0..100 at y 50, and a vertical one 50..50 from y 0 to 100: they cross at (50, 50). */
    private fun cross(
        rule: MeetingRule,
        actionA: MeetAction = MeetAction.TRANSPOSE,
        actionB: MeetAction = MeetAction.NONE,
        startB: Double = 0.0,
        passB: Double = 4.0,
    ) = StrokePlayheads(
        listOf(
            PlayheadTrack("h", listOf(vec(0, 50), vec(100, 50)), passBeats = 4.0, stepBeats = 0.5, onMeet = actionA),
            PlayheadTrack("v", listOf(vec(50, 0), vec(50, 100)), passBeats = passB, stepBeats = 1.0, start = startB, onMeet = actionB),
        ),
        rule,
    )

    @Test
    fun strokesCrossOnceAtTheMiddle() {
        val c = cross(MeetingRule.Crossing(0.1)).crossings
        assertEquals(1, c.size)
        assertEquals(0.5, c[0].atA, 1e-9)
        assertEquals(0.5, c[0].atB, 1e-9)
        assertEquals(50.0, c[0].where.x.toDouble(), 1e-4)
    }

    @Test
    fun simultaneousPlayheadsMeetAtTheCrossingEveryPass() {
        // Both cross the whole stroke in 4 beats from the start, so both reach the middle at beat 2,
        // then 6, 10, 14: four meetings in 16 beats.
        val run = cross(MeetingRule.Crossing(0.1)).run(16.0)
        assertEquals(4, run.meetings.size, run.meetings.toString())
        run.meetings.zip(listOf(2.0, 6.0, 10.0, 14.0)).forEach { (m, b) -> assertEquals(b, m.beat, 1.0 / 96 + 1e-9) }
        // h transposes +5 at each, folding past an octave: 5, 10, -9, -4.
        assertEquals(listOf(5.0, 10.0, -9.0, -4.0), run.changes.filter { it.track == "h" }.map { it.transpose })
        // v does nothing but still records the meeting.
        assertEquals(4, run.changes.count { it.track == "v" })
    }

    @Test
    fun pitchChangesOnlyAtMeetings() {
        val run = cross(MeetingRule.Crossing(0.1)).run(16.0)
        val h = run.notes.filter { it.track == "h" }
        assertEquals(32, h.size) // a step every half beat for 16 beats, probability 1
        val track = PlayheadTrack("ref", listOf(vec(0, 0), vec(1, 0)))
        val offsets = h.mapIndexed { k, n -> n.note - track.pitchOf(k) }
        for (k in 1 until h.size) if (offsets[k] != offsets[k - 1])
            assertTrue(run.meetings.any { it.beat > h[k - 1].start && it.beat <= h[k].start + 1e-9 }, "change at note $k with no meeting")
        assertEquals(4, (1 until h.size).count { offsets[it] != offsets[it - 1] })
    }

    @Test
    fun outOfStepPlayheadsDoNotMeetAtTheCrossing() {
        // v starts a quarter of the way along: it reaches the middle at beat 1, h at beat 2 — a beat apart.
        assertEquals(0, cross(MeetingRule.Crossing(0.5), startB = 0.25).run(16.0).meetings.size)
        // Widen the window past a beat and they meet on every pass.
        assertEquals(4, cross(MeetingRule.Crossing(1.05), startB = 0.25).run(16.0).meetings.size)
    }

    @Test
    fun radiusMeetingsAreEdgeTriggered() {
        // They come within 5 units (about 0.14 beat before the crossing) once per pass.
        val run = cross(MeetingRule.Radius(5.0)).run(16.0)
        assertEquals(4, run.meetings.size, run.meetings.toString())
        assertTrue(run.meetings.all { abs((it.beat % 4.0) - 2.0) < 0.2 })
    }

    @Test
    fun reverseAndRateActions() {
        // h turns round at the first meeting (beat 2) and runs back towards its start.
        val rev = cross(MeetingRule.Crossing(0.1), actionA = MeetAction.REVERSE).run(4.0)
        assertEquals(1, rev.meetings.size)
        assertEquals(-1, rev.changes.first { it.track == "h" }.direction)
        // v doubles its rate at the meeting: its steps go from a beat apart to half a beat apart.
        val seq = cross(MeetingRule.Crossing(0.1), actionB = MeetAction.RATE)
        seq.tracks[1].let { assertEquals(1.0, it.rate) }
        val run = seq.run(4.0)
        val v = run.notes.filter { it.track == "v" }.map { it.start }
        val expected = listOf(0.0, 1.0, 2.0, 2.0 + 1.0 / 1.5, 2.0 + 2.0 / 1.5)
        assertEquals(expected.size, v.size, v.toString())
        expected.zip(v).forEach { (e, got) -> assertEquals(e, got, 1e-9) }
    }

    @Test
    fun probabilityAndRatchetAreDeterministic() {
        fun make() = StrokePlayheads(
            listOf(PlayheadTrack("p", listOf(vec(0, 0), vec(10, 0)), stepBeats = 1.0, probability = 0.5, ratchet = 3)),
            MeetingRule.Radius(1.0), seed = 7,
        )
        val a = make().run(32.0).notes
        assertEquals(a, make().run(32.0).notes)
        assertEquals(0, a.size % 3) // every struck step is three strikes
        assertTrue(a.size / 3 in 8..24, "about half of 32 steps strike, got ${a.size / 3}")
    }

    @Test
    fun frozenLfoHoldsItsValue() {
        val lfo = PlayheadLfo(LfoDestination.GAIN, periodBeats = 4.0, depth = 0.2, freezeAt = 1.0)
        assertEquals(0.2, lfo.value(1.0), 1e-9)
        assertEquals(0.2, lfo.value(3.0), 1e-9)
        assertEquals(lfo.value(0.5), 0.2 * kotlin.math.sin(Math.PI / 4), 1e-9)
    }

    @Test
    fun restrokeKeepsThePlayheadsPlace() {
        val seq = cross(MeetingRule.Crossing(0.1))
        seq.run(1.0)
        val before = seq.tracks[0].at
        seq.restroke("h", listOf(vec(0, 0), vec(100, 100)))
        assertEquals(before, seq.tracks[0].at)
        assertEquals(25.0, seq.tracks[0].position.x.toDouble(), 1e-3)
        assertEquals(1, seq.crossings.size) // the diagonal still crosses the vertical, now at (50, 50) again
    }
}
