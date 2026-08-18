package afield.docs

import afield.SavingContext
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Regression coverage for the inline-versus-loose persistence boundary used by agent demos. */
class AgentDemoPersistenceTest {
    @Test
    fun refreshUsesTheLooseFileAndFieldsOwnEdnCodec() {
        val demo = Files.createTempDirectory("agent-demo-drift").toFile()
        File(demo, "box_code_.kt").writeText("println(\"current\")\n")
        File(demo, SavingContext.METADATA_EDN).writeText(
            """{"version" 2 "boxes" [{"id" "box" "properties" {"code" #afield/file-inline {"path" "box_code_.kt" "inline" "println(\"stale\")\n"}}}]}"""
        )

        val saving = SavingContext()
        assertEquals("println(\"stale\")\n", saving.readInlineFileSnapshots(demo.path).single().inline)
        assertEquals(listOf("box_code_.kt"), saving.refreshInlineFilesFromDisk(demo.path))
        assertEquals("println(\"current\")\n", saving.readInlineFileSnapshots(demo.path).single().inline)
        assertFalse(File(demo, "backups").exists(), "regeneration should not leave workspace backups")
    }

    @Test
    fun refreshRefusesAnInlinePathOutsideTheDocument() {
        val parent = Files.createTempDirectory("agent-demo-escape").toFile()
        val demo = File(parent, "demo").also { it.mkdirs() }
        File(parent, "outside.kt").writeText("secret\n")
        File(demo, SavingContext.METADATA_EDN).writeText(
            """{"version" 2 "boxes" [{"id" "box" "properties" {"code" #afield/file-inline {"path" "../outside.kt" "inline" "stale\n"}}}]}"""
        )

        val error = assertFailsWith<IllegalArgumentException> {
            SavingContext().refreshInlineFilesFromDisk(demo.path)
        }
        assertEquals("inline file escapes document directory: ../outside.kt", error.message)
    }
}
