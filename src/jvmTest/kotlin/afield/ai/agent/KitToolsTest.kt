package afield.ai.agent

import afield.document.graph.Box
import afield.app.server.SessionInformation
import afield.ai.completion.*
import afield.ai.conversation.*
import afield.ai.diagnostics.*
import afield.ai.models.*
import afield.ai.tools.*
import afield.runtime.languages.*
import afield.document.workspace.Kits
import afield.study.source.SourceStudy
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class KitToolsTest {
    private val host = object : FieldGraphHost by LiveFieldGraphHost {
        override fun <T> onOwner(info: SessionInformation, action: () -> T): T = action()
    }
    private val registry = ToolRegistry().also(::registerBuiltInAgentTools)

    private fun call(info: SessionInformation, q: InspectKitRequest) = registry.executeByName(
        ToolExecutionContext(fieldGraph = FieldGraphTools(info, host)), "InspectKitRequest", aiToolMapper.writeValueAsString(q)) as KitToolResult

    @Test fun theToolIsAReadOnlyDeferredKitTool() {
        val meta = registry.coreMetas().getValue("InspectKitRequest")
        assertEquals("kit", meta.namespace)
        assertEquals(ToolExposure.DEFERRED, meta.exposure)
        assertTrue(meta.readOnly)
    }

    @Test fun itSaysWhichKitTheDocumentRunsAndWhatAMigrationWouldRefuse() {
        val doc = Files.createTempDirectory("kit-tool-").toFile()
        val kit = File(doc.parentFile, doc.name + "-kit").apply { mkdirs() }
        fun manifest(revision: String, code: String) = mapOf("schema" to "field.kit.v1", "name" to "rooms", "launchOrder" to listOf("rules"),
            "roles" to mapOf("rules" to mapOf("name" to "Rules", "properties" to mapOf("code" to mapOf("file" to "roles/rules/code.kt",
                "sha256" to SourceStudy.hash(code))))), "assets" to emptyMap<String, String>(), "revision" to revision)
        val mapper = jacksonObjectMapper()
        File(kit, "kit.json").writeText(mapper.writeValueAsString(manifest("r2", "val rules = 2")))
        File(doc, Kits.RECORD).apply { parentFile.mkdirs() }.writeText(mapper.writeValueAsString(manifest("r1", "val rules = 1") + ("directory" to kit.path)))
        val info = SessionInformation(doc.path, "rooms-1", root = Box("root"), ephemeral = true)
        val rules = Box("rules-box").apply { properties["kitRole"] = "rules"; properties["code"] = "val rules = 7" }
        info.boxes[rules.id] = rules

        val result = call(info, InspectKitRequest())
        assertTrue(result.success, result.message)
        assertContains(result.message, "runs kit 'rooms' at revision r1")
        assertContains(result.message, "the kit is at revision r2")
        assertEquals("r2", result.details["kitRevision"])
        val difference = (result.details["differences"] as List<*>).single() as Map<*, *>
        assertEquals("document_edited", difference["state"])
        assertEquals("rules-box", difference["box"])
        assertContains((result.details["migrationRefusals"] as List<*>).single().toString(), "Rules · code was edited in this document")

        val missing = call(info, InspectKitRequest(kit = File(doc, "nowhere").path))
        assertTrue(missing.success)
        assertContains(missing.message, "The kit is not at")
    }
}
