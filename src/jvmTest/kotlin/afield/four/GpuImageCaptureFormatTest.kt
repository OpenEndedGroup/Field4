package afield.four

import afield.four.texture.vkFormatBytesPerPixel
import afield.four.texture.vkFormatBytesPerPixelOrNull
import org.lwjgl.vulkan.VK10.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the shared bytes-per-pixel table and its agreement with [GpuImageCapture.readRgba].
 *
 * These two used to be separate `when` blocks that had to be kept in step by hand, and they came
 * apart: the capture table listed only the four-channel formats, so a one- or two-channel target
 * got `else -> 4`. For the formats wider than four bytes that silently under-sized the staging
 * buffer, which lets `vkCmdCopyImageToBuffer` write past the end of the allocation.
 *
 * No Vulkan device is needed — the table is arithmetic and [GpuImageCapture.readRgba] reads a
 * plain ByteBuffer.
 */
class GpuImageCaptureFormatTest {

    // Every format the shared table claims to know, with the size it must report.
    private val knownFormats = listOf(
        VK_FORMAT_R8_UNORM to 1,
        VK_FORMAT_R8G8_UNORM to 2,
        VK_FORMAT_R16_SFLOAT to 2,
        VK_FORMAT_R16_UNORM to 2,
        VK_FORMAT_R16_SNORM to 2,
        VK_FORMAT_R8G8B8_UNORM to 3,
        VK_FORMAT_B8G8R8_UNORM to 3,
        VK_FORMAT_R8G8B8A8_UNORM to 4,
        VK_FORMAT_R8G8B8A8_SRGB to 4,
        VK_FORMAT_B8G8R8A8_UNORM to 4,
        VK_FORMAT_B8G8R8A8_SRGB to 4,
        VK_FORMAT_R16G16_SFLOAT to 4,
        VK_FORMAT_R16G16_UNORM to 4,
        VK_FORMAT_R16G16_SNORM to 4,
        VK_FORMAT_R32_SFLOAT to 4,
        VK_FORMAT_R16G16B16_SFLOAT to 6,
        VK_FORMAT_R16G16B16A16_SFLOAT to 8,
        VK_FORMAT_R32G32_SFLOAT to 8,
        VK_FORMAT_R32G32B32_SFLOAT to 12,
        VK_FORMAT_R32G32B32A32_SFLOAT to 16
    )

    @Test
    fun tableReportsChannelCountTimesChannelWidth() {
        knownFormats.forEach { (format, expected) ->
            assertEquals(expected, vkFormatBytesPerPixel(format, "test"), "VkFormat $format")
        }
    }

    @Test
    fun formatsWithNoPerPixelSizeAreRefused() {
        // Depth/stencil is not addressable by flat width*height*bpp arithmetic, block-compressed
        // formats have no per-pixel size at all, and UNDEFINED is the uninitialised-field case.
        listOf(VK_FORMAT_D32_SFLOAT, VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_BC7_UNORM_BLOCK, VK_FORMAT_UNDEFINED)
            .forEach { format ->
                assertNull(vkFormatBytesPerPixelOrNull(format), "VkFormat $format")
                val thrown = assertFailsWith<IllegalArgumentException>("VkFormat $format") {
                    GpuImageCapture.bytesPerPixel(format, "window")
                }
                // The message has to name both the caller and the format, or the throw is no more
                // debuggable than the wrong number it replaced.
                val message = thrown.message ?: ""
                assertTrue(message.contains("window"), "message did not name the caller: $message")
                assertTrue(message.contains("$format"), "message did not name the format: $message")
            }
    }

    /**
     * The invariant that actually broke: for every format [GpuImageCapture.readRgba] can decode, it
     * must consume exactly the number of bytes the table sizes the staging buffer at. Reading short
     * means the table over-allocates; reading long throws BufferUnderflowException here and
     * corrupts memory in production.
     */
    @Test
    fun readRgbaConsumesExactlyTheStagingBufferItWasSized() {
        val width = 5
        val height = 3
        var decodable = 0

        knownFormats.forEach { (format, bytesPerPixel) ->
            val byteCount = width * height * bytesPerPixel
            val staging = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.LITTLE_ENDIAN)
            repeat(byteCount) { staging.put(it.toByte()) }
            staging.rewind()

            val rgba = try {
                GpuImageCapture.readRgba(staging, format, width, height, "test")
            } catch (expected: IllegalStateException) {
                // Format is in the table but has no decode branch — the explicit unsupported error.
                return@forEach
            }

            decodable++
            assertEquals(width * height * 4, rgba.size, "VkFormat $format produced the wrong RGBA size")
            assertEquals(
                byteCount,
                staging.position(),
                "VkFormat $format read ${staging.position()} bytes from a buffer sized $byteCount"
            )
        }

        // Cheap guard against the whole loop silently short-circuiting into the catch.
        assertTrue(decodable >= 14, "only $decodable formats decoded; readRgba coverage regressed")
    }

    @Test
    fun singleChannelFloatBecomesOpaqueGrey() {
        val width = 2
        val height = 1
        val staging = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        staging.putShort(0x3C00)  // half-float 1.0
        staging.putShort(0)       // half-float 0.0
        staging.rewind()

        val rgba = GpuImageCapture.readRgba(staging, VK_FORMAT_R16_SFLOAT, width, height, "test")

        // Replicated across RGB with an opaque alpha, so a depth map is visible rather than refused.
        assertEquals(listOf(255, 255, 255, 255), rgba.take(4).map { it.toInt() and 0xff })
        assertEquals(listOf(0, 0, 0, 255), rgba.drop(4).map { it.toInt() and 0xff })
    }
}
