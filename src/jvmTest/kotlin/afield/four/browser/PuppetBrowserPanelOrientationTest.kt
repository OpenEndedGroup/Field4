package afield.four.browser

import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PuppetBrowserPanelOrientationTest {
    // Geometry must not call a browser or require GPU/native resources.
    private fun panel(): PuppetBrowserPanel {
        val browser = Proxy.newProxyInstance(PuppetBrowser::class.java.classLoader,
            arrayOf(PuppetBrowser::class.java)) { _, method, _ -> error("Unexpected browser call: ${method.name}") } as PuppetBrowser
        return PuppetBrowserPanel(browser, 0.8f, 0.45f).also {
            PuppetBrowserVrPanel.placeInitialPanel(it, Vector3f(), 1.7f)
        }
    }

    @Test fun browserLeftToRightProjectsLeftToRightInBothEyes() {
        val panel = panel()
        for (eyeX in listOf(-0.032f, 0.032f)) {
            val view = Matrix4f().lookAt(Vector3f(eyeX, 0f, 0f), Vector3f(0f, 0f, 1f), Vector3f(0f, 1f, 0f))
            val projection = Matrix4f().setPerspective(1f, 1f, 0.01f, 100f)
            val mvp = panel.panelMvp(view, projection)
            val left = mvp.transform(Vector4f(-0.8f, 0f, 0f, 1f))
            val right = mvp.transform(Vector4f(0.8f, 0f, 0f, 1f))
            assertTrue(left.x / left.w < right.x / right.w, "Browser text must not mirror in eye $eyeX")
        }
    }

    @Test fun panelFrontFacesTheViewer() {
        val panel = panel()
        val normal = panel.model.transformDirection(Vector3f(0f, 0f, 1f))
        val center = panel.model.transformPosition(Vector3f())
        assertTrue(normal.dot(Vector3f(center).negate()) > 0f)
    }

    @Test fun displayedTextureCoordinatesAndPointerHitsAgree() {
        val panel = panel()
        for ((u, v) in listOf(0.1 to 0.2, 0.8 to 0.7)) {
            val world = panel.model.transformPosition(Vector3f(
                ((u - 0.5) * 2 * panel.halfWidth).toFloat(),
                ((0.5 - v) * 2 * panel.halfHeight).toFloat(), 0f))
            for (eyeX in listOf(-0.032f, 0.032f)) {
                val eye = Vector3f(eyeX, 0f, 0f)
                val hit = assertNotNull(panel.raycast(eye, Vector3f(world).sub(eye).normalize()))
                assertTrue(hit.onPanel)
                assertEquals(u, hit.u, 1e-5)
                assertEquals(v, hit.v, 1e-5)
            }
        }
    }
}
