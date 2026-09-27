package afield.stdlib

import afield.Box
import afield.rectsl.study.RectSLStudy
import afield.stacksl.study.StackSLStudy
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a line of a program shows, as the editor's completion popup and cursor notes offer it
 * ([LineNotes]): the registry, and the StackSL and RectSL providers on the CPU reference.
 */
class LineNotesTest {
    private val folders = mutableListOf<File>()

    @AfterTest
    fun removeFolders() = folders.forEach { it.deleteRecursively() }

    private fun folder() = Files.createTempDirectory("line-notes").toFile().also { folders += it }

    @Test
    fun theNewestProviderThatTakesTheBoxAnswers() {
        val a = Box("line-notes-a"); val b = Box("line-notes-b")
        LineNotes.register("lineNotesTestProperty") { _, _, line -> LineNotes.Note("any box, line $line") }
        LineNotes.register("lineNotesTestProperty", { it === b }) { _, _, _ -> LineNotes.Note("only b") }
        assertEquals("any box, line 3", LineNotes.note(a, "lineNotesTestProperty", "", 3)?.caption)
        assertEquals("only b", LineNotes.note(b, "lineNotesTestProperty", "", 3)?.caption)
        assertNull(LineNotes.note(a, "noProviderForThisProperty", "", 1))
    }

    @Test
    fun aStackSLLineShowsTheStageItEnds() {
        val program = "# a comment\nuv 5 *\nfbm >cloud\n<cloud palette\n"
        var text = program
        val study = StackSLStudy(folder(), { text }, { expected, next -> if (text == expected) { text = next; true } else false })
        val stage = assertNotNull(study.lineNote(program, 3))
        assertTrue("stores in >cloud" in stage.caption, stage.caption)
        val image = ImageIO.read(stage.png!!.inputStream())
        assertEquals(StackSLStudy.LINE_NOTE_SIZE, image.width)
        assertTrue("leaves on the stack" in assertNotNull(study.lineNote(program, 4)).caption)
        assertNull(study.lineNote(program, 1), "a comment ends no stage")
    }

    @Test
    fun aRectSLLineShowsTheSceneSoFarWithItsRectanglesOutlined() {
        val scene = "->wall 16 9 meters\n->duration 2\n\"a\" rect -4 0 ->xy 2 ->square 0xff0000 solid ->fill ;\n\"b\" rect\n  4 0 ->xy\n  2 ->square\n;\n"
        var text = scene
        val study = RectSLStudy(folder(), { text }, { expected, next -> if (text == expected) { text = next; true } else false })
        val first = assertNotNull(study.lineNote(scene, 3, 0.0))
        assertTrue("1 rectangle on the wall" in first.caption && "the 1 it makes outlined" in first.caption, first.caption)
        // Inside b's statement: the scene is cut where the statement ends, and holds both.
        val inside = assertNotNull(study.lineNote(scene, 5, 0.0))
        assertTrue("2 rectangles on the wall" in inside.caption, inside.caption)
        val image = ImageIO.read(inside.png!!.inputStream())
        assertEquals(RectSLStudy.LINE_NOTE_LONG, image.width)
        // The outline is drawn in the note's colour around a (line 3), on the left of the wall.
        val outlined = (0 until image.height).any { y -> (0 until image.width / 2).any { x -> image.getRGB(x, y) and 0xffffff == 0x5ce1ff } }
        assertTrue(!outlined, "line 5 makes nothing of a's, so a is not outlined")
        val a = ImageIO.read(first.png!!.inputStream())
        assertTrue((0 until a.height).any { y -> (0 until a.width / 2).any { x -> (a.getRGB(x, y) shr 8 and 0xff) > 0xc0 && (a.getRGB(x, y) and 0xff) > 0xc0 } },
            "line 3's rectangle is outlined in pale blue")
    }
}
