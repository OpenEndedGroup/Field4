package afield.examples.four

import afield.four.Renderer
import afield.four.imgui.ImGuiRenderToTexture
import afield.four.modules.*
import afield.four.targets.SideBySideStereoTarget
import afield.four.texture.TextureSampler
import afield.four.util.Window
import afield.four.util.forever
import afield.stdlib.fromViews
import imgui.ImGui
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB
import kotlin.math.abs

class ImGuiStereoQuadExample(
    val window: Window = fromContext(),
    val renderer: Renderer = fromContext(),
    val sbs: SideBySideStereoTarget = fromContext()
) {

    var lastHitWorld: Vector3f? = null

    fun go() {

        val imgui = ImGuiRenderToTexture(
            renderer = renderer,
            window = window,
            parameters = {
                width = 1024*2
                height = 1024*2
                clearFirst = true
                format = VK_FORMAT_B8G8R8A8_SRGB
                mipLevels = 4
            }, displayScale = 1f, fontSupersample = 1f
        )
        imgui.useWindowMouseEvents = false

        imgui.loadFontFromResource("Iosevka-Light.ttf", sizePixels = 12f*4)
        imgui.addUi("demo") { gui { ImGui.showDemoWindow() } }

        // Render ImGui each frame.
        renderer.renderList.preTargets["imgui-rtt"] = imgui

        // Panel geometry sized to RTT aspect.
        val aspect = imgui.width.toFloat() / imgui.height.toFloat()
        val halfH = 0.5f
        val halfW = halfH * aspect
        val positions = floatArrayOf(
            -halfW, +halfH, 0f,
            -halfW, -halfH, 0f,
            +halfW, -halfH, 0f,
            +halfW, +halfH, 0f,
        )
        val uvs = floatArrayOf(
            0f, 0f,
            0f, 1f,
            1f, 1f,
            1f, 0f,
        )
        val indices = intArrayOf(0, 1, 2, 0, 2, 3)

        val model = Matrix4f().translate(0f, 0f, -1.5f)
        val sampler = TextureSampler(renderer, mipLevels = 4, anisotropyEnable = true)

        sbs += "imgui-panel" to {
            val module = Module(
                parameters(renderer) {
                    name = "imgui-stereo-panel"
                    color = 1
                    depth = 1
                    depthTest = true
                    // Match the underlying RTT formats (default B8G8R8A8_SRGB for SideBySideStereoTarget eyes).
                    colorFormats = listOf(VK_FORMAT_B8G8R8A8_SRGB)
                    vertex = panelVertexShader
                    fragment = panelFragmentShader
                    textureResolver = { tex ->
                        if (tex.name == "panelTex") fromViews(listOf(imgui), tex.name, sampler) else null
                    }
                }
            )

            val mp = module.pointer()
            val mesh = Mesh(renderer, mp)
            mesh.resize(4, 2)
            mp.current.meshes["panel"] = mesh

            mesh.v[0]!!.mapAndWrite {
                positions.forEach { put(it) }
                rewind()
            }
            mesh.v[1]!!.mapAndWrite {
                uvs.forEach { put(it) }
                rewind()
            }
            mesh.t!!.mapAndWrite_ints {
                indices.forEach { put(it) }
                rewind()
            }

            mesh.update["mvp"] = forever {
                val view = sbs.currentEyeViewMatrix(Matrix4f())
                val proj = sbs.currentEyeProjectionMatrix(Matrix4f())
                val mvp = proj.mul(view.mul(Matrix4f(model)))
                mesh.push["mvp"] = mvp

                // Ray hit testing from controller/mouse to ImGui panel.
                val ctrl = sbs.controllerState()
//                println(ctrl.position)
                if (ctrl.active && ctrl.poseValid) {
                    val origin = Vector3f(ctrl.position)
                    val dir = Vector3f(0f, 0f, 1f)
                    ctrl.rotation.transform(dir)

                    val inv = Matrix4f(model).invert()
                    val localOrigin = inv.transformPosition(Vector3f(origin))
                    val localDir = inv.transformDirection(Vector3f(dir))
                    val dz = localDir.z
                     if (abs(dz) > 1e-5f) {
                        val t = -localOrigin.z / dz

                         if (t > 0f) {
                            val hit = Vector3f(localOrigin).fma(t, localDir)
                            if (abs(hit.x) <= halfW && abs(hit.y) <= halfH) {
                                lastHitWorld = Matrix4f(model).transformPosition(hit, Vector3f())
                                val u = (hit.x / (halfW * 2f)) + 0.5f
                                val v = 0.5f - (hit.y / (halfH * 2f))
                                imgui.injectPointer(
                                    u * imgui.width,
                                    v * imgui.height,
                                    ctrl.selectPressed || ctrl.triggerPressed
                                )
                            } else {
                                lastHitWorld = Matrix4f(model).transformPosition(hit, Vector3f())
                                imgui.injectPointer(imgui.width.toFloat() * 2f, imgui.height.toFloat() * 2f, false)
                            }
                        } else {
                            lastHitWorld = null
                            imgui.injectPointer(imgui.width.toFloat() * 2f, imgui.height.toFloat() * 2f, false)
                        }
                    } else {
                        lastHitWorld = null
                        imgui.injectPointer(imgui.width.toFloat() * 2f, imgui.height.toFloat() * 2f, false)
                    }
                } else {
                    lastHitWorld = null
                    imgui.injectPointer(imgui.width.toFloat() * 2f, imgui.height.toFloat() * 2f, false)
                }

                true
            }

            mp
        }

        // Enable mouse-driven mock controller when not in XR.
        sbs.enableMouseController(distanceMeters = 1.5f)
    }

    companion object {
        private val panelVertexShader = """
            #version 460
            layout(location = 0) in vec3 inPosition;
            layout(location = 1) in vec2 inUV;
            layout(location = 0) out vec2 fragUV;
            
            layout(push_constant) uniform Push {
                mat4 mvp;
            } push;
            
            void main() {
                fragUV = inUV;
                gl_Position = push.mvp * vec4(inPosition, 1.0);
            }
        """.trimIndent()

        private val panelFragmentShader = """
            #version 460
            layout(location = 0) in vec2 fragUV;
            layout(location = 0) out vec4 outColor;
            
            layout(set = 0, binding = 0) uniform sampler2D panelTex;
            
            void main() {
                outColor = texture(panelTex, fragUV);
                outColor.x += 0.01;
                outColor.w += 0.01;
            }
        """.trimIndent()
    }
}
