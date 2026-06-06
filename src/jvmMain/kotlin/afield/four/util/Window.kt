package afield.four.util

import afield.Rect
import afield.four.Renderer
import afield.stdlib.Vec2
import afield.stdlib.d
import afield.stdlib.i
import afield.stdlib.vec
import afield.util.PngEncoder
import org.lwjgl.glfw.Callbacks
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.*

var lastWindowToOpen: Window? = null

private val openWindowsLock = Any()
private val openWindows = linkedSetOf<Window>()

fun openWindowsSnapshot(): List<Window> =
    synchronized(openWindowsLock) { openWindows.toList() }

class Window(val title : String, val fullscreen : Boolean = false, initialDimensions : Rect? = null) {
    @JvmField
    val windowHandle: Long
    var height: Int
        private set
    var isResized: Boolean = false
    var width: Int
        private set

    @Volatile
    var associatedRenderer: Renderer? = null

    // --- Scroll accumulator (since last poll)
    private var scrollAccumX = 0.0
    private var scrollAccumY = 0.0

    var mouseEvents : MouseEvents? = null
    var keyEvents : KeyEvents? = null
    private val keyListeners = mutableListOf<(Int, Int, Int, Int) -> Unit>()
    private val charListeners = mutableListOf<(Int) -> Unit>()

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
        if (windowHandle == MemoryUtil.NULL) {
            throw RuntimeException("Failed to create the GLFW window")
        }

        initialDimensions?.let {
            GLFW.glfwSetWindowPos(windowHandle, it.x.i, it.y.i)
            GLFW.glfwSetWindowSize(windowHandle, it.w.i, it.h.i)
        }

        // Ensure initial width/height reflect framebuffer pixels (HiDPI / Retina).
        // The framebuffer callback may not fire until the first event poll, but swapchain/RTT sizing
        // often happens immediately after window creation.
        MemoryStack.stackPush().use { stack ->
            val fbW = stack.mallocInt(1)
            val fbH = stack.mallocInt(1)
            GLFW.glfwGetFramebufferSize(windowHandle, fbW, fbH)
            width = fbW[0]
            height = fbH[0]
        }

        GLFW.glfwSetFramebufferSizeCallback(windowHandle) { _: Long, w: Int, h: Int -> resize(w, h) }

        GLFW.glfwSetKeyCallback(windowHandle) { window: Long, key: Int, scancode: Int, action: Int, mods: Int ->
            keyCallback(key, scancode, action, mods)
        }
        GLFW.glfwSetCharCallback(windowHandle) { _: Long, codepoint: Int ->
            charCallback(codepoint)
        }

        // --- Scroll callback: accumulate until consumed in poll loop
        GLFW.glfwSetScrollCallback(windowHandle) { _: Long, xoffset: Double, yoffset: Double ->
            scrollAccumX += xoffset
            scrollAccumY += yoffset
        }

        GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_STICKY_KEYS, GLFW.GLFW_TRUE)
        GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_STICKY_MOUSE_BUTTONS, GLFW.GLFW_TRUE)

        lastWindowToOpen = this
        synchronized(openWindowsLock) {
            openWindows += this
        }
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

    fun keyCallback(key: Int, scancode: Int, action: Int, mods: Int) {
        keyListeners.forEach { it(key, scancode, action, mods) }
    }

    fun charCallback(codepoint: Int) {
        charListeners.forEach { it(codepoint) }
    }

    fun cleanup() {
        synchronized(openWindowsLock) {
            openWindows -= this
            if (lastWindowToOpen === this) {
                lastWindowToOpen = openWindows.lastOrNull()
            }
        }
        Callbacks.glfwFreeCallbacks(windowHandle)
        GLFW.glfwDestroyWindow(windowHandle)
        GLFW.glfwTerminate()
    }

    fun isKeyPressed(keyCode: Int): Boolean {
        return GLFW.glfwGetKey(windowHandle, keyCode) == GLFW.GLFW_PRESS
    }

    fun addKeyListener(listener: (Int, Int, Int, Int) -> Unit): () -> Unit {
        keyListeners.add(listener)
        return { keyListeners.remove(listener) }
    }

    fun addCharListener(listener: (Int) -> Unit): () -> Unit {
        charListeners.add(listener)
        return { charListeners.remove(listener) }
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
            val pngBytes = PngEncoder.encodeFromBuffer(imageData, width, height)
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
            val pngBytes = PngEncoder.encodeFromBuffer(imageData, width, height)
            Base64.getEncoder().encodeToString(pngBytes)
        } catch (e: Exception) {
            System.err.println("Failed to get framebuffer as base64 PNG: ${e.message}")
            e.printStackTrace()
            null
        }
    }
}
