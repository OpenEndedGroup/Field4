package afield.petri

import afield.four.synth.PatArc
import afield.four.synth.PatPattern
import afield.four.synth.PatQ
import afield.four.synth.pat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `#petri` notation in `PetriNotation.kt`.
 *
 * Two kinds of claim here. The first is that the notation and the Kotlin DSL describe the *same*
 * nets — asserted by playing both and comparing what comes out, not by comparing structures, since
 * agreeing on shape while disagreeing on sound would be the useless kind of equivalence. The
 * second is that a half-typed net produces useful complaints and, wherever possible, still a net:
 * live coding means the source is wrong most of the time.
 */
class PetriNotationTest {

    private fun PatPattern<String>.soundsIn(cycles: LongRange): List<Pair<PatQ, String>> =
        cycles.flatMap { cycle ->
            events(PatArc(PatQ.of(cycle), PatQ.of(cycle + 1)))
                .filter { it.hasOnset }
                .sortedBy { it.arc.start }
                .map { it.arc.start to it.value }
        }

    // ── the notation and the DSL agree ────────────────────────────────────────────────────

    @Test
    fun `a notation net plays what the same net written in Kotlin plays`() {
        val written = petri(
            """
            #petri
            !grid 1/8

            kick : 1
            kick -[ hit  @1/8 : bd ]-> hats
            hats -[ back @1/8 : sd ]-> kick
            """
        )
        val built = petri {
            grid = cyc(1, 8)
            place("kick", tokens = 1)
            t("hit", dur = cyc(1, 8), emit = "bd") { from("kick"); to("hats") }
            t("back", dur = cyc(1, 8), emit = "sd") { from("hats"); to("kick") }
        }
        assertEquals(built.emits().soundsIn(0L..3L), written.emits().soundsIn(0L..3L))
        assertTrue(written.emits().soundsIn(0L..0L).isNotEmpty())
    }

    @Test
    fun `directives set the semantics`() {
        val net = petri(
            """
            !timing timed
            !grid 1/32
            !conflict random
            !seed 11
            a : 1
            a -[ go @2 ]-> b
            """
        )
        assertEquals(PetriTiming.TIMED, net.timing)
        assertEquals(cyc(1, 32), net.grid)
        assertEquals(PetriConflict.RANDOM, net.conflict)
        assertEquals(11, net.seed)
        assertEquals(cyc(2), net.transition("go")!!.duration)
    }

    // ── place declarations ────────────────────────────────────────────────────────────────

    @Test
    fun `a place can be declared with a count, a capacity, numbers and strings`() {
        val net = petri(
            """
            kick  : 2
            bound : 0 ^4
            label : 'lead'
            notes : #60, #64, #67
            kick -[ t ]-> bound
            """
        )
        assertEquals(2, net.initial.count("kick"))
        assertEquals(4, net.place("bound")!!.capacity)
        assertEquals(listOf<Any?>("lead"), net.initial.colours("label"))
        assertEquals(listOf(60.0, 64.0, 67.0), net.initial.colours("notes"))
    }

    @Test
    fun `a double-quoted place holds one token that is a whole pattern`() {
        val net = petri("""verse : "bd ~ sd ~" """)
        val colours = net.initial.colours("verse")
        assertEquals(1, colours.size, "a quoted string is one token, not one token per step")
        val inner = colours.single()
        assertTrue(inner is PatPattern<*>, "and its colour is a pattern, which is what makes a place a section")

        @Suppress("UNCHECKED_CAST")
        val events = (inner as PatPattern<String>).events(PatArc(PatQ.ZERO, PatQ.ONE))
        assertEquals(listOf("bd", "sd"), events.map { it.value })
    }

    @Test
    fun `a place mentioned only by an arc is created empty`() {
        val net = petri("a : 1\na -[ t ]-> b")
        assertNotNull(net.place("b"))
        assertEquals(0, net.initial.count("b"))
    }

    // ── arcs ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `weights, inhibitor and read arcs parse into the right arc kinds`() {
        val net = petri(
            """
            hats : 2
            armed : 0
            clock : 1
            2*hats + ~armed + &clock -[ roll @1/8 ]-> fill
            """
        )
        val inputs = net.transition("roll")!!.inputs
        assertEquals(listOf("hats", "armed", "clock"), inputs.map { it.place })
        assertEquals(listOf(PetriArcKind.NORMAL, PetriArcKind.INHIBIT, PetriArcKind.READ), inputs.map { it.kind })
        assertEquals(listOf(2, 1, 1), inputs.map { it.weight })
    }

    @Test
    fun `a transition named on several lines collects all of its arcs`() {
        val net = petri(
            """
            verse  : 1
            energy : 1
            verse  -[ build @4 : go ]-> chorus
            energy -[ build ]-> chorus
            """
        )
        val build = net.transition("build")!!
        assertEquals(listOf("verse", "energy"), build.inputs.map { it.place })
        assertEquals(2, build.outputs.size, "each line contributes its own output arc")
        assertEquals(cyc(4), build.duration, "the settings from the first line survive")
        assertEquals("go", build.emit)
    }

    @Test
    fun `an empty side makes a source or a drain`() {
        val spawn = petri("-[ spawn ]-> pool")
        assertTrue(spawn.transition("spawn")!!.inputs.isEmpty())
        assertEquals(1, spawn.transition("spawn")!!.outputs.size)

        val drain = petri("pool : 1\npool -[ sink ]->")
        assertTrue(drain.transition("sink")!!.outputs.isEmpty())
    }

    @Test
    fun `settings after the name parse in any order`() {
        val net = petri("a : 1\na -[ t @1/4 delay=1/32 pri=5 w=2.5 : bd ]-> b")
        val t = net.transition("t")!!
        assertEquals(cyc(1, 4), t.duration)
        assertEquals(cyc(1, 32), t.delay)
        assertEquals(5, t.priority)
        assertEquals(2.5, t.weight)
        assertEquals("bd", t.emit)
    }

    @Test
    fun `an emit can be a quoted string or a number`() {
        assertEquals("bd sd", petri("""a : 1 ${"\n"} a -[ t : "bd sd" ]-> b""").transition("t")!!.emit)
        assertEquals(60.0, petri("a : 1\na -[ t : #60 ]-> b").transition("t")!!.emit)
        assertNull(petri("a : 1\na -[ t : ~ ]-> b").transition("t")!!.emit)
    }

    @Test
    fun `comments and the editor's header line are ignored`() {
        val net = petri(
            """
            #petri
            // a comment on its own
            a : 1   // and one after some content
            a -[ t ]-> b
            """
        )
        assertEquals(1, net.transitions.size)
        assertEquals(2, net.places.size)
    }

    // ── combinators ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a combinator transforms the colour a transition produces`() {
        val net = petri(
            """
            !timing timed
            l : "bd bd"
            r : "sd sd"
            l + r -[ merge @1 => stack ]-> both
            """
        )
        val run = net.run()
        run.steps(1)
        val merged = run.markingAt(cyc(2)).colours("both").single()
        assertTrue(merged is PatPattern<*>)

        @Suppress("UNCHECKED_CAST")
        val events = (merged as PatPattern<String>).events(PatArc(PatQ.ZERO, PatQ.ONE))
        assertEquals(listOf("bd", "bd", "sd", "sd"), events.map { it.value }.sorted())
    }

    @Test
    fun `every combinator in the registry has a description`() {
        assertEquals(
            PetriCombinators.names.toSet(),
            PetriCombinators.docs.keys,
            "a word without a description cannot be hovered, documented or guarded",
        )
        assertTrue(PetriCombinators.names.size >= 10)
    }

    @Test
    fun `every combinator produces something rather than throwing`() {
        val binding = PetriBinding(
            transition = "t",
            at = PatQ.ZERO,
            cycle = 0,
            consumed = listOf(PetriToken(pat("bd sd")), PetriToken(pat("hh"))),
            byPlace = emptyMap(),
        )
        for (name in PetriCombinators.names) {
            val combinator = PetriCombinators[name]!!
            // `black` legitimately returns null; everything else must produce a colour.
            val result = combinator(binding)
            if (name != "black") assertNotNull(result, "combinator '$name' produced nothing")
        }
    }

    @Test
    fun `numeric combinators work on numeric colours`() {
        val binding = PetriBinding("t", PatQ.ZERO, 0, listOf(PetriToken(3.0), PetriToken(4.0)), emptyMap())
        assertEquals(7.0, PetriCombinators["sum"]!!(binding))
        assertEquals(4.0, PetriCombinators["inc"]!!(binding))
        assertEquals(2.0, PetriCombinators["count"]!!(binding))
        assertEquals(4.0, PetriCombinators["swap"]!!(binding))
    }

    // ── diagnostics ───────────────────────────────────────────────────────────────────────

    @Test
    fun `every complaint is collected, not just the first`() {
        val result = petriParse(
            """
            a -[ t @nonsense ]-> b
            c -[ u => notacombinator ]-> d
            !timing sideways
            """
        )
        assertTrue(result.errors.size >= 3, "three broken lines should produce three complaints: ${result.errors}")
        assertTrue(result.errors.map { it.span.line }.toSet().size >= 3, "and they should point at different lines")
    }

    @Test
    fun `an error still leaves a net behind so the last good thing keeps playing`() {
        val result = petriParse(
            """
            a : 1
            a -[ good : bd ]-> b
            b -[ bad @wrong ]-> c
            """
        )
        assertTrue(result.errors.isNotEmpty())
        assertNotNull(result.net, "a half-typed source must still yield a playable net")
        assertEquals("bd", result.net.transition("good")!!.emit)
    }

    @Test
    fun `an unknown combinator names the ones that exist`() {
        val message = petriParse("a -[ t => wobble ]-> b").errors.single().message
        assertTrue("wobble" in message)
        assertTrue("stack" in message, "the complaint should list the vocabulary: $message")
    }

    @Test
    fun `a diagnostic points at the line and column the mistake is on`() {
        val result = petriParse("a : 1\nb : 1\na -[ t @oops ]-> b")
        val error = result.errors.single()
        assertEquals(3, error.span.line)
        assertTrue(error.span.column >= 1)
    }

    @Test
    fun `a transition with no arcs is a warning, not an error`() {
        val result = petriParse("-[ lonely ]->")
        assertTrue(result.errors.isEmpty(), "it parses fine — it just cannot do anything")
        assertTrue(result.warnings.any { "lonely" in it.message })
    }

    @Test
    fun `spans are recorded for places and transitions so an editor can jump to them`() {
        val result = petriParse("kick : 1\nkick -[ hit ]-> hats")
        assertEquals(1, result.placeSpans["kick"]!!.line)
        assertEquals(2, result.transitionSpans["hit"]!!.line)
        assertEquals("hit", result.transitionSpans["hit"]!!.text)
    }

    @Test
    fun `the throwing form reports the first error with its position`() {
        val thrown = runCatching { petri("a -[ t @oops ]-> b") }.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue("line 1" in thrown.message.orEmpty(), "message was: ${thrown.message}")
    }
}
