package afield.four.texture.video

import kotlin.test.Test
import kotlin.test.assertEquals
import java.nio.ByteBuffer

class VideoFrameTest {
    @Test
    fun closeReleasesBackingBuffersExactlyOnce() {
        var releases = 0
        val frame = VideoFrame(
            width = 1,
            height = 1,
            format = VideoDecodeFormat.RGBA8,
            plane0 = ByteBuffer.allocate(4),
            plane1 = ByteBuffer.allocate(0),
            plane0Stride = 4,
            plane1Stride = 0,
            timestamp = 0.0,
            release = { releases++ }
        )

        frame.close()
        frame.close()

        assertEquals(1, releases)
    }
}
