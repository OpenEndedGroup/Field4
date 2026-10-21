package afield.graphics.vulkan.util

/**
 * The pointer over a renderer window and the window's size, in window points, read once a frame.
 */

import org.lwjgl.glfw.GLFW

/**
 * The pointer and the window's size **in points** (the cursor's units, half the framebuffer's
 * pixels on a Retina display), read on the window's poll, where GLFW allows it, and readable from
 * any thread afterwards. A sketch that works in canvas pixels, origin top left, y down, uses
 * [width] and [height] as its world and [x], [y] as its mouse.
 *
 * [inside] means the window is under the pointer (`GLFW_HOVERED`) and the pointer is within it,
 * a browser's "over the canvas": a window that merely surrounds where the pointer is, behind
 * another window, does not count.
 *
 * [onPoll] runs on every poll after the fields are updated, for pointer logic that has to see
 * every frame (a brush that paints along a drag). Registering a second pointer under the same
 * [name] on a window replaces the first.
 */
class WindowPointer(
    val window: Window,
    val name: String,
    var onPoll: (WindowPointer) -> Unit = {},
) {
    /** The window's width in points; its framebuffer width until the first poll. */
    @Volatile var width: Float = window.width.toFloat()
        private set

    /** The window's height in points; its framebuffer height until the first poll. */
    @Volatile var height: Float = window.height.toFloat()
        private set

    /** The pointer, in points from the window's top-left corner. */
    @Volatile var x: Float = 0f
        private set

    @Volatile var y: Float = 0f
        private set

    /** The window is under the pointer and the pointer is within it. */
    @Volatile var inside: Boolean = false
        private set

    /** The left button is held. */
    @Volatile var down: Boolean = false
        private set

    /**
     * Whether the window has been polled yet. Until it has, [width] and [height] are the
     * framebuffer's pixels, twice the points on a Retina display, so a sketch that places things
     * by the window's size waits for this.
     */
    @Volatile var polled: Boolean = false
        private set

    init {
        window.onPoll[name] = sequence {
            while (true) {
                val size = window.size()
                width = size.w.toFloat().coerceAtLeast(1f)
                height = size.h.toFloat().coerceAtLeast(1f)
                val cursor = window.cursor
                x = cursor.x
                y = cursor.y
                val hovered = GLFW.glfwGetWindowAttrib(window.windowHandle, GLFW.GLFW_HOVERED) == GLFW.GLFW_TRUE
                inside = hovered && x >= 0 && y >= 0 && x <= size.w && y <= size.h
                down = window.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)
                polled = true
                onPoll(this@WindowPointer)
                yield(true)
            }
        }
    }
}
