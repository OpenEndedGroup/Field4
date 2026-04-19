package afield.four.targets

import afield.Named
import afield.four.Renderer
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.modules.ComputeModule
import afield.four.modules.ComputeModulePointer
import afield.four.modules.pointer
import org.joml.primitives.Rectanglef
import org.lwjgl.system.MemoryStack

class ComputeTarget(val renderer: Renderer) : Target {

    var modules = mutableMapOf<String, ComputeModulePointer>()
    var others = mutableMapOf<String, Target>()

    @JvmName("plusAssign")
    operator fun plusAssign(m: Pair<String, ComputeModulePointer>) {
        modules[m.first] = m.second
    }

    @JvmName("plusAssign_n")
    operator fun plusAssign(m: Named<ComputeModulePointer>) {
        modules[m.name] = m.value
    }

    @JvmName("plusAssign_module")
    operator fun plusAssign(m: Pair<String, ComputeModule>) {
        modules[m.first] = m.second.pointer()
    }

    @JvmName("plusAssign_target")
    operator fun plusAssign(m: Pair<String, Target>) {
        others[m.first] = m.second
    }

    @JvmName("plusAssign_function")
    operator fun plusAssign(m: Pair<String, (CommandBuffer, Int) -> Unit>) {
        others[m.first] = object : Target {
            override fun render(command: CommandBuffer, index: Int) {
                m.second.invoke(command, index)
            }
        }
    }

    override fun render(command: CommandBuffer, index: Int) {
        MemoryStack.stackPush().use { stack ->

            // this is a difference from rendertarget (which is using a more dynamic thing)

//            val begin = VkCommandBufferBeginInfo.calloc(stack)
//            begin.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
//            vkBeginCommandBuffer(command.vkCommandBuffer, begin)

            modules.forEach {
                it.value.render(command, Rectanglef(0f, 0f, 0f, 0f))
            }

//            vkEndCommandBuffer(command.vkCommandBuffer)

            others.forEach { it.value.render(command, index) }
        }
    }


}
