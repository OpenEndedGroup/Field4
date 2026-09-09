package afield

import afield.ai.FilestoreDir
import afield.ai.KotlinDefinitionRecord
import afield.ai.KotlinSourceIndex
import afield.ai.KotlinSourceIndexDb
import afield.ai.core.completion.Ai4CompletionDoc
import afield.ai.core.completion.GENERATED_COMPLETION_DOCS_FILE
import afield.ai.core.completion.GeneratedCompletionDocCache
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.pathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Ai4CompletionDoc.dropStaleGeneratedDocs] — the eviction the generated-completion-doc cache never had.
 *
 * The defect these cover: the cache is keyed by declaration identity (prompt version, name, path, line,
 * source hash), so the completion path never *reads* a record whose declaration moved or changed — but
 * nothing removed the record it missed either, so the file only ever grew. `polishCompletionDocs` reads
 * the whole file rather than by key, and a real run on 2026-09-04 was offered two records pointing at
 * `afield/four/synth/GraphQuery.kt` (moved to `afield/graphquery/`) and a `generateSmoothIsoContours`
 * draft describing `pointWeights`/`edgeWeights` parameters the declaration no longer has.
 *
 * Every case here is a divergence rather than a "the sweep runs" assertion: the same fixture, one thing
 * changed about the tree, and the record must survive or not accordingly.
 */
class GeneratedCompletionDocSweepTest {

    /** The baseline. Without this, the three drop cases pass just as happily against a sweep that wipes. */
    @Test
    fun aRecordWhoseDeclarationIsUnchangedSurvives() {
        val fixture = fixture()
        fixture.withIndexOf(fixture.source) {
            val sweep = Ai4CompletionDoc.dropStaleGeneratedDocs(fixture.cacheFile)
            assertNull(sweep.skipped, "the fixture tree could not be seen: ${sweep.describe()}")
            assertEquals(emptyList(), sweep.dropped)
            assertEquals(1, sweep.considered)
            assertEquals(1, fixture.recordsOnDisk())
        }
    }

    /**
     * The `generateSmoothIsoContours` case: the file is where the record says, but the declaration there
     * is not the one the draft was written from, so the draft can describe parameters that are gone.
     */
    @Test
    fun anEditedDeclarationLosesItsDraft() {
        val fixture = fixture()
        fixture.source.writeText(SOURCE.replace("return a + 1", "return a + 2"))
        fixture.withIndexOf(fixture.source) {
            val sweep = Ai4CompletionDoc.dropStaleGeneratedDocs(fixture.cacheFile)
            assertEquals(1, sweep.dropped.size, "the edited declaration kept its draft: ${sweep.describe()}")
            assertTrue(
                "declaration source changed" in sweep.dropped.single(),
                "wrong reason: ${sweep.dropped.single()}"
            )
            assertEquals(0, fixture.recordsOnDisk())
        }
    }

    /**
     * The `GraphQuery.kt` case: the declaration is alive and indexed at its new path, and the record
     * still names the old one. Nothing about the record itself says so — only the tree does.
     */
    @Test
    fun aMovedFileLosesItsDraft() {
        val fixture = fixture()
        val moved = fixture.dir.resolve("moved").createDirectories().resolve(fixture.source.fileName)
        Files.move(fixture.source, moved)
        fixture.withIndexOf(moved) {
            val sweep = Ai4CompletionDoc.dropStaleGeneratedDocs(fixture.cacheFile)
            assertEquals(1, sweep.dropped.size, "the moved declaration kept its draft: ${sweep.describe()}")
            assertTrue(
                "declaration source is gone" in sweep.dropped.single(),
                "wrong reason: ${sweep.dropped.single()}"
            )
            assertEquals(0, fixture.recordsOnDisk())
        }
    }

    /**
     * The reason this is a sweep and not a rebuild: from the wrong working directory, or with no index,
     * every record looks stale at once. Deleting the cache is the worst available reading of that, so the
     * sweep has to recognise it and decline.
     */
    @Test
    fun aTreeThatCannotBeSeenIsLeftAlone() {
        val fixture = fixture()
        val absent = fixture.dir.resolve("not-a-checkout").resolve("Sample.kt")
        fixture.withIndexOf(absent) {
            val sweep = Ai4CompletionDoc.dropStaleGeneratedDocs(fixture.cacheFile)
            assertNotNull(sweep.skipped, "an unseeable tree was swept anyway: ${sweep.describe()}")
            assertEquals(emptyList(), sweep.dropped)
            assertEquals(1, fixture.recordsOnDisk(), "the cache was pruned against a tree it could not see")
        }
    }

    /**
     * `src/personal` is an optional submodule: a worktree without it has no such directory, and every
     * record under it would otherwise read as a deleted declaration. Twelve did, on the run that
     * prompted this. The fixture stands in a source root of its own, because a committed file may name
     * that submodule's root and not reach inside it — see `PersonalBoundaryTest`.
     *
     * The pair with [aMovedFileLosesItsDraft] is the whole distinction: both records name a file that is
     * not on disk, and only the one whose source root the index has never heard of survives.
     */
    @Test
    fun aRecordFromASourceSetThatIsNotCheckedOutIsLeftAlone() {
        val fixture = fixture()
        fixture.cache.put(
            sourceName = "OptionalThing.radius",
            sourcePath = "src/optionalSourceSet/kotlin/OptionalThing.kt",
            sourceLine = 82,
            sourceHash = "0".repeat(64),
            documentation = "drafted in a checkout that had this source set",
            model = "test"
        )
        fixture.withIndexOf(fixture.source) {
            val sweep = Ai4CompletionDoc.dropStaleGeneratedDocs(fixture.cacheFile)
            assertEquals(
                emptyList(), sweep.dropped,
                "an uninitialised source set was read as a set of deleted declarations"
            )
            assertEquals(1, sweep.unverifiable)
            assertEquals(2, fixture.recordsOnDisk())
        }
    }

    /** A dependency's sources come and go with the Gradle cache, which is not news about the declaration. */
    @Test
    fun aDeclarationInAnAbsentJarIsLeftAlone() {
        val fixture = fixture()
        fixture.cache.put(
            sourceName = "Absent.method",
            sourcePath = "jar:file://${fixture.dir.pathString}/no-such-sources.jar!/Absent.kt",
            sourceLine = 12,
            sourceHash = "0".repeat(64),
            documentation = "drafted when the sources jar was still resolved",
            model = "test"
        )
        fixture.withIndexOf(fixture.source) {
            val sweep = Ai4CompletionDoc.dropStaleGeneratedDocs(fixture.cacheFile)
            assertEquals(emptyList(), sweep.dropped, "an unresolvable jar was read as a missing declaration")
            assertEquals(1, sweep.unverifiable)
            assertEquals(2, fixture.recordsOnDisk())
        }
    }

    /**
     * One checkout-shaped fixture: a source file with two declarations, and a cache holding a draft of
     * the first that is current as of construction. Paths are absolute so the fixture does not depend on
     * the test JVM's working directory, which is the repo root.
     */
    private fun fixture(): Fixture {
        val dir = Files.createTempDirectory("completion-doc-sweep-")
        val source = dir.resolve("Sample.kt")
        source.writeText(SOURCE)
        val fixture = Fixture(dir, source)
        // Seeded through the production `put`, so the record's key is spelled by the code under test.
        // A test that computed the key itself would pass against a broken key function.
        val hash = fixture.withIndexOf(source) {
            assertNotNull(
                Ai4CompletionDoc.currentDeclarationSourceHash(source.pathString, DECLARATION_LINE),
                "the fixture's own declaration has no source"
            )
        }
        fixture.cache.put(
            sourceName = "documentMe",
            sourcePath = source.pathString,
            sourceLine = DECLARATION_LINE,
            sourceHash = hash,
            documentation = "Adds one to [a].",
            model = "test"
        )
        assertEquals(1, fixture.recordsOnDisk())
        return fixture
    }

    private class Fixture(val dir: Path, val source: Path) {
        val cacheFile: Path = dir.resolve("filestore").resolve(GENERATED_COMPLETION_DOCS_FILE)

        /** The writer. Its in-memory map is not what the assertions read; see [recordsOnDisk]. */
        val cache = GeneratedCompletionDocCache(cacheFile)

        /**
         * What survived in the file, read through a fresh instance.
         *
         * The sweep runs in the polish CLI's own process against its own instance, so asserting on
         * [cache]'s in-memory map would pass whatever the sweep wrote — or failed to write — to disk.
         */
        fun recordsOnDisk(): Int = GeneratedCompletionDocCache(cacheFile).recordCount()

        /**
         * Run [block] with an index that describes [file] and nothing else.
         *
         * The index is what supplies the snippet's end bound, so it has to be in place for any read of a
         * declaration's source — and it is set under a pinned [FilestoreDir] because
         * [KotlinSourceIndex.latestIndex] stops answering when the directory it was loaded for moves.
         */
        fun <T> withIndexOf(file: Path, block: () -> T): T {
            val was = KotlinSourceIndex.latestIndex
            return try {
                FilestoreDir.withDirectory(dir.resolve("filestore")) {
                    KotlinSourceIndex.latestIndex = KotlinSourceIndexDb(
                        root = dir.pathString,
                        apiKeyHash = null,
                        updatedAtEpochMs = 0L,
                        files = emptyList(),
                        definitions = listOf(
                            definition("documentMe", file, DECLARATION_LINE),
                            definition("theNextOne", file, NEXT_DECLARATION_LINE)
                        )
                    )
                    block()
                }
            } finally {
                KotlinSourceIndex.latestIndex = was
            }
        }

        private fun definition(name: String, file: Path, line: Int) = KotlinDefinitionRecord(
            name = name, kind = "fun", filePath = file.pathString, line = line,
            fileId = null, contentHash = "", kdoc = null
        )
    }

    private companion object {
        const val DECLARATION_LINE = 3
        const val NEXT_DECLARATION_LINE = 7

        /** Two declarations, because a snippet ends where the next indexed one begins. */
        val SOURCE = """
            package fixture

            fun documentMe(a: Int): Int {
                return a + 1
            }

            fun theNextOne() {
            }
        """.trimIndent()
    }
}
