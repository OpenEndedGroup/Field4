package afield

import afield.ai.FilestoreDir
import afield.ai.KotlinDefinitionRecord
import afield.ai.KotlinSourceIndex
import afield.ai.KotlinSourceIndexDb
import afield.ai.OpenAiFileStoreCache
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FilestoreDir] — the one place `~/.afield/filestore` is resolved, and the seam that makes the
 * completion path's derived indexes a property of a checkout rather than of a machine.
 *
 * The defect these cover: `KotlinSourceIndex` wrote a single machine-global
 * `~/.afield/filestore/kotlin_source_index.json`, indexed relative to `user.dir`, so two checkouts
 * indexed different trees into the same file and whichever Field ran last won. Completion reads that
 * index for a candidate's category, and category is a ranking input — so
 * `FieldSuggestPresentationTest`'s ranks moved with it. See `dev_docs/HERMETIC_COMPLETION_INPUTS.md`.
 */
class FilestoreDirTest {

    @Test
    fun theDefaultIsPerCheckoutRatherThanPerMachine() {
        val expected = Path.of(System.getProperty("user.dir")).resolve(".field").resolve("filestore")
        assertEquals(expected, FilestoreDir.path)
        assertNotEquals(
            FilestoreDir.legacyGlobal, FilestoreDir.path,
            "the default is back under ~/.afield, so two checkouts share one index again"
        )
    }

    @Test
    fun anExplicitDirectoryWins() {
        val dir = Files.createTempDirectory("filestore-dir-")
        FilestoreDir.withDirectory(dir) {
            assertEquals(dir, FilestoreDir.path)
            assertEquals(dir.resolve("kotlin_source_index.json"), FilestoreDir.resolve("kotlin_source_index.json"))
            assertTrue("pinned" in FilestoreDir.describe())
        }
    }

    @Test
    fun pinsNestAndRestore() {
        val outer = Files.createTempDirectory("filestore-outer-")
        val inner = Files.createTempDirectory("filestore-inner-")
        val before = FilestoreDir.path
        FilestoreDir.withDirectory(outer) {
            FilestoreDir.withDirectory(inner) { assertEquals(inner, FilestoreDir.path) }
            assertEquals(outer, FilestoreDir.path, "the inner pin un-pinned the outer one")
        }
        assertEquals(before, FilestoreDir.path)
    }

    /**
     * The whole point, end to end: two directories holding two different indexes give two different
     * answers for the same symbol, and each answer comes from the directory that is pinned at the time.
     *
     * Written as a divergence rather than "the pin works", because a test that only ever seeds one
     * state passes just as happily when the pin is ignored and both reads hit the machine's file.
     */
    @Test
    fun twoIndexesGiveTwoAnswersAndThePinDecidesWhich() {
        val a = seed("Math")
        val b = seed("Graphics")

        assertEquals("Math", FilestoreDir.withDirectory(a) { category() })
        assertEquals("Graphics", FilestoreDir.withDirectory(b) { category() })
        // ...and again, to catch an in-memory index that latched onto whichever was read first.
        assertEquals("Math", FilestoreDir.withDirectory(a) { category() })
    }

    /**
     * The other half of the pin, and the one a disk-only test cannot see: a *loaded* index must stop
     * answering when the directory it came from is no longer the one in force.
     *
     * A live Field syncs at startup and holds the result in memory, so without this guard pinning the
     * filestore would move only the disk reads and the in-memory index would keep answering for the
     * tree it was built from — the same machine-global answer, one level up, and invisible.
     */
    @Test
    fun aLoadedIndexStopsAnsweringWhenTheDirectoryMoves() {
        val a = seed("Math")
        val b = seed("Graphics")
        val was = KotlinSourceIndex.latestIndex
        try {
            FilestoreDir.withDirectory(a) {
                KotlinSourceIndex.latestIndex = read(a)
                assertEquals("Math", category(), "the in-memory index is not being consulted at all")
                FilestoreDir.withDirectory(b) {
                    assertEquals(
                        "Graphics", category(),
                        "the in-memory index from $a answered while $b was pinned"
                    )
                }
                assertEquals("Math", category(), "the in-memory index did not come back")
            }
        } finally {
            KotlinSourceIndex.latestIndex = was
        }
    }

    /** An empty directory must answer nothing rather than fall back to the machine's index. */
    @Test
    fun anEmptyFilestoreDoesNotFallBackToTheMachine() {
        val empty = Files.createTempDirectory("filestore-empty-")
        FilestoreDir.withDirectory(empty) {
            assertNull(KotlinSourceIndex.loadIndex())
            assertNull(KotlinSourceIndex.getCompletionCategoryForSymbol(SYMBOL))
        }
    }

    private fun category(): String? = KotlinSourceIndex.getCompletionCategoryForSymbol(SYMBOL)

    private fun read(dir: Path): KotlinSourceIndexDb =
        FilestoreDir.withDirectory(dir) { KotlinSourceIndex.loadIndex()!! }

    private fun seed(categoryName: String): Path {
        val dir = Files.createTempDirectory("filestore-seed-")
        val db = KotlinSourceIndexDb(
            root = ".",
            apiKeyHash = null,
            updatedAtEpochMs = 0L,
            files = emptyList(),
            definitions = listOf(
                KotlinDefinitionRecord(
                    name = SYMBOL, kind = "fun", filePath = "src/jvmMain/kotlin/probe.kt", line = 1,
                    fileId = null, contentHash = "", kdoc = null, completionCategory = categoryName
                )
            ),
        )
        dir.resolve("kotlin_source_index.json")
            .writeText(OpenAiFileStoreCache.defaultMapper().writeValueAsString(db))
        return dir
    }

    private companion object {
        /** Deliberately not a real Field symbol, so a leaked read of the machine's index answers null. */
        const val SYMBOL = "filestoreDirTestProbeSymbol"
    }
}
