package afield.study.source

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.graphics.render.rangeTo
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.linalg.x
import afield.math.linalg.zzz
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.runtime.execution.start

import afield.data.collections.s
import afield.geometry.mesh.contains
import afield.math.functions.StudySourceEdit
import afield.simulation.automata.history
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class SourceStudyTest {
    private class Editor(var text: String) {
        val read = { text }
        val replace = { expected: String, next: String -> if (text != expected) false else { text = next; true } }
    }

    private fun study(dir: File, editor: Editor, admit: (String) -> Int = { it.length }) =
        SourceStudy(dir, "txt", editor.read, editor.replace, { source, _ ->
            if (source.contains("broken")) throw StudyRejection("refused: broken") else StudyAdmission(admit(source), mapOf("length" to source.length))
        })

    private fun withDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("source-study").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun editsAreTransactionsAgainstTheRevisionAndTheLiveText() = withDir { dir ->
        val editor = Editor("start")
        val s = study(dir, editor)
        val r1 = s.edit(SourceStudy.EMPTY, SourceStudy.hash("start"), "first", "one")
        assertTrue(r1.id.matches(SourceStudy.REVISION_ID))
        assertEquals("first", editor.text)
        assertEquals(5, r1.value)
        // Stale revision and stale hash both refuse, overwriting nothing.
        assertFailsWith<StudyRejection> { s.edit(SourceStudy.EMPTY, SourceStudy.hash("first"), "x", "stale revision") }
        assertFailsWith<StudyRejection> { s.edit(r1.id, SourceStudy.hash("start"), "x", "stale hash") }
        // A person types; a model's edit prepared against the old text is refused.
        editor.text = "typed by hand"
        assertFailsWith<StudyRejection> { s.edit(r1.id, SourceStudy.hash("first"), "model", "stale") }
        assertEquals("typed by hand", editor.text)
        assertEquals(true, s.liveState()["dirty"])
        // A refused candidate changes nothing.
        assertFailsWith<StudyRejection> { s.edit(r1.id, SourceStudy.hash("typed by hand"), "broken", "bad") }
        assertEquals(r1.id, s.currentId())
        assertEquals("typed by hand", editor.text)
    }

    @Test fun patchesAreUniqueAndNonOverlapping() = withDir { dir ->
        val editor = Editor("a b c b")
        val s = study(dir, editor)
        val r1 = s.edit(SourceStudy.EMPTY, SourceStudy.hash("a b c b"), "a b c b", "start")
        assertFailsWith<StudyRejection> { s.patch(r1.id, SourceStudy.hash(editor.text), listOf(StudySourceEdit("b", "B")), "ambiguous") }
        assertFailsWith<StudyRejection> { s.patch(r1.id, SourceStudy.hash(editor.text), listOf(StudySourceEdit("a b", "x"), StudySourceEdit("b c", "y")), "overlap") }
        assertFailsWith<StudyRejection> { s.patch(r1.id, SourceStudy.hash(editor.text), listOf(StudySourceEdit("zzz", "y")), "missing") }
        val r2 = s.patch(r1.id, SourceStudy.hash(editor.text), listOf(StudySourceEdit("c b", "C b"), StudySourceEdit("a ", "A ")), "two")
        assertEquals("A b C b", editor.text)
        assertEquals(r1.id, r2.parent)
    }

    @Test fun restoreAppendsAndHistoryFollowsTheActiveLine() = withDir { dir ->
        val editor = Editor("")
        val s = study(dir, editor)
        val r1 = s.edit(SourceStudy.EMPTY, SourceStudy.hash(""), "one", "1")
        val r2 = s.edit(r1.id, SourceStudy.hash("one"), "two", "2")
        val r3 = s.restore(r2.id, SourceStudy.hash("two"), r1.id)
        assertEquals("one", editor.text)
        assertEquals(r2.id, r3.parent)
        assertNotEquals(r1.id, r3.id)
        assertEquals(listOf(r1.id, r2.id, r3.id), s.history().map { it.id })
        assertEquals(3, s.allRevisions().size)
        assertTrue(r3.description.startsWith("restore ${r1.id}"))
    }

    @Test fun aNewSessionResumesAndRefusesASourceEditedOnDisk() = withDir { dir ->
        val editor = Editor("")
        val r1 = study(dir, editor).edit(SourceStudy.EMPTY, SourceStudy.hash(""), "kept", "1")
        val again = study(dir, editor)
        assertEquals(r1.id, again.resume())
        assertEquals("kept", again.revision().source)
        File(dir, "${r1.id}.txt").writeText("tampered")
        assertFailsWith<StudyRejection> { study(dir, editor).resume() }
    }

    @Test fun checkAdmitsWithoutPublishing() = withDir { dir ->
        val editor = Editor("live")
        val s = study(dir, editor)
        assertEquals(9, s.check("candidate").value)
        assertEquals(SourceStudy.EMPTY, s.currentId())
        assertEquals("live", editor.text)
        assertFailsWith<StudyRejection> { s.check("broken") }
    }

    @Test fun jsonSourcesSurviveReloadAndRestoreSeparatelyFromMetadata() = withDir { dir ->
        val editor = Editor("{}")
        fun jsonStudy() = SourceStudy(dir, "json", editor.read, editor.replace,
            { source, _ -> StudyAdmission(source) })
        val first = """{"value":1}"""
        val second = """{"value":2}"""
        val s = jsonStudy()
        val r1 = s.edit(SourceStudy.EMPTY, SourceStudy.hash(editor.text), first, "first JSON")
        val r2 = s.edit(r1.id, SourceStudy.hash(editor.text), second, "second JSON")
        assertEquals(first, File(dir, "${r1.id}.source.json").readText())
        val again = jsonStudy()
        assertEquals(r2.id, again.resume())
        assertEquals(second, again.revision().source)
        assertEquals(listOf(r1.id, r2.id), again.allRevisions().map { it.id })
        val restored = again.restore(r2.id, SourceStudy.hash(editor.text), r1.id)
        val reloaded = jsonStudy()
        assertEquals(restored.id, reloaded.resume())
        assertEquals(first, reloaded.revision().source)
        assertEquals(3, reloaded.history().size)
        File(dir, "${r1.id}.source.json").writeText("tampered")
        assertFailsWith<StudyRejection> { jsonStudy().revision(r1.id) }
    }
}
