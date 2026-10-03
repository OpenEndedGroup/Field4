package afield.ai

import afield.*
import afield.ai.core.AgentExtensionRegistry
import afield.ai.core.AgentToolExtensions
import afield.ai.core.DocumentToolRegistry
import afield.ai.core.ToolEffect
import afield.ai.core.wireToolName
import afield.ai.core.aiTool
import afield.ai.core.InstructionMode
import afield.ai.core.KOTLIN_SCRIPT
import afield.ai.core.ToolExecutionContext
import afield.ai.core.ToolFocus
import afield.ai.core.ToolRegistry
import afield.ai.core.agentExtension
import afield.ai.core.buildInstructionFragments
import java.nio.file.Files
import kotlin.test.*

data class ExtProbeRequest(val text: String = "")
data class ExtScopedRequest(val unused: Int = 0)

/**
 * `agentExtension { }`: a box's before/after-tool hooks, context and commands reach the agent of its own
 * document; a refusal is the result the model reads; a hook that throws or overruns is ignored; and every
 * part is owned by the run that registered it — a re-run replaces it, a re-run without it drops it,
 * retiring the box releases it. AgentToolExtensions registrations made from a running box are owned the same way.
 */
class AgentExtensionsTest {

    private fun <T> inDocument(block: (SessionInformation, Box, ToolExecutionContext) -> T): T {
        val directory = Files.createTempDirectory("agent-extensions").toFile()
        val root = Box("root")
        val session = SessionInformation(directory.path, documentName = "ext-test", root = root, ephemeral = true)
        root.inside = session
        thisSessionInfo.set(session)
        try {
            val box = Box("studio").also { it.name = "Studio"; it.inside = session; session.boxes[it.id] = it }
            val ctx = ToolExecutionContext(focus = ToolFocus(session.filename, box, "code"))
            return block(session, box, ctx)
        } finally {
            thisSessionInfo.remove()
            directory.deleteRecursively()
        }
    }

    private val registry = ToolRegistry().also { it.register(ExtProbeRequest::class.java) { _, r -> mapOf("echo" to r.text) } }

    @Test
    fun aBeforeHookCanRefuseAndAnAfterHookSeesTheResult() = inDocument { _, box, ctx ->
        val seen = mutableListOf<String>()
        box.agentExtension("guard") {
            beforeTool({ it == "ExtProbeRequest" }) { if (argumentsJson.contains("forbidden")) refuse("Not in this document.") else proceed() }
            afterTool { call, result -> seen += "${call.name} ok=${result.ok} origin=${call.origin}" }
        }
        assertEquals(mapOf("echo" to "fine"), registry.executeByName(ctx, "ExtProbeRequest", """{"text":"fine"}"""))
        val refused = registry.executeByName(ctx, "ExtProbeRequest", """{"text":"forbidden"}""") as Map<*, *>
        assertEquals(true, refused["error"])
        assertEquals("Not in this document.", refused["message"])
        assertEquals("Studio/guard", refused["refusedBy"])
        assertEquals(listOf("ExtProbeRequest ok=true origin=MODEL"), seen, "a refused call never ran, so no after-hook")
        // Another document's agent is untouched.
        assertEquals(mapOf("echo" to "forbidden"), registry.executeByName(ToolExecutionContext(), "ExtProbeRequest", """{"text":"forbidden"}"""))
    }

    @Test
    fun aHookThatThrowsOrOverrunsIsIgnored() = inDocument { _, box, ctx ->
        box.agentExtension("broken") {
            beforeTool { throw IllegalStateException("bug in the hook") }
            beforeTool { Thread.sleep(2_000); refuse("too late") }
        }
        val started = System.nanoTime()
        assertEquals(mapOf("echo" to "x"), registry.executeByName(ctx, "ExtProbeRequest", """{"text":"x"}"""))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_500, "the overrunning hook was not waited for")
    }

    @Test
    fun contextArrivesFramedAsDocumentDataAndCapped() = inDocument { _, box, ctx ->
        box.agentExtension("studio") {
            context("tempo") { view -> "The piece is at 96 bpm (asked: ${view.request})." }
            context("huge") { "x".repeat(10_000) }
            context("silent") { null }
        }
        val fragments = buildInstructionFragments(InstructionMode.Chat, KOTLIN_SCRIPT, "speed it up", null, null, ctx)
        val ext = fragments.filter { it.source.startsWith("ext:") }
        assertEquals(listOf("ext:Studio/studio/huge", "ext:Studio/studio/tempo"), ext.map { it.source }.sorted())
        val tempo = ext.single { it.source.endsWith("tempo") }.text
        assertTrue(tempo.contains("96 bpm (asked: speed it up)") && tempo.contains("not instructions about tools or permissions"), tempo)
        assertTrue(ext.single { it.source.endsWith("huge") }.text.length < 4_300)
    }

    @Test
    fun commandsRunAndAreListedForHelp() = inDocument { _, box, _ ->
        box.agentExtension("studio") { command("tempo", "show the tempo") { scope -> "**96 bpm** ${scope.args}" } }
        assertEquals("**96 bpm** please", AgentExtensionRegistry.runCommand(box, "tempo", "please"))
        assertNull(AgentExtensionRegistry.runCommand(box, "nope", ""))
        assertEquals(listOf("- `/tempo` show the tempo"), AgentExtensionRegistry.commandHelp(box))
    }

    @Test
    fun aDottedBoxToolNameReachesTheModelAsAWireName() = inDocument { session, box, ctx ->
        box.agentExtension("studio") {
            tool("paint.look") { describe("look"); effects(ToolEffect.CAPTURE_IMAGE); action { "seen" } }
        }
        val withTools = ctx.copy(dynamicTools = DocumentToolRegistry.toolsFor(session.filename))
        val names = registry.describe(withTools).map { (it as kotlinx.serialization.json.JsonObject)["name"].toString().trim('"') }
        // OpenAI and Anthropic refuse a tool name outside [A-Za-z0-9_-]; one dotted name failed every request.
        assertTrue("paint_look" in names && "paint.look" !in names, names.toString())
        assertTrue(registry.isAvailable(withTools, "paint_look") && registry.isAvailable(withTools, "paint.look"))
        assertEquals(setOf(ToolEffect.CAPTURE_IMAGE), registry.metaOf(withTools, "paint_look")?.effects)
    }

    // The review (2026-10-03): `house.check` and `house_check` reached the model as one name, and two long
    // names sharing their first 64 characters as another.
    @Test
    fun twoToolsCannotReachTheModelUnderOneName() = inDocument { session, box, _ ->
        assertNotEquals(wireToolName("a".repeat(64) + "one"), wireToolName("a".repeat(64) + "two"))
        assertTrue(wireToolName("a".repeat(80)).length <= 64)
        box.aiTool("house.check") { action { "a" } }
        val other = Box("other").also { it.name = "Other"; it.inside = session; session.boxes[it.id] = it }
        val refused = assertFailsWith<IllegalArgumentException> { other.aiTool("house_check") { action { "b" } } }
        assertTrue(refused.message!!.contains("house.check") && refused.message!!.contains("Studio"), refused.message)
        // The same box renaming its own tool replaces it.
        box.aiTool("house_check") { action { "c" } }
        assertEquals(listOf("house_check"), DocumentToolRegistry.toolsFor(session.filename).map { it.name })
        assertFailsWith<IllegalArgumentException> { box.aiTool("RunBoxRequest") { action { "d" } } }
        Unit
    }

    @Test
    fun branchStateIsSnapshottedByContentAndRestored() = inDocument { _, box, _ ->
        var canvas = "blank"
        val restored = mutableListOf<String?>()
        box.agentExtension("studio") {
            branchState("canvas", save = { it.writeText(canvas) }, restore = { f -> restored += f?.readText(); canvas = f?.readText() ?: "fresh" })
        }
        val first = AgentExtensionRegistry.snapshotBranchStates(box)
        assertEquals(listOf("branch:${box.id}:studio:canvas"), first.keys.toList())
        canvas = "a sky"
        val second = AgentExtensionRegistry.snapshotBranchStates(box)
        assertNotEquals(first, second)
        canvas = "blank"
        assertEquals(first, AgentExtensionRegistry.snapshotBranchStates(box), "the same state is the same snapshot")

        // The conversation last left the canvas blank (first), and it still is: no conflict.
        assertEquals(listOf("Studio/studio/canvas"), AgentExtensionRegistry.restoreBranchStates(box, second, expected = first).restored)
        assertEquals("a sky", canvas)
        AgentExtensionRegistry.restoreBranchStates(box, emptyMap(), expected = second)
        assertEquals("fresh", canvas, "a conversation with no snapshot restores null")
        assertEquals(listOf("a sky", null), restored)
    }

    // The review (2026-10-03): a restore replaced whatever was there, so a design a person had changed with the
    // mouse since the conversation last saw it would have been rolled back with the chat.
    @Test
    fun aStateChangedOutsideTheConversationIsNotRestoredOverByDefault() = inDocument { _, box, _ ->
        var design = "plan v1"
        box.agentExtension("house") { branchState("plan", save = { it.writeText(design) }, restore = { f -> design = f?.readText() ?: "empty" }) }
        val v1 = AgentExtensionRegistry.snapshotBranchStates(box)
        design = "plan v2"
        val v2 = AgentExtensionRegistry.snapshotBranchStates(box)
        design = "plan v2, a window dragged by hand"
        val kept = AgentExtensionRegistry.restoreBranchStates(box, v1, expected = v2)
        assertEquals("plan v2, a window dragged by hand", design, "KEEP leaves the person's change")
        assertTrue(kept.restored.isEmpty())
        val note = kept.notes.single()
        assertTrue(note.contains("left as it is"), note)
        val saved = kept.effective.values.single()
        assertTrue(note.contains(saved))
        assertEquals("plan v2, a window dragged by hand", afield.ai.core.AiStateBlobs.get(saved, box)!!.readText(), "and it is saved")

        var canvas = "painted by hand"
        box.agentExtension("studio") {
            branchState("canvas", save = { it.writeText(canvas) }, restore = { f -> canvas = f?.readText() ?: "fresh" },
                onConflict = afield.ai.core.BranchConflict.RESTORE)
        }
        val key = AgentExtensionRegistry.snapshotBranchStates(box).keys.single { it.endsWith(":studio:canvas") }
        canvas = "the agent's sky"
        val agents = AgentExtensionRegistry.snapshotBranchStates(box)[key]!!
        canvas = "painted by hand"
        val restoredAnyway = AgentExtensionRegistry.restoreBranchStates(box, mapOf(key to agents), expected = emptyMap())
        assertEquals("the agent's sky", canvas, "RESTORE restores anyway")
        assertTrue(restoredAnyway.notes.any { it.contains("restored anyway") }, restoredAnyway.notes.toString())
    }

    // Re-review (2026-10-03, PiRereviewProbeTest.failedSafetySnapshotStillOverwritesHumanEdit): a save that
    // failed made the safety copy null, which read as "no conflict", and the old snapshot overwrote a person's edit.
    @Test
    fun noRestoreWithoutASafetyCopyOfTheStateNow() = inDocument { _, box, _ ->
        for (policy in afield.ai.core.BranchConflict.entries) {
            var state = "agent v1"
            var failSave = false
            box.agentExtension("review") {
                branchState("design", save = { if (failSave) throw java.io.IOException("storage unavailable"); it.writeText(state) },
                    restore = { state = it?.readText() ?: "empty" }, onConflict = policy)
            }
            val v1 = AgentExtensionRegistry.snapshotBranchStates(box)
            state = "human moved window"
            failSave = true
            val result = AgentExtensionRegistry.restoreBranchStates(box, v1, expected = v1)
            assertEquals("human moved window", state, "$policy left the live state alone")
            assertTrue(result.restored.isEmpty(), "$policy reported no restore")
            assertTrue(result.notes.single().contains("storage unavailable"), result.notes.toString())
            assertTrue(result.effective.isEmpty(), "nothing is claimed about a state that could not be read")
        }
    }

    @Test
    fun aCancelledSaveCancelsTheRestore() = inDocument { _, box, _ ->
        var cancel = false
        box.agentExtension("review") {
            branchState("design", save = { if (cancel) throw java.util.concurrent.CancellationException("turn cancelled"); it.writeText("x") },
                restore = { })
        }
        val v1 = AgentExtensionRegistry.snapshotBranchStates(box)
        cancel = true
        assertFailsWith<java.util.concurrent.CancellationException> { AgentExtensionRegistry.restoreBranchStates(box, v1, expected = v1) }
        Unit
    }

    // Re-review (PiRereviewProbeTest.missingTargetSnapshotIsPassedAsReset): a target hash whose blob was gone
    // reached `restore` as null, which means "start fresh", and the design was reset to empty.
    @Test
    fun aMissingOrDamagedSnapshotIsReportedAndNotRestoredAsEmpty() = inDocument { _, box, _ ->
        var state = "current valid design"
        box.agentExtension("review") { branchState("design", save = { it.writeText(state) }, restore = { state = it?.readText() ?: "empty" }) }
        val current = AgentExtensionRegistry.snapshotBranchStates(box)
        val key = current.keys.single()
        val missing = AgentExtensionRegistry.restoreBranchStates(box, mapOf(key to "0123456789abcdef01234567"), expected = current)
        assertEquals("current valid design", state)
        assertTrue(missing.restored.isEmpty())
        assertTrue(missing.notes.single().contains("is missing"), missing.notes.toString())
        assertEquals(current, missing.effective, "the effective state is the one actually there")

        state = "a later design"
        val later = AgentExtensionRegistry.snapshotBranchStates(box)
        afield.ai.core.AiStateBlobs.fileFor(later.getValue(key), box).writeText("not gzip at all")
        state = "current valid design"
        val damaged = AgentExtensionRegistry.restoreBranchStates(box, later, expected = current)
        assertEquals("current valid design", state)
        assertTrue(damaged.notes.single().contains("could not be read"), damaged.notes.toString())
        // A target that is absent on purpose still means "this point had no state".
        AgentExtensionRegistry.restoreBranchStates(box, emptyMap(), expected = current)
        assertEquals("empty", state)
    }

    // Re-review (PiRereviewProbeTest.reorderedPersistentListStillEligibleForDeletion): every collection was
    // sorted, so reordering a list on an agent-created box left it eligible for deletion.
    @Test
    fun aReorderedListIsAChangeAndValuesDoNotPrintAlike() = inDocument { session, box, _ ->
        val key = "review_ordered_vertices"
        Box.ioPersistentProperties.add(key)
        try {
            box.setPropertyRaw(key, listOf("A", "B", "C"))
            val before = afield.ai.core.AiWorldUndo.fingerprint(box)
            val node = kotlinx.serialization.json.buildJsonObject {
                put("op", kotlinx.serialization.json.buildJsonObject {
                    put("createdBoxes", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(box.id))))
                    put("fpAfter", kotlinx.serialization.json.JsonObject(mapOf(box.id to kotlinx.serialization.json.JsonPrimitive(before))))
                })
            }
            box.setPropertyRaw(key, listOf("A", "C", "B"))
            assertNotEquals(before, afield.ai.core.AiWorldUndo.fingerprint(box))
            val plan = afield.ai.core.AiWorldUndo.plan(listOf(node), session.boxes)
            assertTrue(plan.delete.isEmpty() && plan.keep.single().second == "changed since", plan.toString())
        } finally { Box.ioPersistentProperties.remove(key) }
        val c = afield.ai.core.AiWorldUndo::canonical
        assertNotEquals(c(listOf("a,b")), c(listOf("a", "b")))
        assertNotEquals(c("1"), c(1))
        assertEquals(c(setOf("b", "a")), c(linkedSetOf("a", "b")), "a set has no order")
    }

    @Test
    fun aMandatoryGuardThatFailsRefusesTheCall() = inDocument { _, box, ctx ->
        box.agentExtension("guard") {
            beforeTool({ it == "ExtProbeRequest" }, mandatory = true) { throw IllegalStateException("revision service down") }
        }
        val refused = registry.executeByName(ctx, "ExtProbeRequest", """{"text":"cut"}""") as Map<*, *>
        assertEquals(true, refused["error"])
        assertTrue((refused["message"] as String).contains("revision service down"), refused.toString())
        box.agentExtension("guard") {
            beforeTool({ it == "ExtProbeRequest" }, mandatory = true, timeoutMs = 50) { Thread.sleep(1_000); proceed() }
        }
        val slow = registry.executeByName(ctx, "ExtProbeRequest", """{"text":"cut"}""") as Map<*, *>
        assertTrue((slow["message"] as String).contains("did not answer within 50 ms"), slow.toString())
        box.agentExtension("guard") { beforeTool({ it == "ExtProbeRequest" }, mandatory = true) { proceed() } }
        assertEquals(mapOf("echo" to "cut"), registry.executeByName(ctx, "ExtProbeRequest", """{"text":"cut"}"""))
    }

    // The review (2026-10-03): world undo deleted every box the abandoned turns had created, including one the
    // person had typed into since, though the panel promises anything changed since is left alone.
    @Test
    fun worldUndoDeletesOnlyBoxesAsTheTurnsLeftThem() = inDocument { session, box, _ ->
        fun newBox(name: String) = Box(name).also { it.name = name; it.inside = session; session.boxes[it.id] = it; it.setPropertyRaw("code", "// $name") }
        val untouched = newBox("Untouched")
        val edited = newBox("Edited")
        val wired = newBox("Wired")
        val betweenTurns = newBox("BetweenTurns")
        val mine = newBox("Mine")
        fun node(created: List<Box>, before: Map<String, String>, after: List<Box>) = kotlinx.serialization.json.buildJsonObject {
            put("t", kotlinx.serialization.json.JsonPrimitive("turn"))
            put("op", kotlinx.serialization.json.buildJsonObject {
                put("createdBoxes", kotlinx.serialization.json.JsonArray(created.map { kotlinx.serialization.json.JsonPrimitive(it.id) }))
                put("fpBefore", kotlinx.serialization.json.JsonObject(before.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
                put("fpAfter", kotlinx.serialization.json.JsonObject(afield.ai.core.AiWorldUndo.fingerprints(session.boxes, after.map { it.id })
                    .mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
            })
        }
        val first = node(listOf(untouched, edited, wired, betweenTurns), emptyMap(), listOf(untouched, edited, wired, betweenTurns))
        betweenTurns.setPropertyRaw("code", "// the person's edit, between the turns")
        val second = node(emptyList(), afield.ai.core.AiWorldUndo.fingerprints(session.boxes, listOf(untouched, edited, wired, betweenTurns).map { it.id }),
            listOf(untouched, edited, wired, betweenTurns))
        // After the turns: a person types into one box and wires another to a box of their own.
        edited.setPropertyRaw("code", "// the person's edit, afterwards")
        wired.children.add(mine); mine.parents.add(wired)
        // Reopening a document fills in defaults (`autorun false`); an untouched box must still match.
        untouched.setPropertyRaw("autorun", false)
        val plan = afield.ai.core.AiWorldUndo.plan(listOf(first, second), session.boxes)
        assertEquals(listOf("Untouched"), plan.delete.map { it.name })
        assertEquals(mapOf("Edited" to "changed since", "Wired" to "changed since", "BetweenTurns" to "changed between the turns"),
            plan.keep.associate { (b, why) -> b.name to why })
    }

    @Test
    fun aRerunReplacesAnExtensionAndRetiringTheBoxReleasesIt() = inDocument { session, box, ctx ->
        fun refusal() = (registry.executeByName(ctx, "ExtProbeRequest", "{}") as? Map<*, *>)?.get("message")
        val first = box.runScopes.begin(BoxRunScopes.RUN_STRAND, "ext-run-1")
        first.withCurrent { box.agentExtension("guard") { beforeTool { refuse("v1") } } }
        assertEquals("v1", refusal())
        val second = box.runScopes.begin(BoxRunScopes.RUN_STRAND, "ext-run-2")
        second.withCurrent { box.agentExtension("guard") { beforeTool { refuse("v2") } } }
        box.runScopes.supersede(second)
        assertEquals("v2", refusal(), "the re-run's hook replaced the first, in place")
        assertEquals(1, AgentExtensionRegistry.hooksFor(session.filename).size)
        val third = box.runScopes.begin(BoxRunScopes.RUN_STRAND, "ext-run-3")
        box.runScopes.supersede(third)
        assertNull(refusal(), "a re-run that no longer registers the hook drops it")
        third.withCurrent { box.agentExtension("guard") { beforeTool { refuse("v4") } } }
        box.runScopes.closeAll()
        assertNull(refusal(), "retiring the box releases its extension")
        assertTrue(AgentExtensionRegistry.hooksFor(session.filename).isEmpty())
    }

    @Test
    fun anExtensionToolRegisteredFromARunningBoxIsOwnedByTheRun() = inDocument { _, box, _ ->
        val tools = ToolRegistry().also { AgentToolExtensions.applyTo(it) }
        try {
            val run = box.runScopes.begin(BoxRunScopes.RUN_STRAND, "ext-tool-run")
            run.withCurrent { AgentToolExtensions.register(ExtScopedRequest::class.java) { _, _ -> mapOf("ok" to true) } }
            assertTrue(tools.isAvailable(ToolExecutionContext(), "ExtScopedRequest"))
            box.runScopes.closeAll()
            assertFalse(tools.isAvailable(ToolExecutionContext(), "ExtScopedRequest"), "retiring the box retired the tool")
        } finally {
            AgentToolExtensions.unregister("ExtScopedRequest")
        }
    }
}
