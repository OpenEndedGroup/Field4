package afield.stdlib

import afield.four.modules.Introspection
import afield.four.modules.ShaderKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What `computeModuleHere` puts in a box's preamble for a compute shader's uniform block. It used to be a
 * `ModulePointer` accessor — the graphics module's — so `module.params` did not resolve on the
 * `ComputeModulePointer` a compute box holds.
 */
class ComputePreambleTest {
    @Test
    fun aComputeUniformBlockIsReachedFromTheComputeModule() {
        val shader = "#version 450\nlayout(local_size_x = 1) in;\nlayout(std140, set = 0, binding = 0) uniform params { float gain; };\n" +
            "layout(std430, set = 0, binding = 1) buffer outbuf { float values[]; };\nvoid main() { values[0] = gain; }\n"
        val preamble = buildComputeInsert(Introspection(shader, ShaderKind.COMPUTE_SHADER)).preambleAfterImports
        assertTrue("val ComputeModulePointer.params by ComputeMesh.FromUniform()" in preamble, preamble)
        assertFalse("val ModulePointer.params" in preamble, "not the graphics module's accessor")
        assertFalse("var Mesh.params" in preamble, "nor a graphics mesh's push constant")
        assertTrue("var Structure.gain by Structure.FromStructure" in preamble, "its members are still reachable")
    }
}
