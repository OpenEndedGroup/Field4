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
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Path
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
}
