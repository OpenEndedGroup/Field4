package afield.server

import afield.installFieldApiCors
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The revision pass's routes have two checks and no switch or token, so both annotation hatches
 * must stay shut for them. Ktor's test engine reports its peer as `localhost`, which is not a
 * literal loopback address — so with the hatches honoured it would be let in, and with them shut
 * it is refused. The annotation route answering the same request is the control: it proves the
 * hatch really was open, and that the refusal belongs to the pass.
 */
class DocsPassRouteTest {

    private val everyHatchOpen = DocsAccessPolicy(allowRemoteAnnotations = true, allowCrossSiteAnnotations = true)

    @Test
    fun theRemoteHatchThatOpensAnnotationsDoesNotOpenThePass() = passApp {
        val control = get("/api/docs-annotations") { fromThePage() }
        assertEquals(HttpStatusCode.OK, control.status, "control: the hatch lets annotations in from this peer")

        val launch = post("/api/docs-improvement/pass") {
            fromThePage(); contentType(ContentType.Application.Json); setBody("""{"action":"create"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, launch.status)
        assertTrue("loopback-only" in launch.bodyAsText(), launch.bodyAsText())

        for (path in listOf("/api/docs-improvement/pass", "/api/docs-improvement/pass/diff", "/docs-pass-site/index.html")) {
            assertEquals(HttpStatusCode.Forbidden, get(path) { fromThePage() }.status, path)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.fromThePage() {
        header(HttpHeaders.Host, "localhost")
        header(HttpHeaders.Origin, "http://localhost")
        header(SEC_FETCH_SITE_HEADER, "same-origin")
    }

    private fun passApp(block: suspend io.ktor.client.HttpClient.() -> Unit) {
        val dir = createTempDirectory("docs-pass-route-test-")
        try {
            withDocsAnnotationStoreAt(dir.resolve("docs-annotations.json")) {
                testApplication {
                    application {
                        install(ContentNegotiation) { json() }
                        installFieldApiCors()
                        routing { installDocsAnnotationRoutes(everyHatchOpen, DocsMirror(DocsMirrorSource.LOCAL, Path.of("/no-such-docs-build"))) }
                    }
                    client.block()
                }
            }
        } finally {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
