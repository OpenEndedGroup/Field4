package afield.app.server

import afield.app.server.fieldApiRequiresSameSite
import afield.app.server.fieldServerBindHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldServerDefaultsTest {
    @Test
    fun desktopIsLocalAndBrowserProtectedWithoutConfiguration() {
        assertEquals("127.0.0.1", fieldServerBindHost(null))
        assertEquals("127.0.0.1", fieldServerBindHost("  "))
        assertTrue(fieldApiRequiresSameSite(null, null))
        assertTrue(fieldApiRequiresSameSite(null, ""))
    }

    @Test
    fun remoteClientsAndCrossOriginToolsRequireExplicitConfiguration() {
        assertEquals("0.0.0.0", fieldServerBindHost("0.0.0.0"))
        assertFalse(fieldApiRequiresSameSite(null, "0"))
        assertTrue(fieldApiRequiresSameSite("true", "0"))
        assertFalse(fieldApiRequiresSameSite("false", "1"))
    }
}
