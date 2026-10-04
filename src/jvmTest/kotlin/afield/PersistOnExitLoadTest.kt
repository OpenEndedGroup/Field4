package afield

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PersistOnExitLoadTest {
    @Test
    fun missingHistoryIsAnEmptyCacheAndExistingValuesStillLoad() {
        val directory = Files.createTempDirectory("field-persist-read-").toFile()
        try {
            assertNull(PersistOnExit.load<String>("recentFns", directory))
            File(directory, "recentFns").writeText("\"one document\"")
            assertEquals("one document", PersistOnExit.load<String>("recentFns", directory))
            File(directory, "count").writeText("17")
            assertEquals(17, PersistOnExit.load<Int>("count", directory))
        } finally { directory.deleteRecursively() }
    }
}
