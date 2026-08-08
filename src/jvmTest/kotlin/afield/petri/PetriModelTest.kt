package afield.petri

import afield.four.synth.PatQ
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The firing semantics in `PetriNets.kt`, asserted against nets small enough to reason about by
 * hand.
 *
 * The load-bearing claims are the ones the pattern layer depends on: that a maximal step really is
 * *maximal and simultaneous* (produced tokens do not cascade within one tick), that conflicts are
 * resolved deterministically, and that a TIMED transition genuinely holds its tokens for its
 * duration rather than round-tripping them instantly.
 */
class PetriModelTest {

    private fun PetriRun.transitionsIn(steps: Int): List<List<String>> =
        steps(steps).map { step -> step.firings.map { it.transition } }

    // ── the two-place loop, which everything else is a variation on ───────────────────────

    private val pingPong = petri {
        place("a", tokens = 1)
        t("there", dur = cyc(1, 4), emit = "bd") { from("a"); to("b") }
        t("back", dur = cyc(1, 4), emit = "sd") { from("b"); to("a") }
        grid = cyc(1, 4)
    }

    @Test
    fun `a token alternates between two places, one transition per tick`() {
        assertEquals(
            listOf(listOf("there"), listOf("back"), listOf("there"), listOf("back")),
            pingPong.run().transitionsIn(4),
            "one token cannot enable both transitions at once, so the loop must alternate",
        )
    }

    @Test
    fun `produced tokens do not cascade inside a single maximal step`() {
        // If production landed immediately, "there" and "back" would both fire on tick 0 and the
        // token would be back where it started having sounded twice — a whole class of nets would
        // collapse into one instant. The step boundary is what stops that.
        val first = pingPong.run().steps(1).single()
        assertEquals(listOf("there"), first.firings.map { it.transition })
        assertEquals(0, first.after.count("a"))
        assertEquals(1, first.after.count("b"))
    }

    @Test
    fun `a firing's event arc carries duration and delay, and its fire time does not`() {
        val net = petri {
            place("a", tokens = 1)
            t("late", dur = cyc(1, 8), delay = cyc(1, 32)) { from("a"); to("b") }
            grid = cyc(1, 4)
        }
        val firing = net.run().steps(1).single().firings.single()
        assertEquals(PatQ.ZERO, firing.at, "delay must not move when the net's logic fires")
        assertEquals(cyc(1, 32), firing.arc.start, "the event starts late by exactly the delay")
        assertEquals(cyc(1, 8), firing.arc.dur, "the event lasts exactly the duration")
    }

    @Test
    fun `a transition with no duration emits an event one grid long`() {
        val net = petri {
            place("a", tokens = 1)
            t("plain") { from("a"); to("b") }
            grid = cyc(1, 16)
        }
        assertEquals(cyc(1, 16), net.run().steps(1).single().firings.single().arc.dur)
    }

    // ── maximal step ──────────────────────────────────────────────────────────────────────

    @Test
    fun `every independently enabled transition fires in the same step`() {
        val net = petri {
            place("k", tokens = 1)
            place("h", tokens = 1)
            t("kick") { from("k"); to("k2") }
            t("hat") { from("h"); to("h2") }
        }
        assertEquals(setOf("kick", "hat"), net.run().steps(1).single().firings.map { it.transition }.toSet())
    }

    @Test
    fun `two transitions competing for one token resolve by priority, and only one fires`() {
        val net = petri {
            place("shared", tokens = 1)
            t("loud", priority = 10) { from("shared"); to("a") }
            t("quiet", priority = 1) { from("shared"); to("b") }
        }
        val step = net.run().steps(1).single()
        assertEquals(listOf("loud"), step.firings.map { it.transition })
        assertEquals(1, step.after.count("a"))
        assertEquals(0, step.after.count("b"))
    }

    @Test
    fun `two tokens in a contested place feed both competitors`() {
        val net = petri {
            place("shared", tokens = 2)
            t("loud", priority = 10) { from("shared"); to("a") }
            t("quiet", priority = 1) { from("shared"); to("b") }
        }
        val step = net.run().steps(1).single()
        assertEquals(setOf("loud", "quiet"), step.firings.map { it.transition }.toSet())
        assertEquals(0, step.after.count("shared"))
    }

    @Test
    fun `round robin rotates which competitor wins so neither starves`() {
        val net = petri {
            conflict = PetriConflict.ROUND_ROBIN
            place("shared", tokens = 1)
            t("a") { from("shared"); to("shared") }
            t("b") { from("shared"); to("shared") }
        }
        val winners = net.run().transitionsIn(4).map { it.single() }
        assertEquals(listOf("a", "b", "a", "b"), winners)
    }

    @Test
    fun `random conflict resolution is reproducible for a given seed and differs between seeds`() {
        fun winners(seed: Int) = petri {
            conflict = PetriConflict.RANDOM
            this.seed = seed
            place("shared", tokens = 1)
            t("a") { from("shared"); to("shared") }
            t("b") { from("shared"); to("shared") }
        }.run().transitionsIn(24).map { it.single() }

        assertEquals(winners(7), winners(7), "the same seed must replay exactly — the pattern layer depends on it")
        assertNotEquals(winners(7), winners(8), "different seeds should not agree over 24 steps")
        assertTrue(winners(7).toSet() == setOf("a", "b"), "both transitions should win sometimes")
    }

    // ── arc weights, guards, capacity, and the two odd arc kinds ──────────────────────────

    @Test
    fun `a weighted arc waits until the place has enough`() {
        val net = petri {
            place("in", tokens = 1)
            t("fill") { to("in") }
            t("burst") { from("in", weight = 3); to("out") }
        }
        // "fill" adds one per tick; "burst" cannot go until three have accumulated.
        val fired = net.run().transitionsIn(4)
        assertEquals(listOf("fill"), fired[0])
        assertEquals(listOf("fill"), fired[1])
        assertTrue("burst" in fired[2], "three tokens are present on the third tick, so burst fires")
    }

    @Test
    fun `an inhibitor arc blocks while the place is occupied and releases when it empties`() {
        val net = petri {
            place("armed", tokens = 1)
            place("src", tokens = 1)
            t("disarm", priority = 5) { from("armed"); to("spent") }
            t("fill", priority = 1) { inhibit("armed"); from("src"); to("dst") }
        }
        val fired = net.run().transitionsIn(2)
        assertEquals(listOf("disarm"), fired[0], "while 'armed' holds a token, 'fill' is inhibited")
        assertEquals(listOf("fill"), fired[1], "once 'armed' is empty, 'fill' goes")
    }

    @Test
    fun `a read arc requires a token without consuming it`() {
        val net = petri {
            place("clock", tokens = 1)
            place("src", tokens = 2)
            t("tick") { read("clock"); from("src"); to("dst") }
        }
        val run = net.run()
        run.steps(2)
        assertEquals(1, run.markingAt(cyc(1)).count("clock"), "a read arc must leave the token where it was")
        assertEquals(2, run.steps(2).sumOf { it.firings.size }, "so it can gate every tick")
    }

    @Test
    fun `a guard makes a transition selective about colour`() {
        val net = petri {
            place("notes", colours = listOf(48, 72, 50, 79))
            t("high") { from("notes", guard = { (it.colour as? Int ?: 0) >= 60 }); to("lead") }
        }
        val run = net.run()
        val played = run.steps(2).flatMap { it.firings }.flatMap { it.consumed }.map { it.colour }
        assertEquals(listOf(72, 79), played, "only the high notes are eligible, oldest first")
        assertEquals(listOf(48, 50), run.markingAt(cyc(4)).colours("notes"), "the low ones stay put")
    }

    @Test
    fun `a bounded place refuses a firing that would overfill it`() {
        val net = petri {
            place("src", tokens = 4)
            place("dst", capacity = 2)
            t("push") { from("src"); to("dst") }
        }
        val run = net.run()
        run.steps(8)
        assertEquals(2, run.markingAt(cyc(4)).count("dst"), "capacity 2 means capacity 2")
        assertEquals(PetriHalt.DEADLOCK, run.halt, "and once it is full the net is stuck")
    }

    // ── colour flow ───────────────────────────────────────────────────────────────────────

    @Test
    fun `colours are carried through a transition by default`() {
        val net = petri {
            place("verse", colours = listOf("a-rhythm"))
            t("advance") { from("verse"); to("chorus") }
        }
        assertEquals(listOf("a-rhythm"), net.run().steps(1).single().after.colours("chorus"))
    }

    @Test
    fun `BLACK colour flow deliberately forgets what it consumed`() {
        val net = petri {
            place("verse", colours = listOf("a-rhythm"))
            t("advance", colourFlow = PetriColourFlow.BLACK) { from("verse"); to("chorus") }
        }
        assertEquals(listOf<Any?>(null), net.run().steps(1).single().after.colours("chorus"))
    }

    @Test
    fun `an output arc can compute a new colour from the tokens consumed`() {
        val net = petri {
            place("l", colours = listOf(3))
            place("r", colours = listOf(4))
            t("combine") {
                from("l"); from("r")
                toColoured("sum") { b -> b.colours<Int>().sum() }
            }
        }
        assertEquals(listOf(7), net.run().steps(1).single().after.colours("sum"))
    }

    @Test
    fun `emit can be computed from the binding`() {
        val net = petri {
            place("notes", colours = listOf(60, 64))
            t("play") { from("notes"); emit { b -> "n${b.colour()}" } }
        }
        assertEquals(listOf("n60", "n64"), net.run().steps(2).flatMap { it.firings }.map { it.emit })
    }

    @Test
    fun `onlyIf vetoes a binding the arcs would otherwise have allowed`() {
        val net = petri {
            place("notes", colours = listOf(1, 2, 3, 4))
            t("evens") {
                from("notes")
                onlyIf { b -> (b.colourAs<Int>() ?: 0) % 2 == 0 }
            }
        }
        // 1 is at the front and fails the veto, so nothing fires at all — a veto is not a filter.
        assertEquals(PetriHalt.DEADLOCK, net.run().also { it.steps(4) }.halt)
    }

    // ── timing ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a TIMED transition holds its tokens for exactly its duration`() {
        val net = petri {
            timing = PetriTiming.TIMED
            place("a", tokens = 1)
            t("hold", dur = cyc(2)) { from("a"); to("b") }
        }
        val run = net.run()
        run.steps(4)
        assertEquals(0, run.markingAt(cyc(1)).count("a"), "consumed at the start")
        assertEquals(0, run.markingAt(cyc(1)).count("b"), "and still in flight one cycle in")
        assertEquals(1, run.markingAt(cyc(2) + cyc(1, 64)).count("b"), "landing exactly two cycles later")
    }

    @Test
    fun `a TIMED run advances to the next interesting instant rather than walking the grid`() {
        val net = petri {
            timing = PetriTiming.TIMED
            grid = cyc(1, 16)
            place("a", tokens = 1)
            t("slow", dur = cyc(4)) { from("a"); to("b") }
        }
        val steps = net.run().steps(2)
        assertEquals(PatQ.ZERO, steps[0].at)
        assertEquals(cyc(4), steps[1].at, "no sixty-four empty grid ticks in between")
    }

    @Test
    fun `EVENT timing fires exactly one transition per tick`() {
        val net = petri {
            timing = PetriTiming.EVENT
            place("k", tokens = 1)
            place("h", tokens = 1)
            t("kick", priority = 2) { from("k"); to("k2") }
            t("hat", priority = 1) { from("h"); to("h2") }
        }
        assertEquals(listOf(listOf("kick"), listOf("hat")), net.run().transitionsIn(2))
    }

    // ── halting ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a net with nothing enabled deadlocks immediately rather than idling`() {
        val net = petri {
            place("a")
            t("never") { from("a"); to("b") }
        }
        val run = net.run()
        assertTrue(run.steps(4).isEmpty())
        assertEquals(PetriHalt.DEADLOCK, run.halt)
    }

    @Test
    fun `maxUnroll stops a runaway query instead of hanging`() {
        val net = petri {
            place("a", tokens = 1)
            t("spin") { from("a"); to("a") }
        }
        val run = net.run(maxUnroll = 50)
        assertEquals(50, run.steps(10_000).size)
        assertEquals(PetriHalt.EXHAUSTED, run.halt)
    }

    @Test
    fun `a place that only fills stops the run and gets named, rather than filling the heap`() {
        val net = petri {
            place("run", tokens = 1)
            t("make") { from("run"); to("run"); to("queue") }
        }
        val run = net.run(maxTokens = 32)
        run.steps(10_000)
        assertEquals(PetriHalt.FLOODED, run.halt)
        assertEquals("queue", run.floodedPlace, "the name of the leak is the whole answer")
        assertEquals(33, run.markingAt(cyc(1_000)).count("queue"), "it stops one past the ceiling, so you can see it")
        assertNull(net.run().floodedPlace, "and a run that has not been asked anything has not flooded")
    }

    // ── shared token storage ──────────────────────────────────────────────────────────────

    /*
     Appending to a place shares the storage of the list it appended to (see [SharedTokenList]), so
     the thing worth testing is that two markings grown from the *same* parent cannot see each
     other's tokens — the one case where sharing has to give up and copy.
    */

    @Test
    fun `a long trajectory through a filling place costs its length, not its length squared`() {
        // A step keeps a marking and a marking used to copy the whole target place, so a net that
        // leaks one token per step cost O(n²): sixteen thousand steps of this exhausted the heap,
        // and four thousand — the default budget every analysis in `PetriAnalysis.kt` asks for —
        // already cost seventy megabytes. Asking for four times the default is the assertion.
        val net = petri {
            place("run", tokens = 1)
            t("make") { from("run"); to("run"); to("queue") }
        }
        val run = net.run(maxTokens = 100_000)
        run.lasso(16_384)

        assertEquals(16_384, run.materialised)
        assertEquals(PetriHalt.RUNNING, run.halt, "it ran out of budget, not out of room")

        // Every step's marking is still its own length, which is what says the sharing did not
        // quietly hand every step the same list. Asked for by count, not by instant: `markingAt`
        // would extend the trajectory rather than read the part of it already built.
        val steps = run.steps(16_384)
        assertEquals(1, steps.first().after.count("queue"))
        assertEquals(4_096, steps[4_095].after.count("queue"))
        assertEquals(16_384, steps.last().after.count("queue"))
    }

    @Test
    fun `two markings grown from one parent do not see each other's tokens`() {
        val parent = PetriMarking.counts("q" to 1)
        val left = parent.add("q", listOf(PetriToken("L")))
        val right = parent.add("q", listOf(PetriToken("R")))

        assertEquals(listOf(null), parent.colours("q"), "the parent is immutable and stayed that way")
        assertEquals(listOf(null, "L"), left.colours("q"))
        assertEquals(listOf(null, "R"), right.colours("q"), "the second child branches rather than overwriting")

        // And a branch is itself appendable, several deep, without disturbing its siblings.
        val deeper = right.add("q", listOf(PetriToken("R2"))).add("q", listOf(PetriToken("R3")))
        assertEquals(listOf(null, "R", "R2", "R3"), deeper.colours("q"))
        assertEquals(listOf(null, "L"), left.colours("q"))
    }

    @Test
    fun `a long chain of appends keeps every intermediate marking intact`() {
        val history = mutableListOf(PetriMarking.EMPTY)
        repeat(200) { index -> history += history.last().add("q", listOf(PetriToken(index))) }

        // Every snapshot still reads as its own length, and a branch off the middle of the chain
        // does not rewrite the tokens the chain already handed out.
        history.forEachIndexed { length, marking ->
            assertEquals(length, marking.count("q"))
            assertEquals((0 until length).toList(), marking.colours("q"))
        }
        val branch = history[100].add("q", listOf(PetriToken("elsewhere")))
        assertEquals("elsewhere", branch.colours("q").last())
        assertEquals(100, history[101].colours("q").last(), "the original 101st is untouched")
        assertEquals(200, history.last().count("q"))
    }

    @Test
    fun `an appended token list is an ordinary list to everyone who reads it`() {
        val marking = PetriMarking.EMPTY
            .add("q", listOf(PetriToken("a")))
            .add("q", listOf(PetriToken("b"), PetriToken("c")))
        val tokens = marking["q"]

        assertEquals(3, tokens.size)
        assertEquals(listOf(PetriToken("a"), PetriToken("b"), PetriToken("c")), tokens, "equal to a plain list")
        assertEquals(listOf(PetriToken("a"), PetriToken("b"), PetriToken("c")).hashCode(), tokens.hashCode())
        assertEquals(listOf("b", "c"), tokens.drop(1).map { it.colour }, "and the stdlib works on it")
        assertEquals(PetriMarking.of(mapOf("q" to tokens)), marking, "so markings still compare by content")
    }

    @Test
    fun `the marking hash agrees with the signature it stands in for`() {
        // Recurrence detection keeps the hash and not the string, so equal signatures must hash
        // equally — the direction the search relies on. Birth times and colour order are the two
        // things the signature deliberately ignores, and so must the hash.
        val early = PetriMarking.of(mapOf("q" to listOf(PetriToken("a", cyc(1)), PetriToken("b", cyc(2)))))
        val late = PetriMarking.of(mapOf("q" to listOf(PetriToken("b", cyc(9)), PetriToken("a", cyc(9)))))
        assertEquals(early.signature(), late.signature())
        assertEquals(early.signatureHash(), late.signatureHash())
        assertNotEquals(early, late, "…while the markings themselves are still distinguishable")

        val other = PetriMarking.of(mapOf("q" to listOf(PetriToken("a"), PetriToken("c"))))
        assertNotEquals(early.signature(), other.signature())
        assertNotEquals(early.signatureHash(), other.signatureHash())

        // A place's name is part of the state, not just its contents.
        val here = PetriMarking.counts("a" to 1, "b" to 2)
        val swapped = PetriMarking.counts("a" to 2, "b" to 1)
        assertNotEquals(here.signatureHash(), swapped.signatureHash())
        assertNotEquals(here.signatureHash(), PetriMarking.counts("a" to 1).signatureHash())
        assertEquals(PetriMarking.EMPTY.signatureHash(), PetriMarking.counts("a" to 0).signatureHash())
    }

    // ── the memo ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a run replays identically no matter what order it is asked in`() {
        fun sample(run: PetriRun) = run.stepsIn(cyc(2), cyc(3)).map { it.toString() }

        val forwards = pingPong.run().also { it.steps(64) }
        val outOfOrder = pingPong.run().also {
            it.stepsUntil(cyc(8))
            it.stepsUntil(cyc(1))
            it.stepsUntil(cyc(4))
        }
        assertEquals(sample(forwards), sample(outOfOrder))
        assertTrue(sample(forwards).isNotEmpty(), "the window must actually contain something")
    }

    @Test
    fun `firingsOver returns a long firing that started before the window`() {
        val net = petri {
            timing = PetriTiming.TIMED
            place("a", tokens = 1)
            t("sustain", dur = cyc(4)) { from("a"); to("b") }
        }
        val overlapping = net.run().firingsOver(afield.four.synth.PatArc(cyc(2), cyc(3)))
        assertEquals(listOf("sustain"), overlapping.map { it.transition })
    }

    @Test
    fun `reset returns a run to its initial marking`() {
        val run = pingPong.run()
        run.steps(8)
        run.reset()
        assertEquals(0, run.materialised)
        assertEquals(1, run.markingAt(PatQ.ZERO).count("a"))
    }

    // ── construction errors are caught at build time, not at run time ─────────────────────

    @Test
    fun `a net rejects duplicate names and impossible weights`() {
        val duplicate = runCatching { PetriNet(listOf(PetriPlace("a"), PetriPlace("a")), emptyList()) }
        assertTrue(duplicate.exceptionOrNull()?.message?.contains("Duplicate place") == true)

        val badWeight = runCatching { PetriInput("a", weight = 0) }
        assertTrue(badWeight.exceptionOrNull()?.message?.contains("weight") == true)
    }

    @Test
    fun `a marking drops empty places so two spellings of nothing compare equal`() {
        assertEquals(PetriMarking.EMPTY, PetriMarking.counts("a" to 0, "b" to 0))
        assertNull(PetriMarking.counts("a" to 0).toMap()["a"])
    }
}
