package afield.mqtt

import afield.Box
import afield.dmx.DmxOutput
import afield.dmx.DmxProtocol
import afield.stdlib.OscClient
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MQTT → DMX and MQTT → OSC, end to end over loopback. */
class MqttBridgeTest {
    private val broker = MqttBroker()

    @AfterTest
    fun tearDown() { MqttConnections.closeAll(); broker.close() }

    private fun waitFor(timeoutMs: Long = 3000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    @Test
    fun `a topic drives a DMX channel through a range`() {
        val sink = DatagramSocket(0)
        val out = DmxOutput(DmxProtocol.ARTNET, host = "127.0.0.1", port = sink.localPort)
        try {
            val m = Box("bridge-dmx").mqtt(broker.url)
            m.toDmx("lights/level", out, universe = 1, channel = 3, range = 0.0..10.0)
            m.toDmx("lights/env", out, universe = 1, channel = 4, path = "dim")
            m.client.publish("settle", "x", QoS.AT_LEAST_ONCE).get()
            m.publish("lights/level", 5.0, qos = 1)
            m.publish("lights/env", mapOf("dim" to 1.0), qos = 1)
            assertTrue(waitFor { out.universe(1)[3] == 128 && out.universe(1)[4] == 255 }, "ch3=${out.universe(1)[3]} ch4=${out.universe(1)[4]}")
        } finally { out.close(); sink.close() }
    }

    @Test
    fun `a filter is forwarded as OSC messages`() {
        val sink = DatagramSocket(0).apply { soTimeout = 3000 }
        val osc = OscClient("127.0.0.1", sink.localPort)
        try {
            val m = Box("bridge-osc").mqtt(broker.url)
            m.toOsc("sensors/#", osc)
            m.client.publish("settle", "x", QoS.AT_LEAST_ONCE).get()
            m.publish("sensors/a/temp", 21.5, qos = 1)
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            sink.receive(p)
            val bytes = buf.copyOf(p.length)
            val text = String(bytes, Charsets.ISO_8859_1)
            assertTrue(text.startsWith("/sensors/a/temp\u0000"), text)
            assertTrue(",f" in text, text)
            val f = java.nio.ByteBuffer.wrap(bytes, bytes.size - 4, 4).float
            assertEquals(21.5f, f)
        } finally { osc.disconnect(); sink.close() }
    }
}
