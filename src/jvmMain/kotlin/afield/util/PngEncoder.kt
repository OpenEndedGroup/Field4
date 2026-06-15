package afield.util

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Simple PNG encoder that creates PNG files from ARGB pixel data.
 * 
 * This implementation avoids external dependencies (AWT, javax.imageio) and provides
 * a pure Kotlin solution for encoding images to PNG format. It's particularly useful
 * for screenshot capture and image export functionality.
 * 
 * The encoder supports RGBA color type (color type 6 in PNG specification) and uses
 * DEFLATE compression for the image data.
 */
object PngEncoder {
    
    /**
     * Encode ARGB pixel data to PNG format.
     * 
     * @param pixels IntArray containing ARGB pixel data (one int per pixel)
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @return ByteArray containing the complete PNG file data
     */
    fun encode(pixels: IntArray, width: Int, height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        
        // PNG signature (8 bytes)
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        
        // IHDR chunk (image header)
        writeChunk(output, "IHDR", createIHDR(width, height))
        
        // IDAT chunk (image data)
        writeChunk(output, "IDAT", createIDAT(pixels, width, height))
        
        // IEND chunk (end of file)
        writeChunk(output, "IEND", byteArrayOf())
        
        return output.toByteArray()
    }
    
    /**
     * Convert a ByteBuffer containing RGBA image data to PNG bytes.
     * 
     * @param buffer ByteBuffer containing RGBA pixel data (4 bytes per pixel)
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @param flipY When true, invert row order for framebuffer-style readbacks whose
     * origin is bottom-left. Vulkan image-to-buffer readbacks usually want false.
     * @return ByteArray containing the complete PNG file data
     */
    fun encodeFromBuffer(buffer: ByteBuffer, width: Int, height: Int, flipY: Boolean = true): ByteArray {
        // Reset buffer position
        buffer.rewind()
        
        // Create ARGB array (PNG encoder expects ARGB ints)
        val pixels = IntArray(width * height)
        
        // Read pixels, optionally flipping vertically.
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = buffer.get().toInt() and 0xFF
                val g = buffer.get().toInt() and 0xFF
                val b = buffer.get().toInt() and 0xFF
                val a = buffer.get().toInt() and 0xFF
                
                val argb = (a shl 24) or (r shl 16) or (g shl 8) or b
                val targetY = if (flipY) height - 1 - y else y
                pixels[x + targetY * width] = argb
            }
        }
        
        return encode(pixels, width, height)
    }
    
    /**
     * Create IHDR (Image Header) chunk data.
     */
    private fun createIHDR(width: Int, height: Int): ByteArray {
        val ihdr = ByteArrayOutputStream()
        writeInt(ihdr, width)
        writeInt(ihdr, height)
        ihdr.write(8)  // bit depth
        ihdr.write(6)  // color type (RGBA)
        ihdr.write(0)  // compression method (deflate)
        ihdr.write(0)  // filter method (adaptive)
        ihdr.write(0)  // interlace method (none)
        return ihdr.toByteArray()
    }
    
    /**
     * Create IDAT (Image Data) chunk data with DEFLATE compression.
     */
    private fun createIDAT(pixels: IntArray, width: Int, height: Int): ByteArray {
        val uncompressed = ByteArrayOutputStream()
        
        // Write each scanline with filter byte
        for (y in 0 until height) {
            uncompressed.write(0)  // filter type: none
            for (x in 0 until width) {
                val pixel = pixels[x + y * width]
                uncompressed.write((pixel shr 16) and 0xFF)  // R
                uncompressed.write((pixel shr 8) and 0xFF)   // G
                uncompressed.write(pixel and 0xFF)           // B
                uncompressed.write((pixel shr 24) and 0xFF)  // A
            }
        }
        
        // Compress using DEFLATE
        val compressed = java.util.zip.Deflater().let { deflater ->
            val data = uncompressed.toByteArray()
            deflater.setInput(data)
            deflater.finish()
            
            val buffer = ByteArray(data.size + 1024)
            val compressedSize = deflater.deflate(buffer)
            deflater.end()
            
            buffer.copyOf(compressedSize)
        }
        
        return compressed
    }
    
    /**
     * Write a PNG chunk (length, type, data, CRC).
     */
    private fun writeChunk(output: ByteArrayOutputStream, type: String, data: ByteArray) {
        // Length (4 bytes)
        writeInt(output, data.size)
        
        // Type (4 bytes)
        output.write(type.toByteArray(Charsets.US_ASCII))
        
        // Data (variable length)
        output.write(data)
        
        // CRC (4 bytes) - calculated over type and data
        val crc = calculateCRC(type.toByteArray(Charsets.US_ASCII) + data)
        writeInt(output, crc.toInt())
    }
    
    /**
     * Write a 32-bit integer in big-endian format.
     */
    private fun writeInt(output: ByteArrayOutputStream, value: Int) {
        output.write((value shr 24) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write(value and 0xFF)
    }
    
    /**
     * Calculate CRC-32 checksum for PNG chunks.
     * Uses the polynomial defined in the PNG specification.
     */
    private fun calculateCRC(data: ByteArray): Long {
        var crc = 0xFFFFFFFFL
        
        for (b in data) {
            crc = crc xor (b.toLong() and 0xFF)
            for (i in 0 until 8) {
                crc = if ((crc and 1) != 0L) {
                    0xEDB88320L xor (crc ushr 1)
                } else {
                    crc ushr 1
                }
            }
        }
        
        return crc xor 0xFFFFFFFFL
    }
}
