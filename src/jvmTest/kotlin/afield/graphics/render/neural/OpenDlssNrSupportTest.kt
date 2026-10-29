package afield.graphics.render.neural

import kotlin.test.Test
import kotlin.test.assertEquals

/** What [OpenDlssNrSupport] says when the device was created before the adapter existed. */
class OpenDlssNrSupportTest {
    @Test
    fun libraryBuiltAfterDeviceAsksForRendererRestart() {
        assertEquals(OpenDlssNrSupport.BUILT_AFTER_DEVICE,
            OpenDlssNrSupport.deviceFailureReason(OpenDlssNrNative.ABSENT, libraryPresentNow = true))
    }

    @Test
    fun libraryStillAbsentKeepsBuildInstruction() {
        assertEquals(OpenDlssNrNative.ABSENT,
            OpenDlssNrSupport.deviceFailureReason(OpenDlssNrNative.ABSENT, libraryPresentNow = false))
    }

    @Test
    fun otherDeviceFailuresPassThrough() {
        assertEquals("cooperative matrix unsupported",
            OpenDlssNrSupport.deviceFailureReason("cooperative matrix unsupported", libraryPresentNow = true))
    }
}
