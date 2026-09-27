package afield.stdlib

import afield.Box
import afield.stacksl.study.StackSLStudy
import afield.stacksl.study.StackSLStudyViewer
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A study card's keys ([StudyKeys]): every number of a program, saved by name as a keyframe key with
 * its history, applied only to the program shape it came from, and reported to the model.
 */
class StudyKeysTest {
    private val folders = mutableListOf<File>()

    @AfterTest
    fun removeFolders() = folders.forEach { it.deleteRecursively() }

    private fun folder() = Files.createTempDirectory("study-keys").toFile().also { folders += it }

    @Test
    fun aKeyIsSavedWithItsHistoryAndReadBackForItsShape() {
        val directory = folder()
        StudyKeys.save(directory, "dawn", "shape-a", mapOf(0 to 5.0, 1 to 0.2))
        StudyKeys.save(directory, "dawn", "shape-a", mapOf(0 to 6.0, 1 to 0.2), "moved k0")
        StudyKeys.save(directory, "Dusk light!", "shape-b", mapOf(0 to 1.0))
        val keys = StudyKeys.all(directory).associateBy { it.name }
        assertEquals(setOf("dawn", "dusk-light"), keys.keys)
        assertEquals(mapOf(0 to 6.0, 1 to 0.2), keys.getValue("dawn").values, "the latest save wins")
        assertTrue(File(directory, "keys/dawn.json.versions").isDirectory, "each save is in the key's history, as any keyframe key's is")
        assertEquals(listOf(true, false), StudyKeys.config(directory, "shape-a").map { it["applies"] })
        assertEquals("key-1", StudyKeys.nextName(directory))
    }

    @Test
    fun theModelIsToldWhatEachKeyWouldChange() {
        val directory = folder()
        assertNull(StudyKeys.summary(directory, "s", emptyMap()))
        StudyKeys.save(directory, "brighter", "s", mapOf(0 to 5.0, 1 to 0.6))
        StudyKeys.save(directory, "old", "t", mapOf(0 to 1.0))
        assertEquals(listOf("brighter: k1 0.43→0.6", "old: from an earlier version of the program; it does not apply"),
            StudyKeys.summary(directory, "s", mapOf(0 to 5.0, 1 to 0.43)))
    }

    @Test
    fun theCardCapturesAKeyAndItsShapeOutlivesAKeptNumber() {
        var text = "uv 5 * 0.43 +\n"
        val study = StackSLStudy(folder(), { text }, { expected, next -> if (text == expected) { text = next; true } else false })
        study.checkIn("start")
        val viewer = StackSLStudyViewer(study, Box("study_keys_test"))
        assertEquals("captured bright", viewer.handleAction("""{"action":"capture-key","name":"bright","values":{"0":7.0,"1":0.6}}"""))
        assertTrue("\"name\":\"bright\",\"applies\":true" in viewer.html(), "the card offers the key")
        // Keeping a number changes the text but not its shape, so the key still applies.
        val r = study.currentId()
        viewer.handleAction("""{"action":"keep","revision":"$r","sourceHash":"${SourceStudy.hash(text)}","values":{"0":7.0}}""")
        assertTrue(text.startsWith("uv 7 *"), text)
        assertTrue("\"name\":\"bright\",\"applies\":true" in viewer.html())
        assertEquals(listOf("bright: k1 0.43→0.6"), study.inspect().facts["keys"])
    }
}
