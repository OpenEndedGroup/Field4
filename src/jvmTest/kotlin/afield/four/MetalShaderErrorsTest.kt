package afield.four

import afield.Box
import afield.four.modules.ShaderKind
import afield.stdlib.preprocess
import afield.stdlib.stackAutoShaderSources
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A shader name that collides with Metal's standard library, on macOS ([MetalShaderErrors]): said
 * plainly when the compile fails, and refused early for a `stack.auto` slot.
 */
class MetalShaderErrorsTest {
    // What MoltenVK reported for a stack.auto slot named `decay` (2026-09-28).
    private val reported = """
        VK_ERROR_INITIALIZATION_FAILED: Shader library compile failed (Error code 3):
        program_source:170:16: error: reference to 'decay' is ambiguous
            float4 d = decay(param);
                       ^
        program_source:161:8: note: candidate found by name lookup is 'decay'
        float4 decay(thread const float4& decay_in)
               ^
        /System/Library/PrivateFrameworks/GPUCompiler.framework/Versions/32023/Libraries/lib/clang/32023.884/include/metal/metal_type_traits:883:8: note: candidate found by name lookup is 'metal::decay'
        struct decay
    """.trimIndent()

    @Test
    fun aCollisionWithMetalsStandardLibraryIsSaidPlainly() {
        val said = MetalShaderErrors.explain(reported)!!
        assertTrue("'decay' in your shader has the same name as metal::decay" in said && "Rename 'decay'" in said, said)
        assertNull(MetalShaderErrors.explain("program_source:12:3: error: use of undeclared identifier 'q'"), "other Metal errors are left as they are")
    }

    @Test
    fun aSlotNamedLikeSomethingInMetalIsRefusedBeforeItBreaksTheShader() {
        // Only where the Metal headers are, which is only macOS.
        if (MetalShaderErrors.reservedNames.isEmpty()) return
        assertTrue(listOf("decay", "enable_if", "array").all { it in MetalShaderErrors.reservedNames })
        // Metal's functions are only overloaded by a function of the same name, or renamed on the way: both build.
        assertTrue("select" !in MetalShaderErrors.reservedNames && "saturate" !in MetalShaderErrors.reservedNames)
        assertTrue("explode" !in MetalShaderErrors.reservedNames && "face_decay" !in MetalShaderErrors.reservedNames)
        val box = Box("metal_names_test")
        box.stackAutoShaderSources["decay"] = "uv fbm"
        val refused = assertFailsWith<IllegalArgumentException> {
            preprocess(box, "#version 450\nlayout(location = 0) out vec4 c;\n{{ stack.auto.decay }}\nvoid main() { c = decay(vec4(0.0)); }\n", ShaderKind.FRAGMENT_SHADER)
        }
        assertTrue("metal::decay" in (refused.message ?: "") + (refused.cause?.message ?: ""), refused.toString())
    }
}
