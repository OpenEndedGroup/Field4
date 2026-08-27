package afield.four

import afield.four.texture.vkFormatBytesPerPixel
import org.lwjgl.stb.STBImageWrite.stbi_write_jpg
import org.lwjgl.vulkan.VK10.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

internal object GpuImageCapture {
    /**
     * Size of the staging buffer one pixel of [format] needs, from the shared table in
     * afield.four.texture. Throws for formats the table does not know — an under-sized staging
     * buffer would let `vkCmdCopyImageToBuffer` run off the end of the allocation, which is far
     * harder to trace back than a named format in an exception.
     */
    fun bytesPerPixel(format: Int, sourceName: String): Int =
        vkFormatBytesPerPixel(format, "$sourceName capture")

    fun readRgba(staging: ByteBuffer, format: Int, width: Int, height: Int, sourceName: String): ByteArray {
        val rgba = ByteArray(width * height * 4)
        var dst = 0
        fun put(r: Byte, g: Byte, b: Byte, a: Byte) {
            rgba[dst++] = r
            rgba[dst++] = g
            rgba[dst++] = b
            rgba[dst++] = a
        }

        staging.rewind()
        staging.order(ByteOrder.LITTLE_ENDIAN)
        val pixelCount = width * height
        when (format) {
            VK_FORMAT_B8G8R8_UNORM -> repeat(pixelCount) {
                val b = staging.get()
                val g = staging.get()
                val r = staging.get()
                put(r, g, b, 255.toByte())
            }

            VK_FORMAT_R8G8B8_UNORM -> repeat(pixelCount) {
                val r = staging.get()
                val g = staging.get()
                val b = staging.get()
                put(r, g, b, 255.toByte())
            }

            VK_FORMAT_B8G8R8A8_UNORM,
            VK_FORMAT_B8G8R8A8_SRGB -> repeat(pixelCount) {
                val b = staging.get()
                val g = staging.get()
                val r = staging.get()
                val a = staging.get()
                put(r, g, b, a)
            }

            VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_R8G8B8A8_SRGB -> repeat(pixelCount) {
                val r = staging.get()
                val g = staging.get()
                val b = staging.get()
                val a = staging.get()
                put(r, g, b, a)
            }

            VK_FORMAT_R16G16B16A16_SFLOAT -> repeat(pixelCount) {
                put(
                    linearFloatChannelToSrgbByte(halfFloatToFloat(staging.getShort())),
                    linearFloatChannelToSrgbByte(halfFloatToFloat(staging.getShort())),
                    linearFloatChannelToSrgbByte(halfFloatToFloat(staging.getShort())),
                    floatChannelToByte(halfFloatToFloat(staging.getShort()))
                )
            }

            VK_FORMAT_R32G32B32A32_SFLOAT -> repeat(pixelCount) {
                put(
                    linearFloatChannelToSrgbByte(staging.getFloat()),
                    linearFloatChannelToSrgbByte(staging.getFloat()),
                    linearFloatChannelToSrgbByte(staging.getFloat()),
                    floatChannelToByte(staging.getFloat())
                )
            }

            // One- and two-channel targets carry no colour of their own — an ONNX depth map is the
            // motivating case. Replicate a lone channel to grey, and pad a missing one with zero,
            // so the capture shows the data instead of refusing it. SFLOAT channels are linear
            // light and get the same sRGB encode as the RGBA float branches above; UNORM/SNORM are
            // already encoded, so they pass through the way the 8-bit branches do.
            VK_FORMAT_R16_SFLOAT -> repeat(pixelCount) {
                val v = linearFloatChannelToSrgbByte(halfFloatToFloat(staging.getShort()))
                put(v, v, v, 255.toByte())
            }

            VK_FORMAT_R32_SFLOAT -> repeat(pixelCount) {
                val v = linearFloatChannelToSrgbByte(staging.getFloat())
                put(v, v, v, 255.toByte())
            }

            VK_FORMAT_R16_UNORM -> repeat(pixelCount) {
                val v = unormShortToByte(staging.getShort())
                put(v, v, v, 255.toByte())
            }

            VK_FORMAT_R16_SNORM -> repeat(pixelCount) {
                val v = snormShortToByte(staging.getShort())
                put(v, v, v, 255.toByte())
            }

            VK_FORMAT_R16G16_SFLOAT -> repeat(pixelCount) {
                put(
                    linearFloatChannelToSrgbByte(halfFloatToFloat(staging.getShort())),
                    linearFloatChannelToSrgbByte(halfFloatToFloat(staging.getShort())),
                    0,
                    255.toByte()
                )
            }

            VK_FORMAT_R32G32_SFLOAT -> repeat(pixelCount) {
                put(
                    linearFloatChannelToSrgbByte(staging.getFloat()),
                    linearFloatChannelToSrgbByte(staging.getFloat()),
                    0,
                    255.toByte()
                )
            }

            VK_FORMAT_R16G16B16_SFLOAT -> repeat(pixelCount) {
                put(
                    linearFloatChannelToSrgbByte(halfFloatToFloat(staging.getShort())),
                    linearFloatChannelToSrgbByte(halfFloatToFloat(staging.getShort())),
                    linearFloatChannelToSrgbByte(halfFloatToFloat(staging.getShort())),
                    255.toByte()
                )
            }

            VK_FORMAT_R32G32B32_SFLOAT -> repeat(pixelCount) {
                put(
                    linearFloatChannelToSrgbByte(staging.getFloat()),
                    linearFloatChannelToSrgbByte(staging.getFloat()),
                    linearFloatChannelToSrgbByte(staging.getFloat()),
                    255.toByte()
                )
            }

            else -> throw IllegalStateException("Unsupported $sourceName capture format $format")
        }
        return rgba
    }

    fun packRgbaToRgbBottomUp(rgba: ByteArray, width: Int, height: Int): ByteBuffer {
        val rgb = ByteBuffer.allocateDirect(width * height * 3)
        for (y in height - 1 downTo 0) {
            var src = y * width * 4
            repeat(width) {
                rgb.put(rgba[src])
                rgb.put(rgba[src + 1])
                rgb.put(rgba[src + 2])
                src += 4
            }
        }
        rgb.flip()
        return rgb
    }

    /** Pack rows without changing their order. Vulkan swapchain image copies are already top-down. */
    fun packRgbaToRgbTopDown(rgba: ByteArray, width: Int, height: Int): ByteBuffer {
        val rgb = ByteBuffer.allocateDirect(width * height * 3)
        var src = 0
        repeat(width * height) {
            rgb.put(rgba[src])
            rgb.put(rgba[src + 1])
            rgb.put(rgba[src + 2])
            src += 4
        }
        rgb.flip()
        return rgb
    }

    fun writeJpegRgb(outputPath: String, rgb: ByteBuffer, width: Int, height: Int, quality: Int) {
        val ok = stbi_write_jpg(outputPath, width, height, 3, rgb, quality.coerceIn(1, 100))
        if (!ok) {
            throw IllegalStateException("stbi_write_jpg failed for $outputPath")
        }
    }

    fun writeJpeg(outputPath: String, rgba: ByteArray, width: Int, height: Int, quality: Int) {
        val path = Path.of(outputPath)
        path.parent?.let { Files.createDirectories(it) }
        writeJpegRgb(path.toString(), packRgbaToRgbBottomUp(rgba, width, height), width, height, quality)
    }

    private fun halfFloatToFloat(raw: Short): Float {
        val h = raw.toInt() and 0xffff
        val sign = (h and 0x8000) shl 16
        val exponent = (h ushr 10) and 0x1f
        val mantissa = h and 0x03ff
        val bits = when (exponent) {
            0 -> {
                if (mantissa == 0) {
                    sign
                } else {
                    var m = mantissa
                    var e = -14
                    while ((m and 0x0400) == 0) {
                        m = m shl 1
                        e--
                    }
                    m = m and 0x03ff
                    sign or ((e + 127) shl 23) or (m shl 13)
                }
            }
            0x1f -> sign or 0x7f800000 or (mantissa shl 13)
            else -> sign or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
        }
        return Float.fromBits(bits)
    }

    private fun unormShortToByte(raw: Short): Byte = ((raw.toInt() and 0xffff) ushr 8).toByte()

    private fun snormShortToByte(raw: Short): Byte =
        floatChannelToByte(raw.toInt().coerceAtLeast(-32767) / 32767f)

    private fun floatChannelToByte(value: Float): Byte {
        if (!value.isFinite() || value <= 0f) return 0
        if (value >= 1f) return 255.toByte()
        return (value * 255f + 0.5f).toInt().toByte()
    }

    private fun linearFloatChannelToSrgbByte(value: Float): Byte {
        if (!value.isFinite() || value <= 0f) return 0
        val encoded = if (value >= 1f) {
            1f
        } else if (value <= 0.0031308f) {
            value * 12.92f
        } else {
            1.055f * Math.pow(value.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
        }
        return floatChannelToByte(encoded)
    }
}
