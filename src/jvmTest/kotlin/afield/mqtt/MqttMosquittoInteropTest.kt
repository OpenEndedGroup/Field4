package afield.mqtt

import org.junit.Assume
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * afield.mqtt against Eclipse Mosquitto, both ways: [MqttClient] against a `mosquitto` broker
 * process, and `mosquitto_pub` / `mosquitto_sub` against [MqttBroker]. Skipped when the binaries
 * are absent (`brew install mosquitto`; override with `MOSQUITTO_HOME=<prefix>`). Every process a
 * test starts is destroyed in [tearDown].
 */
class MqttMosquittoInteropTest {

    companion object {
        private fun find(name: String, vararg dirs: String): File? {
            val home = System.getenv("MOSQUITTO_HOME")
            val candidates = buildList {
                if (home != null) { add(File(home, "sbin/$name")); add(File(home, "bin/$name")) }
                dirs.forEach { add(File(it, name)) }
            }
            return candidates.firstOrNull { it.canExecute() }
        }

        val mosquitto = find("mosquitto", "/opt/homebrew/sbin", "/usr/local/sbin", "/usr/sbin")
        val pubBin = find("mosquitto_pub", "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
        val subBin = find("mosquitto_sub", "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
    }

    private val processes = CopyOnWriteArrayList<Process>()
    private val closeables = mutableListOf<AutoCloseable>()
    private fun <T : AutoCloseable> T.owned(): T = also { closeables += it }
    private val dir: File = Files.createTempDirectory("mqtt-mosquitto").toFile()

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { runCatching { it.close() } }
        processes.forEach { p -> p.destroy(); if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly().waitFor(2, TimeUnit.SECONDS) }
        dir.deleteRecursively()
    }

    private fun need(vararg f: File?) = Assume.assumeTrue("mosquitto not installed", f.all { it != null })

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(10) }
        return cond()
    }

    private fun start(vararg cmd: String, log: File? = null): Process {
        val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
        if (log != null) pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log)) else pb.redirectOutput(File(dir, "p${processes.size}.out"))
        return pb.start().also { processes += it }
    }

    private fun Process.output(): String { waitFor(15, TimeUnit.SECONDS); return File(dir, "p${processes.indexOf(this)}.out").readText() }

    // ---- a mosquitto broker process ---------------------------------------------------------

    private inner class Mosquitto(val port: Int = freePort()) {
        val log = File(dir, "mosquitto-$port.log")
        var process: Process? = null
        val url = "tcp://127.0.0.1:$port"

        fun start() {
            val conf = File(dir, "mosquitto-$port.conf")
            conf.writeText("listener $port 127.0.0.1\nallow_anonymous true\nlog_dest stdout\nlog_type all\nmax_keepalive 65535\nmax_packet_size 16000000\n")
            process = start(mosquitto!!.path, "-c", conf.path, "-v", log = log)
            assertTrue(waitFor { runCatching { Socket("127.0.0.1", port).close() }.isSuccess }, "mosquitto did not open $port: ${log.readText()}")
        }

        fun stop() {
            process?.let { it.destroy(); it.waitFor(5, TimeUnit.SECONDS) }
            process = null
        }

        fun logText() = log.readText()
    }

    private fun mosquittoBroker() = Mosquitto().also { it.start() }

    private fun client(url: String, id: String, keepAlive: Int = 30, will: MqttPacket.Will? = null) =
        MqttClient(url, clientId = id, keepAliveSeconds = keepAlive, will = will).owned().also { assertTrue(it.awaitConnected(), "$id did not connect: ${it.lastError}; ${dir.listFiles()?.filter { f -> f.name.endsWith(".log") }?.joinToString { f -> f.readText() }}") }

    private fun settle(c: MqttClient) = c.publish("settle/${c.clientId}", "x", QoS.AT_LEAST_ONCE).get(5, TimeUnit.SECONDS)

    private fun pub(port: Int, vararg args: String, v: String = "mqttv311"): Pair<Int, String> {
        val p = start(pubBin!!.path, "-h", "127.0.0.1", "-p", "$port", "-V", v, *args)
        val out = p.output()
        return p.exitValue() to out
    }

    /** A mosquitto_sub that exits after [count] messages or [timeoutSec]; output lines are `topic payload`. */
    private fun sub(port: Int, id: String, vararg args: String, count: Int = 1, timeoutSec: Int = 8): Process =
        start(subBin!!.path, "-h", "127.0.0.1", "-p", "$port", "-V", "mqttv311", "-i", id, "-v", "-C", "$count", "-W", "$timeoutSec", *args)

    private fun protocolErrors(log: String) = log.lines().filter {
        it.contains("protocol error", true) || it.contains("malformed", true) || it.contains("Bad client", true)
    }

    // ---- 1. MqttClient -> mosquitto ---------------------------------------------------------

    @Test
    fun clientWildcardsAndQosBothWays() {
        need(mosquitto, pubBin, subBin)
        val m = mosquittoBroker()
        val c = client(m.url, "field-a")
        val plus = CopyOnWriteArrayList<MqttMessage>()
        val hash = CopyOnWriteArrayList<MqttMessage>()
        c.subscribe("sensors/+/temp", QoS.AT_LEAST_ONCE) { plus += it }
        c.subscribe("sensors/#") { hash += it }
        settle(c)
        // mosquitto_pub -> our client, QoS 0 and 1
        assertEquals(0, pub(m.port, "-t", "sensors/kitchen/temp", "-m", "21.5", "-q", "1").first)
        assertEquals(0, pub(m.port, "-t", "sensors/kitchen/hum", "-m", "40", "-q", "0").first)
        assertTrue(waitFor { plus.size == 1 && hash.size == 2 }, "plus=$plus hash=$hash")
        assertEquals(21.5, plus[0].number)
        assertEquals(QoS.AT_LEAST_ONCE, plus[0].qos)
        // our client -> mosquitto_sub, QoS 0 and 1
        val s = sub(m.port, "msub-1", "-t", "out/#", "-q", "1", count = 2)
        assertTrue(waitFor { "msub-1" in m.logText() && m.logText().contains("Sending SUBACK to msub-1") }, m.logText())
        c.publish("out/zero", "q0").get(3, TimeUnit.SECONDS)
        c.publish("out/one", "q1", QoS.AT_LEAST_ONCE).get(3, TimeUnit.SECONDS)
        val out = s.output()
        assertTrue("out/zero q0" in out && "out/one q1" in out, out)
        assertTrue(m.logText().contains("Received PUBACK from field-a"), "our client acked mosquitto's QoS 1")
        assertEquals(emptyList(), protocolErrors(m.logText()))
    }

    @Test
    fun clientRetainedAndClear() {
        need(mosquitto, pubBin, subBin)
        val m = mosquittoBroker()
        val c = client(m.url, "field-r")
        c.publish("state/mode", "night", QoS.AT_LEAST_ONCE, retain = true).get(3, TimeUnit.SECONDS)
        val s = sub(m.port, "msub-r", "-t", "state/mode")
        assertTrue("state/mode night" in s.output())
        // Retained from mosquitto_pub, seen by our client as retained
        assertEquals(0, pub(m.port, "-t", "state/other", "-m", "x", "-r").first)
        val got = CopyOnWriteArrayList<MqttMessage>()
        c.subscribe("state/#") { got += it }
        assertTrue(waitFor { got.size == 2 }, "$got")
        assertTrue(got.all { it.retained })
        // clear with an empty retained publish
        c.publish("state/mode", ByteArray(0), retain = true).get(3, TimeUnit.SECONDS)
        settle(c)
        val s2 = sub(m.port, "msub-r2", "-t", "state/mode", timeoutSec = 2)
        assertFalse("night" in s2.output(), "retained message survived the clear")
        assertEquals(emptyList(), protocolErrors(m.logText()))
    }

    @Test
    fun clientWillOnDroppedSocketAndNotOnClose() {
        need(mosquitto, subBin)
        val m = mosquittoBroker()
        val s = sub(m.port, "msub-w", "-t", "status/#", count = 1, timeoutSec = 10)
        assertTrue(waitFor { m.logText().contains("Sending SUBACK to msub-w") })
        // A clean close publishes no will.
        client(m.url, "field-clean", will = MqttPacket.Will("status/clean", "gone".encodeToByteArray())).close()
        // A raw socket that sends CONNECT with a will and then vanishes without DISCONNECT.
        val raw = Socket("127.0.0.1", m.port)
        raw.getOutputStream().write(Mqtt.encode(MqttPacket.Connect("field-w", keepAliveSeconds = 30,
            will = MqttPacket.Will("status/field-w", "offline".encodeToByteArray(), QoS.AT_LEAST_ONCE))))
        assertTrue(Mqtt.read(raw.getInputStream()) is MqttPacket.ConnAck)
        raw.setSoLinger(true, 0)
        raw.close()
        val out = s.output()
        assertTrue("status/field-w offline" in out, out)
        assertFalse("status/clean" in out, out)
    }

    @Test
    fun clientKeepalivePings() {
        need(mosquitto)
        val m = mosquittoBroker()
        val c = client(m.url, "field-k", keepAlive = 1)
        assertTrue(waitFor(6000) { Regex("Received PINGREQ from field-k").findAll(m.logText()).count() >= 3 }, m.logText())
        assertTrue(c.isConnected)
        assertEquals(1, c.connections.get(), "the keepalive must not drop a healthy connection")
        assertFalse(m.logText().contains("field-k has exceeded timeout"), m.logText())
    }

    @Test
    fun clientReconnectsAfterBrokerRestartAndResubscribes() {
        need(mosquitto, pubBin)
        val m = mosquittoBroker()
        val c = client(m.url, "field-rc")
        val got = CopyOnWriteArrayList<MqttMessage>()
        c.subscribe("rc/+") { got += it }
        settle(c)
        m.stop()
        assertTrue(waitFor { !c.isConnected })
        m.start()
        assertTrue(waitFor(15_000) { c.isConnected && c.connections.get() == 2 }, "no reconnect: ${c.lastError}")
        assertTrue(waitFor { m.logText().split("Sending SUBACK to field-rc").size >= 3 }, "no resubscribe")
        assertEquals(0, pub(m.port, "-t", "rc/x", "-m", "back").first)
        assertTrue(waitFor { got.any { it.text == "back" } }, "$got")
    }

    @Test
    fun clientLargePayloadsBothWays() {
        need(mosquitto, pubBin, subBin)
        val m = mosquittoBroker()
        val c = client(m.url, "field-big")
        val got = CopyOnWriteArrayList<MqttMessage>()
        c.subscribe("big/#", QoS.AT_LEAST_ONCE) { got += it }
        settle(c)
        for (size in listOf(20_000, 3_000_000)) {
            val f = File(dir, "payload-$size.bin")
            f.writeBytes(ByteArray(size) { ('a' + it % 26).code.toByte() })
            assertEquals(0, pub(m.port, "-t", "big/in", "-f", f.path, "-q", "1").first)
            assertTrue(waitFor(10_000) { got.any { it.payload.size == size } }, "no $size-byte message")
            assertTrue(got.first { it.payload.size == size }.payload.contentEquals(f.readBytes()))
            val s = sub(m.port, "msub-big-$size", "-t", "big/out", "-N")
            assertTrue(waitFor { m.logText().contains("Sending SUBACK to msub-big-$size") })
            c.publish("big/out", f.readBytes(), QoS.AT_LEAST_ONCE).get(10, TimeUnit.SECONDS)
            val out = s.output()
            assertEquals(size, out.removePrefix("big/out ").length, "mosquitto_sub got ${out.length} chars")
        }
        assertEquals(emptyList(), protocolErrors(m.logText()))
    }

    @Test
    fun clientUnicodeTopics() {
        need(mosquitto, pubBin, subBin)
        val m = mosquittoBroker()
        val c = client(m.url, "field-u")
        val got = CopyOnWriteArrayList<MqttMessage>()
        c.subscribe("café/+/温度") { got += it }
        settle(c)
        assertEquals(0, pub(m.port, "-t", "café/κουζίνα/温度", "-m", "℃ 21").first)
        assertTrue(waitFor { got.size == 1 }, "$got")
        assertEquals("café/κουζίνα/温度", got[0].topic)
        assertEquals("℃ 21", got[0].text)
        val s = sub(m.port, "msub-u", "-t", "🎛/#")
        assertTrue(waitFor { m.logText().contains("Sending SUBACK to msub-u") })
        c.publish("🎛/fader", "ü").get(3, TimeUnit.SECONDS)
        assertTrue("🎛/fader ü" in s.output())
    }

    @Test
    fun clientReceivesQos2FromMosquittoPub() {
        need(mosquitto, pubBin)
        val m = mosquittoBroker()
        val c = client(m.url, "field-q2")
        val got = CopyOnWriteArrayList<MqttMessage>()
        c.subscribe("q2/t", QoS.EXACTLY_ONCE) { got += it }
        settle(c)
        assertEquals(0, pub(m.port, "-t", "q2/t", "-m", "once", "-q", "2").first)
        assertTrue(waitFor { got.size == 1 })
        Thread.sleep(300)
        assertEquals(1, got.size, "delivered more than once: $got")
        // mosquitto grants QoS 2 to us; the flow must complete (PUBREC -> PUBREL -> PUBCOMP).
        assertTrue(waitFor { m.logText().contains("Received PUBCOMP from field-q2") || m.logText().contains("Received PUBACK from field-q2") }, m.logText())
        // and our QoS 2 publish completes against mosquitto
        c.publish("q2/t", "mine", QoS.EXACTLY_ONCE).get(3, TimeUnit.SECONDS)
        assertTrue(m.logText().contains("Received PUBREL from field-q2"))
        assertEquals(emptyList(), protocolErrors(m.logText()))
    }

    // ---- 2. mosquitto clients -> MqttBroker -------------------------------------------------

    private fun ourBroker() = MqttBroker(port = freePort()).owned()

    private fun awaitSubscribed(b: MqttBroker, id: String, filter: String) {
        assertTrue(waitFor { id in b.clients }, "$id never connected")
        // The SUBACK follows CONNECT in the same burst; the broker exposes no subscription list.
        Thread.sleep(300)
    }

    @Test
    fun mosquittoClientsAgainstOurBroker() {
        need(pubBin, subBin)
        val b = ourBroker()
        val s = sub(b.port, "m-sub", "-t", "a/+/c", "-t", "x/#", "-q", "1", count = 3)
        awaitSubscribed(b, "m-sub", "a/+/c")
        assertEquals(0, pub(b.port, "-t", "a/b/c", "-m", "plus", "-q", "1").first)
        assertEquals(0, pub(b.port, "-t", "x/y/z", "-m", "hash", "-q", "0").first)
        assertEquals(0, pub(b.port, "-t", "x/q2", "-m", "two", "-q", "2").first)
        val out = s.output()
        assertTrue("a/b/c plus" in out && "x/y/z hash" in out && "x/q2 two" in out, out)
        assertEquals(0, s.exitValue())
    }

    @Test
    fun mosquittoRetainedAndClearAgainstOurBroker() {
        need(pubBin, subBin)
        val b = ourBroker()
        assertEquals(0, pub(b.port, "-t", "keep/me", "-m", "kept", "-r", "-q", "1").first)
        assertTrue("keep/me kept" in sub(b.port, "m-r1", "-t", "keep/#").output())
        assertEquals(0, pub(b.port, "-t", "keep/me", "-n", "-r").first)
        assertTrue(waitFor { "keep/me" !in b.retainedTopics })
        val s = sub(b.port, "m-r2", "-t", "keep/#", timeoutSec = 2)
        assertFalse("kept" in s.output())
    }

    @Test
    fun mosquittoWillAgainstOurBroker() {
        need(pubBin, subBin)
        val b = ourBroker()
        val watcher = sub(b.port, "m-watch", "-t", "lwt/#", timeoutSec = 10)
        awaitSubscribed(b, "m-watch", "lwt/#")
        // A mosquitto_sub with a will, killed hard: no DISCONNECT.
        val victim = sub(b.port, "m-victim", "-t", "nothing", "--will-topic", "lwt/m-victim", "--will-payload", "dead", "--will-qos", "1", timeoutSec = 30)
        assertTrue(waitFor { "m-victim" in b.clients })
        victim.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        assertTrue("lwt/m-victim dead" in watcher.output())
    }

    @Test
    fun mosquittoLargePayloadAndUnicodeAgainstOurBroker() {
        need(pubBin, subBin)
        val b = MqttBroker(port = freePort(), maxPacketSize = 8 shl 20).owned()
        val got = CopyOnWriteArrayList<MqttPacket.Publish>()
        b.onMessage += { _, p -> got += p }
        val f = File(dir, "big.bin").apply { writeBytes(ByteArray(3_000_000) { ('a' + it % 26).code.toByte() }) }
        val s = sub(b.port, "m-big", "-t", "big", "-N")
        awaitSubscribed(b, "m-big", "big")
        assertEquals(0, pub(b.port, "-t", "big", "-f", f.path, "-q", "1").first)
        assertEquals(3_000_000, s.output().removePrefix("big ").length)
        assertEquals(0, pub(b.port, "-t", "café/温度", "-m", "℃").first)
        assertTrue(waitFor { got.any { it.topic == "café/温度" && it.text == "℃" } })
    }

    @Test
    fun mosquittoMqtt5IsRefusedCleanly() {
        need(pubBin)
        val b = ourBroker()
        val (code, out) = pub(b.port, "-t", "v5", "-m", "x", v = "mqttv5")
        assertTrue(code != 0, "an MQTT 5 client must not think it published: $out")
        // Regression: a 3.1.1-shaped CONNACK made mosquitto_pub report "Malformed packet".
        assertFalse(out.contains("Malformed", true), "MQTT 5 refusal was not a v5 CONNACK: $out")
        assertTrue(out.contains("protocol", true) || out.contains("version", true), "unhelpful refusal: $out")
        assertFalse(b.isClosed)
        // the broker is still healthy for 3.1.1 afterwards
        assertEquals(0, pub(b.port, "-t", "v4", "-m", "x").first)
    }
}
