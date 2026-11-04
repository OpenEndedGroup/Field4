package afield.graphics.vulkan.targets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StereoEyeCopyTest {
    @Test
    fun arrayEyesCopyDistinctLayersWithoutHorizontalOffset() {
        assertEquals(StereoEyeCopy(0, 0), StereoEyeCopy.source(0, 2400, layered = true))
        assertEquals(StereoEyeCopy(1, 0), StereoEyeCopy.source(1, 2400, layered = true))
    }

    @Test
    fun packedEyesCopyDistinctHorizontalRegionsFromOneLayer() {
        assertEquals(StereoEyeCopy(0, 0), StereoEyeCopy.source(0, 2400, layered = false))
        assertEquals(StereoEyeCopy(0, 2400), StereoEyeCopy.source(1, 2400, layered = false))
    }

    @Test
    fun invalidEyesAndDimensionsAreRejected() {
        assertFailsWith<IllegalArgumentException> { StereoEyeCopy.source(2, 2400, true) }
        assertFailsWith<IllegalArgumentException> { StereoEyeCopy.source(0, 0, true) }
    }
}
