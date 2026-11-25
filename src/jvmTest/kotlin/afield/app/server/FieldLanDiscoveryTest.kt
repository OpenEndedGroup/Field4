package afield.app.server

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FieldLanDiscoveryTest {
    @Test
    fun loopbackCanBrowseAndCrossSiteRequestsAreRefused() {
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            install(ContentNegotiation) { json() }
            routing { FieldLanDiscovery().installRoutes(this) }
        }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            val uri = URI.create("http://127.0.0.1:$port/api/remote-field/lan")
            HttpClient.newHttpClient().use { client ->
                val local = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build()
                val foreign = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                    .header("Origin", "https://other.example")
                    .header("Sec-Fetch-Site", "cross-site")
                    .build()
                val allowed = client.send(local, HttpResponse.BodyHandlers.ofString())
                assertEquals(200, allowed.statusCode())
                assertIs<JsonArray>(Json.parseToJsonElement(allowed.body()))
                assertEquals(403, client.send(foreign, HttpResponse.BodyHandlers.discarding()).statusCode())
            }
        } finally {
            server.stop(0, 2000)
        }
    }
}
