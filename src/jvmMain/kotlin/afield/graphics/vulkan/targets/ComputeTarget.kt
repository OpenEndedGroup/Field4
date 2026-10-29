package afield.graphics.vulkan.targets

import afield.app.diagnostics.Named
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.render.ComputeModule
import afield.graphics.render.ComputeModulePointer
import afield.graphics.render.RayTracingModule
import afield.graphics.render.RayTracingModulePointer
import afield.graphics.render.pointer
import afield.math.linalg.Rectanglef
import org.lwjgl.system.MemoryStack

/**
 * A [Target] that records compute and ray-tracing modules into a frame without drawing to a window.
 *
 * Modules and plain command-recording functions are added with `+=` on a [ComputeTarget] and run each
 * frame: compute modules first, then ray-tracing modules, then the other targets.
 */

/** Records named compute modules, ray-tracing modules and other [Target]s into the frame's command buffer. */
class ComputeTarget(
    /** The renderer this target records into, and the identity each added module's lifecycle claim is filed under. */
    val renderer: Renderer
) : Target {

    /** The compute modules dispatched each frame, by name, in insertion order and before [rayTracingModules]. Add with `+=`. */
    var modules = mutableMapOf<String, ComputeModulePointer>()

    /** The ray-tracing modules traced each frame, by name, after every compute module has been recorded. */
    var rayTracingModules = mutableMapOf<String, RayTracingModulePointer>()

    /** Targets recorded after this one's own modules, by name. A `(name, (CommandBuffer, Int) -> Unit)` pair added with `+=` lands here too, wrapped as a target, which is how plain recording code joins the frame. */
    var others = mutableMapOf<String, Target>()

    @JvmName("plusAssign")
    operator fun plusAssign(m: Pair<String, ComputeModulePointer>) {
        modules.putOwned(m.first, m.second, renderer)
    }

    @JvmName("plusAssign_n")
    operator fun plusAssign(m: Named<ComputeModulePointer>) {
        modules.putOwned(m.name, m.value, renderer)
    }

    @JvmName("plusAssign_module")
    operator fun plusAssign(m: Pair<String, ComputeModule>) {
        modules.putOwned(m.first, m.second.pointer(), renderer)
    }

    @JvmName("plusAssign_rayTracing")
    operator fun plusAssign(m: Pair<String, RayTracingModulePointer>) {
        rayTracingModules.putOwned(m.first, m.second, renderer)
    }

    @JvmName("plusAssign_rayTracingModule")
    operator fun plusAssign(m: Pair<String, RayTracingModule>) {
        rayTracingModules.putOwned(m.first, m.second.pointer(), renderer)
    }

    @JvmName("plusAssign_target")
    operator fun plusAssign(m: Pair<String, Target>) {
        others.putOwned(m.first, m.second, renderer)
    }

    @JvmName("plusAssign_function")
    operator fun plusAssign(m: Pair<String, (CommandBuffer, Int) -> Unit>) {
        others.putOwned(m.first, object : Target {
            override fun render(command: CommandBuffer, index: Int) {
                m.second.invoke(command, index)
            }
        }, renderer)
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

            rayTracingModules.forEach {
                it.value.render(command, Rectanglef(0f, 0f, 0f, 0f))
            }

//            vkEndCommandBuffer(command.vkCommandBuffer)

            others.forEach { it.value.render(command, index) }
        }
    }


}
