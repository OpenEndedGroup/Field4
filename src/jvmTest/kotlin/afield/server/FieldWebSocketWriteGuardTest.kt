package afield.server

import afield.FIELD_API_REQUIRE_SAME_SITE_ENV
import afield.FIELD_API_REQUIRE_SAME_SITE_PROPERTY
import afield.refuseCrossSiteFieldWebSocket
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Route-level proof that the opt-in write guard also governs command WebSocket upgrades. */
class FieldWebSocketWriteGuardTest {

    @Test
    fun theDefaultStillAllowsCrossSiteBrowserUpgrades() = guardedWebSocketApp(on = false) {
        val session = webSocketSession {
            commandRoute()
            fromEvilPage()
        }
        assertEquals("connected", (session.incoming.receive() as Frame.Text).readText())
        session.close()
    }

    @Test
    fun aCrossSiteBrowserUpgradeIsClosedWhenTheGuardIsOn() = guardedWebSocketApp(on = true) {
        val session = webSocketSession {
            commandRoute()
            fromEvilPage()
        }
        val reason = withTimeout(5_000) { session.closeReason.await() }
        assertNotNull(reason)
        assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason.code)
        assertTrue(FIELD_API_REQUIRE_SAME_SITE_ENV in reason.message, "was: ${reason.message}")
    }

    @Test
    fun fieldsOwnBrowserOriginStillConnects() = guardedWebSocketApp(on = true) {
        val session = webSocketSession {
            commandRoute()
            fromFieldsOwnPage()
        }
        assertEquals("connected", (session.incoming.receive() as Frame.Text).readText())
        session.close()
    }

    @Test
    fun aNativeClientWithoutBrowserOriginHeadersStillConnects() = guardedWebSocketApp(on = true) {
        val session = webSocketSession { commandRoute() }
        assertEquals("connected", (session.incoming.receive() as Frame.Text).readText())
        session.close()
    }

    private fun guardedWebSocketApp(on: Boolean, block: suspend HttpClient.() -> Unit) {
        val previous = System.getProperty(FIELD_API_REQUIRE_SAME_SITE_PROPERTY)
        System.setProperty(FIELD_API_REQUIRE_SAME_SITE_PROPERTY, if (on) "true" else "false")
        try {
            testApplication {
                application {
                    install(ServerWebSockets)
                    routing {
                        webSocket("/command") {
                            if (refuseCrossSiteFieldWebSocket("/command")) return@webSocket
                            send("connected")
                        }
                    }
                }
                createClient { install(ClientWebSockets) }.block()
            }
        } finally {
            if (previous == null) System.clearProperty(FIELD_API_REQUIRE_SAME_SITE_PROPERTY)
            else System.setProperty(FIELD_API_REQUIRE_SAME_SITE_PROPERTY, previous)
        }
    }

    private fun HttpRequestBuilder.fromEvilPage() {
        header(HttpHeaders.Host, "localhost")
        header(HttpHeaders.Origin, "https://somewhere-else.example")
        header(SEC_FETCH_SITE_HEADER, "cross-site")
    }

    private fun HttpRequestBuilder.commandRoute() {
        url("/command")
    }

    private fun HttpRequestBuilder.fromFieldsOwnPage() {
        header(HttpHeaders.Host, "localhost")
        header(HttpHeaders.Origin, "http://localhost")
        header(SEC_FETCH_SITE_HEADER, "same-origin")
    }
}
