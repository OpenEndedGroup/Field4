package afield.io.dmx

import afield.document.persistence.SavingContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Does the chain_scene_to_light starting point (materialized from
 * `dev_docs/agents/transcripts/chain_scene_to_light.yaml`) still hold the three boxes the page describes,
 * with the scene the page measures? Read through Field's own EDN codec, with a committed bundle known
 * to work as the control. That the boxes compile is `DemoTranscriptExecSmokeTest`'s job; that they
 * *run* was checked through the backdoor, see `dev_docs/CHAIN_SCENE_TO_LIGHT.md`.
 */
class SceneOnARigDemoBundleTest {

    private val root = File(System.getProperty("user.dir"))
    private val demo = File(root, "public/docs/assets/agent_transcripts/chain_scene_to_light")
    private val control = File(root, "public/docs/assets/dmx_lighting_demo")
    private val calls = mapOf(
        "scene_sender" to listOf("RectSLImager(", "sacnOut(", "animate("),
        "rig_receiver" to listOf("sacnIn(", "showReceived("),
        "rig_measure" to listOf("SceneOnARig.roundTrip(", "SceneOnARig.aliasing("),
    )

    @Test
    fun `the demo bundle parses through Field's own EDN codec`() {
        val saving = SavingContext()
        assertTrue(saving.readEdnTransfers(control.absolutePath).size >= 3, "control bundle read back empty")
        val byId = saving.readEdnTransfers(demo.absolutePath).associateBy { it.id }
        assertEquals(calls.keys, byId.keys)
        calls.forEach { (id, wanted) ->
            val parents = byId.getValue(id).properties["parents"]?.toString() ?: ""
            assertTrue("root" in parents, "$id is not under root: $parents")
            val code = byId.getValue(id).properties["code"]?.toString() ?: ""
            wanted.forEach { assertTrue(it in code, "$id should call $it") }
        }
    }

    @Test
    fun `the inline fallback matches the loose source file byte for byte`() {
        val snapshots = SavingContext().readInlineFileSnapshots(demo.absolutePath)
        assertEquals(calls.size, snapshots.size, "expected one #afield/file-inline code property per box")
        snapshots.forEach { snapshot ->
            val loose = File(demo, snapshot.path)
            assertTrue(loose.isFile, "missing loose file ${snapshot.path}")
            assertEquals(loose.readText(), snapshot.inline, "${snapshot.path} differs from its metadata.edn copy")
        }
    }

    @Test
    fun `the scene in the sender box is the scene the page measures`() {
        val sender = File(demo, "scene_sender_code_.kt").readText()
        val scene = sender.substringAfter("RectSLImager(\"\"\"\n").substringBefore("\"\"\")")
        assertEquals(SceneOnARig.SCENE.trim(), scene.trim())
    }
}
