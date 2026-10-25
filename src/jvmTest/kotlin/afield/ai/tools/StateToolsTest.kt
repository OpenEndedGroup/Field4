package afield.ai.tools

import afield.ai.compat.DocumentGuideTools
import afield.ai.compat.ToolExecutionContext
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.*

class StateToolsTest {
    @Test fun registryReadsByNameWithoutInliningBodiesAndRejectsAmbiguity() {
        val box = Box("state-tools")
        val body = "private state contents ".repeat(1000)
        var content = body
        box.stateTree("saved", "Saved designs", { StateTreeSnapshot(listOf(
            StateTreeNode("a", title="Courtyard"), StateTreeNode("b", title="Duplicate"), StateTreeNode("c", title="Duplicate"))) }, { content })
        val states = StateTools({ listOf(box) }, { false })
        val dir = Files.createTempDirectory("state-tools").toFile()
        try {
            val ctx = ToolExecutionContext(documentGuides = DocumentGuideTools(dir, states=states) { false })
            val registry = ToolRegistry().also { registerBuiltInAgentTools(it) }
            fun names(r: ToolRegistry) = r.describe(ctx).map { it.jsonObject["name"]!!.jsonPrimitive.content }.filter { it.startsWith("State") }
            assertEquals(setOf("StateListRequest", "StateReadRequest", "StateShowRequest", "StateForkRequest"), names(registry).toSet())
            assertEquals(names(registry), names(Ai4BackdoorTools.registry))
            val listing = states.list(StateListRequest("Saved designs"))
            assertEquals(3, listing.total); assertEquals("", listing.contents); assertFalse(listing.canFork)
            val encoded = aiToolMapper.writeValueAsString(registry.executeByName(ctx, "StateReadRequest", """{"history":"Saved designs","state":"Courtyard","maxChars":80}"""))
            assertTrue(encoded.contains("private state contents")); assertFalse(encoded.contains(body))
            val first = states.read(StateReadRequest("Saved designs", "Courtyard", maxChars=80))
            assertEquals("a", first.state); assertEquals(80, first.contents.length); assertEquals(80, first.nextChar)
            val next = states.read(StateReadRequest("Saved designs", "a", 80, 80, first.revision))
            assertEquals(body.substring(80,160), next.contents)
            content = "changed"
            assertFalse(states.read(StateReadRequest("Saved designs", "a", 0, 80, first.revision)).success)
            assertFalse(states.read(StateReadRequest("Saved designs", "Duplicate")).success)
            assertFalse(states.read(StateReadRequest("foreign/saved", "a")).success)
            assertFalse(states.fork(StateForkRequest("Saved designs", "a", "New branch")).success)
            assertFalse(states.show(StateShowRequest("Saved designs", "a"), box.id).success)
            val browser = StateTreeBrowser.read(listOf(box), StateTreeRequest(box.id, "${box.id}/saved", "a"))
            assertTrue(browser.reference.contains("StateReadRequest")); assertFalse(browser.reference.contains("changed"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun conversationForkPersistsWithoutMovingHeadAndCanBeContinued() {
        val box = Box("fork-tools")
        val before = "<<you>>\nStarting point\n<<ai>>\nOriginal answer"
        AiBoxConversation.write(box,before)
        val parent = AiSessionTree.recordTurn(box,"",before,AiSessionTree.TurnMeta("general","code"))!!
        val states = StateTools({ listOf(box) }, { false })
        val result = states.fork(StateForkRequest("Conversation", parent, "Courtyard alternative"))
        assertTrue(result.success, result.message)
        assertEquals(parent, AiSessionTree.load(box).head()); assertEquals(before, AiBoxConversation.read(box))
        assertEquals(listOf(parent), states.list(StateListRequest("Conversation")).nodes.first { it.id == result.state }.parents)
        val copied = Box("reopened"); copied.setPropertyRaw(AiSessionTree.PROPERTY, checkNotNull(box.getPropertyRaw(AiSessionTree.PROPERTY)))
        val reopened = StateTools({ listOf(copied) }, { false })
        val read = reopened.read(StateReadRequest("Conversation", "Courtyard alternative"))
        assertTrue(read.success, read.message); assertTrue(read.contents.contains("Original answer"))
        assertFalse(states.fork(StateForkRequest("Conversation", parent, "Courtyard alternative")).success)
        assertFalse(StateTools({ listOf(box) }, { true }).fork(StateForkRequest("Conversation",parent,"Denied")).success)
        val tipIndex = AiSessionTree.load(box).branchPoints().indexOfFirst { it["id"]?.jsonPrimitive?.content == result.state }
        AiSessionTree.switchTo(box,tipIndex)
        val after = "$before\n<<you>>\nContinue this branch\n<<ai>>\nDone"
        AiBoxConversation.write(box,after)
        val child = AiSessionTree.recordTurn(box,before,after,AiSessionTree.TurnMeta("general","code"))!!
        assertEquals(result.state, AiSessionTree.load(box).nodes()[child]!!["parent"]!!.jsonPrimitive.content)
        assertTrue(AiSessionTree.branches(box).contains("Courtyard alternative"))
    }

    @Test fun sourceForkCopiesCheckedSourceWithoutCallingAdmissionOrMovingHead() {
        val dir = Files.createTempDirectory("state-source-fork").toFile()
        try {
            val source="saved source"; val id="r1-${SourceStudy.hash(source).take(12)}"
            dir.resolve("$id.kt").writeText(source)
            dir.resolve("$id.json").writeText("""{"parent":null,"description":"Original","facts":{"passed":true}}""")
            dir.resolve("HEAD").writeText(id)
            fun study() = SourceStudy<String>(dir,"kt",{ error("no live reads") }, { _,_ -> error("no writes") }, { _,_ -> error("no compilation") })
            val original=study(); val head=original.currentId()
            val child=original.forkSaved(id,"Alternative")
            assertEquals(source,study().savedSource(child))
            assertEquals(id,study().allRevisions().first { it.id==child }.parent)
            assertEquals(head,original.currentId()); assertEquals(id,dir.resolve("HEAD").readText())
            assertFailsWith<IllegalArgumentException> { original.forkSaved(id,"Alternative") }
        } finally { dir.deleteRecursively() }
    }
}
