package afield.graphics.render

import afield.graphics.shader.Introspection
import afield.graphics.shader.ShaderKind
import afield.graphics.vulkan.buffers.Structure
import afield.graphics.vulkan.buffers.bindPushConstant
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A push-constant binding runs once per dispatch. The fan-out check evaluates every binding before
 * the write does, and the write used to evaluate them again, so `parity = { step++ % 2 }` advanced
 * twice per frame and the GPU saw parity 1 every time (the growing-NCA freeze, dev_docs/GROWING_NCA.md).
 */
class PushBindingsOncePerDispatchTest {
    private val shader = """
        #version 450
        layout(local_size_x = 1) in;
        layout(push_constant) uniform pcs { uint parity; uint other; } pc;
        layout(std430, binding = 0) buffer Out { uint o[]; };
        void main() { o[pc.parity] = pc.other; }
    """.trimIndent()

    @Test
    fun aSideEffectingBindingRunsOnceAndItsValueIsWhatIsWritten() {
        val members = Introspection(shader, ShaderKind.COMPUTE_SHADER).pushConstants.single().members
        val push = Structure().setMembers(members)
        var step = 0
        push.bindPushConstant("parity", { step++ % 2 }) { error("no parity member") }
        push.bindPushConstant("other", { 7 }) { error("no other member") }
        val written = mutableListOf<Int>()
        repeat(4) {
            val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            withEvaluatedPush(push) { refs ->
                assertEquals(0, refs.size)
                push.writeToBuffer(buffer, null) { error("unexpected autowire of $it") }
            }
            written += buffer.getInt(0)
        }
        assertEquals(4, step, "one evaluation per dispatch")
        assertEquals(listOf(0, 1, 0, 1), written, "the GPU sees every value the binding produced")
    }
}
