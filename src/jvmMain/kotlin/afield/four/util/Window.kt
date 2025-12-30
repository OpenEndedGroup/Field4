package afield.four.util

import afield.Rect
import afield.stdlib.Vec2
import afield.stdlib.d
import afield.stdlib.i
import afield.stdlib.invoke
import afield.stdlib.vec
import kotlinx.coroutines.*
import org.lwjgl.glfw.Callbacks
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import kotlin.math.roundToInt

var lastWindowToOpen: Window? = null

class Window(val title : String, val fullscreen : Boolean = false, initialDimensions : Rect? = null) {
    @JvmField
    val windowHandle: Long
    var height: Int
        private set
    var isResized: Boolean = false
    var width: Int
        private set

    // --- Scroll accumulator (since last poll)
    private var scrollAccumX = 0.0
    private var scrollAccumY = 0.0

    val cursor: Vec2
        get() {
            MemoryStack.stackPush().use { stack ->
                val x = stack.mallocDouble(1)
                val y = stack.mallocDouble(1)
                GLFW.glfwGetCursorPos(windowHandle, x, y)
                return vec(x[0].d, y[0].d)
            }
        }

    init {

        check(GLFW.glfwInit()) { "Unable to initialize GLFW" }

        check(GLFWVulkan.glfwVulkanSupported()) { "Cannot find a compatible Vulkan installable client driver (ICD)" }

        val vidMode = GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor())
        width = vidMode!!.width()
        height = vidMode.height()

        initialDimensions?.let {
            width = it.w.i
            height = it.h.i
        }

        GLFW.glfwDefaultWindowHints()
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API)
        GLFW.glfwWindowHint(GLFW.GLFW_MAXIMIZED, if (fullscreen) GLFW.GLFW_TRUE else GLFW.GLFW_FALSE)
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_TRUE)
        GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, if (!fullscreen) GLFW.GLFW_TRUE else GLFW.GLFW_FALSE)

        windowHandle = GLFW.glfwCreateWindow(width, height, title, MemoryUtil.NULL, MemoryUtil.NULL)
        initialDimensions?.let {
            GLFW.glfwSetWindowPos(windowHandle, it.x.i, it.y.i)
            GLFW.glfwSetWindowSize(windowHandle, it.w.i, it.h.i)
        }
        if (windowHandle == MemoryUtil.NULL) {
            throw RuntimeException("Failed to create the GLFW window")
        }

        GLFW.glfwSetFramebufferSizeCallback(windowHandle) { _: Long, w: Int, h: Int -> resize(w, h) }

        GLFW.glfwSetKeyCallback(windowHandle) { window: Long, key: Int, scancode: Int, action: Int, mods: Int ->
            keyCallback(key, scancode, action, mods)
        }

        // --- Scroll callback: accumulate until consumed in poll loop
        GLFW.glfwSetScrollCallback(windowHandle) { _: Long, xoffset: Double, yoffset: Double ->
            scrollAccumX += xoffset
            scrollAccumY += yoffset
        }

        GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_STICKY_KEYS, GLFW.GLFW_TRUE)
        GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_STICKY_MOUSE_BUTTONS, GLFW.GLFW_TRUE)

        lastWindowToOpen = this
    }

    fun size() : Rect {
        val width = IntArray(1)
        val height = IntArray(1)
        val x = IntArray(1)
        val y = IntArray(1)
        GLFW.glfwGetWindowSize(windowHandle, width, height)
        GLFW.glfwGetWindowPos(windowHandle, x, y)
        return Rect(x[0].d, y[0].d, width[0].d, height[0].d)
    }

    fun keyCallback(key: Int, scancode: Int, action: Int, mods: Int) { }

    fun cleanup() {
        Callbacks.glfwFreeCallbacks(windowHandle)
        GLFW.glfwDestroyWindow(windowHandle)
        GLFW.glfwTerminate()
    }

    fun isKeyPressed(keyCode: Int): Boolean {
        return GLFW.glfwGetKey(windowHandle, keyCode) == GLFW.GLFW_PRESS
    }

    // --- MOUSE: pressed state (used by MouseEvents)
    fun isMouseButtonPressed(buttonCode: Int): Boolean {
        return GLFW.glfwGetMouseButton(windowHandle, buttonCode) == GLFW.GLFW_PRESS
    }

    fun setDimensions(rect : Rect) {
        rect.let {
            GLFW.glfwSetWindowPos(windowHandle, it.x.i, it.y.i)
            GLFW.glfwSetWindowSize(windowHandle, it.w.i, it.h.i)
            GLFW.glfwSetWindowPos(windowHandle, it.x.i, it.y.i)
            GLFW.glfwSetWindowSize(windowHandle, it.w.i, it.h.i)
        }
    }

    // --- MOUSE: scroll delta since last poll (used by MouseEvents)
    fun consumeScrollDelta(): Vec2 {
        val v = vec(scrollAccumX.d, scrollAccumY.d)
        scrollAccumX = 0.0
        scrollAccumY = 0.0
        return v
    }

    var onPoll = MapOfSequences<String>()

    fun pollEvents() {
        GLFW.glfwPollEvents()
        onPoll.update()
    }

    fun resetResized() {
        isResized = false
    }

    fun resize(width: Int, height: Int) {
        isResized = true
        this.width = width
        this.height = height
    }

    fun setShouldClose() {
        GLFW.glfwSetWindowShouldClose(windowHandle, true)
    }

    fun shouldClose(): Boolean {
        return GLFW.glfwWindowShouldClose(windowHandle)
    }
    
    /**
     * Save the current framebuffer content to a temporary PNG file.
     * 
     * When imageData is provided, it is interpreted as raw RGBA pixel data for an image of the
     * given width and height. The pixel data may come from any rendering backend (e.g. Vulkan,
     * OpenGL, or software rendering).
     * 
     * @param imageData ByteBuffer containing RGBA pixel data (4 bytes per pixel), or null for a blank image
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @return File object pointing to the created temporary PNG file
     * @throws IllegalArgumentException if imageData is null
     * @throws IllegalArgumentException if buffer size doesn't match width * height * 4
     * @throws RuntimeException if file creation or image encoding fails
     */
    fun saveFramebufferToTempFile(imageData: ByteBuffer? = null, width: Int = this.width, height: Int = this.height): File {
        if (imageData == null) {
            throw IllegalArgumentException("imageData is null; cannot save framebuffer without explicit image data")
        }
        
        // Validate buffer size
        val expectedSize = width * height * 4
        if (imageData.capacity() < expectedSize) {
            throw IllegalArgumentException(
                "Buffer too small: expected at least $expectedSize bytes for ${width}x${height} RGBA image, " +
                "but got ${imageData.capacity()} bytes"
            )
        }
        
        try {
            // Create temp file with timestamp
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
            val tempFile = Files.createTempFile("field4_screenshot_$timestamp", ".png").toFile()
            
            // Convert buffer to PNG and write to file
            val pngBytes = bufferToPngBytes(imageData, width, height)
            Files.write(tempFile.toPath(), pngBytes, StandardOpenOption.WRITE)
            
            return tempFile
        } catch (e: Exception) {
            throw RuntimeException("Failed to save framebuffer to temp file: ${e.message}", e)
        }
    }
    
    /**
     * Get the current framebuffer as a base64-encoded PNG string.
     * Useful for sending image data over the network.
     * 
     * @param imageData ByteBuffer containing RGBA pixel data (4 bytes per pixel), or null for a blank image
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @return Base64-encoded PNG string, or null if encoding fails
     */
    fun getFramebufferAsBase64(imageData: ByteBuffer? = null, width: Int = this.width, height: Int = this.height): String? {
        if (imageData == null) {
            return null
        }
        
        return try {
            val pngBytes = bufferToPngBytes(imageData, width, height)
            Base64.getEncoder().encodeToString(pngBytes)
        } catch (e: Exception) {
            System.err.println("Failed to get framebuffer as base64 PNG: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Convert a ByteBuffer containing RGBA image data to PNG bytes.
     * The image is flipped vertically to account for OpenGL/Vulkan coordinate systems.
     */
    private fun bufferToPngBytes(buffer: ByteBuffer, width: Int, height: Int): ByteArray {
        // Create a simple PPM-like format, then use external conversion or write PNG directly
        // For simplicity, we'll use a basic PNG encoder approach
        
        // Reset buffer position
        buffer.rewind()
        
        // Create RGB array (PNG encoder expects separate RGB values)
        val pixels = IntArray(width * height)
        
        // Read pixels and flip vertically
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = buffer.get().toInt() and 0xFF
                val g = buffer.get().toInt() and 0xFF
                val b = buffer.get().toInt() and 0xFF
                val a = buffer.get().toInt() and 0xFF
                
                val rgb = (a shl 24) or (r shl 16) or (g shl 8) or b
                // Flip vertically (OpenGL/Vulkan coordinates are bottom-to-top)
                pixels[x + (height - 1 - y) * width] = rgb
            }
        }
        
        // Write PNG using a simple encoder
        return encodePng(pixels, width, height)
    }
    
    /**
     * Simple PNG encoder that creates a PNG file from ARGB pixel data.
     * This avoids the need for BufferedImage and javax.imageio, keeping AWT usage minimal.
     */
    private fun encodePng(pixels: IntArray, width: Int, height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        
        // PNG signature
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        
        // IHDR chunk
        writeChunk(output, "IHDR", createIHDR(width, height))
        
        // IDAT chunk (image data)
        writeChunk(output, "IDAT", createIDAT(pixels, width, height))
        
        // IEND chunk
        writeChunk(output, "IEND", byteArrayOf())
        
        return output.toByteArray()
    }
    
    private fun createIHDR(width: Int, height: Int): ByteArray {
        val ihdr = ByteArrayOutputStream()
        writeInt(ihdr, width)
        writeInt(ihdr, height)
        ihdr.write(8)  // bit depth
        ihdr.write(6)  // color type (RGBA)
        ihdr.write(0)  // compression method
        ihdr.write(0)  // filter method
        ihdr.write(0)  // interlace method
        return ihdr.toByteArray()
    }
    
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
    
    private fun writeChunk(output: ByteArrayOutputStream, type: String, data: ByteArray) {
        // Length
        writeInt(output, data.size)
        
        // Type
        output.write(type.toByteArray(Charsets.US_ASCII))
        
        // Data
        output.write(data)
        
        // CRC
        val crc = calculateCRC(type.toByteArray(Charsets.US_ASCII) + data)
        writeInt(output, crc.toInt())
    }
    
    private fun writeInt(output: ByteArrayOutputStream, value: Int) {
        output.write((value shr 24) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write(value and 0xFF)
    }
    
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
