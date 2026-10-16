package afield.io.osc

import afield.document.graph.Box
import afield.runtime.lifecycle.gather
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An OSC handler is a keyed registration of the box that made it: stop keeps it, retiring the box removes it. */
class OscHandlerLifecycleTest {
    @Test
    fun aHandlerSurvivesStopAndGoesWithItsBox() {
        val server = OscServer(localPort = 57391, autoStart = false)
        try {
            val box = Box("osc-owner")
            Box.withBoxContextNow(box) {
                server.on("/a") { _ -> gather<OscServer.Message> { } }
                server.on("/a") { _ -> gather<OscServer.Message> { } }   // a re-run, in place
            }
            box.runScopes.stopAll()
            assertTrue(server.hasHandler("/a"), "stopping the box keeps its handler")
            box.runScopes.closeAll()
            assertFalse(server.hasHandler("/a"), "retiring the box removes it")
        } finally {
            server.close()
        }
    }

    @Test
    fun anotherBoxThatTookThePatternKeepsIt() {
        val server = OscServer(localPort = 57392, autoStart = false)
        try {
            val first = Box("osc-first")
            val second = Box("osc-second")
            Box.withBoxContextNow(first) { server.on("/shared") { _ -> gather<OscServer.Message> { } } }
            Box.withBoxContextNow(second) { server.on("/shared") { _ -> gather<OscServer.Message> { } } }
            first.runScopes.closeAll()
            assertTrue(server.hasHandler("/shared"), "the box that ran last owns the slot")
        } finally {
            server.close()
        }
    }
}
