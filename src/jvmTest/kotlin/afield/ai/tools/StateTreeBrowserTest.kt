package afield.ai.tools

import afield.ai.agent.*
import afield.ai.conversation.*
import afield.ai.tools.*
import afield.animation.gesture.*
import afield.animation.interpolation.*
import afield.animation.keyframes.*
import afield.app.diagnostics.*
import afield.app.editor.*
import afield.app.notifications.*
import afield.app.protocol.*
import afield.app.server.*
import afield.app.settings.*
import afield.audio.synth.*
import afield.data.table.*
import afield.document.context.*
import afield.document.graph.*
import afield.document.history.*
import afield.document.persistence.*
import afield.document.properties.*
import afield.document.workspace.*
import afield.graphics.color.*
import afield.graphics.paint.*
import afield.graphics.vulkan.*
import afield.math.functions.*
import afield.media.capture.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.completion.*
import afield.runtime.execution.*
import afield.runtime.kotlin.*
import afield.runtime.lifecycle.*
import afield.tools.automation.*
import afield.tools.docs.*
import afield.ui.controls.*
import afield.ui.layout.*
import afield.ui.notation.*
import afield.ui.output.*
import afield.ui.output.templates.*
import afield.ui.slides.*
import afield.ui.visualization.*
import afield.ai.completion.*
import afield.ai.diagnostics.*
import afield.ai.models.*
import afield.runtime.languages.*
import afield.study.source.SourceStudy
import java.nio.file.Files
import kotlin.test.*

class StateTreeBrowserTest {
    private fun say(box: Box, user: String): String {
        val before = AiBoxConversation.read(box)
        val after = "$before\n<<you>>\n$user\n<<ai>>\nSaved."
        AiBoxConversation.write(box, after)
        return AiSessionTree.recordTurn(box, before, after, AiSessionTree.TurnMeta(kind = "general", property = "code"))!!
    }

    @Test fun readingAnAbandonedStateDoesNotMoveHeadOrChangeTranscript() {
        val box = Box("browser")
        val first = say(box, "Initial sketch")
        val abandoned = say(box, "Alternative A")
        AiSessionTree.rewind(box, 1, "Alternative A")
        val current = say(box, "Alternative B")
        val history = AiBoxConversation.read(box)
        val session = box.getPropertyRaw(AiSessionTree.PROPERTY)
        val request = StateTreeRequest(box.id, "${box.id}/conversation")
        val reply = StateTreeBrowser.read(listOf(box), request)
        assertEquals(current, reply.tree!!.current)
        assertEquals(listOf(first), reply.tree.nodes.first { it.id == abandoned }.parents)
        val detail = StateTreeBrowser.read(listOf(box), request.copy(node = abandoned))
        assertTrue(detail.reference.contains("Alternative A"))
        assertTrue(detail.reference.contains(abandoned))
        assertEquals(session, box.getPropertyRaw(AiSessionTree.PROPERTY))
        assertEquals(history, AiBoxConversation.read(box))
    }

    @Test fun branchStateIsDiscoverableBeforeFirstTurnAndSavedSnapshotsRemainDiscoverable() {
        val box = Box("studio-state-browser")
        val hook = AgentExtensionRegistry.BranchStateHook(box, "studio", "canvas",
            { error("Browsing must not save") }, { error("Browsing must not restore") })
        AgentExtensionRegistry.register(box, hook)
        try {
            val fresh = StateTreeBrowser.read(listOf(box, Box("unrelated-helper"), Box("root")), StateTreeRequest(box.id))
            assertTrue(fresh.sources.single().hasState, "A fresh Studio must show States before its first turn")
        } finally {
            LifecycleLedger.entries().filter { it.ownerId == box.id }.forEach { it.close() }
        }
        val plain = Box("plain-state-browser")
        say(plain, "Hello")
        assertFalse(StateTreeBrowser.read(listOf(plain), StateTreeRequest(plain.id)).sources.single().hasState)
        val before = AiBoxConversation.read(box)
        val after = "$before\n<<you>>\nPaint dusk\n<<ai>>\nPainted."
        AiBoxConversation.write(box, after)
        AiSessionTree.recordTurn(box, before, after, AiSessionTree.TurnMeta(kind = "general", property = "code",
            branchAfter = mapOf("branch:${box.id}:studio:canvas" to "saved-canvas")))
        assertTrue(StateTreeBrowser.read(listOf(box), StateTreeRequest(box.id)).sources.single().hasState,
            "Saved canvas history stays discoverable when its extension is not running")
    }

    @Test fun providerFailureAndMissingIdsFailExplicitlyAndTextIsBounded() {
        val box = Box("provider")
        box.stateTree("test", "Test", { StateTreeSnapshot(listOf(StateTreeNode("a", title = "Saved"))) }, { "x".repeat(300000) })
        val request = StateTreeRequest(box.id, "${box.id}/test", "a")
        val reply = StateTreeBrowser.read(listOf(box), request)
        assertTrue(reply.detail.length < 10100)
        assertTrue(reply.detail.contains("Excerpt"))
        assertTrue(StateTreeBrowser.read(listOf(box), request.copy(node = "absent")).error.isNotBlank())
        assertTrue(StateTreeBrowser.read(listOf(box), request.copy(uid = "another-document")).error.isNotBlank())
        box.stateTree("test", "Test", { error("Unavailable history") }, { "" })
        assertEquals("Unavailable history", StateTreeBrowser.read(listOf(box), request).error)
    }

    @Test fun pagesRetainStableIdsAndExplicitMissingParents() {
        val box = Box("pages")
        box.stateTree("test", "Test", { StateTreeSnapshot((0..450).map {
            StateTreeNode("s$it", if (it == 0) emptyList() else listOf("s${it - 1}"), "State $it")
        }, "s450") }, { it })
        val q = StateTreeRequest(box.id, "${box.id}/test")
        val newest = StateTreeBrowser.read(listOf(box), q)
        assertEquals(451, newest.total)
        assertEquals(400, newest.tree!!.nodes.size)
        assertEquals(listOf("s50"), newest.tree.nodes.first().parents)
        val older = StateTreeBrowser.read(listOf(box), q.copy(offset = 400))
        assertEquals("s0", older.tree!!.nodes.first().id)
        assertEquals("s50", older.tree.nodes.last().id)
    }

    @Test fun sourceInspectionNeverInvokesAdmissionAndDetectsTampering() {
        val dir = Files.createTempDirectory("state-browser-source").toFile()
        try {
            val source = "saved source"
            val id = "r1-${SourceStudy.hash(source).take(12)}"
            dir.resolve("$id.kt").writeText(source)
            dir.resolve("$id.json").writeText("""{"parent":null,"description":"Checked source"}""")
            val study = SourceStudy<String>(dir, "kt", { error("must not read live source") }, { _, _ -> error("must not replace") },
                { _, _ -> error("must not compile") })
            val box = Box("recipe")
            box.stateTree("source", "Source", study)
            val reply = StateTreeBrowser.read(listOf(box), StateTreeRequest(box.id, "${box.id}/source", id))
            assertEquals(source, reply.detail)
            dir.resolve("$id.kt").writeText("tampered")
            assertTrue(StateTreeBrowser.read(listOf(box), StateTreeRequest(box.id, "${box.id}/source", id)).error.contains("changed on disk"))
        } finally { dir.deleteRecursively() }
    }
}
