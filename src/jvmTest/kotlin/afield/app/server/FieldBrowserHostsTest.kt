package afield.app.server

import java.nio.file.Files
import kotlin.test.*

class FieldBrowserHostsTest {
    private val hosts = setOf("a0.example.ts.net:8443")

    @Test fun `registered private DNS authority permits same origin assets and websocket shape only`() {
        assertTrue(isSameSiteRequest("same-origin", "https://a0.example.ts.net:8443", "a0.example.ts.net:8443", hosts))
        assertTrue(isSameSiteRequest(null, "https://a0.example.ts.net:8443", "a0.example.ts.net:8443", hosts))
        assertFalse(isSameSiteRequest("cross-site", "https://evil.example", "a0.example.ts.net:8443", hosts))
        assertFalse(isSameSiteRequest(null, "https://evil.example", "a0.example.ts.net:8443", hosts))
        assertFalse(isSameSiteRequest("same-origin", null, "a0.example.ts.net:443", hosts))
        assertFalse(isSameSiteRequest("same-origin", null, "other.example.ts.net:8443", hosts))
        assertFalse(isSameSiteRequest("same-origin", null, "a0.example.ts.net:8443", emptySet()))
        assertFalse(isAgentRouteHost("a0.example.ts.net:8443"))
    }

    @Test fun `a registered host without a port matches that host on any port and nothing else`() {
        val portless = setOf("a0.example.ts.net")
        assertTrue(isSameSiteRequest("same-origin", "https://a0.example.ts.net:9092", "a0.example.ts.net:9092", portless))
        assertTrue(isSameSiteRequest("same-origin", "https://a0.example.ts.net", "a0.example.ts.net", portless))
        assertFalse(isSameSiteRequest("same-origin", null, "b1.example.ts.net:9092", portless))
        assertFalse(isSameSiteRequest("same-origin", null, "a0.example.ts.net.evil.example:9092", portless))
        assertFalse(isSameSiteRequest("cross-site", "https://evil.example", "a0.example.ts.net:9092", portless))
    }

    @Test fun `file and explicit environment register exact authorities and malformed files fail closed`() {
        val path = Files.createTempFile("field-browser-hosts", ".json")
        try {
            Files.writeString(path, "[\"a0.example.ts.net:8443\"]")
            assertEquals(hosts + "field.example:9443", readFieldBrowserHosts(path, "FIELD.example:9443"))
            Files.writeString(path, "{bad json")
            assertEquals(emptySet(),readFieldBrowserHosts(path))
            Files.writeString(path, "[3,\"a0.example.ts.net:8443\"]")
            assertEquals(emptySet(),readFieldBrowserHosts(path))
            Files.writeString(path," ".repeat(16_385))
            assertEquals(emptySet(),readFieldBrowserHosts(path))
            for (value in listOf("https://a0.example.ts.net:8443", "*.ts.net:8443", "evil@a0.example.ts.net:8443",
                "a0.example.ts.net:0", "a0.example.ts.net:", "a0..example.ts.net", "a0.example.ts.net/path")) {
                assertFalse(validFieldDnsAuthority(value), value)
            }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun `a host added to the file while Field runs is honoured without a restart`() {
        val dir = Files.createTempDirectory("field-browser-hosts")
        val path = dir.resolve("browser-hosts.json")
        try {
            val file = FieldBrowserHostsFile(path, null)
            assertEquals(emptySet(), file.current())
            Files.writeString(path, "[\"a0.example.ts.net:8443\"]")
            assertEquals(hosts, file.current())
            Files.writeString(path, "[\"a0.example.ts.net:8443\", \"b1.example.ts.net\"]")
            assertEquals(hosts + "b1.example.ts.net", file.current())
            Files.delete(path)
            assertEquals(emptySet(), file.current())
        } finally { Files.deleteIfExists(path); Files.deleteIfExists(dir) }
    }
}
