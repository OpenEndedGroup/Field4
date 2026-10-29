package afield.graphics.shader

import afield.graphics.render.AccelerationStructure
import arrow.core.Either

/**
 * Decides whether a recompiled shader can be swapped in without a full reload.
 *
 * [graphicsShaderAbiCheck] and [computeShaderAbiCheck] compare the old and new [Introspection] of each
 * stage; a changed interface in any stage returns a [ShaderAbiCheck] that is not compatible, with a
 * message asking for a full reload.
 */

data class ShaderAbiCheck(
    /**
     * True when every stage presents the same interface after the recompile as before it, so the
     * running pipeline can take the new code instead of being rebuilt.
     */
    val compatible: Boolean,
    /**
     * Empty when compatible; otherwise names the stage that changed and prints its old and new
     * interface signatures in full. This is the text a refused *Update Shader* reports on the box,
     * below the summary of what changed.
     */
    val message: String = ""
)

/**
 * Compares a graphics shader's vertex and fragment interfaces across a recompile.
 *
 * The comparison covers each stage's inputs and outputs by location, its uniform buffers, storage
 * buffers, textures, images and acceleration structures by set and binding, its push constants and
 * its buffer-reference structures — and within each of those, member names, offsets and array
 * strides. A renamed uniform therefore reports incompatible even though the memory layout is
 * unchanged.
 */
fun graphicsShaderAbiCheck(
    oldVertex: Introspection,
    oldFragment: Introspection,
    newVertex: Introspection,
    newFragment: Introspection
): ShaderAbiCheck =
    shaderAbiCheck(
        old = listOf("vertex" to oldVertex, "fragment" to oldFragment),
        new = listOf("vertex" to newVertex, "fragment" to newFragment)
    )

/**
 * Compares a compute shader's interface across a recompile, on the same terms
 * [graphicsShaderAbiCheck] applies to each graphics stage.
 */
fun computeShaderAbiCheck(
    oldCompute: Introspection,
    newCompute: Introspection
): ShaderAbiCheck =
    shaderAbiCheck(
        old = listOf("compute" to oldCompute),
        new = listOf("compute" to newCompute)
    )

private fun shaderAbiCheck(
    old: List<Pair<String, Introspection>>,
    new: List<Pair<String, Introspection>>
): ShaderAbiCheck {
    val oldMap = old.toMap()
    val newMap = new.toMap()
    if (oldMap.keys != newMap.keys) {
        return ShaderAbiCheck(false, "Shader stage set changed: ${oldMap.keys.sorted()} -> ${newMap.keys.sorted()}. Use full reload.")
    }

    oldMap.keys.sorted().forEach { stage ->
        val oldSignature = oldMap.getValue(stage).shaderStageAbiSignature(stage)
        val newSignature = newMap.getValue(stage).shaderStageAbiSignature(stage)
        if (oldSignature != newSignature) {
            return ShaderAbiCheck(
                false,
                "Shader ABI changed in $stage stage. Use full reload.\nOld: $oldSignature\nNew: $newSignature"
            )
        }
    }

    return ShaderAbiCheck(true)
}

private fun Introspection.shaderStageAbiSignature(stage: String): String =
    buildString {
        append(stage)
        append("|kind=")
        append(kind.name)
        append("|inputs=")
        append(inputs.sortedBy { it.location }.joinToString(",", transform = ::inputSignature))
        append("|outputs=")
        append(outputs.sortedBy { it.location }.joinToString(",", transform = ::inputSignature))
        append("|ubos=")
        append(ubos.sortedWith(bindableComparator()).joinToString(",", transform = ::uboSignature))
        append("|ssbos=")
        append(ssbo.sortedWith(bindableComparator()).joinToString(",", transform = ::uboSignature))
        append("|textures=")
        append(textures.sortedWith(bindableComparator()).joinToString(",", transform = ::textureSignature))
        append("|images=")
        append(images.sortedWith(bindableComparator()).joinToString(",", transform = ::imageSignature))
        append("|accel=")
        append(accelerationStructures.sortedWith(bindableComparator()).joinToString(",", transform = ::accelerationSignature))
        append("|push=")
        append(pushConstants.sortedBy { it.name }.joinToString(",", transform = ::pushConstantSignature))
        append("|structs=")
        append(structureTypes.sortedBy { it.name }.joinToString(",", transform = ::structureTypeSignature))
    }

private fun bindableComparator(): Comparator<Introspected.Bindable> =
    compareBy<Introspected.Bindable> { it.set }.thenBy { it.binding }.thenBy { it.name }

private fun inputSignature(input: Introspected.Input): String =
    "${input.location}:${input.name}:${typeSignature(input.type)}:${input.sizeInBytes}"

private fun uboSignature(ubo: Introspected.UBO): String =
    "${ubo.set}.${ubo.binding}:${ubo.name}:${ubo.size}:${membersSignature(ubo.members)}"

private fun textureSignature(texture: Introspected.Texture): String =
    "${texture.set}.${texture.binding}:${texture.name}:${texture.type}:${texture.arrayCount}"

private fun imageSignature(image: Introspected.Image): String =
    "${image.set}.${image.binding}:${image.name}:${image.type}"

private fun accelerationSignature(acceleration: Introspected.AccelerationStructure): String =
    "${acceleration.set}.${acceleration.binding}:${acceleration.name}:${acceleration.type}"

private fun pushConstantSignature(pushConstant: Introspected.PushConstant): String =
    "${pushConstant.name}:${pushConstant.size}:${membersSignature(pushConstant.members)}"

private fun structureTypeSignature(structure: Introspected.StructureType): String =
    "${structure.name}:${structure.size}:${structure.bufferReference}:${membersSignature(structure.members)}"

private fun membersSignature(members: List<Introspected.Member>): String =
    members.sortedBy { it.offset }.joinToString(";", transform = ::memberSignature)

private fun memberSignature(member: Introspected.Member): String =
    "${member.offset}:${member.name}:${member.arrayStride}:${member.physicalPointer}:${typeSignature(member.type)}"

private fun typeSignature(type: Either<PrimitiveType, List<Introspected.Member>>): String =
    type.fold(
        { it.name },
        { nested -> "{${membersSignature(nested)}}" }
    )
