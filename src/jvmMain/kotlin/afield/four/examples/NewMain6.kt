package afield.four.examples

import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB
import afield.four.*
import afield.four.modules.*
import afield.four.targets.RenderTarget
import afield.four.targets.RenderToTexture
import afield.four.texture.LoadedTexture
import afield.four.texture.TextureSampler
import afield.four.util.Projection
import afield.four.util.Window
import afield.four.util.forever
import java.io.File

class NewMain6 {
    val window = Window("Field")
    val renderer = Renderer(window)

//    private lateinit var cubeEntity: Entity

    var angle = 0.0f

    init {

        val texture = LoadedTexture("/Users/marc/Desktop/bbc.jpg", renderer, VK_FORMAT_R8G8B8A8_SRGB)
        texture.transferNow()

        val positions = floatArrayOf(
            -0.5f, 0.5f, 0.5f,
            -0.5f, -0.5f, 0.5f,
            0.5f, -0.5f, 0.5f,
            0.5f, 0.5f, 0.5f,
        )
        val textCoords = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 1.0f,
        )
        val indices = intArrayOf(
            0, 1, 2, 0, 2, 3
        )

        val module = Module(
            Parameters(
                renderer,
                vertex = File("resources/shaders/fwd_vertex.glsl").readText(),
                fragment = File("resources/shaders/fwd_fragment.glsl").readText(),
                depth = 0,
                textureResolver = {
                    texture.imageView to TextureSampler(renderer, 4, true)
                },
                name = "inner square shader"
            )
        )

        val rt2 = RenderToTexture(renderer)
        rt2.modules += "foo" to module.pointer()

        val justTexture = Module(
            Parameters(
                renderer,
                vertex = File("resources/shaders/plain_vertex.glsl").readText(),
                fragment = File("resources/shaders/plain_fragment.glsl").readText(),
                depth = 0,
                textureResolver = {
                    rt2.imageView to TextureSampler(renderer, 4, true)
                },
                name = "onscreen shader"
            )
        )

        val rt = RenderTarget(renderer)
        rt += "justTexture" to justTexture
        rt += "rt2" to rt2

        renderer.renderList = rt

        var onscreenRectangle = Mesh(renderer, justTexture.pointer())
        onscreenRectangle.resize( 4, 2)
        justTexture += "onscreenRect" to onscreenRectangle

        onscreenRectangle.v[0]!!.mapAndWrite {
            for (v in 0 until positions.size / 3) {
                this.put(positions[v * 3 + 0])
                this.put(positions[v * 3 + 1])
                this.put(positions[v * 3 + 2])
            }
            this.rewind()
        }
        onscreenRectangle.v[1]!!.mapAndWrite {
            for (v in 0 until positions.size / 3) {
                this.put(textCoords[v * 2 + 0])
                this.put(textCoords[v * 2 + 1])
            }
            this.rewind()
        }
        onscreenRectangle.t!!.mapAndWrite_ints {
            this.put(indices)
            this.rewind()
        }


        onscreenRectangle.push["projectionMatrix"] = Projection().resize(window.width, window.height)
        onscreenRectangle.push["modelMatrix"] = Matrix4f().setTranslation(0f, 0f, -3f)

        val innerRectangle = Mesh(renderer, module.pointer())
        innerRectangle.push["projectionMatrix"] = Projection().resize(window.width, window.height)
        innerRectangle.push["modelMatrix"] = Matrix4f().setTranslation(0f, 0f, -3f)


        innerRectangle.resize( 4, 2)
        module += "innerRectangle" to innerRectangle

        innerRectangle.v[0]!!.mapAndWrite {
            for (v in 0 until positions.size / 3) {
                this.put(positions[v * 3 + 0])
                this.put(positions[v * 3 + 1])
                this.put(positions[v * 3 + 2])
            }
            this.rewind()
        }

        innerRectangle.t!!.mapAndWrite_ints {
            this.put(indices)
            this.rewind()
        }

        innerRectangle.update["rot"] = forever {
            innerRectangle.push.get<Matrix4f>("modelMatrix").rotate(0.01f, Vector3f(1f, 0.8f, 0.3f))
            true
        }

        onscreenRectangle.update["rot"] = forever {
            onscreenRectangle.push.get<Matrix4f>("modelMatrix").rotate(0.02f, Vector3f(1f, 0.8f, 0.3f))
            true
        }

        println(" dimensions are ${window.width} ${window.height}")
        innerRectangle.push.get<Projection>("projectionMatrix").resize(window.width, window.height)
        innerRectangle.push.get<Matrix4f>("modelMatrix").setTranslation(0f, 0f, -6f).rotateY(0.2f)
        onscreenRectangle.push.get<Projection>("projectionMatrix").resize(window.width, window.height)
        onscreenRectangle.push.get<Matrix4f>("modelMatrix").setTranslation(0f, 0f, -6f).rotateY(0.2f)
    }

    fun update() {
        angle += 1.0f
        if (angle >= 360) {
            angle = angle - 360
        }
    }

    fun cleanup() {

    }

    var running = true

    fun run() {
        var initialTime = System.currentTimeMillis()
        var frame = 0
        while (running && !window.shouldClose()) {
            print(".")
            window.pollEvents()

            update()

            renderer.render()

            frame++
            if (frame % 100 == 0) {
                println("\n\n fps=${1000.0 * 100 / (System.currentTimeMillis() - initialTime)} \n\n")
                initialTime = System.currentTimeMillis()
            }
        }

        cleanup()
    }


    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
//            useVulkanSdk()
//            Configuration.VULKAN_LIBRARY_NAME.set("/Users/marc/VulkanSDK/1.3.296.0//macOS/lib/libvulkan.1.dylib")
            val main = NewMain6()
            main.run()
        }
    }
}


