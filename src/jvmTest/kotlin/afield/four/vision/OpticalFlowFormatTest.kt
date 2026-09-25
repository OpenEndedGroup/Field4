package afield.four.vision

import kotlin.test.Test
import kotlin.test.assertEquals

class OpticalFlowFormatTest {
    @Test fun signedLanesRetainFractionAndInputPixelUnits() {
        // +12.5 pixels right, -8.25 pixels up, independent of the chosen output grid.
        val flow=OpticalFlow.decodePacked(0xfef80190.toInt())
        assertEquals(12.5f,flow.x);assertEquals(-8.25f,flow.y)
    }
    @Test fun signedEndpointsDoNotBecomeHugePositiveMotion() {
        val flow=OpticalFlow.decodePacked(0x7fff8000)
        assertEquals(-1024f,flow.x);assertEquals(1023.96875f,flow.y)
    }
}
