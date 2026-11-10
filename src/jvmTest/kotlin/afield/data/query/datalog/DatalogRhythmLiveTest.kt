package afield.data.query.datalog

import afield.app.protocol.Rect
import afield.app.server.SessionInformation
import afield.app.server.thisSessionInfo
import afield.audio.synth.PatArc
import afield.audio.synth.PatQ
import afield.audio.synth.patSynthRenderStereo
import afield.document.graph.Box
import afield.ui.notation.notation
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `DlRhythm` played live: rules swapped while the pattern is queried, and facts read from the
 * drawing layer each bar. See `dev_docs/DATALOG_RHYTHM.md`, "Live, and from the drawing".
 */
class DatalogRhythmLiveTest {
    private lateinit var box: Box

    @BeforeTest
    fun setUp() {
        val root = Box("root")
        val session = SessionInformation(filename = "<rhythm>", documentName = "rhythm", root = root, ephemeral = true)
        root.inside = session
        session.loading = false
        thisSessionInfo.set(session)
        box = Box("drummer").also { it.inside = session; session.boxes[it.id] = it; it.frame = Rect(-900.0, -900.0, 10.0, 10.0) }
    }

    private fun DlRhythm.stepsOf(inst: String, cycle: Long) = events(cycle).filter { it.instrument == inst }.map { it.step }

    private val drumRules = "event(V, 36, 100, T) :- hit(P, V, _), pos(T, P)."

    @Test
    fun `fact extraction maps steps across and lanes down`() {
        val (hits, spans) = rhythmFactsOf(
            listOf(
                DrawnNote(0.0, 0.5, 0.5, "a"),     // step 0, lane 0
                DrawnNote(4.2, 4.6, 1.5, "b"),     // step 4, lane 1
                DrawnNote(8.0, 12.0, 2.9, "c"),    // step 8, lane 2, four steps wide
                DrawnNote(3.0, 3.5, 7.0, "off"),   // below the last lane
                DrawnNote(16.0, 16.0, 0.5, "end"), // the right edge is the next bar
            ),
            x = 0.0..16.0, y = 0.0..3.0, voices = listOf("kick", "snare", "hh"), steps = 16,
        )
        assertEquals(listOf(listOf<Any>(0L, "kick", "a"), listOf<Any>(4L, "snare", "b"), listOf<Any>(8L, "hh", "c")), hits)
        assertEquals(listOf(listOf<Any>("hh", 8L, 12L, "c")), spans)
    }

    @Test
    fun `a space in other units still lands on the grid`() {
        val (hits, _) = rhythmFactsOf(listOf(DrawnNote(0.5, 0.6, 10.0, "m")), x = 0.0..4.0, y = 0.0..20.0, voices = listOf("a", "b"), steps = 16)
        assertEquals(listOf(listOf<Any>(2L, "b", "m")), hits)
    }

    @Test
    fun `swapping the rules re-solves the next query without a new pattern`() {
        val r = Datalog().apply { add("event(kick, 36, 100, T) :- every(T, 4, 0).") }.rhythm()
        val pattern = r.pattern()
        val bar = PatArc(PatQ.of(0), PatQ.of(1))
        assertEquals(4, pattern.events(bar).size)
        r.rules("event(kick, 36, 100, T) :- every(T, 8, 0).")
        assertEquals(2, pattern.events(bar).size)
        assertEquals(listOf(0L, 8L), r.stepsOf("kick", 0))
    }

    @Test
    fun `a bad edit is refused and the old rules keep playing`() {
        val r = Datalog().apply { add("event(kick, 36, 100, T) :- every(T, 4, 0).") }.rhythm()
        assertFailsWith<DlException> { r.rules("event(kick, 36, 100, T) :- every(T, N, 0).") }
        assertEquals(listOf(0L, 4L, 8L, 12L), r.stepsOf("kick", 0))
    }

    @Test
    fun `live facts are memoised by value and re-solved when they change`() {
        var rows = listOf(listOf<Any>(0L, "kick", "m1"))
        val r = Datalog().apply { add(drumRules) }.rhythm().facts("hit") { rows }
        assertEquals(listOf(0L), r.stepsOf("kick", 0))
        val before = r.solves
        r.events(0); r.events(0)
        assertEquals(before, r.solves, "unchanged facts are served from the memo")
        rows = listOf(listOf(0L, "kick", "m1"), listOf(6L, "kick", "m2"))
        assertEquals(listOf(0L, 6L), r.stepsOf("kick", 0))
        assertEquals(listOf(16L, 22L), r.stepsOf("kick", 1))
        // Same count, different value: the old key (counts only) would have served the stale bar.
        rows = listOf(listOf(0L, "kick", "m1"), listOf(10L, "kick", "m2"))
        assertEquals(listOf(0L, 10L), r.stepsOf("kick", 0))
    }

    @Test
    fun `drawing a mark changes what plays and why cites it`() {
        val n = box.notation
        val space = n.space(Rect(0.0, 0.0, 160.0, 30.0), x = 0.0..16.0, y = 0.0..3.0)
        val r = Datalog().apply { add(drumRules) }.rhythm().notationHits(space, listOf("kick", "snare", "hh"))
        assertTrue(r.events(0).isEmpty(), "an empty space plays nothing: ${r.events(0)}")

        n { rect(40, 12, 6, 6, name = "back beat") }          // x 40 → step 4; y 15 → lane 1
        assertEquals(listOf(4L), r.stepsOf("snare", 0))
        val why = assertNotNull(r.why("event(snare, 36, 100, 4)")).render()
        assertTrue("hit(4, snare, \"back beat\")" in why || "hit(4, snare, back beat)" in why, why)

        // A box's `notation { }` replaces the marks it drew last time, so this is an edit of the drawing.
        n { rect(40, 12, 6, 6, name = "back beat"); rect(120, 22, 6, 6, name = "hat") } // x 120 → step 12; y 25 → lane 2
        assertEquals(listOf(4L), r.stepsOf("snare", 0))
        assertEquals(listOf(12L), r.stepsOf("hh", 0))
        n.delete(n.named("back beat")!!)
        assertEquals(listOf(12L), r.stepsOf("hh", 0))
        assertFalse(r.events(0).any { it.instrument == "snare" }, "erasing the mark silences it")
    }

    @Test
    fun `an offline render through PatSynth changes after a fact edit`() {
        var rows = listOf(listOf<Any>(0L, "kick", "m"))
        val r = Datalog().apply { add(drumRules) }.rhythm().facts("hit") { rows }
        val a = patSynthRenderStereo(r.pattern(), cycles = 1, cps = 1.0)
        rows = listOf(listOf(0L, "kick", "m"), listOf(8L, "kick", "m2"))
        val b = patSynthRenderStereo(r.pattern(), cycles = 1, cps = 1.0)
        val half = a.left.size / 2
        val window = a.left.size / 32
        fun energy(x: FloatArray, from: Int, until: Int) = (from until until).sumOf { (x[it] * x[it]).toDouble() }
        val onsetA = energy(a.left, half, half + window)
        val onsetB = energy(b.left, half, half + window)
        assertEquals(energy(a.left, 0, half), energy(b.left, 0, half), 1e-6, "the first half is the same kick")
        assertTrue(onsetB > 10 * onsetA + 1e-6, "a second kick at step 8 sounds: $onsetA vs $onsetB")
    }
}
