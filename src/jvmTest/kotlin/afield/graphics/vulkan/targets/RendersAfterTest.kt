package afield.graphics.vulkan.targets

import afield.graphics.vulkan.Target
import afield.graphics.vulkan.device.CommandBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/** A [RendersAfter] pass is moved behind its target, whichever was added first. */
class RendersAfterTest {
    private open class Pass : Target { override fun render(command: CommandBuffer, index: Int) {} }
    private class Publisher(override val rendersAfter: Target) : Pass(), RendersAfter

    @Test
    fun aPublisherAddedBeforeItsTargetRendersAfterIt() {
        val target = Pass(); val other = Pass()
        val list = linkedMapOf<String, Target>("publish" to Publisher(target), "other" to other, "target" to target)
        orderRendersAfter(list)
        assertEquals(listOf("other", "target", "publish"), list.keys.toList())
    }

    @Test
    fun aPublisherAlreadyBehindItsTargetStaysPut() {
        val target = Pass()
        val list = linkedMapOf<String, Target>("target" to target, "publish" to Publisher(target), "after" to Pass())
        orderRendersAfter(list)
        assertEquals(listOf("target", "publish", "after"), list.keys.toList())
    }

    @Test
    fun aPublisherWhoseTargetIsElsewhereIsLeftAlone() {
        val list = linkedMapOf<String, Target>("publish" to Publisher(Pass()), "other" to Pass())
        orderRendersAfter(list)
        assertEquals(listOf("publish", "other"), list.keys.toList())
    }
}
