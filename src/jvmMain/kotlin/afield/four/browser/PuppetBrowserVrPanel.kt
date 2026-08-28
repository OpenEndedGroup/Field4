package afield.four.browser

import afield.four.Renderer
import afield.four.modules.Mesh
import afield.four.modules.Module
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.four.targets.SideBySideStereoTarget
import afield.four.texture.TextureSampler
import afield.four.util.forever
import afield.stdlib.fromViews
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB
import kotlin.math.abs

/**
 * Presents a [PuppetBrowser] as an aspect-correct, interactive world-space quad.
 *
 * [SideBySideStereoTarget] supplies either real OpenXR poses/controllers or its mouse-driven desktop
 * stand-in. Trigger/select becomes a primary click; the touched thumbstick or trackpad scrolls the
 * page. Keyboard events can independently be routed with [PuppetBrowserWindowInput].
 */
class PuppetBrowserVrPanel(
    val renderer: Renderer,
    val stereo: SideBySideStereoTarget,
    val browser: PuppetBrowser,
    val widthMeters: Float = 1.6f,
    val distanceMeters: Float = 1.7f,
    val name: String = "puppet-browser-panel",
) {
    val aspect: Float = browser.spec.width.toFloat() / browser.spec.height.toFloat()
    val panel = PuppetBrowserPanel(browser, widthMeters * 0.5f, widthMeters * 0.5f / aspect)

    @Volatile
    var lastHit: PuppetBrowserPanel.Hit? = null
        private set

    @Volatile
    private var pointerPressed = false

    private val sampler = TextureSampler(renderer, mipLevels = 1, anisotropyEnable = true)
    private var removeDesktopScroll: (() -> Unit)? = null

    init {
        require(widthMeters > 0f) { "widthMeters must be positive" }
        require(distanceMeters > 0f) { "distanceMeters must be positive" }
    }

    fun build() {
        stereo.stereoCamera.base.at.set(0f, 0f, 0f)
        stereo.stereoCamera.base.target.set(0f, 0f, 1f)
        // The desktop fallback renders each eye into half of a wide window. Camera's default
        // projection is square; matching it to the actual eye target keeps physical and pixel
        // aspect ratios honest. OpenXR supplies its own per-eye projection when attached.
        val eyeAspect = stereo.eyeWidth.toFloat() / stereo.eyeHeight.toFloat()
        stereo.stereoCamera.base.p.auto = {
            stereo.stereoCamera.base.p.matchDepth = stereo.stereoCamera.base.p.autoMatchDistance()
            stereo.stereoCamera.base.p.setAspect(eyeAspect)
        }
        stereo.stereoCamera.base.setAspect(eyeAspect)
        panel.place(
            Vector3f(0f, 0f, distanceMeters),
            Vector3f(1f, 0f, 0f),
            Vector3f(0f, 1f, 0f),
        )

        val halfWidth = panel.halfWidth
        val halfHeight = panel.halfHeight
        val positions = floatArrayOf(
            -halfWidth, +halfHeight, 0f,
            -halfWidth, -halfHeight, 0f,
            +halfWidth, -halfHeight, 0f,
            +halfWidth, +halfHeight, 0f,
        )
        val uvs = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f)
        val indices = intArrayOf(0, 1, 2, 0, 2, 3)

        stereo += name to {
            val module = Module(parameters(renderer) {
                this.name = this@PuppetBrowserVrPanel.name
                color = 1
                depth = 1
                depthTest = true
                colorFormats = listOf(VK_FORMAT_B8G8R8A8_SRGB)
                vertex = vertexShader
                fragment = fragmentShader
                textureResolver = { texture ->
                    if (texture.name == "browserTexture") {
                        fromViews(listOf(browser), texture.name, sampler)
                    } else null
                }
            })

            val pointer = module.pointer()
            val mesh = Mesh(renderer, pointer)
            mesh.resize(4, 2)
            pointer.current.meshes[name] = mesh
            mesh.v[0]!!.mapAndWrite { positions.forEach(::put); rewind() }
            mesh.v[1]!!.mapAndWrite { uvs.forEach(::put); rewind() }
            mesh.t!!.mapAndWrite_ints { indices.forEach(::put); rewind() }

            mesh.update["mvp"] = forever {
                mesh.push["mvp"] = panel.panelMvp(
                    stereo.currentEyeViewMatrix(Matrix4f()),
                    stereo.currentEyeProjectionMatrix(Matrix4f()),
                )
                // This factory is instantiated once per eye. Input belongs to the frame, not the eye.
                if (stereo.currentEyeIndex == 0) updateInteraction()
                val hit = lastHit
                mesh.push["pointer"] = Vector4f(
                    hit?.u?.toFloat() ?: -1f,
                    hit?.v?.toFloat() ?: -1f,
                    if (hit?.onPanel == true) 1f else 0f,
                    if (pointerPressed) 1f else 0f,
                )
                true
            }
            pointer
        }
    }

    /** Install the desktop preview's mouse ray. A headset controller automatically supersedes it. */
    fun enableDesktopInteraction() {
        stereo.enableMouseController(name = "$name-mouse", distanceMeters = 0.001f)
        removeDesktopScroll?.invoke()
        removeDesktopScroll = renderer.window.addScrollListener { deltaX, deltaY ->
            lastHit?.takeIf { it.onPanel }?.let { hit ->
                panel.uv.wheel(-deltaX * 40.0, -deltaY * 40.0, hit.u, hit.v)
            }
        }
    }

    private fun updateInteraction() {
        val controller = stereo.controllerState()
        val axis = when {
            controller.thumbstickTouched || abs(controller.thumbstickY) > 0.08f -> controller.thumbstickY
            controller.trackpadTouched || abs(controller.trackpadY) > 0.08f -> controller.trackpadY
            else -> 0f
        }
        pointerPressed = controller.selectPressed || controller.triggerPressed
        lastHit = panel.interact(controller, scrollY = -axis * 22.0)
    }

    fun cleanup() {
        removeDesktopScroll?.invoke()
        removeDesktopScroll = null
        sampler.cleanup()
    }

    companion object {
        private val vertexShader = """
            #version 460
            layout(location = 0) in vec3 inPosition;
            layout(location = 1) in vec2 inUV;
            layout(location = 0) out vec2 fragUV;
            layout(push_constant) uniform Push { mat4 mvp; vec4 pointer; } push;
            void main() {
                fragUV = inUV;
                gl_Position = push.mvp * vec4(inPosition, 1.0);
            }
        """.trimIndent()

        private val fragmentShader = """
            #version 460
            layout(location = 0) in vec2 fragUV;
            layout(location = 0) out vec4 outColor;
            layout(set = 0, binding = 0) uniform sampler2D browserTexture;
            layout(push_constant) uniform Push { mat4 mvp; vec4 pointer; } push;
            void main() {
                vec4 browser = texture(browserTexture, fragUV);
                float edge = min(min(fragUV.x, 1.0 - fragUV.x), min(fragUV.y, 1.0 - fragUV.y));
                vec3 rgb = edge < 0.0025 ? vec3(0.18) : browser.rgb;
                vec2 texSize = vec2(textureSize(browserTexture, 0));
                vec2 delta = (fragUV - push.pointer.xy) * vec2(texSize.x / texSize.y, 1.0);
                float ringDistance = abs(length(delta) - 0.012);
                float ring = 1.0 - smoothstep(0.0015, 0.0035, ringDistance);
                vec3 pointerColor = mix(vec3(0.95), vec3(0.25, 0.65, 1.0), push.pointer.w);
                rgb = mix(rgb, pointerColor, ring * push.pointer.z);
                outColor = vec4(rgb, 1.0);
            }
        """.trimIndent()
    }
}
