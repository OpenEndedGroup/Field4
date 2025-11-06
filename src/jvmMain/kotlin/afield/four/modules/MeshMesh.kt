package afield.four.modules

import org.lwjgl.vulkan.EXTMeshShader.vkCmdDrawMeshTasksEXT
import afield.four.Renderer
import afield.four.buffers.MissingRefException
import afield.four.buffers.Structure
import afield.four.device.CommandBuffer
import afield.four.util.MapOfSequences
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
        if (inside != this.inside.current) println("WARNING, MeshMesh was built in a different module ${inside}!=${this.inside.current}")
        update.update()

        MemoryStack.stackPush().use { stack ->
            if (pushConstantBuffer.limit() > 0) {
                for (pc in inside.mi.pushConstants) {
                    push.setMembers(pc.members)
                }
                for (pc in inside.ti.pushConstants) {
                    push.setMembers(pc.members)
                }
                pushConstantBuffer.clear()
                push.writeToBuffer(pushConstantBuffer, cmd) {
                    throw MissingRefException("risk of GPU null ref `$it`")
                }
                pushConstantBuffer.clear()

                VK10.vkCmdPushConstants(
                    cmd.vkCommandBuffer,
                    inside.pipelineLayout,
                    VK10.VK_SHADER_STAGE_COMPUTE_BIT,
                    0,
                    pushConstantBuffer
                )
            } else {
            }

            if (x * y * z > 0) {
                vkCmdDrawMeshTasksEXT(cmd.vkCommandBuffer, x, y, z);
            } else {
                println(" MeshMesh has no work to do $x $y $z")
            }
        }
    }


}
