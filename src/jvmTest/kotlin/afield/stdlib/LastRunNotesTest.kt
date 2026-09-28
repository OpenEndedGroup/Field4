package afield.stdlib

import afield.Box
import afield.clojure.ClojureHost
import afield.datalog.DatalogExecution
import afield.datalog.DatalogLineNotes
import afield.forth.ForthInterpreter
import afield.forth.evalRecordingLineNotes
import afield.forth.forthOkHtml
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Line notes for the languages that cannot evaluate part of a program without running it: the
 * last-run record ([LastRunNotes]) and what Forth and Clojure put in it, and Datalog's notes from the
 * model ([DatalogLineNotes]). No device, no server; Python's recorder lives in its interpreter and is
 * driven end to end instead (see `dev_docs/agents/LINE_NOTES_EVERY_LANGUAGE.md`).
 */
class LastRunNotesTest {
    private val t0 = 1_000_000L

    @Test
    fun aProviderWithNothingToSayPassesTheLineToTheNext() {
        val box = Box("last-run-fallthrough")
        LineNotes.register("lastRunNotesTestProperty") { _, _, _ -> LineNotes.Note("older") }
        LineNotes.register("lastRunNotesTestProperty") { _, _, line -> if (line == 1) LineNotes.Note("newer") else null }
        assertEquals("newer", LineNotes.note(box, "lastRunNotesTestProperty", "", 1)?.caption)
        assertEquals("older", LineNotes.note(box, "lastRunNotesTestProperty", "", 2)?.caption)
    }

    @Test
    fun aRecordedLineFollowsItsTextAndSaysWhenItHasChanged() {
        val box = Box("last-run-record")
        val code = "a\nb\nc"
        LastRunNotes.record(box, "code", code, 0, listOf(LastRunNotes.Produced(2, 2, "b was 2")), at = t0)
        val fresh = assertNotNull(LastRunNotes.note(box, "code", code, 2, now = t0 + 12_000))
        assertEquals("last run, 12 s ago · b was 2", fresh.caption)
        assertTrue(fresh.volatile)
        assertNull(LastRunNotes.note(box, "code", code, 1, now = t0))
        // Two lines inserted above: the note moves with "b".
        assertEquals("last run, just now · b was 2", LastRunNotes.note(box, "code", "x\ny\na\nb\nc", 4, now = t0)?.caption)
        // The line itself edited: the note says it is about the old text.
        val stale = assertNotNull(LastRunNotes.note(box, "code", "a\nb + 1\nc", 2, now = t0 + 300_000))
        assertTrue(stale.caption.startsWith("changed since the last run (5 min ago)"), stale.caption)
        assertTrue("b was 2" in stale.caption)
    }

    @Test
    fun fragmentRunsReplaceOnlyTheLinesTheyCover() {
        val box = Box("last-run-fragment")
        val code = "one\ntwo\nthree"
        LastRunNotes.record(box, "code", code, 0, (1..3).map { LastRunNotes.Produced(it, it, "whole run $it") }, at = t0)
        // Running line 2 alone: Execute.line is 1, the fragment's text is just that line.
        LastRunNotes.record(box, "code", "two", 1, listOf(LastRunNotes.Produced(1, 1, "fragment")), at = t0)
        assertTrue(LastRunNotes.note(box, "code", code, 2, now = t0)!!.caption.endsWith("fragment"))
        assertTrue(LastRunNotes.note(box, "code", code, 3, now = t0)!!.caption.endsWith("whole run 3"))
    }

    @Test
    fun aForthLineShowsTheStackItLeftAndARunThatFailedSaysWhere() {
        val box = Box("last-run-forth")
        val code = "1 2\n+ 10\n: sq dup *\n  ;\nsq\nnope 3\n4"
        val diagnostics = ForthInterpreter().evalRecordingLineNotes(box, code, 0)
        assertEquals(6, diagnostics.single().line)
        val first = assertNotNull(LastRunNotes.note(box, "code", code, 1))
        assertTrue("the stack after it: <2> 1.0 2.0" in first.caption, first.caption)
        val picture = ImageIO.read(first.png!!.inputStream())
        assertTrue(picture.width > 100 && picture.height > 30)
        assertTrue("<2> 3.0 10.0" in LastRunNotes.note(box, "code", code, 2)!!.caption)
        // A definition across two lines is one step, noted on both of its lines.
        val defines = LastRunNotes.note(box, "code", code, 4)!!.caption
        assertTrue("defines sq" in defines && defines == LastRunNotes.note(box, "code", code, 3)!!.caption, defines)
        assertTrue("<2> 3.0 100.0" in LastRunNotes.note(box, "code", code, 5)!!.caption)
        assertTrue("stopped here: Unknown word: 'nope'" in LastRunNotes.note(box, "code", code, 6)!!.caption)
        assertTrue("not reached" in LastRunNotes.note(box, "code", code, 7)!!.caption)
    }

    @Test
    fun aClojureFormNotesTheValueItEvaluatedToWithoutForcingALazySequence() {
        val values = mutableListOf<Triple<Int, Int, String>>()
        ClojureHost(defaultNs = "field.test.linenotes").evalPage(
            "(def x 21)\n(* 2\n   x)\n(map #(do (println \"forced!\") %) [1 2])\n(range 100)",
            onFormValue = { first, last, _, printed -> values += Triple(first, last, printed) },
        )
        assertEquals(Triple(2, 3, "42"), values[1])
        assertTrue(values[0].third.endsWith("/x"), values[0].third)
        assertEquals("an unrealised lazy sequence (a note does not force it)", values[2].third)
        assertEquals("(0 1 2 3 4 5 6 7 8 9 10 11 ...)", values[3].third, "printing is bounded by *print-length*")
    }

    @Test
    fun aDatalogLineIsAnsweredFromTheBoxsModel() {
        val program = "edge(a, b).\nedge(b, c).\npath(X, Y) :- edge(X, Y).\npath(X, Z) :-\n  path(X, Y), edge(Y, Z).\n?- path(a, W).\nlonely(q).\n#reset"
        val box = Box("last-run-datalog")
        val execution = DatalogExecution(box)
        execution.runProgram(program.lines().take(5).joinToString("\n"))
        val ran = t0
        val rule = assertNotNull(DatalogLineNotes.note(execution.datalog, program, 5, ran, now = t0 + 3_000))
        assertTrue(rule.caption.startsWith("derives 1 of path/2's 3 facts: path(a, c)"), rule.caption)
        assertTrue("3 s ago" in rule.caption)
        assertNotNull(rule.png)
        assertEquals(listOf("1"), rule.value.filter { it.strong }.map { it.text }, "the derived count is the result")
        assertTrue(rule.meta.startsWith("the box's model"))
        assertTrue(DatalogLineNotes.note(execution.datalog, program, 3, ran)!!.caption.startsWith("derives 2 of path/2's 3 facts"))
        assertTrue(DatalogLineNotes.note(execution.datalog, program, 6, ran)!!.caption.startsWith("?- path(a, W): 2 answers"))
        val fact = DatalogLineNotes.note(execution.datalog, program, 1, ran)!!.caption
        assertTrue("edge(a, b) is in the model" in fact && "read by the rules for path/2" in fact, fact)
        assertTrue("not in the model" in DatalogLineNotes.note(execution.datalog, program, 7, ran)!!.caption, "line 7 never ran")
        assertTrue("never for a note" in DatalogLineNotes.note(execution.datalog, program, 8, ran)!!.caption)
        // A rule edited since it ran is not answered for.
        val edited = program.replace("edge(X, Y).", "edge(Y, X).")
        assertTrue("not in the model as written" in DatalogLineNotes.note(execution.datalog, edited, 3, ran)!!.caption)
        // A statement being typed below does not cost the lines above their notes.
        assertNotNull(DatalogLineNotes.note(execution.datalog, "$program\nhalf(", 1, ran))
    }

    @Test
    fun anAIToolReadsTheSameNotesTheEditorShows() {
        installLastRunLineNotes() // what @RunsAt(plugins) does in a running Field
        val box = Box("last-run-tool")
        val code = "1 2\n+"
        box.properties["code"] = code
        ForthInterpreter().evalRecordingLineNotes(box, code, 0)
        val result = readLineNotes(box, "code", listOf(2, 1, 99))
        assertEquals(listOf(2, 1, 99), result.notes.map { it.line })
        assertTrue("<1> 3.0" in result.notes[0].caption!!, result.notes[0].caption)
        assertEquals("+", result.notes[0].text)
        assertNull(result.notes[2].caption, "a line past the end has no note")
        assertEquals(2, result.toolImages.size)
    }

    @Test
    fun aFieldMLProgramReportsEachTopLevelBindingAsItIsMade() {
        val result = afield.ml.FieldMLCompiler.compile("let x = 20\nlet double n = n * 2\ndouble x + 2")
        val bound = mutableListOf<Pair<String, String>>()
        val value = afield.ml.core.Evaluator().also { it.onTopLevelLet = { _, name, v -> bound += name to afield.ml.core.renderFieldMLValue(v) } }
            .evaluate(assertNotNull(result.core, result.diagnostics.toString()))
        assertEquals(listOf("x", "double"), bound.map { it.first })
        assertEquals("20", bound[0].second)
        assertEquals("42", afield.ml.core.renderFieldMLValue(value))
    }

    @Test
    fun aNoteSplitsItsResultsFromWhereTheyCameFrom() {
        val box = Box("last-run-split")
        val code = "1 2\n"
        ForthInterpreter().evalRecordingLineNotes(box, code, 0)
        val note = assertNotNull(LastRunNotes.note(box, "code", code, 1, now = System.currentTimeMillis()))
        assertEquals("last run, just now", note.meta)
        assertTrue(note.value.isEmpty(), "the picture draws the stack, so it is not also written out")
        assertEquals("last run, just now · the stack after it: <2> 1.0 2.0", note.caption, "the caption keeps it, for a reader that cannot see the picture")
        assertEquals(LineNotePictures.SCALE, note.imageScale)
        assertEquals(168 * LineNotePictures.SCALE, ImageIO.read(note.png!!.inputStream()).width, "drawn at 2x")
    }

    @Test
    fun aPictureIsDrawnOnlyWhenItsNoteIsAskedForAndThenOnce() {
        val box = Box("last-run-lazy")
        var drawn = 0
        LastRunNotes.record(box, "code", "a\nb", 0, listOf(1, 2).map { line ->
            LastRunNotes.Produced(line, line, "line $line", picture = { drawn++; LineNotePictures.stack(listOf("$line")) })
        })
        assertEquals(0, drawn, "recording draws nothing")
        assertNotNull(LastRunNotes.note(box, "code", "a\nb", 2)?.png)
        assertNotNull(LastRunNotes.note(box, "code", "a\nb", 2)?.png)
        assertEquals(1, drawn, "one note asked for twice: one picture")
    }

    @Test
    fun aDeepStackIsRecordedByItsTopAndDepth() {
        val box = Box("last-run-deep")
        val code = "clear\n" + (1..200).joinToString("\n") { "$it" }
        ForthInterpreter().evalRecordingLineNotes(box, code, 0)
        val last = assertNotNull(LastRunNotes.note(box, "code", code, 201))
        assertTrue("<200> … 195.0 196.0 197.0 198.0 199.0 200.0" in last.caption, last.caption)
        assertNotNull(last.png)
    }

    @Test
    fun aCleanForthRunMarksWhatItLeftOnTheStack() {
        assertEquals("ok", forthOkHtml(emptyList(), 0))
        assertEquals("<span class=\"afield-forth-ok\">ok · &lt;3&gt; 3.0 100.0 <b>&quot;done&quot;</b></span>", forthOkHtml(listOf("3.0", "100.0", "\"done\""), 3))
        assertEquals("<span class=\"afield-forth-ok\">ok · &lt;9&gt; … 4 5 6 7 8 <b>&lt;9&gt;</b></span>", forthOkHtml((4..8).map { "$it" } + "<9>", 9))
    }

    @Test
    fun besideAPictureANoteSaysOnlyWhatThePictureDoesNot() {
        val box = Box("last-run-beside")
        val code = "3\n: sq dup * ;\nsq"
        ForthInterpreter().evalRecordingLineNotes(box, code, 0)
        val defines = assertNotNull(LastRunNotes.note(box, "code", code, 2))
        assertEquals("defines sq", defines.value.joinToString("") { it.text })
        assertEquals(listOf("sq"), defines.value.filter { it.strong }.map { it.text })
        assertTrue("the stack after it: <1> 3.0" in defines.caption)
        // Changed since the run: no picture, so the stack is written out again.
        val stale = assertNotNull(LastRunNotes.note(box, "code", "3\n: sq dup * ;\nsq sq", 3))
        assertNull(stale.png)
        assertEquals(listOf("9.0"), stale.value.filter { it.strong }.map { it.text })
    }
}
