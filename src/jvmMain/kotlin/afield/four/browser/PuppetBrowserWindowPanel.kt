package afield.four.browser

import afield.four.Renderer
import afield.four.modules.Mesh
import afield.four.modules.Module
import afield.four.modules.ModulePointer
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.four.texture.TextureSampler
import afield.four.util.forever
import afield.stdlib.fromViews

/** An aspect-correct rectangle in one Field [afield.four.util.Window]'s logical coordinates. */
data class PuppetBrowserWindowViewport(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    fun hitTest(px: Double, py: Double): Pair<Double, Double>? {
        if (px < x || py < y || px >= x + width || py >= y + height) return null
        return (px - x) / width to (py - y) / height
    }

    companion object {
        /** Largest centered [contentWidth] × [contentHeight] rectangle that fits the window. */
        fun fit(
            windowWidth: Double,
            windowHeight: Double,
            contentWidth: Double,
            contentHeight: Double,
        ): PuppetBrowserWindowViewport {
            require(windowWidth > 0.0 && windowHeight > 0.0) { "window dimensions must be positive" }
            require(contentWidth > 0.0 && contentHeight > 0.0) { "content dimensions must be positive" }
            val windowAspect = windowWidth / windowHeight
            val contentAspect = contentWidth / contentHeight
            return if (windowAspect > contentAspect) {
                val width = windowHeight * contentAspect
                PuppetBrowserWindowViewport((windowWidth - width) * 0.5, 0.0, width, windowHeight)
            } else {
                val height = windowWidth / contentAspect
                PuppetBrowserWindowViewport(0.0, (windowHeight - height) * 0.5, windowWidth, height)
            }
        }
    }
}

/**
 * Draws one [PuppetBrowser] into a native Field window and routes that window's real mouse,
 * keyboard, text-input and wheel stream back into Chromium.
 *
 * The browser is fitted without distortion. Pointer input outside the resulting letterbox is
 * ignored; inside it, coordinates map exactly to browser UVs and then CSS pixels.
 */
class PuppetBrowserWindowPanel(
    val renderer: Renderer,
    val browser: PuppetBrowser,
    val name: String = "puppet-browser-window-panel",
) : AutoCloseable {
    @Volatile
    var viewport: PuppetBrowserWindowViewport = fittedViewport()
        private set

    private val sampler = TextureSampler(renderer, mipLevels = 1, anisotropyEnable = true)
    private val input = PuppetBrowserWindowInput(browser, renderer.window) { x, y -> viewport.hitTest(x, y) }
    private var pointer: ModulePointer? = null
    private var mesh: Mesh? = null
    private var lastWindowWidth = -1.0
    private var lastWindowHeight = -1.0

    fun build(takeFocus: Boolean = true) {
        check(pointer == null) { "$name is already built" }
        val module = Module(parameters(renderer) {
            this.name = this@PuppetBrowserWindowPanel.name
            color = 1
            depth = 1
            depthTest = false
            vertex = vertexShader
            fragment = fragmentShader
            textureResolver = { texture ->
                if (texture.name == "browserTexture") {
                    fromViews(listOf(browser), texture.name, sampler)
                } else null
            }
        })
        val createdPointer = module.pointer()
        val createdMesh = Mesh(renderer, createdPointer)
        createdMesh.resize(4, 2)
        createdPointer.current.meshes[name] = createdMesh
        createdMesh.v[1]!!.mapAndWrite {
            floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f).forEach(::put)
            rewind()
        }
        createdMesh.t!!.mapAndWrite_ints {
            intArrayOf(0, 1, 2, 0, 2, 3).forEach(::put)
            rewind()
        }
        updateViewport(createdMesh, force = true)
        createdMesh.update["browser-window-viewport"] = forever {
            updateViewport(createdMesh)
            true
        }
        renderer.renderList.modules[name] = createdPointer
        pointer = createdPointer
        mesh = createdMesh
        if (takeFocus) input.takeFocus()
    }

    private fun fittedViewport(): PuppetBrowserWindowViewport {
        val size = renderer.window.size()
        return PuppetBrowserWindowViewport.fit(
            size.w,
            size.h,
            browser.spec.width.toDouble(),
            browser.spec.height.toDouble(),
        )
    }

    private fun updateViewport(mesh: Mesh, force: Boolean = false) {
        val size = renderer.window.size()
        if (!force && size.w == lastWindowWidth && size.h == lastWindowHeight) return
        lastWindowWidth = size.w
        lastWindowHeight = size.h
        viewport = PuppetBrowserWindowViewport.fit(
            size.w,
            size.h,
            browser.spec.width.toDouble(),
            browser.spec.height.toDouble(),
        )
        val left = (viewport.x / size.w * 2.0 - 1.0).toFloat()
        val right = ((viewport.x + viewport.width) / size.w * 2.0 - 1.0).toFloat()
        val top = (1.0 - viewport.y / size.h * 2.0).toFloat()
        val bottom = (1.0 - (viewport.y + viewport.height) / size.h * 2.0).toFloat()
        mesh.v[0]!!.mapAndWrite {
            floatArrayOf(
                left, top, 0f,
                left, bottom, 0f,
                right, bottom, 0f,
                right, top, 0f,
            ).forEach(::put)
            rewind()
        }
    }

    override fun close() {
        renderer.renderList.modules.remove(name)
        input.close()
        sampler.cleanup()
        pointer = null
        mesh = null
    }

    companion object {
        private val vertexShader = """
            #version 460
            layout(location = 0) in vec3 inPosition;
            layout(location = 1) in vec2 inUV;
            layout(location = 0) out vec2 fragUV;
            void main() {
                fragUV = inUV;
                gl_Position = vec4(inPosition, 1.0);
            }
        """.trimIndent()

        private val fragmentShader = """
            #version 460
            layout(location = 0) in vec2 fragUV;
            layout(location = 0) out vec4 outColor;
            layout(set = 0, binding = 0) uniform sampler2D browserTexture;
            void main() {
                outColor = vec4(texture(browserTexture, fragUV).rgb, 1.0);
            }
        """.trimIndent()
    }
}
