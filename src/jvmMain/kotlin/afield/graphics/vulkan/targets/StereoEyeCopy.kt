package afield.graphics.vulkan.targets

/** Source subresource for the copy into one runtime-owned, single-layer eye swapchain. */
internal data class StereoEyeCopy(val layer: Int, val x: Int) {
    companion object {
        fun source(eye: Int, eyeWidth: Int, layered: Boolean): StereoEyeCopy {
            require(eye in 0..1 && eyeWidth > 0)
            return if (layered) StereoEyeCopy(eye, 0) else StereoEyeCopy(0, eye * eyeWidth)
        }
    }
}
