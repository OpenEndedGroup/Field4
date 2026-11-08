package afield.graphics.shader

import kotlin.test.Test
import kotlin.test.assertTrue

class ShaderNestedPhysicalPointerTest {
    @Test
    fun nestedBufferReferencesAcceptPhysicalPointerAliases() {
        // SPIRV-Cross includes an alias type with no `members` array for nested pointers.
        val shader =
            Introspection(
                """
                #version 450
                #extension GL_EXT_buffer_reference : require
                layout(std430, buffer_reference, buffer_reference_align=4) readonly buffer Data { uint words[]; };
                struct Entry { Data data; vec4 bounds; };
                layout(std430, buffer_reference) readonly buffer Entries { Entry item[]; };
                layout(push_constant) uniform Push { Entries entries; } pc;
                layout(location=0) out vec4 colour;
                void main() { colour=vec4(float(pc.entries.item[0].data.words[0])); }
                """
                    .trimIndent(),
                ShaderKind.FRAGMENT_SHADER,
            )
        assertTrue(shader.valid, "nested physical-pointer reflection must succeed")
    }
}
