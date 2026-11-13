package afield.app.server

import afield.graphics.three.ThreeSceneBuilder
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `GET /extension-web/{...}` and the content hash `three { tools(…) }` appends: a module in a
 * registered folder is served, never cached, and an edit to it changes the URL a scene asks for.
 */
class ExtensionWebTest {

    private fun toolUrl(path: String): String = ThreeSceneBuilder(12).apply { tools(path) }.toolModules.single()

    @Test
    fun aRegisteredFoldersModuleIsServedUnderANewHashAfterAnEdit() {
        val root = createTempDirectory("extension-web-test-").toFile()
        val stop = ExtensionWeb.register(root)
        try {
            val name = "extension_web_test_${System.nanoTime()}.js"
            val module = File(root, name)
            module.writeText("export default () => ({ id: 'first' })\n")
            val path = ExtensionWeb.PREFIX + name
            val first = toolUrl(path)
            assertTrue(first.startsWith("$path?v="), first)
            assertEquals(first, ExtensionWeb.url(name), "url() and tools() agree")
            assertEquals(first, toolUrl(path), "unchanged bytes, unchanged URL")

            testApplication {
                application { routing { installExtensionWebRoute() } }
                val served = client.get(first)
                assertEquals(HttpStatusCode.OK, served.status)
                assertEquals("no-store", served.headers[HttpHeaders.CacheControl])
                assertTrue("javascript" in served.headers[HttpHeaders.ContentType].orEmpty(), "${served.headers[HttpHeaders.ContentType]}")
                assertEquals(module.readText(), served.bodyAsText())

                module.writeText("export default () => ({ id: 'second' })\n")
                val second = toolUrl(path)
                assertNotEquals(first, second, "an edit must change the module URL")
                assertTrue(second.startsWith("$path?v="), second)
                assertTrue("'second'" in client.get(second).bodyAsText())

                File(root.parentFile, "$name.secret").writeText("outside")
                for (probe in listOf("missing.js", "%2e%2e/$name.secret", "..%2f$name.secret"))
                    assertEquals(HttpStatusCode.NotFound, client.get(ExtensionWeb.PREFIX + probe).status, probe)
                File(root.parentFile, "$name.secret").delete()
            }
        } finally {
            stop()
            root.deleteRecursively()
        }
        assertEquals(ExtensionWeb.PREFIX + "gone.js", toolUrl(ExtensionWeb.PREFIX + "gone.js"), "an unserved path is left alone")
    }

    @Test
    fun onlyPlainExtensionWebPathsAreVersioned() {
        assertEquals("/threejs_tools_demo.js", ExtensionWeb.versioned("/threejs_tools_demo.js"))
        assertEquals("/extension-web/x.js?v=pinned", ExtensionWeb.versioned("/extension-web/x.js?v=pinned"))
    }
}
