package afield.server

import afield.installFieldApiCors
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /api/docs-source-file`: the local copy of a repo file the docs link to GitHub, for Field's
 * docs pane. Its guard is the revision pass's (loopback + same-site, hatches shut), so the same
 * control as `DocsPassRouteTest` applies; its path checks are pinned directly on a temp checkout.
 */
class DocsSourceFileRouteTest {

    private val everyHatchOpen = DocsAccessPolicy(allowRemoteAnnotations = true, allowCrossSiteAnnotations = true)

    @Test
    fun theRemoteHatchThatOpensAnnotationsDoesNotOpenTheSourceRoute() = withCheckout { checkout ->
        val dir = createTempDirectory("docs-source-route-test-")
        try {
            withDocsAnnotationStoreAt(dir.resolve("docs-annotations.json")) {
                testApplication {
                    application {
                        install(ContentNegotiation) { json() }
                        installFieldApiCors()
                        routing {
                            installDocsAnnotationRoutes(everyHatchOpen, DocsMirror(DocsMirrorSource.LOCAL, Path.of("/no-such-docs-build")))
                            route("/test") { installDocsSourceFileRoute(everyHatchOpen, checkout) }
                        }
                    }
                    val control = client.get("/api/docs-annotations") { fromThePage() }
                    assertEquals(HttpStatusCode.OK, control.status, "control: the hatch lets annotations in from this peer")
                    for (path in listOf("/api/docs-source-file?path=src/A.kt", "/test/api/docs-source-file?path=src/A.kt")) {
                        val response = client.get(path) { fromThePage() }
                        assertEquals(HttpStatusCode.Forbidden, response.status, path)
                        assertTrue("loopback-only" in response.bodyAsText(), response.bodyAsText())
                    }
                }
            }
        } finally {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun aFileUnderALinkedRootIsServed() = withCheckout { checkout ->
        for (path in listOf("src/A.kt", "tools/b.py", "server/README.md", "public/docs/c.md")) {
            assertNotNull(docsSourceFile(checkout, path), path)
        }
    }

    @Test
    fun everythingElseIsRefused() = withCheckout { checkout ->
        for (path in listOf(
            "", "src", "src/", "dev_docs/NOTES.md", "config/secret.p12", "/etc/passwd", "src/../dev_docs/NOTES.md",
            "src/./A.kt", "src//A.kt", "src\\A.kt", "src/missing.kt", "src/dir", "src/escape.kt", "src/A.kt\u0000",
        )) {
            assertNull(docsSourceFile(checkout, path), "must refuse '$path'")
        }
    }

    @Test
    fun onlyUtf8TextIsText() {
        assertEquals("fun a() = 1\n", docsSourceText("fun a() = 1\n".toByteArray()))
        assertNull(docsSourceText(byteArrayOf(0x50, 0x00, 0x51)))
        assertNull(docsSourceText(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    private fun io.ktor.client.request.HttpRequestBuilder.fromThePage() {
        header(HttpHeaders.Host, "localhost")
        header(HttpHeaders.Origin, "http://localhost")
        header(SEC_FETCH_SITE_HEADER, "same-origin")
    }

    private fun withCheckout(block: suspend (Path) -> Unit) {
        val checkout = createTempDirectory("docs-source-checkout-")
        val outside = createTempDirectory("docs-source-outside-")
        try {
            checkout.resolve("src/dir").createDirectories()
            checkout.resolve("src/A.kt").writeText("fun a() = 1\n")
            checkout.resolve("tools").createDirectories(); checkout.resolve("tools/b.py").writeText("print(1)\n")
            checkout.resolve("server").createDirectories(); checkout.resolve("server/README.md").writeText("# s\n")
            checkout.resolve("public/docs").createDirectories(); checkout.resolve("public/docs/c.md").writeText("c\n")
            checkout.resolve("dev_docs").createDirectories(); checkout.resolve("dev_docs/NOTES.md").writeText("n\n")
            checkout.resolve("config").createDirectories(); checkout.resolve("config/secret.p12").writeBytes(byteArrayOf(1, 2))
            outside.resolve("secret.kt").writeText("val secret = 1\n")
            Files.createSymbolicLink(checkout.resolve("src/escape.kt"), outside.resolve("secret.kt"))
            kotlinx.coroutines.runBlocking { block(checkout) }
        } finally {
            for (dir in listOf(checkout, outside)) {
                Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }
}
