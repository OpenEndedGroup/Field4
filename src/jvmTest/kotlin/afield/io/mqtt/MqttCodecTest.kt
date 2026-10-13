package afield.io.mqtt

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wire format against byte sequences worked out by hand from the MQTT 3.1.1 standard (the
 * section is named on each), then round trips and the wildcard matrix of §4.7.
 */
class MqttCodecTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `remaining length uses the 2_2_3 boundary values`() {
        // Table 2.4: 0, 127 | 128, 16383 | 16384, 2097151 | 2097152, 268435455
        assertContentEquals(bytes(0x00), Mqtt.remainingLengthBytes(0))
        assertContentEquals(bytes(0x7f), Mqtt.remainingLengthBytes(127))
        assertContentEquals(bytes(0x80, 0x01), Mqtt.remainingLengthBytes(128))
        assertContentEquals(bytes(0xff, 0x7f), Mqtt.remainingLengthBytes(16_383))
        assertContentEquals(bytes(0x80, 0x80, 0x01), Mqtt.remainingLengthBytes(16_384))
        assertContentEquals(bytes(0xff, 0xff, 0x7f), Mqtt.remainingLengthBytes(2_097_151))
        assertContentEquals(bytes(0x80, 0x80, 0x80, 0x01), Mqtt.remainingLengthBytes(2_097_152))
        assertContentEquals(bytes(0xff, 0xff, 0xff, 0x7f), Mqtt.remainingLengthBytes(268_435_455))
        listOf(0, 1, 127, 128, 300, 16_383, 16_384, 2_097_151, 2_097_152, 268_435_455).forEach {
            assertEquals(it, Mqtt.readRemainingLength(ByteArrayInputStream(Mqtt.remainingLengthBytes(it))))
        }
        assertFailsWith<MqttProtocolException> { Mqtt.readRemainingLength(ByteArrayInputStream(bytes(0xff, 0xff, 0xff, 0xff, 0x01))) }
        assertFailsWith<IllegalArgumentException> { Mqtt.remainingLengthBytes(268_435_456) }
    }

    @Test
    fun `CONNECT matches 3_1 byte for byte`() {
        // Clean session, keepalive 60, client id "abc", will "w" = "x" at QoS 1 retained, user "u", password "p".
        val p = MqttPacket.Connect(
            "abc", cleanSession = true, keepAliveSeconds = 60,
            will = MqttPacket.Will("w", "x".encodeToByteArray(), QoS.AT_LEAST_ONCE, retain = true),
            username = "u", password = "p".encodeToByteArray(),
        )
        val expected = bytes(
            0x10, 27,
            0, 4, 'M'.code, 'Q'.code, 'T'.code, 'T'.code, // protocol name
            4, // level
            0b1110_1110, // user, password, will retain, will QoS 1, will, clean session
            0, 60, // keepalive
            0, 3, 'a'.code, 'b'.code, 'c'.code,
            0, 1, 'w'.code, 0, 1, 'x'.code,
            0, 1, 'u'.code, 0, 1, 'p'.code,
        )
        assertContentEquals(expected, Mqtt.encode(p))
        assertEquals(p, Mqtt.decode(expected))
    }

    @Test
    fun `the small packets match their fixed headers`() {
        assertContentEquals(bytes(0x20, 2, 0, 0), Mqtt.encode(MqttPacket.ConnAck(false, 0)))     // §3.2
        assertContentEquals(bytes(0x20, 2, 1, 5), Mqtt.encode(MqttPacket.ConnAck(true, 5)))
        assertContentEquals(bytes(0x40, 2, 0x12, 0x34), Mqtt.encode(MqttPacket.PubAck(0x1234))) // §3.4
        assertContentEquals(bytes(0x62, 2, 0, 7), Mqtt.encode(MqttPacket.PubRel(7)))            // §3.6: flags 0010
        assertContentEquals(bytes(0xC0, 0), Mqtt.encode(MqttPacket.PingReq))                    // §3.12
        assertContentEquals(bytes(0xD0, 0), Mqtt.encode(MqttPacket.PingResp))
        assertContentEquals(bytes(0xE0, 0), Mqtt.encode(MqttPacket.Disconnect))
        assertContentEquals(bytes(0xB0, 2, 0, 9), Mqtt.encode(MqttPacket.UnsubAck(9)))
    }

    @Test
    fun `PUBLISH QoS 1 retained carries its packet id and flags`() {
        val p = MqttPacket.Publish("a/b", "hi".encodeToByteArray(), QoS.AT_LEAST_ONCE, retain = true, dup = true, packetId = 10)
        val expected = bytes(0x3B, 9, 0, 3, 'a'.code, '/'.code, 'b'.code, 0, 10, 'h'.code, 'i'.code) // §3.3: DUP|QoS1|RETAIN
        assertContentEquals(expected, Mqtt.encode(p))
        assertEquals(p, Mqtt.decode(expected))
        // QoS 0 has no packet id.
        assertContentEquals(bytes(0x30, 5, 0, 1, 't'.code, '4'.code, '2'.code), Mqtt.encode(MqttPacket.Publish("t", "42".encodeToByteArray())))
    }

    @Test
    fun `SUBSCRIBE, SUBACK and UNSUBSCRIBE match 3_8 to 3_10`() {
        val sub = MqttPacket.Subscribe(1, listOf("a/+" to QoS.AT_LEAST_ONCE, "#" to QoS.AT_MOST_ONCE))
        val expected = bytes(0x82, 12, 0, 1, 0, 3, 'a'.code, '/'.code, '+'.code, 1, 0, 1, '#'.code, 0)
        assertContentEquals(expected, Mqtt.encode(sub))
        assertEquals(sub, Mqtt.decode(expected))
        val ack = MqttPacket.SubAck(1, listOf(1, 0x80))
        assertContentEquals(bytes(0x90, 4, 0, 1, 1, 0x80), Mqtt.encode(ack))
        assertEquals(ack, Mqtt.decode(Mqtt.encode(ack)))
        val unsub = MqttPacket.Unsubscribe(2, listOf("a/+"))
        assertContentEquals(bytes(0xA2, 7, 0, 2, 0, 3, 'a'.code, '/'.code, '+'.code), Mqtt.encode(unsub))
        assertEquals(unsub, Mqtt.decode(Mqtt.encode(unsub)))
    }

    @Test
    fun `malformed packets are refused`() {
        assertFailsWith<MqttProtocolException> { Mqtt.decode(bytes(0x80, 2, 0, 1)) }          // SUBSCRIBE with flags 0000
        assertFailsWith<MqttProtocolException> { Mqtt.decode(bytes(0x82, 2, 0, 1)) }          // no filters
        assertFailsWith<MqttProtocolException> { Mqtt.decode(bytes(0x30, 5, 0, 9, 'a'.code, 0, 0)) } // string runs past
        assertFailsWith<MqttProtocolException> { Mqtt.decode(bytes(0x30, 3, 0, 1, '+'.code)) } // wildcard in a name
        assertFailsWith<MqttProtocolException> { Mqtt.decode(bytes(0x30, 3, 0, 1, 0)) }       // U+0000
        assertFailsWith<MqttProtocolException> { Mqtt.decode(bytes(0xF0, 0)) }                // reserved type 15
    }

    @Test
    fun `a large payload round trips through a multi-byte length`() {
        val payload = ByteArray(200_000) { (it * 31).toByte() }
        val p = MqttPacket.Publish("big", payload, QoS.AT_LEAST_ONCE, packetId = 65535)
        val back = Mqtt.decode(Mqtt.encode(p)) as MqttPacket.Publish
        assertContentEquals(payload, back.payload)
        assertEquals(65535, back.packetId)
    }

    @Test
    fun `wildcard matrix from 4_7`() {
        val cases = listOf(
            Triple("sport/tennis/player1/#", "sport/tennis/player1", true),
            Triple("sport/tennis/player1/#", "sport/tennis/player1/ranking", true),
            Triple("sport/tennis/player1/#", "sport/tennis/player1/score/wimbledon", true),
            Triple("sport/#", "sport", true),
            Triple("#", "anything/at/all", true),
            Triple("sport/tennis/+", "sport/tennis/player1", true),
            Triple("sport/tennis/+", "sport/tennis/player1/tranking", false),
            Triple("sport/+", "sport", false),
            Triple("sport/+", "sport/", true),
            Triple("+/+", "/finance", true),
            Triple("/+", "/finance", true),
            Triple("+", "/finance", false),
            Triple("sensors/+/temp", "sensors/kitchen/temp", true),
            Triple("sensors/+/temp", "sensors/kitchen/humidity", false),
            Triple("a/b", "a/b", true),
            Triple("a/b", "a/b/c", false),
            Triple("a/b/c", "a/b", false),
            Triple("#", "\$SYS/broker/uptime", false),
            Triple("+/broker/uptime", "\$SYS/broker/uptime", false),
            Triple("\$SYS/#", "\$SYS/broker/uptime", true),
            Triple("Accounts", "accounts", false),
        )
        cases.forEach { (f, t, want) -> assertEquals(want, MqttTopic.matches(f, t), "$f vs $t") }
    }

    @Test
    fun `filter and name validity`() {
        listOf("#", "+", "a/#", "+/+", "a/+/b", "/", "sport/tennis/#").forEach { assertTrue(MqttTopic.isValidFilter(it), it) }
        listOf("", "a#", "a/#/b", "sport+", "a/b+/c", "#/a").forEach { assertFalse(MqttTopic.isValidFilter(it), it) }
        assertTrue(MqttTopic.isValidName("a/b"))
        listOf("", "a/+", "a/#").forEach { assertFalse(MqttTopic.isValidName(it), it) }
    }
}
