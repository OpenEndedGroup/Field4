package afield.mqtt

import java.io.BufferedInputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Clients against the embedded broker over real loopback TCP, on an ephemeral port per test.
 * Callbacks are direct (no Runloop in a test JVM).
 */
class MqttBrokerClientTest {

    private val closeables = mutableListOf<AutoCloseable>()
    private fun <T : AutoCloseable> T.owned(): T = also { closeables += it }

    @AfterTest
    fun tearDown() = closeables.asReversed().forEach { runCatching { it.close() } }

    private fun waitFor(timeoutMs: Long = 3000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(5)
        }
        return cond()
    }

    private fun broker() = MqttBroker().owned()
    private fun client(b: MqttBroker, id: String, keepAlive: Int = 30, will: MqttPacket.Will? = null) =
        MqttClient(b.url, clientId = id, keepAliveSeconds = keepAlive, will = will).owned().also { assertTrue(it.awaitConnected(), "$id did not connect") }

    /** Subscribing is asynchronous; a round trip through the same connection proves the SUBACK landed. */
    private fun settle(c: MqttClient) = c.publish("settle/${c.clientId}", "x", QoS.AT_LEAST_ONCE).get(3, TimeUnit.SECONDS)

    @Test
    fun `wildcard subscriptions get exactly the matching messages`() {
        val b = broker()
        val sub = client(b, "sub")
        val pub = client(b, "pub")
        val got = CopyOnWriteArrayList<String>()
        sub.subscribe("sensors/+/temp") { got += "plus:${it.topic}=${it.text}" }
        sub.subscribe("sensors/#") { got += "hash:${it.topic}" }
        settle(sub)
        pub.publish("sensors/kitchen/temp", "21.5")
        pub.publish("sensors/kitchen/humidity", "40")
        pub.publish("other/kitchen/temp", "0")
        assertTrue(waitFor { got.size >= 3 }, "$got")
        Thread.sleep(100)
        assertEquals(setOf("plus:sensors/kitchen/temp=21.5", "hash:sensors/kitchen/temp", "hash:sensors/kitchen/humidity"), got.toSet())
        assertEquals(3, got.size)
        assertEquals(21.5, sub.latest["sensors/kitchen/temp"]?.number)
    }

    @Test
    fun `QoS 1 publish completes on PUBACK and arrives at QoS 1`() {
        val b = broker()
        val sub = client(b, "s1")
        val pub = client(b, "p1")
        val got = CopyOnWriteArrayList<MqttMessage>()
        sub.subscribe("q/#", QoS.AT_LEAST_ONCE) { got += it }
        settle(sub)
        repeat(50) { pub.publish("q/$it", "$it", QoS.AT_LEAST_ONCE).get(3, TimeUnit.SECONDS) }
        assertTrue(waitFor { got.size == 50 }, "got ${got.size}")
        assertTrue(got.all { it.qos == QoS.AT_LEAST_ONCE })
        assertEquals((0 until 50).map { "$it" }, got.map { it.text })
        // QoS 2 publish is completed by the broker (PUBREC/PUBREL/PUBCOMP) and delivered at the granted QoS 1.
        pub.publish("q/two", "2", QoS.EXACTLY_ONCE).get(3, TimeUnit.SECONDS)
        assertTrue(waitFor { got.any { it.topic == "q/two" } })
    }

    @Test
    fun `retained messages reach later subscribers and an empty one clears them`() {
        val b = broker()
        val pub = client(b, "rp")
        pub.publish("state/lamp", "on", QoS.AT_LEAST_ONCE, retain = true).get(3, TimeUnit.SECONDS)
        val late = client(b, "late")
        val got = CopyOnWriteArrayList<MqttMessage>()
        late.subscribe("state/+") { got += it }
        assertTrue(waitFor { got.isNotEmpty() })
        assertEquals("on", got[0].text)
        assertTrue(got[0].retained)
        pub.publish("state/lamp", ByteArray(0), QoS.AT_LEAST_ONCE, retain = true).get(3, TimeUnit.SECONDS)
        assertTrue(waitFor { "state/lamp" !in b.retainedTopics })
        val later = client(b, "later")
        val got2 = CopyOnWriteArrayList<MqttMessage>()
        later.subscribe("state/+") { got2 += it }
        settle(later)
        Thread.sleep(100)
        assertTrue(got2.isEmpty(), "$got2")
    }

    @Test
    fun `the will is published when a client vanishes, not when it closes`() {
        val b = broker()
        val watcher = client(b, "watcher")
        val got = CopyOnWriteArrayList<String>()
        watcher.subscribe("status/#") { got += "${it.topic}=${it.text}" }
        settle(watcher)

        val polite = client(b, "polite", will = MqttPacket.Will("status/polite", "gone".encodeToByteArray()))
        polite.close()

        // A raw socket that CONNECTs with a will and then just drops.
        Socket("127.0.0.1", b.port).use { s ->
            s.getOutputStream().write(Mqtt.encode(MqttPacket.Connect("rude", will = MqttPacket.Will("status/rude", "gone".encodeToByteArray()))))
            assertTrue(Mqtt.read(BufferedInputStream(s.getInputStream())) is MqttPacket.ConnAck)
        }
        assertTrue(waitFor { got.isNotEmpty() })
        Thread.sleep(150)
        assertEquals(listOf("status/rude=gone"), got.toList())
    }

    @Test
    fun `a missed keepalive drops the client and fires its will`() {
        val b = broker()
        val watcher = client(b, "kw")
        val got = CopyOnWriteArrayList<String>()
        watcher.subscribe("dead") { got += it.text }
        settle(watcher)
        val s = Socket("127.0.0.1", b.port).also { closeables += it }
        s.getOutputStream().write(Mqtt.encode(MqttPacket.Connect("silent", keepAliveSeconds = 1, will = MqttPacket.Will("dead", "silent".encodeToByteArray()))))
        assertTrue(Mqtt.read(BufferedInputStream(s.getInputStream())) is MqttPacket.ConnAck)
        // 1.5 × 1 s of silence.
        assertTrue(waitFor(4000) { got.isNotEmpty() }, "will never came")
        assertEquals("silent", got[0])
    }

    @Test
    fun `the client reconnects to a restarted broker and resubscribes`() {
        val b1 = MqttBroker()
        val port = b1.port
        val c = MqttClient(b1.url, clientId = "re").owned()
        assertTrue(c.awaitConnected())
        val got = CopyOnWriteArrayList<String>()
        c.subscribe("x/#") { got += it.text }
        settle(c)
        b1.close()
        assertTrue(waitFor { !c.isConnected })
        val b2 = MqttBroker(port = port).owned()
        assertTrue(waitFor(10_000) { c.isConnected }, "no reconnect: ${c.lastError}")
        assertTrue(c.connections.get() >= 2)
        val pub = client(b2, "p")
        // The resubscribe is in flight right after the CONNACK; give it the same round trip.
        settle(c)
        pub.publish("x/y", "after", QoS.AT_LEAST_ONCE).get(3, TimeUnit.SECONDS)
        assertTrue(waitFor { "after" in got }, "$got")
    }

    @Test
    fun `QoS 1 published while the broker is down is delivered after reconnect`() {
        val b1 = MqttBroker()
        val port = b1.port
        val c = MqttClient(b1.url, clientId = "queue").owned()
        assertTrue(c.awaitConnected())
        b1.close()
        assertTrue(waitFor { !c.isConnected })
        val f = c.publish("later/q1", "kept", QoS.AT_LEAST_ONCE, retain = true)
        val f0 = c.publish("later/q0", "lost")
        assertTrue(f0.isCompletedExceptionally)
        val b2 = MqttBroker(port = port).owned()
        f.get(10, TimeUnit.SECONDS)
        assertTrue("later/q1" in b2.retainedTopics)
    }

    @Test
    fun `client id takeover closes the first connection`() {
        val b = broker()
        val first = MqttClient(b.url, clientId = "same", autoReconnect = false).owned()
        assertTrue(first.awaitConnected())
        client(b, "same")
        assertTrue(waitFor { !first.isConnected })
        assertEquals(setOf("same"), b.clients)
    }

    @Test
    fun `unsubscribe stops delivery and a bad protocol level is refused`() {
        val b = broker()
        val c = client(b, "u")
        val got = CopyOnWriteArrayList<String>()
        val s = c.subscribe("t") { got += it.text }
        settle(c)
        c.publish("t", "1", QoS.AT_LEAST_ONCE).get(3, TimeUnit.SECONDS)
        assertTrue(waitFor { got.size == 1 })
        s.close()
        c.publish("t", "2", QoS.AT_LEAST_ONCE).get(3, TimeUnit.SECONDS)
        settle(c)
        Thread.sleep(100)
        assertEquals(listOf("1"), got.toList())
        assertFalse(s.count.get() > 1)

        Socket("127.0.0.1", b.port).use { sock ->
            sock.getOutputStream().write(Mqtt.encode(MqttPacket.Connect("v5", protocolLevel = 5)))
            assertEquals(MqttPacket.ConnAck(false, 1), Mqtt.read(BufferedInputStream(sock.getInputStream())))
        }
    }

    @Test
    fun `JSON payload helpers`() {
        val m = MqttMessage("t", """{"temp": 21.5, "env": {"hum": 40}, "values": [1, 2, 3], "name": "kitchen"}""".encodeToByteArray())
        assertEquals(21.5, m.number("temp"))
        assertEquals(40.0, m.number("env.hum"))
        assertEquals(3.0, m.number("values.2"))
        assertEquals("kitchen", m.string("name"))
        assertEquals(null, m.number)
        assertEquals(7.0, MqttMessage("t", " 7 ".encodeToByteArray()).number)
        assertEquals("""{"a":1,"b":[true,"x"]}""", BoxMqtt.encode(mapOf("a" to 1, "b" to listOf(true, "x"))).decodeToString())
    }

    @Test
    fun `a retained publish made before connecting reaches a subscribe made after it as retained`() {
        val b = broker()
        val c = MqttClient(b.url, clientId = "early").owned() // not awaited: still connecting
        val f = c.publish("early/scene", "dusk", QoS.AT_LEAST_ONCE, retain = true)
        val got = CopyOnWriteArrayList<MqttMessage>()
        c.subscribe("early/#") { got += it }
        f.get(5, TimeUnit.SECONDS)
        assertTrue(waitFor { got.isNotEmpty() })
        assertTrue(got[0].retained, "delivered live, not as the retained copy")
    }
}
