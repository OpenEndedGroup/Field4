package afield.dmx

import afield.SavingContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Will `public/docs/assets/dmx_lighting_demo/` open?
 *
 * Read through [SavingContext.readEdnTransfers], the codec Field's loader uses, with a committed
 * bundle known to work as a control (so "boxes came back" means something), and with each
 * `#afield/file-inline` fallback compared byte for byte against the loose `_code_.kt` Field
 * actually prefers. That the four boxes then *run* is not something a jvmTest can see — box code is
 * compiled by the K2 REPL against its own import set — so that was verified through the backdoor on
 * a fresh copy of the bundle; see `dev_docs/dmx_lighting.md`.
 */
class DmxLightingDemoBundleTest {

    private val root = File(System.getProperty("user.dir"))
    private val demo = File(root, "public/docs/assets/dmx_lighting_demo")
    private val control = File(root, "public/docs/assets/mesh_repair_demo")
    private val ids = listOf("dmx-receiver", "dmx-sender", "dmx-artpoll", "dmx-sacn-matrix")

    @Test
    fun `the demo bundle parses through Field's own EDN codec`() {
        val saving = SavingContext()
        assertTrue(saving.readEdnTransfers(control.absolutePath).size >= 3, "control bundle read back empty")

        val byId = saving.readEdnTransfers(demo.absolutePath).associateBy { it.id }
        assertEquals((ids + "root").toSet(), byId.keys)
        val rootChildren = byId.getValue("root").properties["children"]?.toString() ?: ""
        ids.forEach { id ->
            assertTrue(id in rootChildren, "root does not parent $id: $rootChildren")
            val code = byId.getValue(id).properties["code"]?.toString() ?: ""
            assertTrue("import afield.dmx.*" in code, "$id does not import afield.dmx")
        }
        val calls = mapOf(
            "dmx-receiver" to "artnetIn(", "dmx-sender" to "artnetOut(",
            "dmx-artpoll" to "artPoll(", "dmx-sacn-matrix" to "sacnOut(",
        )
        calls.forEach { (id, call) ->
            assertTrue(call in byId.getValue(id).properties["code"].toString(), "$id should call $call")
        }
    }

    @Test
    fun `the inline fallback matches the loose source file byte for byte`() {
        val snapshots = SavingContext().readInlineFileSnapshots(demo.absolutePath)
        assertEquals(ids.size, snapshots.size, "expected one #afield/file-inline code property per box")
        snapshots.forEach { snapshot ->
            val loose = File(demo, snapshot.path)
            assertTrue(loose.isFile, "missing loose file ${snapshot.path}")
            assertEquals(loose.readText(), snapshot.inline, "${snapshot.path} differs from its metadata.edn copy")
        }
    }
}
