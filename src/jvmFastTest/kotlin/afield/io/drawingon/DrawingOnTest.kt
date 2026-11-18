package afield.io.drawingon

import afield.app.server.installDrawingOnRoutes
import afield.app.server.DrawingOnPeers
import afield.document.graph.Box
import afield.geometry.path.FLine
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DrawingOnTest {
    private val session = UUID.randomUUID().toString()
    private fun event(seq: Int, type: String = "stroke.samples") = buildJsonObject {
        put("v", 1); put("session", session); put("seq", seq); put("t", seq * 0.01); put("type", type)
        put("data", buildJsonObject { put("label", "café · façade") })
    }
    private fun archive(test: (DrawingOnArchive) -> Unit) {
        val root = Files.createTempDirectory("drawingon-test-").toFile()
        try { test(DrawingOnArchive(root)) } finally { root.deleteRecursively() }
    }
    @Test fun durableReplayGapsAndCrashRecovery() = archive { a ->
        a.bind("ipad", session)
        assertEquals(2, a.commit(session, listOf(event(1), event(2))).fresh.size)
        assertEquals(0, a.commit(session, listOf(event(1), event(2))).fresh.size)
        assertEquals(3, a.commit(session, listOf(event(4))).reply["replayFrom"]!!.jsonPrimitive.int)
        assertEquals(2L, a.head(session))
        File(a.directory, "$session.ndjson").appendText("{\"partial\":")
        val recovered = DrawingOnArchive(a.directory)
        assertEquals(2L, recovered.head(session))
        assertEquals(1, recovered.commit(session, listOf(event(3, "stroke.visibility"))).fresh.size)
        val events = mutableListOf<JsonObject>(); recovered.read(session) { events += it }
        assertEquals(listOf(1, 2, 3), events.map { it["seq"]!!.jsonPrimitive.int })
        assertEquals("café · façade", events.first()["data"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals(listOf(session), recovered.sessions("ipad"))
        assertTrue(recovered.sessions("other").isEmpty())
    }
    @Test fun invalidBatchIsAtomicAndCompleteCorruptionIsNotSilentlyDiscarded() = archive { a ->
        val bad = JsonObject(event(2) + ("t" to JsonPrimitive("no time")))
        assertFails { a.commit(session, listOf(event(1), bad)) }
        assertEquals(0L, a.head(session))
        a.commit(session, listOf(event(1)))
        File(a.directory, "$session.ndjson").appendText("{invalid}\n")
        assertFails { DrawingOnArchive(a.directory).head(session) }
        assertContains(File(a.directory, "$session.ndjson").readText(), "{invalid}")
    }
    @Test fun explicitOwnerAndProjectionAndEmptyReplacement() {
        val name = "test-${UUID.randomUUID()}"
        val box = Box("drawing-owner")
        val target = box.drawingOn(name)
        val peer = Channel<DrawingOnReference>(Channel.CONFLATED)
        DrawingOnPeers.attach(name, peer)
        try {
            assertSame(target, box.drawingOn(name))
            assertFailsWith<IllegalArgumentException> { Box("someone-else").drawingOn(name) }
            assertFailsWith<IllegalArgumentException> { Box(box.id).drawingOn(name) }
            assertFailsWith<IllegalArgumentException> { target.publish(emptyList(), plane = DrawingOnPlane(yAxis = listOf(2.0, 0.0, 0.0))) }
            target.publish(listOf(FLine().moveTo(10, 20).lineTo(110, 120)))
            val first = target.reference!!
            assertEquals(sha256(first.bytes), first.message["sha256"]!!.jsonPrimitive.content)
            assertTrue(first.bytes.isNotEmpty())
            assertEquals("three/geometry", first.message["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            target.publish(emptyList())
            assertNotEquals(first.generation, target.reference!!.generation)
            peer.tryReceive()
            box.runScopes.closeAll()
            assertFailsWith<IllegalStateException> { target.publish(emptyList()) }
            val replacement = Box("new-owner").drawingOn(name)
            replacement.publish(listOf(FLine().moveTo(1, 2).lineTo(3, 4)))
            assertEquals(replacement.reference!!.generation, peer.tryReceive().getOrThrow().generation,
                "Retiring a box keeps the socket subscription available for the next owner")
        } finally { DrawingOnPeers.detach(name, peer); peer.close(); box.runScopes.closeAll(); DrawingOnServices.targets.remove(name) }
    }

    /** Opt-in cross-language test: real Ktor endpoint, real FLine buffers, native URLSession client. */
    @Test fun nativeBidirectionalRoundTrip() {
        val native = System.getenv("DRAWINGON_NATIVE_PATH")
        org.junit.Assume.assumeTrue("Set DRAWINGON_NATIVE_PATH for the Swift ⇄ Field test", native != null)
        val root = Files.createTempDirectory("drawingon-native-").toFile()
        val target = DrawingOnTarget("integration", "integration-box")
        val received = CopyOnWriteArrayList<DrawingOnEvent>()
        DrawingOnServices.targets[target.name] = target
        target.onEvent { e ->
            received += e
            if (e.type == "stroke.end" && !e.upload) {
                target.publish(listOf(FLine().moveTo(40, 50).lineTo(240, 150)), title = "Field response",
                    plane = DrawingOnPlane(scale = 2.0, offsetX = 10.0, offsetY = 20.0))
            }
        }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing { installDrawingOnRoutes(DrawingOnArchive(root)) }
        }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val output = File(root, "swift.log")
            val process = ProcessBuilder("/usr/bin/env", "swift", "test", "--filter", "SocketTests")
                .directory(File(native!!)).redirectErrorStream(true).redirectOutput(output)
                .also {
                    it.environment()["DRAWINGON_FIELD_ENDPOINT"] = "ws://127.0.0.1:$port/drawingon/ws?target=integration"
                    it.environment()["DRAWINGON_TEST_RECEIVER"] = "ws://127.0.0.1:$port/drawingon/ws?target=integration"
                    it.environment()["DRAWINGON_TEST_ARCHIVE"] = root.absolutePath
                }.start()
            val done = process.waitFor(90, TimeUnit.SECONDS)
            if (!done) process.destroyForcibly()
            assertTrue(done, "Native test timed out: ${output.readText()}")
            assertEquals(0, process.exitValue(), output.readText())
            assertTrue(received.any { it.type == "audio.buffer" && !it.upload })
            assertTrue(received.any { it.type == "stroke.visibility" })
            assertTrue(received.any { it.type == "field.reference" })
            assertTrue(received.any { it.upload }, "Saved captures also flow through the durable receiver")
            val keys = received.map { it.session to it.event["seq"]!!.jsonPrimitive.int }
            assertEquals(keys.size, keys.distinct().size, "Reconnects never redeliver committed input")
        } finally {
            server.stop(100, 2000); DrawingOnServices.targets.remove(target.name); root.deleteRecursively()
        }
    }
}
