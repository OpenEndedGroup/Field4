package afield.dmx

import afield.SavingContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Will `public/docs/assets/chain_scene_to_light_demo/` open? Read through Field's own EDN codec, with
 * a committed bundle known to work as the control, and each `#afield/file-inline` fallback compared
 * byte for byte with the loose `_code_.kt` Field prefers. That the three boxes *run* is not visible
 * to a jvmTest (box code compiles through the K2 REPL); that was checked through the backdoor, see
 * `dev_docs/CHAIN_SCENE_TO_LIGHT.md`.
 */
class SceneOnARigDemoBundleTest {

    private val root = File(System.getProperty("user.dir"))
    private val demo = File(root, "public/docs/assets/chain_scene_to_light_demo")
    private val control = File(root, "public/docs/assets/dmx_lighting_demo")
    private val calls = mapOf(
        "scene-sender" to listOf("RectSLImager(", "sacnOut(", "animate("),
        "rig-receiver" to listOf("sacnIn(", "showReceived("),
        "rig-measure" to listOf("SceneOnARig.roundTrip(", "SceneOnARig.aliasing("),
    )

    @Test
    fun `the demo bundle parses through Field's own EDN codec`() {
        val saving = SavingContext()
        assertTrue(saving.readEdnTransfers(control.absolutePath).size >= 3, "control bundle read back empty")
        val byId = saving.readEdnTransfers(demo.absolutePath).associateBy { it.id }
        assertEquals((calls.keys + "root").toSet(), byId.keys)
        val rootChildren = byId.getValue("root").properties["children"]?.toString() ?: ""
        calls.forEach { (id, wanted) ->
            assertTrue(id in rootChildren, "root does not parent $id: $rootChildren")
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
        val sender = File(demo, "scene-sender_code_.kt").readText()
        val scene = sender.substringAfter("RectSLImager(\"\"\"\n").substringBefore("\"\"\")")
        assertEquals(SceneOnARig.SCENE.trim(), scene.trim())
    }
}
