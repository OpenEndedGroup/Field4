package afield.mqtt

import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bugs found running against mosquitto 2.1.2, pinned without needing mosquitto installed. */
class MqttInteropRegressionTest {

    /** Mosquitto sends one copy per matching subscription; each handler must still fire once. */
    @Test
    fun overlappingSubscriptionCopiesDispatchOnce() {
        ServerSocket(0).use { server ->
            val fake = thread(isDaemon = true) {
                server.accept().use { s ->
                    val input = s.getInputStream()
                    assertTrue(Mqtt.read(input) is MqttPacket.Connect)
                    s.getOutputStream().write(Mqtt.encode(MqttPacket.ConnAck(false, 0)))
                    // wait for both subscriptions, then send what mosquitto sends: two copies
                    var subs = 0
                    while (subs < 2) {
                        val p = Mqtt.read(input) ?: return@thread
                        if (p is MqttPacket.Subscribe) { subs += p.filters.size; s.getOutputStream().write(Mqtt.encode(MqttPacket.SubAck(p.packetId, p.filters.map { 0 }))) }
                    }
                    val pub = Mqtt.encode(MqttPacket.Publish("a/b", "v".encodeToByteArray()))
                    s.getOutputStream().write(pub + pub)
                    Thread.sleep(400)
                    // a later identical message (outside the copy window) is a new message
                    s.getOutputStream().write(pub + pub)
                    Thread.sleep(400)
                }
            }
            MqttClient("tcp://127.0.0.1:${server.localPort}", autoReconnect = false).use { c ->
                assertTrue(c.awaitConnected())
                val plus = CopyOnWriteArrayList<MqttMessage>()
                val hash = CopyOnWriteArrayList<MqttMessage>()
                c.subscribe("a/+") { plus += it }
                c.subscribe("a/#") { hash += it }
                fake.join(3000)
                assertEquals(2, plus.size, "a/+ handler: $plus")
                assertEquals(2, hash.size, "a/# handler: $hash")
            }
        }
    }

    /** Nothing reaches the socket before CONNECT, nor between CONNECT and CONNACK; it is queued instead. */
    @Test
    fun nothingIsSentBeforeConnack() {
        ServerSocket(0).use { server ->
            val seen = CopyOnWriteArrayList<MqttPacket>()
            val fake = thread(isDaemon = true) {
                server.accept().use { s ->
                    val input = s.getInputStream()
                    seen += Mqtt.read(input)!!
                    Thread.sleep(300) // the client publishes and subscribes during this window
                    s.soTimeout = 100
                    runCatching { while (true) seen += Mqtt.read(input) ?: break }
                    seen += MqttPacket.PingResp // marker: CONNACK goes out here
                    s.soTimeout = 0
                    s.getOutputStream().write(Mqtt.encode(MqttPacket.ConnAck(false, 0)))
                    s.soTimeout = 500
                    runCatching { while (true) seen += Mqtt.read(input) ?: break }
                }
            }
            MqttClient("tcp://127.0.0.1:${server.localPort}", autoReconnect = false).use { c ->
                Thread.sleep(100)
                c.publish("q", "1", QoS.AT_LEAST_ONCE)
                c.subscribe("t/#") {}
                fake.join(3000)
            }
            assertTrue(seen[0] is MqttPacket.Connect, "$seen")
            assertEquals(MqttPacket.PingResp, seen[1], "sent before CONNACK: $seen")
            assertTrue(seen.drop(2).any { it is MqttPacket.Publish } && seen.drop(2).any { it is MqttPacket.Subscribe }, "$seen")
        }
    }

    /** An MQTT 5 CONNECT gets a v5-shaped CONNACK 0x84, which a v5 client can parse. */
    @Test
    fun mqtt5ConnectGetsV5Refusal() {
        MqttBroker().use { b ->
            Socket("127.0.0.1", b.port).use { s ->
                // CONNECT, "MQTT", level 5, clean start, keepalive 60, properties length 0, client id "v5"
                val body = byteArrayOf(0, 4, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), 5, 2, 0, 60, 0, 0, 2, 'v'.code.toByte(), '5'.code.toByte())
                val out = ByteArrayOutputStream().apply { write(0x10); write(body.size); write(body) }
                s.getOutputStream().write(out.toByteArray())
                assertContentEquals(byteArrayOf(0x20, 3, 0, 0x84.toByte(), 0), s.getInputStream().readAllBytes())
            }
        }
    }
}
