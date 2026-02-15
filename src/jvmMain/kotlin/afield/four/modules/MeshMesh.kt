package afield.four.modules

import org.lwjgl.vulkan.EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT
import org.lwjgl.vulkan.EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT
import org.lwjgl.vulkan.EXTMeshShader.vkCmdDrawMeshTasksEXT
import afield.four.Renderer
import afield.four.buffers.MissingRefException
import afield.four.buffers.Structure
import afield.four.device.CommandBuffer
import afield.four.util.MapOfSequences
import afield.logger.lprintln
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import java.nio.ByteBuffer
import kotlin.reflect.KProperty


interface MeshTask {
    fun compute(inside: MeshModule, cmd: CommandBuffer, pushConstantBuffer: ByteBuffer)

}

fun meshTask(c: (inside: MeshModule, cmd: CommandBuffer, pushConstantBuffer: ByteBuffer) -> Unit): MeshTask {
    return object : MeshTask {
        override fun compute(
            inside: MeshModule,
            cmd: CommandBuffer,
            pushConstantBuffer: ByteBuffer
        ) {
            c(inside, cmd, pushConstantBuffer)
        }
    }
}
// As names go, this one is pretty bad
class MeshMesh(var renderer: Renderer = fromContext()) : MeshTask {

    class FromUniform {
        operator fun getValue(inside: MeshModulePointer, property: KProperty<*>): Structure {
            return inside.current.liveUniformStructures[property.name]
                ?: throw IllegalArgumentException(" can't find '${property.name}' in this module, options are ${inside.current.liveUniformStructures.keys}")
        }
    }

    class BoundFromStructure<T> {
        operator fun setValue(inside: MeshMesh, property: KProperty<*>, any: () -> T) {
            if (inside.push.offsets.isEmpty()) {
                inside.initializePushConstantsFromModule(inside.inside)
            }
            if (inside.push.offsets.containsKey(property.name))
                inside.push.bind(property.name, any as Function0<*>)
            else throw IllegalArgumentException(" can't find ${property.name} in a mesh in this module ${inside.inside.current.parameters.name} as a push_constant")
        }

        operator fun getValue(inside: MeshMesh, property: KProperty<*>): () -> T {
            if (inside.push.offsets.isEmpty()) {
                inside.initializePushConstantsFromModule(inside.inside)
            }
            if (inside.push.offsets.containsKey(property.name))
                return inside.push.bindings[property.name] as () -> T
            else throw IllegalArgumentException(" can't find ${property.name} in a mesh in this module ${inside.inside.current.parameters.name} as a push_constant or a UBO structure")
        }
    }


    constructor(renderer: Renderer = fromContext(), pointer: MeshModulePointer = fromContext()) : this(renderer) {
        this.inside = pointer
    }

    lateinit var inside: MeshModulePointer

    var update = MapOfSequences<String>()

    var push = Structure()

    var x = 1;
    var y = 1;
    var z = 1;

    internal fun initializePushConstantsFromModule(inside: MeshModulePointer) {
        for (pc in inside.current.mi.pushConstants) {
            push.setMembers(pc.members)
        }
        for (pc in inside.current.ti.pushConstants) {
            push.setMembers(pc.members)
        }
    }

    class FromStructure<T> {
        operator fun setValue(inside: MeshMesh, property: KProperty<*>, any: T) {
            if (inside.push.offsets.isEmpty()) {
                inside.initializePushConstantsFromModule(inside.inside)
            }

            if (inside.push.offsets.containsKey(property.name)) inside.push.set(property.name, any as Any)
            else throw IllegalArgumentException(" can't find ${property.name} in a mesh in this module ${inside.inside.current.parameters.name} as a push_constant")
        }

        operator fun getValue(inside: MeshMesh, property: KProperty<*>): T {
            if (inside.push.offsets.isEmpty()) {
                inside.initializePushConstantsFromModule(inside.inside)
            }
            if (inside.push.offsets.containsKey(property.name)) return inside.push.get(property.name) as T
//            else if (property.name in inside.inside.current.liveUniformStructures) {
//                return inside.inside.current.liveUniformStructures[property.name] as T
//            }
            else throw IllegalArgumentException(" can't find ${property.name} in a mesh in this module ${inside.inside.current.parameters.name} as a push_constant or a UBO structure")
        }
    }


    override fun compute(inside: MeshModule, cmd: CommandBuffer, pushConstantBuffer: ByteBuffer) {
        if (inside != this.inside.current) lprintln("warning,graphics", "MeshMesh built in a different module ${inside}!=${this.inside.current}")
        update.update()

        MemoryStack.stackPush().use { _ ->
            fun pushConstantsForCurrentDispatch(): Boolean {
                if (pushConstantBuffer.limit() <= 0) return true
                for (pc in inside.mi.pushConstants) {
                    push.setMembers(pc.members)
                }
                for (pc in inside.ti.pushConstants) {
                    push.setMembers(pc.members)
                }
                pushConstantBuffer.clear()
                try {
                    push.writeToBuffer(pushConstantBuffer, cmd) {
                        throw MissingRefException("risk of GPU null ref `$it`")
                    }
                } catch (e: Exception) {
                    inside.parameters.errorHandler(e.message ?: "push constant error", e)
                    lprintln("error,graphics", "push constant error, not dispatching mesh task : ${e.message}")
                    return false
                }
                pushConstantBuffer.clear()

                VK10.vkCmdPushConstants(
                    cmd.vkCommandBuffer,
                    inside.pipelineLayout,
                    VK_SHADER_STAGE_TASK_BIT_EXT or VK_SHADER_STAGE_MESH_BIT_EXT,
                    0,
                    pushConstantBuffer
                )
                return true
            }

            fun dispatchForCurrentWriter(dispatchX: Int?, dispatchY: Int?, dispatchZ: Int?) {
                val xx = dispatchX ?: x
                val yy = dispatchY ?: y
                val zz = dispatchZ ?: z
                if (xx * yy * zz > 0) {
                    vkCmdDrawMeshTasksEXT(cmd.vkCommandBuffer, xx, yy, zz)
                } else {
                    lprintln("warning,graphics", "MeshMesh has no work to do $xx $yy $zz")
                }
            }

            try {
                forEachFanOutDispatch(
                    renderer = renderer,
                    push = push,
                    onNoFanOut = {
                        if (pushConstantsForCurrentDispatch()) {
                            dispatchForCurrentWriter(null, null, null)
                        }
                    },
                    onWriter = { writer ->
                        if (pushConstantsForCurrentDispatch()) {
                            dispatchForCurrentWriter(
                                dispatchX = writer.publication.dispatchX,
                                dispatchY = writer.publication.dispatchY,
                                dispatchZ = writer.publication.dispatchZ
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                inside.parameters.errorHandler(e.message ?: "fan-out dispatch error", e)
                lprintln("error,graphics", "fan-out dispatch error, not dispatching mesh task : ${e.message}")
            }
        }
    }


}
