package afield.ui.output

import afield.document.graph.Box
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `fileChooser(...)`: its mask, what a typed path completes to, and what reaches the box. */
class FileChooserTest {

    /** a.png, B.JPG, notes.txt, take2.png, take10.png, .hidden.png, shots/inner.png, empty/ */
    private fun tree(): Path {
        val root = Files.createTempDirectory("file-chooser-test-")
        listOf("a.png", "B.JPG", "notes.txt", "take2.png", "take10.png", ".hidden.png").forEach {
            Files.writeString(root.resolve(it), it)
        }
        Files.createDirectories(root.resolve("shots"))
        Files.writeString(root.resolve("shots/inner.png"), "x")
        Files.createDirectories(root.resolve("empty"))
        return root
    }

    private fun complete(spec: FileChooserSpec, text: String) = completeFilePath(spec, "id", text)

    @Test
    fun masksMatchNamesIgnoringCase() {
        assertTrue(FileMask("*.png").matches("A.PNG"))
        assertFalse(FileMask("*.png").matches("a.png.txt"))
        assertTrue(FileMask("*.{png,jpg}").matches("b.jpg"))
        assertTrue(FileMask("*.wav, *.aif").matches("x.aif"))
        assertTrue(FileMask("*.wav;*.aif").matches("x.wav"))
        assertTrue(FileMask("png").matches("x.png"), "a bare extension")
        assertTrue(FileMask(".csv").matches("data.csv"), "a dotted extension")
        assertTrue(FileMask("").matches("anything"))
        assertTrue(FileMask("*").matches("README"))
        assertTrue(FileMask("*.png, *").matches("README"), "any match-everything glob opens the mask")
    }

    @Test
    fun aBlankFieldListsTheRootDirectoriesFirstThenMatchingFilesInNaturalOrder() {
        val root = tree()
        val reply = complete(FileChooserSpec(root, FileMask("*.png")), "")
        assertEquals(listOf("../", "empty/", "shots/", "a.png", "take2.png", "take10.png"), reply.entries.map { it.insert })
        assertNull(reply.error)
        assertFalse(reply.choosable, "nothing typed is nothing chosen")
        assertTrue(reply.entries.first { it.name == "a.png" }.size > 0)
    }

    @Test
    fun theLastSegmentIsAPrefixAndBetterMatchesComeFirst() {
        val root = tree()
        val spec = FileChooserSpec(root, FileMask(""))
        assertEquals(listOf("take2.png", "take10.png"), complete(spec, "ta").entries.map { it.insert })
        // "b" starts B.JPG (ignoring case) and appears inside nothing else.
        assertEquals("B.JPG", complete(spec, "b").entries.first().insert)
        // Letters in order: t…1…0 only fits take10.
        assertEquals(listOf("take10.png"), complete(spec, "t10").entries.map { it.insert })
    }

    @Test
    fun aTrailingSlashListsThatDirectoryKeepingTheTypedSpelling() {
        val root = tree()
        val spec = FileChooserSpec(root, FileMask("*.png"))
        assertEquals(listOf("shots/../", "shots/inner.png"), complete(spec, "shots/").entries.map { it.insert })
        val absolute = root.resolve("shots").toString() + "/"
        assertEquals(listOf("$absolute../", "${absolute}inner.png"), complete(spec, absolute).entries.map { it.insert })
    }

    @Test
    fun dotFilesNeedADotUnlessShown() {
        val root = tree()
        assertFalse(complete(FileChooserSpec(root), "").entries.any { it.name == ".hidden.png" })
        assertTrue(complete(FileChooserSpec(root), ".h").entries.any { it.name == ".hidden.png" })
        assertTrue(complete(FileChooserSpec(root, showHidden = true), "").entries.any { it.name == ".hidden.png" })
    }

    @Test
    fun aMissingDirectorySaysSo() {
        val reply = complete(FileChooserSpec(tree()), "nowhere/x")
        assertNotNull(reply.error)
        assertTrue(reply.entries.isEmpty())
    }

    @Test
    fun choosableFollowsMaskExistenceAndKind() {
        val root = tree()
        val png = FileChooserSpec(root, FileMask("*.png"))
        assertTrue(complete(png, "a.png").choosable)
        assertFalse(complete(png, "notes.txt").choosable, "outside the mask")
        assertFalse(complete(png, "shots").choosable, "a directory is not a file")
        assertFalse(complete(png, "new.png").choosable, "must exist by default")
        assertTrue(complete(FileChooserSpec(root, FileMask("*.png"), mustExist = false), "new.png").choosable)
        val dirs = FileChooserSpec(root, chooseDirectory = true)
        assertTrue(complete(dirs, "shots").choosable)
        assertEquals(listOf("../", "empty/", "shots/"), complete(dirs, "").entries.map { it.insert }, "files are not listed")
    }

    @Test
    fun displayIsRelativeInsideTheRootAndAbsoluteOutside() {
        val root = tree()
        val spec = FileChooserSpec(root)
        assertEquals("shots/inner.png", spec.display(root.resolve("shots/inner.png")))
        assertEquals(root.resolve("a.png"), spec.resolve("a.png"))
        assertEquals(root.resolve("a.png"), spec.resolve(root.resolve("a.png").toString()))
        assertEquals(Path.of(System.getProperty("user.home")).resolve("x"), spec.resolve("~/x"))
    }

    @Test
    fun naturalOrderComparesDigitRunsAsNumbers() {
        assertEquals(listOf("take1", "take2", "take10", "Take11"), listOf("take10", "Take11", "take2", "take1").sortedWith(::naturalCompare))
    }

    @Test
    fun theBoxHearsOnlyAcceptedFilesOnEveryRun() {
        val root = tree()
        val box = Box("chooser-${System.nanoTime()}").also { it.output.displayProxy = { _, _ -> } }
        val heard = mutableListOf<File>()
        fun run() = box.controlGroup("in") {
            fileChooser("image", directory = root.toString(), mask = "*.png", initialValue = "a.png") { heard += it }
        }
        run()
        assertEquals(listOf(root.resolve("a.png").toFile()), heard, "a valid initial value is delivered on the first run")

        val control = controllerControlsForBox(box.id).single { it.label == "image" }
        assertEquals("file", control.type)
        val commit = getStringCallback(control.id)!!
        commit("notes.txt")
        commit("shots/inner.png")
        assertEquals(root.resolve("shots/inner.png").toFile(), heard.last())
        assertEquals(2, heard.size, "notes.txt is outside the mask and never reached the box")

        // What the page does on a commit: the registry first, then the callback; a rerun restores it.
        updateControllerControlValue(control.id) { it.copy(text = "take2.png") }
        heard.clear()
        run()
        assertEquals(listOf(root.resolve("take2.png").toFile()), heard)
        val completion = completeFilePath(fileChooserSpec(control.id)!!, control.id, "sh")
        assertEquals(listOf("shots/"), completion.entries.map { it.insert })
    }
}
