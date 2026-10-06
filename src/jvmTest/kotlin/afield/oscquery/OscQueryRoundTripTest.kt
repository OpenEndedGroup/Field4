package afield.oscquery

import afield.stdlib.OscClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An [OscQueryServer] on ephemeral ports, exercised over real loopback HTTP, UDP and WebSocket by
 * clients that do not share its code: the JDK HTTP and WebSocket clients, and JavaOSC for UDP.
 * Callbacks are direct (no Runloop in a test JVM).
 */
class OscQueryRoundTripTest {
    private val server = OscQueryServer("Field test", httpPort = 0, oscPort = 0, bindAddress = "127.0.0.1", advertise = false)
    private val http = HttpClient.newHttpClient()

    @AfterTest
    fun tearDown() { server.close() }

    private fun waitFor(timeoutMs: Long = 4000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    private fun get(pathAndQuery: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${server.httpPort}$pathAndQuery")).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun json(pathAndQuery: String): JsonObject {
        val r = get(pathAndQuery)
        assertEquals(200, r.statusCode(), "GET $pathAndQuery")
        assertTrue(r.headers().firstValue("content-type").orElse("").startsWith("application/json"))
        return Json.parseToJsonElement(r.body()).jsonObject
    }

    @Test
    fun `the namespace is served as spec-shaped JSON`() {
        server.add("/synth/cutoff", "f", listOf(0.25), listOf(0.0 to 1.0), description = "filter cutoff")
        server.add("/synth/on", "T", listOf(true))
        server.add("/scene", "s", listOf("dusk"), values = listOf("dawn", "dusk", "night"))
        server.add("/meter", "f", listOf(0.0), access = OscAccess.READ)
        server.describe("/synth", "a small synth")

        val root = json("/")
        assertEquals("/", root["FULL_PATH"]!!.jsonPrimitive.content)
        val contents = root["CONTENTS"]!!.jsonObject
        assertEquals(setOf("synth", "scene", "meter"), contents.keys)
        val synth = contents["synth"]!!.jsonObject
        assertEquals("/synth", synth["FULL_PATH"]!!.jsonPrimitive.content)
        assertEquals("a small synth", synth["DESCRIPTION"]!!.jsonPrimitive.content)

        val cutoff = synth["CONTENTS"]!!.jsonObject["cutoff"]!!.jsonObject
        assertEquals("/synth/cutoff", cutoff["FULL_PATH"]!!.jsonPrimitive.content)
        assertEquals("f", cutoff["TYPE"]!!.jsonPrimitive.content)
        assertEquals(3, cutoff["ACCESS"]!!.jsonPrimitive.int)
        assertEquals(0.25, (cutoff["VALUE"] as JsonArray)[0].jsonPrimitive.double)
        val range = (cutoff["RANGE"] as JsonArray)[0].jsonObject
        assertEquals(0.0, range["MIN"]!!.jsonPrimitive.double)
        assertEquals(1.0, range["MAX"]!!.jsonPrimitive.double)
        assertEquals("filter cutoff", cutoff["DESCRIPTION"]!!.jsonPrimitive.content)

        // a leaf can be fetched directly, and so can one attribute of it
        assertEquals(cutoff, json("/synth/cutoff"))
        assertEquals(setOf("VALUE"), json("/synth/cutoff?VALUE").keys)
        assertEquals("true", (json("/synth/on?VALUE")["VALUE"] as JsonArray)[0].jsonPrimitive.content)
        val vals = ((json("/scene?RANGE")["RANGE"] as JsonArray)[0].jsonObject["VALS"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(listOf("dawn", "dusk", "night"), vals)
        assertEquals(1, json("/meter?ACCESS")["ACCESS"]!!.jsonPrimitive.int)

        val info = json("/?HOST_INFO")
        assertEquals("Field test", info["NAME"]!!.jsonPrimitive.content)
        assertEquals(server.oscPort, info["OSC_PORT"]!!.jsonPrimitive.int)
        assertEquals("UDP", info["OSC_TRANSPORT"]!!.jsonPrimitive.content)
        assertEquals("true", info["EXTENSIONS"]!!.jsonObject["LISTEN"]!!.jsonPrimitive.content)

        assertEquals(404, get("/nothing/here").statusCode())
        assertEquals(204, get("/synth/on?RANGE").statusCode())
    }

    @Test
    fun `an OSC UDP set reaches the callback, coerced and clipped`() {
        val heard = CopyOnWriteArrayList<List<Any?>>()
        server.add("/synth/cutoff", "f", listOf(0.0), listOf(0.0 to 1.0)).onSet { heard += it }
        server.add("/meter", "f", listOf(0.0), access = OscAccess.READ).onSet { heard += listOf("meter") }

        OscClient("127.0.0.1", server.oscPort).use { osc ->
            osc.send("/synth/cutoff", 0.75f)
            assertTrue(waitFor { heard.size == 1 })
            osc.send("/synth/cutoff", 7) // an int to a float, out of range
            assertTrue(waitFor { heard.size == 2 })
            osc.send("/meter", 0.5f) // read-only: ignored
            Thread.sleep(200)
        }
        assertEquals(listOf<List<Any?>>(listOf(0.75f), listOf(1.0f)), heard.toList())
        assertEquals(1.0, server["/synth/cutoff"]!!.number)
    }

    private class RawListener : WebSocket.Listener {
        val binary = CopyOnWriteArrayList<OscQueryCodec.Packet>()
        val text = CopyOnWriteArrayList<String>()
        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            val b = ByteArray(data.remaining()).also { data.get(it) }
            OscQueryCodec.decode(b)?.let { binary += it }
            webSocket.request(1); return null
        }
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text += data.toString(); webSocket.request(1); return null
        }
    }

    @Test
    fun `a WebSocket LISTEN client receives changes from Field and from OSC`() {
        val cutoff = server.add("/synth/cutoff", "f", listOf(0.0), listOf(0.0 to 1.0))
        server.add("/synth/res", "f", listOf(0.0))
        val l = RawListener()
        val ws = http.newWebSocketBuilder().buildAsync(URI("ws://127.0.0.1:${server.httpPort}/"), l).get()
        ws.sendText("""{"COMMAND":"LISTEN","DATA":"/synth/cutoff"}""", true).get()
        Thread.sleep(200) // the LISTEN is processed asynchronously on the server

        cutoff.set(0.5)
        assertTrue(waitFor { l.binary.size == 1 }, "no frame after a Field-side set")
        assertEquals(OscQueryCodec.Packet("/synth/cutoff", listOf(0.5f)), l.binary[0])

        server["/synth/res"]!!.set(0.9) // not listened to
        OscClient("127.0.0.1", server.oscPort).use { it.send("/synth/cutoff", 0.25f) }
        assertTrue(waitFor { l.binary.size == 2 }, "no frame after an OSC set")
        assertEquals(listOf(0.25f), l.binary[1].arguments)

        server.add("/synth/drive", "f", listOf(0.0))
        assertTrue(waitFor { l.text.any { "PATH_ADDED" in it && "/synth/drive" in it } })

        ws.sendText("""{"COMMAND":"IGNORE","DATA":"/synth/cutoff"}""", true).get()
        Thread.sleep(200)
        cutoff.set(0.1)
        Thread.sleep(200)
        assertEquals(2, l.binary.size)
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get()
    }

    @Test
    fun `Field's own client mirrors a Field server's tree and drives it`() {
        val heard = CopyOnWriteArrayList<Double>()
        server.add("/synth/cutoff", "f", listOf(0.2), listOf(0.0 to 1.0), description = "cutoff").onSet { heard += (it[0] as Number).toDouble() }
        server.add("/synth/on", "T", listOf(false))
        server.add("/count", "i", listOf(3), listOf(0.0 to 10.0))

        OscQueryRemote("127.0.0.1", server.httpPort).use { remote ->
            assertEquals("Field test", remote.name)
            assertEquals(server.oscPort, remote.oscPort)
            assertTrue(remote.supportsListen)
            assertEquals(listOf("/count", "/synth/cutoff", "/synth/on"), remote.params.map { it.path })
            val c = remote["/synth/cutoff"]
            assertEquals("f", c.type)
            assertEquals(0.2, c.number, 1e-6)
            assertEquals(listOf(0.0 to 1.0), c.range)
            assertEquals("cutoff", c.description)
            server.add("/scene", "s", listOf("dusk"), values = listOf("dawn", "dusk"))
            remote.refresh()
            assertEquals(emptyList(), remote["/scene"].range)
            assertEquals(listOf<Any?>("dawn", "dusk"), remote["/scene"].choices)

            c.set(0.6) // a Double, sent as the node's float
            assertTrue(waitFor { heard.isNotEmpty() })
            assertEquals(0.6, heard[0], 1e-6)

            val pushed = CopyOnWriteArrayList<List<Any?>>()
            remote["/count"].listen { pushed += it }
            Thread.sleep(300)
            server["/count"]!!.set(7)
            assertTrue(waitFor { pushed.isNotEmpty() }, "no LISTEN push reached the remote")
            assertEquals(listOf<Any?>(7), pushed[0])
            assertEquals(7.0, remote["/count"].number)

            var changed = 0
            remote.onNamespaceChanged { changed++ }
            server.add("/late", "s", listOf("hi"))
            assertTrue(waitFor { changed > 0 && remote["/late"].exists })
            assertEquals(listOf<Any?>("hi"), remote["/late"].fetch())
        }
    }

    @Test
    fun `the OSC codec round-trips every supported type and reads bundles`() {
        val args = listOf(1, 2.5f, "hé", true, false, null, 3.25, 1L shl 40)
        val bytes = OscQueryCodec.encode("/a/b", args)
        assertEquals(0, bytes.size % 4)
        assertEquals(OscQueryCodec.Packet("/a/b", args), OscQueryCodec.decode(bytes))

        val m1 = OscQueryCodec.encode("/x", listOf(1f)); val m2 = OscQueryCodec.encode("/y", listOf("s"))
        val bundle = java.io.ByteArrayOutputStream().apply {
            write("#bundle".toByteArray()); write(0); write(ByteArray(8))
            for (m in listOf(m1, m2)) { write(ByteBuffer.allocate(4).putInt(m.size).array()); write(m) }
        }.toByteArray()
        assertEquals(listOf("/x", "/y"), OscQueryCodec.decodeAll(bundle).map { it.address })
    }

    @Test
    fun `mDNS messages round-trip, including instance names with dots and spaces`() {
        val m = Mdns.Message(id = 7, flags = 0x8400)
        m.records += Mdns.Record.ptr("_oscjson._tcp.local", "Field 1.2._oscjson._tcp.local", 120)
        m.records += Mdns.Record.srv("Field 1.2._oscjson._tcp.local", "field-1.local", 9020, 120)
        m.records += Mdns.Record.a("field-1.local", "192.168.1.5", 120)
        val back = Mdns.Message.decode(m.encode())
        assertEquals(3, back.records.size)
        assertEquals("Field 1.2._oscjson._tcp.local", back.records[0].target)
        assertEquals(9020, back.records[1].port)
        assertEquals("field-1.local", back.records[1].target)
        assertEquals("192.168.1.5", back.records[2].address)
    }

    @Test
    fun `an advertised server can be discovered over DNS-SD`() {
        val advertised = OscQueryServer("Field discovery ${System.nanoTime()}", httpPort = 0, oscPort = 0, advertise = true)
        try {
            val found = (0 until 3).asSequence()
                .map { OscQueryRemote.discover(1500).firstOrNull { it.instance == advertised.name } }
                .firstOrNull { it != null }
            if (found == null) {
                // Multicast can be unavailable (no interface, sandbox); the codec test above still pins the wire format.
                println("DNS-SD: no multicast route here; discovery not exercised")
                return
            }
            assertEquals(advertised.httpPort, found.port)
            val remote = OscQueryRemote(found.address, found.port)
            assertNotNull(remote.hostInfo["NAME"])
            remote.close()
        } finally {
            advertised.close()
        }
    }
}
