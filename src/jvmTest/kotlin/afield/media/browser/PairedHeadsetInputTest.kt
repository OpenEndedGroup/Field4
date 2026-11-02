package afield.media.browser

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import kotlin.test.*

/** Speaks the headset side of the paired-input protocol, as `FieldInputBridge.swift` does. */
private class PairedTestClient(port: Int, code: String) : AutoCloseable {
    private val socket = Socket("127.0.0.1", port).apply { soTimeout = 3000 }
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())
    private val key = PairedInputProtocol.pairingKey(code)
    var sequence = 0L
    private var sessionKey: ByteArray? = null
    var refusal: String? = null

    private fun read(): ByteArray = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
    private fun write(bytes: ByteArray) { output.writeShort(bytes.size); output.write(bytes); output.flush() }

    /** True when Field accepted the code and proved it knows it too. */
    fun handshake(): Boolean {
        val hello = read()
        assertEquals("FVRI", String(hello, 0, 4)); assertEquals(1, hello[4].toInt())
        val serverNonce = hello.copyOfRange(5, 21)
        val clientNonce = ByteArray(16).also(SecureRandom()::nextBytes)
        write("FVRI".toByteArray() + byteArrayOf(1) + clientNonce + PairedInputProtocol.hmac(key, "client", serverNonce, clientNonce))
        val answer = read()
        if (answer[0].toInt() != 1) { refusal = String(answer, 1, answer.size - 1); return false }
        assertContentEquals(PairedInputProtocol.hmac(key, "server", serverNonce, clientNonce), answer.copyOfRange(1, answer.size))
        sessionKey = PairedInputProtocol.hmac(key, "session", serverNonce, clientNonce)
        return true
    }

    fun send(sequenceOverride: Long? = null, build: Records.() -> Unit) {
        val records = Records().apply(build).bytes.toByteArray()
        val seq = sequenceOverride ?: ++sequence
        val sealed = PairedInputProtocol.gcm(sessionKey!!, seq, Cipher.ENCRYPT_MODE).doFinal(records)
        write(ByteBuffer.allocate(8).putLong(seq).array() + sealed)
    }

    fun sendRaw(bytes: ByteArray) = write(bytes)

    override fun close() = socket.close()

    class Records {
        val bytes = ByteArrayOutputStream()
        private val data = DataOutputStream(bytes)
        fun key(usage: Int, down: Boolean) { data.writeByte(1); data.writeShort(usage); data.writeByte(if (down) 1 else 0) }
        fun move(dx: Float, dy: Float) { data.writeByte(2); data.writeFloat(dx); data.writeFloat(dy) }
        fun button(index: Int, down: Boolean) { data.writeByte(3); data.writeByte(index); data.writeByte(if (down) 1 else 0) }
        fun scroll(dx: Float, dy: Float) { data.writeByte(4); data.writeFloat(dx); data.writeFloat(dy) }
        fun reset(reason: String) { data.writeByte(5); val r = reason.toByteArray(); data.writeByte(r.size); data.write(r) }
        fun heartbeat() { data.writeByte(6) }
    }
}

class PairedHeadsetInputTest {
    private val code = "7K3QP-M2X9D"
    private val now = AtomicLong(1_000_000_000L)
    private val sources = mutableListOf<PairedInputSource>()

    private fun source(maxQueued: Int = 8192, commandAsControl: Boolean = false) = PairedInputSource(code, port = 0,
        commandAsControl = commandAsControl, maxQueued = maxQueued, clock = now::get, bindAddress = "127.0.0.1")
        .also { sources += it }

    @AfterTest fun closeSources() { sources.forEach { it.close() } }

    /** Network delivery is asynchronous; wait for the frame thread to see what was sent. */
    private fun PairedInputSource.pollUntil(predicate: (List<WiVRnDeviceEvent>) -> Boolean): List<WiVRnDeviceEvent> {
        val seen = mutableListOf<WiVRnDeviceEvent>()
        val deadline = System.nanoTime() + 3_000_000_000
        while (System.nanoTime() < deadline) {
            seen += poll()
            if (predicate(seen)) return seen
            Thread.sleep(5)
        }
        fail("timed out; saw ${seen.map { it.event }}")
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000
        while (!condition()) { check(System.nanoTime() < deadline) { "timed out" }; Thread.sleep(5) }
    }

    private fun List<WiVRnDeviceEvent>.triples() = map { Triple(it.event.type, it.event.code, it.event.value) }

    @Test fun `hid usages map to the kernel's evdev codes`() {
        assertEquals(30, HidToEvdev.key(0x04))  // a
        assertEquals(44, HidToEvdev.key(0x1d))  // z
        assertEquals(11, HidToEvdev.key(0x27))  // 0
        assertEquals(28, HidToEvdev.key(0x28))  // Enter
        assertEquals(41, HidToEvdev.key(0x35))  // `
        assertEquals(88, HidToEvdev.key(0x45))  // F12
        assertEquals(103, HidToEvdev.key(0x52)) // Up
        assertEquals(86, HidToEvdev.key(0x64))  // ISO \
        assertEquals(125, HidToEvdev.key(0xe3)) // Left Command / Super
        assertEquals(29, HidToEvdev.key(0xe3, commandAsControl = true))
        assertEquals(125, HidToEvdev.key(0xe0, commandAsControl = true))
        assertNull(HidToEvdev.key(0x00)); assertNull(HidToEvdev.key(0x03)); assertNull(HidToEvdev.key(0x1000))
    }

    @Test fun `codes are typeable and persist privately`() {
        val generated = PairedInputProtocol.newCode()
        assertTrue(PairedInputProtocol.validCode(generated), generated)
        assertEquals(PairedInputProtocol.normalize("7k3qp m2x9d"), PairedInputProtocol.normalize(code))
        assertContentEquals(PairedInputProtocol.pairingKey("ABCDE-FGHJK"), PairedInputProtocol.pairingKey("abcde fghjk"))
        assertContentEquals(PairedInputProtocol.pairingKey("10OOO-00000"), PairedInputProtocol.pairingKey("1O000-0O0O0"))
        val dir = Files.createTempDirectory("paired-code")
        try {
            val path = dir.resolve("nested/code")
            val first = PairedInputProtocol.loadOrCreateCode(path)
            assertEquals(first, PairedInputProtocol.loadOrCreateCode(path))
            runCatching { Files.getPosixFilePermissions(path) }.getOrNull()?.let {
                assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), it)
            }
            Files.writeString(path, "not a code")
            assertNotEquals("not a code", PairedInputProtocol.loadOrCreateCode(path))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `keys motion buttons and scroll arrive as evdev events in order`() {
        val source = source()
        assertTrue(source.status.contains("code $code"), source.status)
        PairedTestClient(source.port, code).use { client ->
            assertTrue(client.handshake())
            waitFor { source.captured.size == 2 }
            client.send {
                key(0xe1, true); key(0x04, true); key(0x04, false); key(0xe1, false)
                move(10.4f, -3.0f); move(0.4f, 0f) // remainders accumulate: 10.4 + 0.4 rounds once
                button(0, true); move(5f, 0f); button(0, false)
                scroll(0.5f, -1f)
            }
            val events = source.pollUntil { it.size >= 18 }
            assertEquals(listOf(
                Triple(1, 42, 1), Triple(1, 30, 1), Triple(1, 30, 0), Triple(1, 42, 0),
                Triple(2, 0, 10), Triple(2, 1, -3), Triple(0, 0, 0),
                Triple(2, 0, 1), Triple(0, 0, 0),
                Triple(1, 272, 1), Triple(0, 0, 0),
                Triple(2, 0, 5), Triple(0, 0, 0),
                Triple(1, 272, 0), Triple(0, 0, 0),
                Triple(2, 12, 60), Triple(2, 11, -120), Triple(0, 0, 0),
            ), events.triples())
            assertEquals(WiVRnDevice.KEYBOARD, events[0].device); assertEquals(WiVRnDevice.MOUSE, events[4].device)
            assertTrue(source.status.startsWith("Paired keyboard + mouse"), source.status)
            assertFalse(source.status.contains(code), "the code is not shown once paired")
        }
    }

    @Test fun `wrong code is refused and nothing is delivered`() {
        val source = source()
        PairedTestClient(source.port, "ZZZZZ-ZZZZZ").use { client ->
            assertFalse(client.handshake())
            assertEquals("wrong pairing code", client.refusal)
        }
        waitFor { source.problem != null }
        assertTrue(source.captured.isEmpty())
        assertTrue(source.poll().isEmpty())
        assertTrue(source.status.contains("wrong pairing code"), source.status)
    }

    @Test fun `a replayed frame ends the session and releases held input`() {
        val source = source()
        PairedTestClient(source.port, code).use { client ->
            assertTrue(client.handshake())
            client.send { key(0x04, true) }
            source.pollUntil { it.isNotEmpty() }
            client.send(sequenceOverride = 1) { key(0x04, false) }
            waitFor { source.captured.isEmpty() }
            assertTrue(source.poll().isEmpty(), "the stale key-up is not delivered")
            assertEquals(mapOf(WiVRnDevice.KEYBOARD to emptySet(), WiVRnDevice.MOUSE to emptySet<Int>()), source.takeResets())
            assertTrue(source.status.contains("stale or replayed"), source.status)
        }
    }

    @Test fun `tampered ciphertext is rejected`() {
        val source = source()
        PairedTestClient(source.port, code).use { client ->
            assertTrue(client.handshake())
            client.sendRaw(ByteBuffer.allocate(8).putLong(1).array() + ByteArray(32))
            waitFor { source.problem != null }
            assertTrue(source.poll().isEmpty())
            assertTrue(source.status.contains("authentication"), source.status)
        }
    }

    @Test fun `a new session supersedes the old one without replaying its keys`() {
        val source = source()
        PairedTestClient(source.port, code).use { first ->
            assertTrue(first.handshake())
            first.send { key(0x04, true) }
            source.pollUntil { it.isNotEmpty() }
            source.takeResets()
            PairedTestClient(source.port, code).use { second ->
                assertTrue(second.handshake())
                waitFor { source.poll(); source.takeResets().isNotEmpty() }
                second.send { key(0x05, true) }
                assertEquals(listOf(Triple(1, 48, 1)), source.pollUntil { it.isNotEmpty() }.triples())
                // The first link is closed: its late frames go nowhere.
                runCatching { first.send { key(0x06, true) } }
                Thread.sleep(50)
                assertTrue(source.poll().none { it.event.code == 46 })
            }
        }
    }

    @Test fun `silence times out and releases`() {
        val source = source()
        PairedTestClient(source.port, code).use { client ->
            assertTrue(client.handshake())
            client.send { heartbeat() }
            waitFor { source.captured.size == 2 }
            source.poll(); source.takeResets()
            now.addAndGet(3_000_000_000)
            source.poll()
            assertTrue(source.captured.isEmpty())
            assertEquals(2, source.takeResets().size)
            assertTrue(source.status.contains("timed out"), source.status)
        }
    }

    @Test fun `a reset record releases in order with the events around it`() {
        val source = source()
        PairedTestClient(source.port, code).use { client ->
            assertTrue(client.handshake())
            client.send { key(0x04, true); reset("focus lost"); key(0x05, true) }
            val events = source.pollUntil { it.isNotEmpty() }
            assertEquals(listOf(Triple(1, 48, 1)), events.triples(), "only the post-reset key survives")
            assertEquals(2, source.takeResets().size, "the reset is delivered with that same poll")
        }
    }

    @Test fun `held keys repeat like evdev and modifiers do not`() {
        val source = source()
        PairedTestClient(source.port, code).use { client ->
            assertTrue(client.handshake())
            client.send { key(0xe1, true); key(0x04, true) }
            source.pollUntil { it.size >= 2 }
            assertTrue(source.poll().isEmpty())
            now.addAndGet(510_000_000)
            assertEquals(listOf(Triple(1, 30, 2)), source.poll().triples())
            assertTrue(source.poll().isEmpty())
            now.addAndGet(40_000_000)
            assertEquals(listOf(Triple(1, 30, 2)), source.poll().triples())
            client.send { key(0x04, false) }
            source.pollUntil { it.isNotEmpty() }
            now.addAndGet(1_000_000_000)
            client.send { heartbeat() }
            Thread.sleep(50)
            assertTrue(source.poll().isEmpty(), "Shift alone never repeats")
        }
    }

    @Test fun `overflow drops the session instead of growing`() {
        val source = source(maxQueued = 8)
        PairedTestClient(source.port, code).use { client ->
            assertTrue(client.handshake())
            client.send { repeat(10) { key(0x04 + it, true) } }
            waitFor { source.captured.isEmpty() }
            assertTrue(source.poll().isEmpty())
            assertTrue(source.status.contains("overflow"), source.status)
        }
    }

    @Test fun `command can stand in for control`() {
        val source = source(commandAsControl = true)
        PairedTestClient(source.port, code).use { client ->
            assertTrue(client.handshake())
            client.send { key(0xe3, true); key(0x06, true) }
            assertEquals(listOf(Triple(1, 29, 1), Triple(1, 46, 1)), source.pollUntil { it.size >= 2 }.triples())
        }
    }

    @Test fun `closing stops listening and releases`() {
        val source = source()
        val port = source.port
        PairedTestClient(port, code).use { client ->
            assertTrue(client.handshake())
            waitFor { source.captured.size == 2 }
            source.close()
            assertTrue(source.captured.isEmpty())
            assertEquals("Paired input closed", source.status)
        }
        assertFails { Socket("127.0.0.1", port).use { } }
    }

    @Test fun `paired mode is explicit and Linux-only`() {
        assertTrue(FieldVrWorkspace.defaultPairedInput("paired", "Linux"))
        assertFalse(FieldVrWorkspace.defaultPairedInput(null, "Linux"))
        assertFalse(FieldVrWorkspace.defaultPairedInput("auto", "Linux"))
        assertFalse(FieldVrWorkspace.defaultHeadsetInput(true, "paired", "Linux"))
        assertFailsWith<IllegalArgumentException> { FieldVrWorkspace.defaultPairedInput("paired", "Mac OS X") }
    }
}
