package afield.ai.tools

import afield.runtime.completion.KotlinSourceFileRecord
import afield.runtime.completion.KotlinSourceIndexDb
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals

class UnindexedSymbolFilesTest {
    /** 2026-10-28: `Shape2.drawn` was written after the index was built, and the search could not say so. */
    @Test
    fun filesNewerThanTheirRecordAndMentioningTheNameAreReported() {
        val root = Files.createTempDirectory("unindexed-symbols")
        try {
            val dir = Files.createDirectories(root.resolve("src/jvmMain/kotlin"))
            val old = dir.resolve("Old.kt").also { Files.writeString(it, "fun drawn() = 1") }
            Files.setLastModifiedTime(old, FileTime.fromMillis(1_000))
            dir.resolve("New.kt").also { Files.writeString(it, "fun Shape2.drawn() = 2") }
            dir.resolve("Other.kt").also { Files.writeString(it, "fun unrelated() = 3") }
            // Never indexed and older than the build: a tree the index does not cover, not news.
            dir.resolve("Snapshot.kt").also { Files.writeString(it, "fun drawn() = 4"); Files.setLastModifiedTime(it, FileTime.fromMillis(500)) }
            val index = KotlinSourceIndexDb(root = root.toString(), apiKeyHash = null, updatedAtEpochMs = 1_000,
                files = listOf(KotlinSourceFileRecord("src/jvmMain/kotlin/Old.kt", "h", null, 15, 1_000)), definitions = emptyList())
            assertEquals(listOf("src/jvmMain/kotlin/New.kt"), unindexedFilesMentioning(index, "drawn"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
