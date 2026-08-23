package afield

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `assertVideoMoved` refused the movie it exists to protect.
 *
 * The guard asked whether a *fraction* of the frame changed between consecutive frames, which is a
 * question about how much empty space surrounds the animation. `running_and_stopping.mp4` is a small
 * disc travelling along a track inside a capture that is mostly dark background: its moving pairs
 * changed 10-28 of 8960 thumbnail cells against a threshold of 45, so it scored 4 of 159 and was
 * rejected as "very nearly a still" while being frame-for-frame the movie the page describes.
 *
 * The two recordings below are that shape and the shape the guard was built to catch. They differ in
 * whether the picture changes at all, not in how much of the frame it occupies — which is the
 * distinction the guard has to be sensitive to, and the one a fraction-of-frame threshold loses.
 */
class VideoMovedGuardTest {

    private val width = 1130
    private val height = 622

    private fun jpeg(draw: (BufferedImage) -> Unit): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(18, 18, 18)
        g.fillRect(0, 0, width, height)
        draw(image)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    /** One small disc, plus a tick row that grows every fourth frame — the real recording's content. */
    private fun frame(discX: Int, ticks: Int, stopped: Boolean): ByteArray = jpeg { image ->
        val g = image.createGraphics()
        g.color = if (stopped) Color(255, 71, 76) else Color(122, 217, 255)
        g.fillOval(discX - 11, 193 - 11, 22, 22)
        g.drawLine(195, 193, 838, 193)
        for (i in 0 until ticks) {
            val x = 192 + i * 15
            g.drawLine(x, 262, x, 310)
        }
        g.dispose()
    }

    @Test
    fun aSmallObjectMovingInALargeFrameCounts() {
        // 91 frames of motion, then a frozen tail: exactly running_and_stopping.mp4's structure.
        val moving = (0 until 91).map { i -> frame(discX = 200 + i * 5, ticks = i / 4, stopped = false) }
        val frozen = List(69) { frame(discX = 200 + 90 * 5, ticks = 90 / 4, stopped = true) }

        assertVideoMoved(moving + frozen, Paths.get("running_and_stopping.mp4"))
    }

    @Test
    fun aRecordingOfOnePictureIsStillRefused() {
        // The original defect: 36 frames, 3 distinct pictures. Byte-distinct (each is separately
        // JPEG-encoded), which is why counting distinct bytes was vacuous.
        val repeated = (0 until 36).map { i -> frame(discX = 400 + (i / 12) * 5, ticks = 6, stopped = false) }

        val thrown = assertFailsWith<IllegalStateException> {
            assertVideoMoved(repeated, Paths.get("visualizers-inline-metrics.mp4"))
        }
        assertTrue(
            "very nearly a still" in (thrown.message ?: ""),
            "expected the near-still diagnosis, got: ${thrown.message}"
        )
    }
}
