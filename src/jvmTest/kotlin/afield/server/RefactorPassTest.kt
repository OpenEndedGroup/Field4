package afield.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefactorPassTest {
    @Test fun actionsAreStructuredAndRequireSpecificReviewItems() {
        assertEquals(listOf("create", "--plan", "/tmp/a plan"), refactorPassArguments(RefactorPassAction("create", plan = "/tmp/a plan")))
        assertNull(refactorPassArguments(RefactorPassAction("create", plan = "../elsewhere")))
        assertNull(refactorPassArguments(RefactorPassAction("land")))
        assertNull(refactorPassArguments(RefactorPassAction("decide", item = "../file", state = "preserve", reason = "yes")))
        assertNull(refactorPassArguments(RefactorPassAction("decide", item = "a".repeat(24), state = "done", reason = "yes")))
        assertNull(refactorPassArguments(RefactorPassAction("decide", item = "a".repeat(24), state = "preserve")))
        val args = refactorPassArguments(RefactorPassAction("decide", item = "a".repeat(24), state = "preserve", reason = "$(touch /tmp/no); history"))!!
        assertEquals("$(touch /tmp/no); history", args.last())
    }

    @Test fun batchTerminalUsesThePassLeaseAndNeverTheDocsLandingBrief() {
        val checkout = Files.createTempDirectory("refactor-terminal-")
        try {
            assertTrue(refactorTerminalPlan(TerminalLaunch("refactor-batch", batch = "prose"), checkout, "/bin/uv").isFailure)
            Files.createDirectories(checkout.resolve(".field-backdoor/refactor-pass"))
            Files.writeString(checkout.resolve(".field-backdoor/refactor-pass/current.json"), "{}")
            val result = refactorTerminalPlan(TerminalLaunch("refactor-batch", "Preserve old names; ask me", "prose"), checkout, "/bin/uv").getOrThrow()
            assertEquals(checkout, result.directory)
            assertEquals("/bin/uv", result.argv.first())
            assertTrue("tools/refactor_pass.py" in result.argv)
            assertEquals(listOf("agent", "--batch", "prose", "--direction", "Preserve old names; ask me"), result.argv.takeLast(5))
            assertTrue(refactorTerminalPlan(TerminalLaunch("refactor-batch", batch = "../private"), checkout, "/bin/uv").isFailure)
            assertTrue(refactorTerminalPlan(TerminalLaunch("refactor-batch", "\u001b[2J", "prose"), checkout, "/bin/uv").isFailure)
        } finally { checkout.toFile().deleteRecursively() }
    }

    @Test fun privateSourceAndActionsKeepTheDocsPassBoundaryEvenWhenAnnotationHatchesAreOpen() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { installRefactorPassRoutes(DocsAccessPolicy(allowRemoteAnnotations = true, allowCrossSiteAnnotations = true)) }
        }
        val get = client.get("/api/refactor-pass") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
        }
        assertEquals(HttpStatusCode.Forbidden, get.status)
        val post = client.post("/api/refactor-pass") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.Json)
            setBody("""{"action":"create","plan":"/tmp/secret"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, post.status)
    }
}
