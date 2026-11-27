package afield.ui.output

import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Cookies as Electron reports them, read into [WebCookie]. */
class WebCookieTest {

    @Test
    fun aPersistentCookieKeepsItsExpiry() {
        val cookie = WebCookie.from(Json.parseToJsonElement("""
            {"name":"_gh_sess","value":"abc","domain":".github.com","path":"/","secure":true,"httpOnly":true,
             "session":false,"expirationDate":1795771110.5,"sameSite":"lax","hostOnly":false}
        """))!!
        assertEquals("_gh_sess", cookie.name)
        assertEquals(".github.com", cookie.domain)
        assertEquals(true, cookie.secure)
        assertEquals(true, cookie.httpOnly)
        assertEquals("lax", cookie.sameSite)
        assertEquals(Instant.ofEpochMilli(1795771110500), cookie.expires)
    }

    @Test
    fun aSessionCookieHasNoExpiryAndMissingFieldsDefault() {
        val cookie = WebCookie.from(Json.parseToJsonElement("""{"name":"who","value":"A","domain":"httpbin.org","session":true}"""))!!
        assertNull(cookie.expires)
        assertEquals("/", cookie.path)
        assertEquals("unspecified", cookie.sameSite)
        assertNull(WebCookie.from(Json.parseToJsonElement("""{"value":"no name"}""")))
    }
}
