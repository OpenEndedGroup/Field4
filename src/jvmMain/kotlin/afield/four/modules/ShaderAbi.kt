package afield.four.modules

import arrow.core.Either

data class ShaderAbiCheck(
    val compatible: Boolean,
    val message: String = ""
)

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
