package afield.ai

import afield.ai.core.*
import afield.server.DocumentGuideFiles
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class DocumentGuideToolsTest {
    @Test fun bothRegistriesExposeTheSameRevisionRequiredTools() {
        val root = createTempDirectory("field-guide-tools").toFile()
        try {
            val ctx = ToolExecutionContext(documentGuides = DocumentGuideTools(root) { false })
            val chat = ToolRegistry().also { registerBuiltInAgentTools(it) }
            val backdoor = Ai4BackdoorTools.registry
            fun guideSchemas(registry: ToolRegistry) = registry.describe(ctx).filter {
                it.jsonObject["name"]!!.jsonPrimitive.content.startsWith("DocumentGuide")
            }
            assertEquals(6, guideSchemas(chat).size)
            assertEquals(guideSchemas(chat), guideSchemas(backdoor))
            assertFalse(chat.isAvailable(ToolExecutionContext(), "DocumentGuideReadRequest"))
            val patch = guideSchemas(chat).first { it.jsonObject["name"]!!.jsonPrimitive.content == "DocumentGuidePatchRequest" }
            assertEquals(setOf("path", "revision", "oldText", "newText"), patch.jsonObject["inputSchema"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
            fun call(registry: ToolRegistry, name: String, args: Any) = registry.executeByName(ctx, name, aiToolMapper.writeValueAsString(args)) as DocumentGuideToolResult
            val created = call(chat, "DocumentGuideCreateRequest", DocumentGuideCreateRequest("HANDOFF.md", "# Handoff\n\nNext: explore walls.\n"))
            assertTrue(created.success)
            assertEquals("field://guide/HANDOFF.md", created.link)
            val read = call(backdoor, "DocumentGuideReadRequest", DocumentGuideReadRequest("HANDOFF.md"))
            assertEquals(created.revision, read.revision)
            val patched = call(backdoor, "DocumentGuidePatchRequest", DocumentGuidePatchRequest(read.path, read.revision, "explore walls", "test wall clinging"))
            assertTrue(patched.success)
            val appended = call(chat, "DocumentGuideAppendRequest", DocumentGuideAppendRequest(read.path, patched.revision, "\nVerified in Field.\n"))
            assertTrue(appended.success)
            val reopened = DocumentGuideTools(root) { false }.read(DocumentGuideReadRequest(read.path))
            assertEquals("# Handoff\n\nNext: test wall clinging.\n\nVerified in Field.\n", reopened.contents)
            assertEquals(appended.revision, reopened.revision)
            assertEquals(listOf("HANDOFF.md"), DocumentGuideFiles(root).list().map { it.path })
        } finally { root.deleteRecursively() }
    }

    @Test fun concurrentWritersCannotLoseChangesAndAmbiguousPatchesFail() {
        val root = createTempDirectory("field-guide-conflict").toFile()
        try {
            val a = DocumentGuideTools(root) { false }
            val b = DocumentGuideTools(root) { false }
            val created = a.create(DocumentGuideCreateRequest("docs/Decisions.md", "same same\n"))
            assertTrue(created.success)
            assertTrue(b.create(DocumentGuideCreateRequest(created.path, "clobber")).conflict)
            assertFalse(a.patch(DocumentGuidePatchRequest(created.path, created.revision, "same", "ambiguous")).success)
            val changed = b.append(DocumentGuideAppendRequest(created.path, created.revision, "human edit\n"))
            assertTrue(changed.success)
            assertTrue(a.append(DocumentGuideAppendRequest(created.path, created.revision, "stale")).conflict)
            assertTrue(a.patch(DocumentGuidePatchRequest(created.path, created.revision, "same same", "lost")).conflict)
            assertEquals("same same\nhuman edit\n", root.resolve(created.path).readText())
            val readonly = DocumentGuideTools(root) { true }
            assertTrue(readonly.read(DocumentGuideReadRequest(created.path)).success)
            assertFalse(readonly.create(DocumentGuideCreateRequest("New.md", "no")).success)
            assertFalse(readonly.patch(DocumentGuidePatchRequest(created.path, changed.revision, "human edit", "no")).success)
            assertFalse(readonly.append(DocumentGuideAppendRequest(created.path, changed.revision, "no")).success)
        } finally { root.deleteRecursively() }
    }

    @Test fun toolsRespectCatalogueBoundariesAndKeepInstructionBodiesOutOfContext() {
        val parent = createTempDirectory("field-guide-boundary").toFile()
        try {
            val root = parent.resolve("document").apply { mkdir() }
            val tools = DocumentGuideTools(root) { false }
            parent.resolve("outside.md").writeText("private")
            root.resolve("docs/child").mkdirs()
            root.resolve("docs/child/metadata.edn").writeText("{}")
            Files.createSymbolicLink(root.resolve("escape").toPath(), parent.toPath())
            for (path in listOf("../outside.md", "/outside.md", "AGENTS.md", "uid_notes_.md", "document.md", "docs/child/README.md", "escape/outside.md", "docs/../outside.md")) {
                assertFalse(tools.create(DocumentGuideCreateRequest(path, "no")).success, path)
                assertFalse(tools.read(DocumentGuideReadRequest(path)).success, path)
            }
            assertTrue(tools.create(DocumentGuideCreateRequest("HANDOFF.md", "# Useful handoff\nSECRET_BODY_SENTINEL")).success)
            val context = tools.catalogueContext()
            assertTrue("HANDOFF.md" in context)
            assertFalse("SECRET_BODY_SENTINEL" in context)
            assertEquals("private", parent.resolve("outside.md").readText())
        } finally { parent.deleteRecursively() }
    }

    @Test fun searchAndPagedReadReturnRevisionsAndBoundedEvidence() {
        val root = createTempDirectory("field-guide-search").toFile()
        try {
            val tools = DocumentGuideTools(root) { false }
            val created = tools.create(DocumentGuideCreateRequest("docs/Enemy Notes.md", "# Enemies\n\n## Movement\nWall clinging\nwall bouncing\n"))
            val found = tools.search(DocumentGuideSearchRequest("wall", limit = 1))
            assertEquals(1, found.hits.size)
            assertTrue(found.truncated)
            assertEquals(4, found.hits.single().line)
            assertEquals("field://guide/docs/Enemy%20Notes.md#movement", found.hits.single().link)
            val page = tools.read(DocumentGuideReadRequest(created.path, 4, 1))
            assertEquals("Wall clinging", page.contents)
            assertEquals(created.revision, page.revision)
            assertEquals(6, page.totalLines)
            assertTrue(page.truncated)
            assertFalse(tools.read(DocumentGuideReadRequest(created.path, 99)).success)
            assertFalse(tools.search(DocumentGuideSearchRequest("")).success)
        } finally { root.deleteRecursively() }
    }
}
