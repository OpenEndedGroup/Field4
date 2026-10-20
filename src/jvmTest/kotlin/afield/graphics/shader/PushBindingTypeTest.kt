package afield.graphics.shader

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The type `moduleHere` gives a mesh's push-constant supplier. A scalar `float` member takes a
 * `() -> Number`, so `time = { AnimationClock.nowSeconds() - started }` binds the `Double` as it stands
 * and Structure narrows it when it writes (`StructureTypeCheck.coerce`); it used to be `() -> Float`,
 * and the same line needed a `.toFloat()` to compile. Vector members keep their own type.
 */
class PushBindingTypeTest {
    private val vertexSource = """
        #version 450
        layout(push_constant) uniform Push {
            float time;
            vec4 tint;
        } pc;
        layout (location = 0) in vec3 pos;
        void main() { gl_Position = vec4(pos, 1) * pc.time * pc.tint.w; }
    """.trimIndent()

    private val fragmentSource = """
        #version 450
        layout(push_constant) uniform Push {
            float time;
            vec4 tint;
        } pc;
        layout (location = 0) out vec4 col;
        void main() { col = pc.tint * pc.time; }
    """.trimIndent()

    @Test
    fun aScalarFloatSupplierTakesAnyNumber() {
        val vi = Introspection(vertexSource, ShaderKind.VERTEX_SHADER)
        val fi = Introspection(fragmentSource, ShaderKind.FRAGMENT_SHADER)
        assertTrue(vi.valid && fi.valid, "shaders did not compile")

        val preamble = insertForShader(vi, fi, args = listOf("()")).preambleAfterImports

        assertTrue("var Mesh.time by Mesh.BoundFromStructure<Number>()" in preamble, preamble)
        assertTrue("var MeshGroup.time by MeshGroup.BoundFromStructure<Number>()" in preamble, preamble)
        assertTrue("var Mesh.tint by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>()" in preamble, preamble)
    }
}
