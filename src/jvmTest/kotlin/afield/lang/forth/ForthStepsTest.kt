package afield.lang.forth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The step-sequencer words: note names, `< … >`, `[ … ] iter index`, `wchoose`, `seq: … ;seq`. */
class ForthStepsTest {
    private fun forth() = ForthInterpreter().also { it.onOutput = {} }

    private fun seq(f: ForthInterpreter, name: String) = f.dictionary[name].let { (it as ForthWord.Constant).value as ForthSequence }

    @Test
    fun noteNamesAreMidiNumbers() {
        assertEquals(60.0, forthNoteNumber("c4"))
        assertEquals(51.0, forthNoteNumber("eb3"))
        assertEquals(55.0, forthNoteNumber("g3"))
        assertEquals(78.0, forthNoteNumber("f#5"))
        assertEquals(46.0, forthNoteNumber("bb2"))
        assertEquals(59.0, forthNoteNumber("b3"))
        assertEquals("d#3", forthNoteName(51.0))
        val f = forth()
        f.eval("c4 a4")
        assertEquals(listOf(60.0, 69.0), f.stack.toList())
    }

    @Test
    fun alternationCyclesAcrossPassesOfAStep() {
        val f = forth()
        f.eval("seq: alt < c4 eb3 g3 > note ;seq")
        val notes = f.runSteps(seq(f, "alt"), 7).map { it.midi }
        assertEquals(listOf(60.0, 51.0, 55.0, 60.0, 51.0, 55.0, 60.0), notes)
    }

    @Test
    fun alternationIsKeyedByEachStepsOwnPassCount() {
        val f = forth()
        f.eval("seq: two < c4 eb3 g3 > note | < a4 b4 > note ;seq")
        val notes = f.runSteps(seq(f, "two"), 6).map { it.midi }
        assertEquals(listOf(60.0, 69.0, 51.0, 71.0, 55.0, 69.0), notes)
    }

    @Test
    fun lessThanIsStillAComparison() {
        val f = forth()
        f.eval("1 2 < 3 2 >")
        assertEquals(listOf(-1.0, -1.0), f.stack.toList())
        f.stack.clear()
        f.eval("1 2 < if 7 then")
        assertEquals(listOf(7.0), f.stack.toList())
        f.eval("seq: cmp 1 2 < if c4 note then ;seq")
        assertEquals(listOf(60.0), f.runSteps(seq(f, "cmp"), 1).map { it.midi }, "inside a step, < before if compares")
    }

    @Test
    fun iterIndexWalksTheList() {
        val f = forth()
        f.eval("seq: arp [ c4 e4 g4 ] iter index note ;seq")
        val notes = f.runSteps(seq(f, "arp"), 5).map { it.midi }
        assertEquals(listOf(60.0, 64.0, 67.0, 60.0, 64.0), notes)
        f.eval("[ 1 2 3 ] 4 index")
        assertEquals(2.0, f.stack.last())
    }

    @Test
    fun wchooseFollowsItsWeightsAndItsSeed() {
        val f = forth()
        f.eval("42 seed")
        val counts = HashMap<Double, Int>()
        repeat(10_000) {
            f.eval("[ c4 e4 g4 ] [ 6 3 1 ] wchoose")
            val v = f.stack.removeLast() as Double
            counts[v] = (counts[v] ?: 0) + 1
        }
        assertEquals(setOf(60.0, 64.0, 67.0), counts.keys)
        assertTrue(counts.getValue(60.0) in 5700..6300, "$counts")
        assertTrue(counts.getValue(64.0) in 2700..3300, "$counts")
        assertTrue(counts.getValue(67.0) in 800..1200, "$counts")

        fun draw(): List<Any> { val g = forth(); g.eval("7 seed"); repeat(8) { g.eval("[ 1 2 3 ] [ 1 1 1 ] wchoose") }; return g.stack.toList() }
        assertEquals(draw(), draw(), "same seed, same draws")
    }

    @Test
    fun voiceWordsShapeTheNoteAndResetEachStep() {
        val f = forth()
        f.eval(": chime tri 0.6 dur ; seq: v saw 800 lpf c3 note | | chime g5 note | s\" bd\" sound ;seq")
        val e = f.runSteps(seq(f, "v"), 4)
        assertEquals(3, e.size, "the empty second step is a rest")
        assertEquals("saw", e[0].wave); assertEquals(800.0, e[0].cutoff)
        assertEquals("tri", e[1].wave); assertEquals(null, e[1].cutoff); assertEquals(2L, e[1].time); assertEquals(0.6, e[1].dur)
        assertEquals("bd", e[2].sound)
        val audio = renderForthSteps(e, 4, f.steps.stepSeconds)
        assertTrue(audio.left.any { kotlin.math.abs(it) > 0.01f }, "renders sound through PatSynthBank")
    }

    @Test
    fun aGridIsAPatternOneCycleLong() {
        val f = forth()
        f.eval("seq: kit s\" bd\" sound | < c4 g4 > note ;seq")
        val pat = f.forthStepsPattern(seq(f, "kit"), 4)
        val ev = pat.events(afield.audio.synth.PatArc(afield.audio.synth.PatQ.of(1), afield.audio.synth.PatQ.of(2)))
        assertEquals(listOf("bd", "60", "bd", "67"), ev.map { it.value })
        assertEquals(afield.audio.synth.PatQ.of(5, 4), ev[1].arc.start)
        assertEquals("67", ev[3].annotations["note"])
    }

    @Test
    fun runStepsPrintsAnEventLog() {
        val f = ForthInterpreter()
        val out = StringBuilder()
        f.onDisplay = { ch, v -> if (ch == "steps") out.append(v) }
        f.eval("seq: alt < c4 eb3 g3 > note ;seq alt 3 run-steps")
        assertEquals("<pre style='margin:0'>step 0  cell 0  c4 (60)  sine\nstep 1  cell 0  d#3 (51)  sine\nstep 2  cell 0  g3 (55)  sine</pre>", out.toString())
    }
}

/** The audio on forth_examples.md is rendered from [ForthStepsDocs.SCENES]; the page must show that same source. */
class ForthStepsDocsTest {
    @Test
    fun everySceneIsOnThePageAndRendersSound() {
        val page = java.io.File("public/docs/forth_examples.md").readText()
        for ((name, source) in ForthStepsDocs.SCENES + ForthStepsDocs.DIRT_SCENES) {
            assertTrue(source in page, "forth_examples.md no longer shows scene '$name' as ForthStepsDocs renders it")
            assertTrue("name=\"$name\"" in page, "no fence named $name")
            if (name in ForthStepsDocs.DIRT_SCENES && afield.audio.synth.DirtRender.libraryOrNull() == null) continue
            assertTrue(ForthStepsDocs.render(name).frames > 12_000, "scene $name rendered under a quarter second")
        }
    }
}
