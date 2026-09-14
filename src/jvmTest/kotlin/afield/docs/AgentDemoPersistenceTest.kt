package afield.docs

import afield.SavingContext
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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

    /**
     * A hand-authored `\uXXXX` decodes to the same string as the character itself, so the loose-file
     * comparison cannot see it — and a document spelled that way came back modified from every
     * regenerate, dirtying an unrelated file in any branch that added a demo. What separates the two
     * is the encoded text, which is why [SavingContext.canonicalInlineMetadata] exists.
     */
    @Test
    fun canonicalMetadataExposesSpellingThatTheLooseComparisonCannotSee() {
        val demo = Files.createTempDirectory("agent-demo-escapes").toFile()
        val guillemets = "«tag»"
        File(demo, "box_code_.kt").writeText("lprintln(\"$guillemets\")\n")
        // Spelled the way a hand-authored document spells it: a backslash and u00ab, six characters
        // the EDN parser resolves to « and the printer never writes back.
        val escaped =
            """{"version" 2 "boxes" [{"id" "box" "properties" {"code" #afield/file-inline {"path" "box_code_.kt" "inline" "lprintln(\"«tag»\")\n"}}}]}"""
                .replace("«", "\\u00ab")
                .replace("»", "\\u00bb")
        File(demo, SavingContext.METADATA_EDN).writeText(escaped)

        val saving = SavingContext()
        // The inline fallback already agrees with the loose file: nothing is stale here.
        assertEquals("lprintln(\"$guillemets\")\n", saving.readInlineFileSnapshots(demo.path).single().inline)

        val canonical = saving.canonicalInlineMetadata(demo.path)
        assertNotEquals(escaped, canonical.metadataEdn, "the escaped spelling is not what the codec writes")
        assertTrue(guillemets in canonical.metadataEdn, "the codec writes the character, not an escape")

        // And it settles in one pass: regenerating what the codec wrote changes nothing.
        saving.refreshInlineFilesFromDisk(demo.path)
        assertEquals(canonical.metadataEdn, File(demo, SavingContext.METADATA_EDN).readText())
        assertEquals(canonical.metadataEdn, saving.canonicalInlineMetadata(demo.path).metadataEdn)
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
