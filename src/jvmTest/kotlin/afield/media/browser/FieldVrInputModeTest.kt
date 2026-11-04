package afield.media.browser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class FieldVrInputModeTest {
    @Test fun automaticModePreservesWiVRnDefault() {
        assertTrue(FieldVrWorkspace.defaultHeadsetInput(true, null, "Linux"))
        assertFalse(FieldVrWorkspace.defaultHeadsetInput(false, "auto", "Linux"))
        assertFalse(FieldVrWorkspace.defaultHeadsetInput(true, "auto", "Mac OS X"))
    }

    @Test fun alvrUsesDesktopFocusEvenWithLinuxOpenXR() {
        assertFalse(FieldVrWorkspace.defaultHeadsetInput(true, "desktop", "Linux"))
    }

    @Test fun invalidOrUnavailableModesFailBeforeCreatingBrowser() {
        assertFailsWith<IllegalStateException> { FieldVrWorkspace.defaultHeadsetInput(true, "typo", "Linux") }
        assertFailsWith<IllegalArgumentException> { FieldVrWorkspace.defaultHeadsetInput(false, "wivrn", "Linux") }
    }
}
