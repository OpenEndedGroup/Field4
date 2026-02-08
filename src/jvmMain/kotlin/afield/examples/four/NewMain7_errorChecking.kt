package afield.examples.four

import afield.four.Renderer
import afield.four.modules.*
import afield.four.targets.RenderTarget
import afield.four.targets.RenderToTexture
import afield.four.texture.LoadedTexture
import afield.four.texture.TextureSampler
import afield.four.util.Projection
import afield.four.util.Window
import afield.four.util.forever
import afield.stdlib.fromViews
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.system.Configuration
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB
import java.io.File

class NewMain7_errorChecking {
    val window = Window("Field")
    val renderer = Renderer(window)

    var angle = 0.0f

    init {

        val texture = LoadedTexture( "/Users/marc/Desktop/bbc.jpg",renderer, VK_FORMAT_R8G8B8A8_SRGB)
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
            Parameters(renderer,
            vertex = File("resources/shaders/fwd_vertex.glsl").readText(),
            fragment = File("resources/shaders/fwd_fragment.glsl").readText(),
            depth = 0,
            textureResolver = {
                fromViews(listOf(texture))
            },
            ssboSizer = {
//                println(" resolving size for ssob $it @ ${it.memberSize()}")
//                it.memberSize() * 12
                0
            },
            name = "inner square shader",
            errorHandler = { description, exception ->

                when (exception) {
                    is CompilerError -> parseCompilerException(exception) { file, line, message ->
                        print("{file}:{line} {message}")
                    }

                    else -> println("unknown ERROR handler: '$description': message ${exception?.message ?: "(no exception thrown)"}")
                }

                System.exit(0)
            })
        )

        val rt2 = RenderToTexture(renderer)
        rt2.modules += "foo" to module.pointer()

        val justTexture = Module(
            Parameters(renderer,
            vertex = File("resources/shaders/plain_vertex_error.glsl").readText(),
            fragment = File("resources/shaders/plain_fragment_error.glsl").readText(),
            depth = 0,
            textureResolver = {
                rt2 to TextureSampler(renderer, 4, true)
            },
            name = "onscreen shader",
            errorHandler = { description, exception ->

                when (exception) {
                    is CompilerError -> {
                        println(" compilation errors:")
                        parseCompilerException(exception) { file, line, message ->
                            println("\t${file}:${line} ${message}")
                        }
                    }

                    else -> println("unknown ERROR handler: '$description': message ${exception?.message ?: "(no exception thrown)"}")
                }

                System.exit(0)
            })
        )

        val rt = RenderTarget(renderer)
        rt += "justTexture" to justTexture
        rt += "rt2" to rt2
        renderer.renderList = rt

        var onscreenRectangle = Mesh(renderer, justTexture.pointer())
        onscreenRectangle.resize( 4, 2)
        justTexture += "onscreenRectangle" to onscreenRectangle


        onscreenRectangle["pos"].mapAndWrite {
            for (v in 0 until positions.size / 3) {
                this.put(positions[v * 3 + 0])
                this.put(positions[v * 3 + 1])
                this.put(positions[v * 3 + 2])
            }
            this.rewind()
        }
        onscreenRectangle["tc"].mapAndWrite {
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


        onscreenRectangle.push["projectionMatrix"] = Projection(width=window.width, height=window.height)
        onscreenRectangle.push["modelMatrix"] = Matrix4f().setTranslation(0f, 0f, -3f)

        val innerRectangle = Mesh(renderer, module.pointer())
        innerRectangle.push["projectionMatrix"] = Projection(width=window.width, height=window.height)
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

        innerRectangle.push.get<Projection>("projectionMatrix").resize(window.width, window.height)
        innerRectangle.push.get<Matrix4f>("modelMatrix").setTranslation(0f, 0f, -6f).rotateY(0.2f)
        onscreenRectangle.push.get<Projection>("projectionMatrix").resize(window.width, window.height)
        onscreenRectangle.push.get<Matrix4f>("modelMatrix").setTranslation(0f, 0f, -6f).rotateY(0.2f)
    }

    private fun parseCompilerException(exception: CompilerError, accept: (String, Int, String) -> Unit) {
        val message = exception.message/*
        /Users/marc/temp/chapter-07/resources/shaders/plain_vertex_error.glsl:16: error: 'proj' : no such field in structure 'push_constants'
        /Users/marc/temp/chapter-07/resources/shaders/plain_vertex_error.glsl:16: error: 'assign' :  cannot convert from 'layout( column_major std430 push_constant) uniform block{layout( column_major std430 offset=0) uniform highp 4X4 matrix of float projectionMatrix, layout( column_major std430 offset=64) uniform highp 4X4 matrix of float modelMatrix}' to ' gl_Position 4-component vector of float Position'
        /Users/marc/temp/chapter-07/resources/shaders/plain_vertex_error.glsl:16: error: '' :  syntax error, unexpected IDENTIFIER, expecting COMMA or SEMICOLON
         */

        val regex = """:(\d+):""".toRegex() // Matches a number surrounded by ':' and puts the number in group 1
        message.split("\n").forEach {
            val f = regex.find(it)
            if (f != null) {
                val line = f.groupValues[1].toInt()

                val (file, message) = it.split(regex)
                accept(file.split("/").last(), line, message)
            } else {
                if (it.trim().length>0)
                accept("", -1, it)
            }
        }
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
            Configuration.VULKAN_LIBRARY_NAME.set("/Users/marc/VulkanSDK/1.3.296.0//macOS/lib/libvulkan.1.dylib")
            val main = NewMain7_errorChecking()
            main.run()
        }
    }
}
