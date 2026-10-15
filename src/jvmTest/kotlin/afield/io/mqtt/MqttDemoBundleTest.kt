package afield.io.mqtt

import afield.document.graph.Box
import afield.document.persistence.SavingContext
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `public/docs/assets/mqtt_demo/` parses through Field's own EDN codec with each inline fallback
 * matching its loose `_code_.kt`, and the box-facing layer keys subscriptions so a re-run replaces.
 * That the boxes *run* was checked through the backdoor (see `dev_docs/mqtt.md`).
 */
class MqttDemoBundleTest {
    private val demo = File(System.getProperty("user.dir"), "public/docs/assets/mqtt_demo")
    private val ids = listOf("mqtt-sensors", "mqtt-listen", "mqtt-plot", "mqtt-retained")

    @AfterTest
    fun tearDown() = MqttConnections.closeAll()

    @Test
    fun `the demo bundle parses and its inline copies match`() {
        val byId = SavingContext().readEdnTransfers(demo.absolutePath).associateBy { it.id }
        assertEquals((ids + "root").toSet(), byId.keys)
        ids.forEach { assertTrue("import afield.io.mqtt.*" in byId.getValue(it).properties["code"].toString(), it) }
        val snapshots = SavingContext().readInlineFileSnapshots(demo.absolutePath)
        assertEquals(ids.size, snapshots.size)
        snapshots.forEach { assertEquals(File(demo, it.path).readText(), it.inline, it.path) }
    }

    @Test
    fun `a re-run replaces the callback and series records JSON fields`() {
        val broker = MqttBroker()
        val box = Box("mqtt-test-box")
        val m = box.mqtt(broker.url)
        assertTrue(m.client.awaitConnected())
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        m.subscribe("a/+") { _, msg -> first += msg.text }
        m.subscribe("a/+") { _, msg -> second += msg.text } // the "re-run"
        m.track("env")
        assertEquals(listOf("a/+", "track:env"), m.subscriptions.sorted())
        m.client.publish("settle", "x", QoS.AT_LEAST_ONCE).get()
        m.publish("a/b", 1, qos = 1)
        m.publish("env", mapOf("temp" to 3.5, "deep" to mapOf("x" to 2)), qos = 1)
        m.publish("env", mapOf("temp" to 4.5, "deep" to mapOf("x" to 3)), qos = 1)
        val end = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < end && (second.isEmpty() || m.series("env", "deep.x").size < 2)) Thread.sleep(5)
        assertEquals(emptyList(), first)
        assertEquals(listOf("1"), second)
        assertEquals(listOf(3.5, 4.5), m.series("env", "temp"))
        assertEquals(listOf(2.0, 3.0), m.series("env", "deep.x"))
        assertEquals(4.5, m.number("env", "temp"))
        m.unsubscribe()
        assertEquals(emptyList(), m.subscriptions)
        broker.close()
    }

    @Test
    fun `deleting a box releases its subscriptions`() {
        val broker = MqttBroker()
        val box = Box("mqtt-retire-box")
        val m = box.mqtt(broker.url)
        assertTrue(m.client.awaitConnected())
        val got = java.util.concurrent.CopyOnWriteArrayList<String>()
        m.subscribe("r") { _, msg -> got += msg.text }
        m.track("t")
        assertEquals(2, m.subscriptions.size)
        afield.document.graph.retireBoxes(listOf(box), "test")
        assertEquals(emptyList(), m.subscriptions)
        assertEquals(emptyList(), m.client.filters)
        m.client.publish("r", "after", QoS.AT_LEAST_ONCE).get()
        Thread.sleep(100)
        assertEquals(emptyList(), got.toList())
        broker.close()
    }
}
