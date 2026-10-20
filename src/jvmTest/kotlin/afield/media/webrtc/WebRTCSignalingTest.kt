package afield.media.webrtc

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val JsonObject.type get() = this["type"]!!.jsonPrimitive.content
private fun JsonObject.str(name: String) = this[name]!!.jsonPrimitive.content

/**
 * The signalling under WebRTC sources and sinks: Field's local relay speaks aaaframe's ShaderTrinket
 * messages, and the aaaframe bridge copies them verbatim with the student's session cookie.
 */
class WebRTCSignalingTest {
    init {
        // Never read or write the real ~/.afield sign-ins from a test.
        System.setProperty(
            "afield.webrtc.signIns",
            java.nio.file.Files.createTempDirectory("webrtc-sign-ins").resolve("webrtc/aaaframe-sign-ins.json").toString(),
        )
    }

    @Test
    fun aSignInIsKeptPrivatelyAndUsedForThatServer() {
        val base = "http://127.0.0.1:1/sign-in-test"
        val network = WebRTCNetwork.aaaframe(base)
        assertEquals(false, network.hasSession)
        network.signedIn("cookie-value", "alice")
        assertEquals("session=cookie-value", network.cookieHeader())

        val file = java.io.File(System.getProperty("afield.webrtc.signIns"))
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(file.toPath())))
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(file.parentFile.toPath())))
        assertEquals("cookie-value", AaaframeSignIns.session(base))
        assertEquals("alice", AaaframeSignIns.login(base))

        // A session passed explicitly wins over the remembered one.
        assertEquals("session=explicit", WebRTCNetwork.aaaframe(base, session = "explicit").cookieHeader())
        // The same server is one object, so every box sees a sign-in.
        assertTrue(WebRTCNetwork.aaaframe("$base/") === network)
    }

    @Test
    fun joiningAClassRemembersItAsTheDefault() {
        val previous = WebRTCNetwork.default
        try {
            val network = WebRTCNetwork.join("http://127.0.0.1:2/class")
            assertTrue(WebRTCNetwork.default === network)
            assertEquals("http://127.0.0.1:2/class", AaaframeSignIns.classUrl)
        } finally {
            WebRTCNetwork.default = previous
            AaaframeSignIns.classUrl = null
        }
    }
    private class Peer : SignalingPeer {
        val inbox = LinkedBlockingQueue<JsonObject>()
        override fun deliver(text: String) { inbox += Json.parseToJsonElement(text).jsonObject }
        fun next(type: String): JsonObject {
            while (true) {
                val message = assertNotNull(inbox.poll(5, TimeUnit.SECONDS), "no '$type' message arrived")
                if (message.type == type) return message
            }
        }
    }


    @Test
    fun aViewerWatchesAPublisherThroughTheLocalRelay() {
        val relay = LocalSignalingRelay { "Marc" }
        val publisher = Peer()
        val viewer = Peer()
        val publisherId = relay.connect(publisher)
        val viewerId = relay.connect(viewer)
        assertEquals("marc", publisher.next("hello").str("username"))
        assertEquals(0, viewer.next("hello")["publishers"]!!.jsonArray.size)

        relay.receive(publisherId, """{"type":"publish-start","suffix":" Field Orbit! "}""")
        assertEquals("marc/field-orbit", publisher.next("publish-ready").str("username"))
        assertEquals("marc/field-orbit", viewer.next("publisher-online").str("username"))
        assertEquals(listOf("marc/field-orbit"), relay.publishers())

        // A late joiner hears the publisher in its hello, as a browser ShaderTrinket would.
        val late = Peer()
        relay.connect(late)
        assertEquals("marc/field-orbit", late.next("hello")["publishers"]!!.jsonArray.single().jsonPrimitive.content)

        relay.receive(viewerId, """{"type":"watch","target":"@Marc/Field-Orbit","requestId":"r1"}""")
        val watch = publisher.next("watch")
        assertEquals(viewerId, watch.str("fromConnectionId"))
        assertEquals("r1", watch.str("requestId"))

        relay.receive(publisherId, """{"type":"offer","toConnectionId":"$viewerId","requestId":"r1","sdp":{"type":"offer","sdp":"v=0"}}""")
        val offer = viewer.next("offer")
        assertEquals(publisherId, offer.str("fromConnectionId"))
        assertEquals("marc/field-orbit", offer.str("from"))
        assertEquals("v=0", offer["sdp"]!!.jsonObject.str("sdp"))

        relay.receive(viewerId, """{"type":"candidate","toConnectionId":"$publisherId","requestId":"r1","candidate":{"candidate":"candidate:1"}}""")
        assertEquals(viewerId, publisher.next("candidate").str("fromConnectionId"))

        relay.receive(viewerId, """{"type":"watch","target":"nobody","requestId":"r2"}""")
        assertEquals("r2", viewer.next("publisher-unavailable").str("requestId"))

        relay.disconnect(publisherId)
        assertEquals("marc/field-orbit", viewer.next("publisher-offline").str("username"))
        assertTrue(relay.publishers().isEmpty())
    }

    @Test
    fun publishingTheSameNameAgainReplacesTheEarlierPublisher() {
        val relay = LocalSignalingRelay { "student" }
        val first = Peer()
        val second = Peer()
        val a = relay.connect(first)
        val b = relay.connect(second)
        relay.receive(a, """{"type":"publish-start"}""")
        first.next("publish-ready")
        relay.receive(b, """{"type":"publish-start","suffix":""}""")
        assertEquals("student", first.next("publisher-offline").str("username"))
        assertEquals(true, second.next("publish-ready")["replaced"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("student"), relay.publishers())
    }

    @Test
    fun namesNormaliseAsAaaframeNormalisesThem() {
        assertEquals("alice/left", normalizePublishName("@Alice/Left"))
        assertEquals("a-b/x.y", normalizePublishName(" A_B /x.y/ "))
        assertEquals("", normalizePublishName("@/"))
        assertEquals("field-orbit", normalizePublishSuffix("/Field Orbit/"))
        assertEquals(48, normalizePublishSuffix("x".repeat(100)).length)
    }

    @Test
    fun twoPagesMeetThroughFieldsSignalingRoute() = testApplication {
        application {
            install(ServerWebSockets)
            routing { installWebRTCSignalingRoutes() }
        }
        val client = createClient { install(ClientWebSockets) }
        val publisher = client.webSocketSession("/ws/webrtc-signaling?network=local")
        val viewer = client.webSocketSession("/ws/webrtc-signaling?network=local")
        val publisherHello = publisher.nextJson("hello")
        viewer.nextJson("hello")
        publisher.send("""{"type":"publish-start","suffix":"route"}""")
        val name = publisher.nextJson("publish-ready").str("username")
        assertEquals(name, viewer.nextJson("publisher-online").str("username"))
        viewer.send("""{"type":"watch","target":"$name","requestId":"q"}""")
        val watch = publisher.nextJson("watch")
        publisher.send("""{"type":"offer","toConnectionId":"${watch.str("fromConnectionId")}","requestId":"q","sdp":{"type":"offer","sdp":"x"}}""")
        assertEquals(publisherHello.str("connectionId"), viewer.nextJson("offer").str("fromConnectionId"))
        publisher.close()
        assertEquals(name, viewer.nextJson("publisher-offline").str("username"))
        viewer.close()
    }

    @Test
    fun theAaaframeBridgeForwardsVerbatimWithTheSessionCookie() {
        // A stand-in aaaframe: the local relay behind /ws/shadertrinket-signaling, refusing a
        // missing login with 4401 exactly as websocket_login_required does.
        val upstreamRelay = LocalSignalingRelay { "student" }
        val cookies = LinkedBlockingQueue<String>()
        val upstream = embeddedServer(Netty, port = 0, host = "0.0.0.0") {
            install(ServerWebSockets)
            routing {
                webSocket("/ws/shadertrinket-signaling") {
                    val cookie = call.request.headers["Cookie"].orEmpty()
                    cookies += cookie
                    if (cookie != "session=s3cret") { close(CloseReason(4401, "")); return@webSocket }
                    val out = Channel<String>(Channel.UNLIMITED)
                    val writer = launch { for (t in out) send(Frame.Text(t)) }
                    val id = upstreamRelay.connect(object : SignalingPeer { override fun deliver(text: String) { out.trySend(text) } })
                    try { for (f in incoming) if (f is Frame.Text) upstreamRelay.receive(id, f.readText()) }
                    finally { upstreamRelay.disconnect(id); writer.cancel() }
                }
            }
        }.start(wait = false)
        try {
            val port = kotlinx.coroutines.runBlocking { upstream.engine.resolvedConnectors().single().port }
            val good = WebRTCNetwork.aaaframe("http://127.0.0.1:$port", session = "s3cret")
            val bad = WebRTCNetwork.aaaframe("http://localhost:$port", session = null)
            testApplication {
                application {
                    install(ServerWebSockets)
                    routing { installWebRTCSignalingRoutes() }
                }
                val client = createClient { install(ClientWebSockets) }
                val page = client.webSocketSession("/ws/webrtc-signaling?network=${java.net.URLEncoder.encode(good.id, "UTF-8")}")
                val hello = page.nextJson("hello")
                assertEquals("student", hello.str("username"))
                assertEquals("session=s3cret", cookies.poll(5, TimeUnit.SECONDS))
                page.send("""{"type":"publish-start","suffix":"field"}""")
                assertEquals("student/field", page.nextJson("publish-ready").str("username"))
                assertEquals(listOf("student/field"), upstreamRelay.publishers())
                page.close()

                // Without the cookie aaaframe closes the socket, and the page is told why.
                val refused = client.webSocketSession("/ws/webrtc-signaling?network=${java.net.URLEncoder.encode(bad.id, "UTF-8")}")
                val error = refused.nextJson("field-error").str("error")
                assertEquals(LOGIN_REFUSED, error)
                refused.close()
            }
        } finally {
            upstream.stop(100, 500)
        }
    }

    private suspend fun DefaultClientWebSocketSession.nextJson(type: String): JsonObject = withTimeout(10_000) {
        var message: JsonObject? = null
        while (message == null) {
            val frame = incoming.receive()
            if (frame !is Frame.Text) continue
            message = Json.parseToJsonElement(frame.readText()).jsonObject.takeIf { it.type == type }
        }
        message
    }
}
