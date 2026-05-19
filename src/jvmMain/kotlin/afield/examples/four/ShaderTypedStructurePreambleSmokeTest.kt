package afield.examples.four

import afield.Box
import afield.KotlinInterface
import afield.preamble
import afield.stdlib.applyInsertToPreamble
import afield.stdlib.fragment
import afield.stdlib.macro_moduleHereWithDefaultShaders
import afield.stdlib.vertex

class ShaderTypedStructurePreambleSmokeTest {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val errors = mutableListOf<String>()
            val box = Box("shader-typed-structure-preamble").apply {
                name = "shader-typed-structure-preamble"
                output.errorProxy = { message, property, line ->
                    if (message != null) errors += "$property:$line:$message"
                }
            }

            val declarations = """
                #extension GL_EXT_buffer_reference : require
                #extension GL_EXT_scalar_block_layout : enable

                struct Params {
                    vec4 resolution_time_samples;
                    vec4 screen;
                    vec4 basis;
                    vec4 kali0;
                    vec4 kali1;
                    vec4 source0;
                    vec4 source1;
                    vec4 blend0;
                    vec4 blend1;
                    vec4 noise0;
                    vec4 iter0;
                    vec4 aa0;
                    vec4 flags0;
                };

                struct SpatialVar {
                    vec4 meta0;
                    vec4 meta1;
                    vec4 meta2;
                    vec4 meta3;
                };

                layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer KaliParamsBuffer {
                    Params P;
                    SpatialVar V0;
                    SpatialVar V1;
                    SpatialVar V2;
                    SpatialVar V3;
                };

                layout(push_constant, scalar) uniform Push {
                    KaliParamsBuffer params;
                } pc;
            """.trimIndent()

            box.vertex = """
                #version 450
                $declarations

                layout (location = 0) in vec3 pos;
                layout (location = 1) in vec2 tc;
                layout (location = 1) out vec2 ftc;

                void main()
                {
                    gl_Position = vec4(pos + vec3(pc.params.P.screen.w * 0.0), 1);
                    ftc = tc;
                }
            """.trimIndent()

            box.fragment = """
                #version 450
                $declarations

                layout (location = 1) in vec2 ftc;
                layout (location = 0) out vec4 outColor;

                void main()
                {
                    outColor = vec4(pc.params.P.noise0.x + ftc.x * 0.0, pc.params.V0.meta0.y * 0.0, 0.0, 1.0);
                }
            """.trimIndent()

            val insert = requireNotNull(box.macro_moduleHereWithDefaultShaders(listOf("()"))) {
                "Expected moduleHere macro to return an insert"
            }

            require(errors.isEmpty()) { errors.joinToString(separator = "\n") }

            val preamble = insert.preambleAfterImports
            require(preamble.contains("class Params(")) { preamble }
            require(preamble.contains("class SpatialVar(")) { preamble }
            require(preamble.contains("class KaliParamsBuffer(")) { preamble }
            require(preamble.contains("val P: Params")) { preamble }
            require(preamble.contains("val V0: SpatialVar")) { preamble }
            require(preamble.contains("var noise0 by Structure.FromStructure<org.joml.Vector4f>()")) { preamble }
            require(preamble.contains("fun KaliParamsBufferByteSize(): Long = 464L")) { preamble }
            require(!preamble.contains("const val byteSize")) { preamble }
            require(preamble.contains("fun HasBuffer.asKaliParamsBuffer(): KaliParamsBuffer")) { preamble }
            require(preamble.split("class Params(").size - 1 == 1) { preamble }
            require(preamble.split("class KaliParamsBuffer(").size - 1 == 1) { preamble }

            val preambleBox = Box("shader-typed-structure-preamble-qa").apply {
                this.preamble = """
                    var Box.target by Inheritance<Int>() /**/
                    import java.nio.*
                    /*start*/
                    /*graphics-structure-type:KailParamsBuffer:KailParamsBuffer*/
                    class KailParamsBuffer
                    /*end*/
                    /*graphics-pc-structure:params*/ var Structure.params by Structure.FromStructure<Any>()
                """.trimIndent()
            }
            applyInsertToPreamble(preambleBox, insert)
            val normalizedPreamble = preambleBox.preamble
            val firstImport = normalizedPreamble.indexOf("import ")
            val firstDeclaration = normalizedPreamble.indexOf("var Box.target")
            require(firstImport >= 0 && firstImport < firstDeclaration) { normalizedPreamble }
            require("KailParamsBuffer" !in normalizedPreamble) { normalizedPreamble }
            require(normalizedPreamble.split("graphics-pc-structure:params").size - 1 == 1) { normalizedPreamble }
            require(normalizedPreamble.split("class KaliParamsBuffer(").size - 1 == 1) { normalizedPreamble }

            val compileErrors = mutableListOf<String>()
            val evalErrors = mutableListOf<String>()
            val evalExceptions = mutableListOf<String>()
            val ki = KotlinInterface(filePrefix = "shader-typed-structure-preamble")
            try {
                ki.execAndWait(
                    """
                    ${insert.preamble}
                    ${insert.preambleAfterImports}

                    val typedBufferLayoutSmoke = KaliParamsBuffer().apply {
                        P.noise0 = org.joml.Vector4f(1f, 2f, 3f, 4f)
                        V0.meta0 = org.joml.Vector4f(5f, 6f, 7f, 8f)
                    }
                    val typedBufferNoiseSmoke = typedBufferLayoutSmoke.P.noise0
                    val typedBufferAsHasBufferSmoke: HasBuffer = typedBufferLayoutSmoke
                    val typedBufferByteSizeSmoke = KaliParamsBufferByteSize()
                    """.trimIndent(),
                    onCompileError = { diagnostics -> compileErrors += diagnostics.joinToString("\n") { it.message } },
                    onEvalError = { diagnostics -> evalErrors += diagnostics.joinToString("\n") { it.message } },
                    onEvalException = { throwable -> evalExceptions += throwable.stackTraceToString() },
                    implicits = emptyList()
                )
                require(compileErrors.isEmpty()) { compileErrors.joinToString("\n") }
                require(evalErrors.isEmpty()) { evalErrors.joinToString("\n") }
                require(evalExceptions.isEmpty()) { evalExceptions.joinToString("\n") }
            } finally {
                KotlinInterface.compilationThread.close()
            }

            println("ShaderTypedStructurePreambleSmokeTest ok")
        }
    }
}
