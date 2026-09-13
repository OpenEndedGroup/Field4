package afield.server

import afield.Box
import afield.documentGuideNotePath
import kotlin.test.*

/**
 * The document viewer collects `notes` properties beside the Markdown files, so a document's prose
 * can be read in one place. These pin what shows up in that list and what a read of one returns.
 */
class DocumentGuideNotesTest {
    private fun box(id: String, name: String = "", notes: String? = null, tombstone: Boolean = false) = Box(id).also {
        it.setPropertyRaw("name", name, doResync = false)
        if (notes != null) it.setPropertyRaw("notes", notes, doResync = false)
        if (tombstone) it.setPropertyRaw("_tombstone", true, doResync = false)
    }

    @Test fun listsLiveNotesByHeadingThenBoxName() {
        val notes = DocumentGuideNotes(listOf(
            box("aaa", name = "camera", notes = "# Framing decisions\n\nKeep the long lens."),
            box("bbb", name = "audio", notes = "no heading here, so the box name titles it"),
            box("ccc", name = "quiet"),
            box("ddd", name = "blank", notes = "   \n\t\n"),
            box("eee", name = "deleted", notes = "# Gone\n", tombstone = true),
            box("fff", notes = "prose with neither heading nor name")
        ))
        assertEquals(listOf("audio", "fff", "Framing decisions"), notes.list().map { it.title })
        assertEquals(listOf("box/bbb", "box/fff", "box/aaa"), notes.list().map { it.path })
        assertTrue(notes.list().all { it.kind == "note" })
        assertEquals(listOf("bbb", "fff", "aaa"), notes.list().map { it.uid })
    }

    @Test fun readsOneNoteWithARevisionThatTracksItsText() {
        val camera = box("aaa", name = "camera", notes = "# Framing\n")
        val notes = DocumentGuideNotes(listOf(camera, box("bbb", name = "quiet")))
        val read = notes.read(documentGuideNotePath("aaa"))
        assertEquals("# Framing\n", read.contents)
        assertEquals("note", read.kind)
        assertEquals("aaa", read.uid)
        camera.setPropertyRaw("notes", "# Framing\n\nMore.", doResync = false)
        assertNotEquals(read.revision, notes.read(read.path).revision)
        // Nothing to read: a box without notes, a box from another document, and a file path.
        assertFailsWith<IllegalStateException> { notes.read(documentGuideNotePath("bbb")) }
        assertFailsWith<IllegalStateException> { notes.read(documentGuideNotePath("zzz")) }
        assertFailsWith<IllegalStateException> { notes.read("README.md") }
    }

    @Test fun aNoteBiggerThanTheFileLimitIsNeitherListedNorRead() {
        val notes = DocumentGuideNotes(listOf(box("aaa", name = "huge", notes = "x".repeat(1024 * 1024 + 1))))
        assertTrue(notes.list().isEmpty())
        assertFailsWith<IllegalStateException> { notes.read(documentGuideNotePath("aaa")) }
    }
}
