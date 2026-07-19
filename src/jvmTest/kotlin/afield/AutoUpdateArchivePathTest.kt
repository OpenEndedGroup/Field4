package afield

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AutoUpdateArchivePathTest {
    @Test
    fun acceptsNestedEntryInsideStaging() {
        val root = Files.createTempDirectory("afield-update-path").toFile()
        val target = safeUpdateArchiveTarget(root, "field4/lib/example.jar")

        assertEquals(root.resolve("field4/lib/example.jar").canonicalFile, target)
    }

    @Test
    fun rejectsParentTraversal() {
        val root = Files.createTempDirectory("afield-update-path").toFile()

        assertFailsWith<IllegalArgumentException> {
            safeUpdateArchiveTarget(root, "../outside.txt")
        }
    }

    @Test
    fun rejectsAbsoluteEntry() {
        val root = Files.createTempDirectory("afield-update-path").toFile()
        val outside = Files.createTempFile("afield-update-outside", ".txt").toFile()

        assertFailsWith<IllegalArgumentException> {
            safeUpdateArchiveTarget(root, outside.absolutePath)
        }
    }
}
