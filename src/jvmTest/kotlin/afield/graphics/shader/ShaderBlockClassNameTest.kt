package afield.graphics.shader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A uniform block's generated class must not hide a type every box imports. A block called `camera`
 * used to generate `class Camera`, after which `Camera()` built the block and `camera.at` stopped
 * resolving; it now generates `CameraBlock` and says why.
 */
class ShaderBlockClassNameTest {
    private val vertex = """
        #version 450
        layout(set = 0, binding = 0) uniform camera { mat4 view; } cam;
        layout(set = 0, binding = 1) uniform wobble { float amount; } wob;
        layout (location = 0) in vec3 pos;
        void main() { gl_Position = cam.view * vec4(pos * wob.amount, 1); }
    """.trimIndent()

    private val fragment = """
        #version 450
        layout (location = 0) out vec4 col;
        void main() { col = vec4(1); }
    """.trimIndent()

    @Test
    fun aBlockNamedAfterAnImportedTypeIsGivenItsOwnName() {
        val vi = Introspection(vertex, ShaderKind.VERTEX_SHADER)
        val fi = Introspection(fragment, ShaderKind.FRAGMENT_SHADER)
        assertTrue(vi.valid && fi.valid, "the shaders did not compile")
        val preamble = insertForShader(vi, fi, args = listOf("()")).preambleAfterImports

        assertTrue("class CameraBlock(" in preamble, preamble)
        assertFalse(Regex("""\bclass Camera\(""").containsMatchIn(preamble), "the block hides Field's Camera:\n$preamble")
        assertTrue("// GLSL block `camera` is CameraBlock here: Camera would hide afield.graphics.vulkan.util.Camera." in preamble, preamble)
        assertTrue("fun HasBuffer.asCameraBlock(): CameraBlock" in preamble, preamble)
        // A block whose name hides nothing keeps it.
        assertTrue("class Wobble(" in preamble, preamble)
    }
}
