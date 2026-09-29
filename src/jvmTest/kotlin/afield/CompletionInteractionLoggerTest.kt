package afield

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompletionInteractionLoggerTest {
    @Test
    fun `request logging joins unsaved source with code properties and inherited preamble`() {
        val parent = Box("parent").apply {
            name = "context"
            preamble = "import afield.*\nval inherited = 41 /**/"
        }
        val child = Box("child").apply {
            name = "completion target"
            preamble = "val local = inherited + 1"
            setPropertyRaw("code", "old persisted source")
            setPropertyRaw("fragment", "void main() { }")
            setPropertyRaw("notes", "not completion context")
            parents.add(parent)
        }
        val event = CompletionInteractionEvent(
            interactionId = "interaction-1",
            event = "requested",
            uid = child.id,
            property = "code",
            languageId = "kotlin",
            allCode = "val answer = inherited + loc",
            line = 1,
            column = 29,
            position = 28,
            query = "loc"
        )
        val root = Files.createTempDirectory("completion-interactions-")
        val path = CompletionInteractionLogger.recordTo(
            root = root,
            event = event,
            box = child,
            documentName = "logger-test",
            documentPath = "/tmp/logger-test/metadata.edn",
            now = Instant.parse("2026-08-08T12:00:00Z")
        )

        val lines = path.readLines()
        assertEquals(1, lines.size)
        val record = Json.parseToJsonElement(lines.single()).jsonObject
        assertEquals("logger-test", record.getValue("documentName").jsonPrimitive.content)
        assertEquals("completion target", record.getValue("boxName").jsonPrimitive.content)
        val properties = record.getValue("relevantProperties").jsonObject
        assertEquals(event.allCode, properties.getValue("code").jsonPrimitive.content)
        assertEquals("void main() { }", properties.getValue("fragment").jsonPrimitive.content)
        assertTrue("notes" !in properties, "prose-only notes should not be predictive completion context")
        val inherited = record.getValue("inheritedPreamble").jsonPrimitive.content
        assertTrue("val local = inherited + 1" in inherited)
        // since 2026-09-29 an inherited line carries its supplier's uid before the inherit marker
        assertTrue("val inherited = 41 /*from:parent*/ /**/" in inherited, inherited)
        val interaction = record.getValue("interaction").jsonObject
        assertEquals("interaction-1", interaction.getValue("interactionId").jsonPrimitive.content)
        assertEquals(28, interaction.getValue("position").jsonPrimitive.content.toInt())
    }
}
