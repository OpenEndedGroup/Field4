package afield.document.persistence

import afield.app.server.recentFns
import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem

import afield.app.settings.PersistOnExit
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
