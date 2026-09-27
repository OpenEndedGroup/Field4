package afield.stdlib

import afield.gather
import com.illposed.osc.OSCBundle
import com.illposed.osc.OSCMessage
import com.illposed.osc.OSCPacket
import com.illposed.osc.argument.OSCTimeTag64
import com.illposed.osc.transport.OSCPortOutBuilder
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * OSC and TUIO over real UDP loopback, pinning what `public/docs/osc.md` teaches and the four
 * defects found while writing it (2026-09-27):
 *
 *  1. an argument after a blob decoded from the blob's own bytes (JavaOSC 0.9 blob parser);
 *  2. a bundle stamped a few seconds ahead was dispatched at once (JavaOSC 0.9's 1900 epoch is an
 *     hour off);
 *  3. `osc(port)` after `close()` handed back the same closed server forever;
 *  4. `TuioReceiver.start()` threw a NullPointerException on every call (JavaOSC 0.9's
 *     `OSCPortInBuilder.addMessageListener` on a fresh builder).
 *
 * Ports 57390–57399 are this file's; `OscServer` is serviced with [OscServer.tick] because a jvmTest
 * has no Field runloop.
 */
class OscLoopbackTest {

    private fun pumpUntil(server: OscServer, timeoutMs: Long = 3_000, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < deadline) {
            server.tick()
            Thread.sleep(2)
        }
        server.tick()
    }

    @Test
    fun argumentsRoundTripWithTheirKotlinTypes() {
        OscServer(57390).use { server ->
            server.debug = false
            val got = Collections.synchronizedList(mutableListOf<List<Any?>>())
            server.on("/types") { m -> gather<OscServer.Message> { got += m.arguments } }
            OscClient("127.0.0.1", 57390).use { it.send("/types", 1, 0.5f, 0.5, 2L, true, false, "s", null) }
            pumpUntil(server) { got.isNotEmpty() }
            val args = got.single()
            assertEquals(listOf<Any?>(1, 0.5f, 0.5, 2L, true, false, "s", null), args)
            assertEquals(
                listOf("Integer", "Float", "Double", "Long", "Boolean", "Boolean", "String"),
                args.filterNotNull().map { it.javaClass.simpleName }
            )
        }
    }

    @Test
    fun argumentsAfterABlobAreDecodedFromTheirOwnBytes() {
        OscServer(57391).use { server ->
            server.debug = false
            val got = Collections.synchronizedList(mutableListOf<List<Any?>>())
            server.on("/blob") { m -> gather<OscServer.Message> { got += m.arguments } }
            OscClient("127.0.0.1", 57391).use { it.send("/blob", byteArrayOf(1, 2, 3), 9, 0.1) }
            pumpUntil(server) { got.isNotEmpty() }
            val args = got.single()
            val blob = args[0] as ByteBuffer
            assertEquals(listOf<Byte>(1, 2, 3), List(blob.remaining()) { blob.get(blob.position() + it) })
            // Before the fix these were 16909056 (0x01020300, the blob itself) and garbage.
            assertEquals(9, args[1])
            assertEquals(0.1, args[2])
        }
    }

    @Test
    fun theHandlerLambdaRunsOnReceiptAndTheGeneratorOnlyWhenTicked() {
        OscServer(57392).use { server ->
            server.debug = false
            val lambdaRuns = Collections.synchronizedList(mutableListOf<String>())
            val steps = Collections.synchronizedList(mutableListOf<String>())
            server.on("/steps") { m ->
                lambdaRuns += Thread.currentThread().name
                gather<OscServer.Message> {
                    steps += "start"
                    val again = yield()
                    steps += if (again === m) "resumed with the same message" else "resumed with another"
                    yield()
                    steps += "done"
                }
            }
            OscClient("127.0.0.1", 57392).use { it.send("/steps", 1) }
            val deadline = System.currentTimeMillis() + 3_000
            while (lambdaRuns.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(2)
            assertEquals(1, lambdaRuns.size, "the handler lambda runs when the message arrives, untouched by tick()")
            assertTrue(steps.isEmpty(), "the generator body waits for a service pass")
            server.tick()
            // The first service pass starts the generator AND resumes it once.
            assertEquals(listOf("start", "resumed with the same message"), steps.toList())
            server.tick()
            assertEquals("done", steps.last())
            server.tick()
            assertEquals(3, steps.size)
        }
    }

    @Test
    fun aHandlerRegisteredUnderAnotherPatternStaysLiveUntilRemoved() {
        OscServer(57393).use { server ->
            server.debug = false
            val fired = Collections.synchronizedList(mutableListOf<String>())
            server.on("/old") { _ -> gather<OscServer.Message> { fired += "old" } }
            server.on("/new") { _ -> gather<OscServer.Message> { fired += "new" } }
            OscClient("127.0.0.1", 57393).use { c ->
                c.send("/old"); c.send("/new")
                pumpUntil(server) { fired.size >= 2 }
                assertEquals(setOf("old", "new"), fired.toSet())
                server.remove("/old")
                fired.clear()
                c.send("/old"); c.send("/new")
                pumpUntil(server) { fired.isNotEmpty() }
                Thread.sleep(100); server.tick()
                assertEquals(listOf("new"), fired.toList())
            }
        }
    }

    @Test
    fun oscHandsBackAFreshServerAfterClose() {
        val first = osc(57394)
        first.close()
        assertTrue(first.isClosed)
        val second = osc(57394)
        try {
            assertNotSame(first, second, "a closed server must not stay cached")
            assertFalse(second.isClosed)
            assertTrue(second.isListening)
        } finally {
            second.close()
        }
    }

    /** Raw NTP rather than `OSCTimeTag64.valueOf(Date)`, which goes through the hour-off epoch. */
    private fun ntpIn(ms: Long): OSCTimeTag64 {
        val unixMs = System.currentTimeMillis() + ms
        val seconds = unixMs / 1000 + 2_208_988_800L
        val fraction = ((unixMs % 1000) shl 32) / 1000
        return OSCTimeTag64.valueOf((seconds shl 32) or fraction)
    }

    @Test
    fun aFutureBundleIsHeldUntilItsTimetag() {
        OscServer(57395).use { server ->
            server.debug = false
            val arrivals = Collections.synchronizedMap(mutableMapOf<Int, Long>())
            server.on("/when") { m ->
                arrivals[m.arguments[0] as Int] = System.currentTimeMillis()
                gather<OscServer.Message> { }
            }
            val out = OSCPortOutBuilder().setRemoteSocketAddress(InetSocketAddress("127.0.0.1", 57395)).build()
            try {
                val sent = System.currentTimeMillis()
                out.send(OSCBundle(listOf<OSCPacket>(OSCMessage("/when", listOf(0))), OSCTimeTag64.IMMEDIATE))
                out.send(OSCBundle(listOf<OSCPacket>(OSCMessage("/when", listOf(1))), ntpIn(600)))
                pumpUntil(server, 4_000) { arrivals.size == 2 }
                assertTrue(arrivals[0]!! - sent < 300, "an immediate bundle arrives at once")
                val late = arrivals[1]!! - sent
                // Before the fix this was a few milliseconds: JavaOSC scheduled it 3600 s early.
                assertTrue(late in 450..2_000, "a bundle stamped 600 ms ahead arrived after $late ms")
            } finally {
                out.close()
            }
        }
    }

    @Test
    fun tuioReceiverStartsAndReportsCursorDeltasPerFrame() {
        val frames = Collections.synchronizedList(mutableListOf<TuioReceiver.Frame>())
        val receiver = TuioReceiver(57396) { frames += it }
        receiver.start()
        val out = OSCPortOutBuilder().setRemoteSocketAddress(InetSocketAddress("127.0.0.1", 57396)).build()
        fun frame(fseq: Int, alive: List<Int>, sets: List<List<Any>>) {
            val msgs = mutableListOf<OSCPacket>(
                OSCMessage("/tuio/2Dcur", listOf("source", "test@127.0.0.1")),
                OSCMessage("/tuio/2Dcur", listOf<Any>("alive") + alive)
            )
            sets.forEach { msgs += OSCMessage("/tuio/2Dcur", listOf<Any>("set") + it) }
            msgs += OSCMessage("/tuio/2Dcur", listOf("fseq", fseq))
            out.send(OSCBundle(msgs))
        }
        try {
            frame(1, listOf(11), listOf(listOf(11, 0.25f, 0.5f, 0f, 0f, 0f)))
            frame(2, listOf(11, 12), listOf(listOf(11, 0.3f, 0.5f, 0f, 0f, 0f), listOf(12, 0.75f, 0.25f, 0f, 0f, 0f)))
            frame(3, listOf(12), listOf(listOf(12, 0.75f, 0.3f, 0f, 0f, 0f)))
            frame(4, emptyList(), emptyList())
            val deadline = System.currentTimeMillis() + 3_000
            while (frames.size < 4 && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertEquals(listOf(1, 2, 3, 4), frames.map { it.fseq })
            fun ids(l: List<TuioReceiver.TuioCursor>) = l.map { it.sessionId }
            assertEquals(listOf(11L), ids(frames[0].cursorAdded))
            assertEquals(listOf(12L), ids(frames[1].cursorAdded))
            assertEquals(listOf(11L), ids(frames[1].cursorUpdated))
            assertEquals(listOf(11L), ids(frames[2].cursorRemoved))
            assertEquals(listOf(12L), ids(frames[3].cursorRemoved))
            assertTrue(frames[3].cursors.isEmpty())
            assertEquals("test@127.0.0.1", frames[0].source)
            assertEquals(0.75f, frames[2].cursors.single().x)
        } finally {
            out.close()
            receiver.close()
        }
    }
}
