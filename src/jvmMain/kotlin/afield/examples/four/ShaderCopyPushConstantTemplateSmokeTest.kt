package afield.examples.four

import afield.Box
import afield.stdlib._fragment
import afield.stdlib._mesh
import afield.stdlib.fragment
import afield.stdlib.macro_meshModuleHere
import afield.stdlib.macro_moduleHereWithDefaultShaders
import afield.stdlib.mesh
import afield.stdlib.task
import afield.stdlib.vertex

class ShaderCopyPushConstantTemplateSmokeTest {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            verifyGraphicsPipelineCopyPc()
            verifyMeshPipelineCopyPc()
            println("ShaderCopyPushConstantTemplateSmokeTest ok")
        }

        private fun verifyGraphicsPipelineCopyPc() {
            val errors = mutableListOf<String>()
            val box = Box("shader-copy-pc-graphics").apply {
                name = "shader-copy-pc-graphics"
                output.errorProxy = { message, property, line ->
                    if (message != null) errors += "graphics:$property:$line:$message"
                }
            }

            box.vertex = """
                #version 450

                layout(push_constant) uniform Controls {
                    vec4 tint;
                } controls;

                vec2 positions[3] = vec2[](
                    vec2(-0.5, -0.5),
                    vec2(0.5, -0.5),
                    vec2(0.0, 0.5)
                );

                void main() {
                    gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
                }
            """.trimIndent()

            box.fragment = """
                #version 450

                layout(location = 0) out vec4 outColor;
                {% copyPC %}

                void main() {
                    outColor = controls.tint;
                }
            """.trimIndent()

            box.macro_moduleHereWithDefaultShaders(listOf("()"))

            require(errors.isEmpty()) { errors.joinToString(separator = "\n") }
            val fragmentSource = requireNotNull(box._fragment) { "Expected compiled fragment shader source" }
            require("{% copyPC %}" !in fragmentSource) { "Expected copyPC tag to be expanded in graphics fragment shader" }
            require("layout(push_constant) uniform Controls" in fragmentSource) {
                "Expected copied push constant declaration in graphics fragment shader:\n$fragmentSource"
            }
        }

        private fun verifyMeshPipelineCopyPc() {
            val errors = mutableListOf<String>()
            val box = Box("shader-copy-pc-mesh").apply {
                name = "shader-copy-pc-mesh"
                output.errorProxy = { message, property, line ->
                    if (message != null) errors += "mesh:$property:$line:$message"
                }
            }

            box.task = """
                #version 460
                #extension GL_EXT_mesh_shader : enable

                layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;

                layout(push_constant) uniform Controls {
                    vec4 tint;
                } controls;

                void main() {
                    EmitMeshTasksEXT(1, 1, 1);
                }
            """.trimIndent()

            box.mesh = """
                #version 460
                #extension GL_EXT_mesh_shader : enable

                layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;
                layout(triangles) out;
                layout(max_vertices = 3, max_primitives = 1) out;

                {% copyPC %}

                void main() {
                    SetMeshOutputsEXT(3, 1);
                    gl_MeshVerticesEXT[0].gl_Position = vec4(-0.5 + controls.tint.x * 0.0, -0.5, 0.0, 1.0);
                    gl_MeshVerticesEXT[1].gl_Position = vec4(0.5, -0.5, 0.0, 1.0);
                    gl_MeshVerticesEXT[2].gl_Position = vec4(0.0, 0.5, 0.0, 1.0);
                    gl_PrimitiveTriangleIndicesEXT[0] = uvec3(0, 1, 2);
                }
            """.trimIndent()

            box.fragment = """
                #version 450

                layout(location = 0) out vec4 outColor;
                {% copyPC %}

                void main() {
                    outColor = controls.tint;
                }
            """.trimIndent()

            box.macro_meshModuleHere(listOf("()"))

            require(errors.isEmpty()) { errors.joinToString(separator = "\n") }
            val meshSource = requireNotNull(box._mesh) { "Expected compiled mesh shader source" }
            val fragmentSource = requireNotNull(box._fragment) { "Expected compiled fragment shader source" }
            require("{% copyPC %}" !in meshSource) { "Expected copyPC tag to be expanded in mesh shader" }
            require("{% copyPC %}" !in fragmentSource) { "Expected copyPC tag to be expanded in mesh fragment shader" }
            require("layout(push_constant) uniform Controls" in meshSource) {
                "Expected copied push constant declaration in mesh shader:\n$meshSource"
            }
            require("layout(push_constant) uniform Controls" in fragmentSource) {
                "Expected copied push constant declaration in mesh fragment shader:\n$fragmentSource"
            }
        }
    }
}
