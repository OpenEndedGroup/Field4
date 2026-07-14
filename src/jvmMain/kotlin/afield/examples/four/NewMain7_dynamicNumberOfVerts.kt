package afield.examples.four

import org.joml.setTranslation
import org.joml.rotate
import org.joml.Matrix4f
import afield.stdlib.linalg.Vector3f
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB
import afield.four.*
import afield.four.modules.*
import afield.four.targets.RenderTarget
import afield.four.texture.LoadedTexture
import afield.four.util.Projection
import afield.four.util.Window
import afield.four.util.forever
import java.util.Random


//@Suppress("UNCHECKED_CAST")
//class A {
//    class C<T> {
//        operator fun setValue(inside: A, property: KProperty<*>, any: T) {
//        }
//
//        operator fun getValue(inside: A, property: KProperty<*>): T {
//            return "a" as T
//        }
//    }
//
//    var b by C<Vector3f>()
//
//    fun foo() {
//        b = Vector3f()
//        b = "banana"
//    }
//}

class NewMain7_dynamicNumberOfVerts {
    val window = Window("Field")
    val renderer = Renderer(window)

    var angle = 0.0f

    init {

        val rt = RenderTarget(renderer)
        renderer.renderList = rt


        val texture = LoadedTexture( "/Users/marc/Desktop/bbc.jpg", renderer,VK_FORMAT_R8G8B8A8_SRGB)
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
            vertex = """
                #version 450

                layout(location = 0) in vec3 pos;
                layout(location = 1) in vec4 color;
                
                layout(location = 0) out struct Output {
                    vec4 f_color;
                } o;
                
                layout(push_constant) uniform matrices {
                    mat4 projectionMatrix;
                    mat4 modelMatrix;
                } push_constants;


                void main()
                {
                    gl_Position = push_constants.projectionMatrix * push_constants.modelMatrix * vec4(pos, 1);
                    
                    o.f_color = color;
                }

            """.trimIndent(),
            fragment = """
                #version 450

                layout(location = 0) out vec4 uFragColor;
                
                layout(location = 0) in struct Output {
                    vec4 f_color;
                } o;

                void main()
                {
                    uFragColor = vec4(o.f_color.rgb, 1.0);
                }
            """.trimIndent(),
            name = "some shader")
        )


        rt += "module" to module

        val innerRectangle = Mesh(renderer, module.pointer())
        innerRectangle.push["projectionMatrix"] = Projection(width = window.width, height = window.height)
        innerRectangle.push["modelMatrix"] = Matrix4f().setTranslation(0f, 0f, -3f)

        module += "innerRectangle" to innerRectangle

        var frame = 0
        innerRectangle.update["resize"] = forever {

            frame++
            if (frame>10) return@forever true

            val layers = (frame / 50) % 10 + 1

            innerRectangle.resize( 4 * layers, 2 * layers)
            innerRectangle["pos"].mapAndWrite {
                for (layer in 0 until layers) {
                    for (v in 0 until positions.size / 3) {
                        this.put(positions[v * 3 + 0])
                        this.put(positions[v * 3 + 1])
                        this.put(positions[v * 3 + 2] + layer)
                    }
                }
                this.rewind()
            }

            innerRectangle["color"].mapAndWrite {
                val r = Random(100)
                for (v in 0 until this.capacity()) {
                    this.put(v, r.nextFloat())
                }
                this.rewind()
            }

            innerRectangle.t!!.mapAndWrite_ints {
                for (layer in 0 until layers) {
                    for (v in 0 until indices.size / 3) {
                        this.put(indices[v * 3 + 0] + layer * 4)
                        this.put(indices[v * 3 + 1] + layer * 4)
                        this.put(indices[v * 3 + 2] + layer * 4)
                    }
                }
                this.rewind()
            }

            true
        }

        innerRectangle.update["rot"] = forever {
            innerRectangle.push.get<Matrix4f>("modelMatrix").rotate(0.01f, Vector3f(1f, 0.8f, 0.3f))
            true
        }

        innerRectangle.push.get<Projection>("projectionMatrix").resize(window.width, window.height)
        innerRectangle.push.get<Matrix4f>("modelMatrix").setTranslation(0f, 0f, -6f).rotateY(0.2f)
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
                if (it.trim().length > 0)
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
//            Configuration.VULKAN_LIBRARY_NAME.set("/Users/marc/VulkanSDK/1.3.296.0//macOS/lib/libvulkan.1.dylib")
            val main = NewMain7_dynamicNumberOfVerts()
            main.run()
        }
    }
}
