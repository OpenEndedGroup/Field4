package afield.four.fabricated

import afield.four.modules.GltfSupport
import org.lwjgl.assimp.Assimp

/** Converts the existing Assimp/GLTF loader model into the fabricated scene/material data model. */
object FabricateGltfBridge {
    fun fromModel(model: GltfSupport.GltfModel, name: String = "gltf"): FabricateNode {
        val root = FabricateNode(name)
        model.meshes.forEachIndexed { index, mesh ->
            val node = FabricateMeshNode(
                name = mesh.name.ifBlank { "mesh-$index" },
                material = mesh.material.toFabricateMaterial("${mesh.name.ifBlank { "mesh-$index" }}-material"),
                source = mesh
            )
            node.local.translation.set(mesh.bindPose.t)
            node.local.rotation.set(mesh.bindPose.r)
            node.local.scale.set(mesh.bindPose.s)
            root += node
        }
        root.updateWorld(parentWorld = null, force = true)
        return root
    }
}

fun GltfSupport.MaterialTextured.toFabricateMaterial(name: String = "gltf-material"): FabricateStandardMaterial {
    val material = FabricateStandardMaterial(
        name = name,
        baseColor = baseColor,
        metallic = 0f,
        roughness = 0.5f,
        occlusion = 1f
    )
    textures.forEach { slot ->
        val fabricateSlot = FabricateTextureSlot(
            semantic = slot.type.toFabricateTextureSemantic(),
            texture = slot.texture,
            uvSet = slot.uvSet,
            valid = slot.valid
        )
        when (slot.type) {
            Assimp.aiTextureType_BASE_COLOR,
            Assimp.aiTextureType_DIFFUSE -> material.baseColorTexture = fabricateSlot
            Assimp.aiTextureType_NORMALS -> material.normalTexture = fabricateSlot
            Assimp.aiTextureType_METALNESS,
            Assimp.aiTextureType_DIFFUSE_ROUGHNESS -> material.metallicRoughnessTexture = fabricateSlot
            Assimp.aiTextureType_EMISSIVE -> material.emissiveTexture = fabricateSlot
        }
    }
    return material
}

fun Int.toFabricateTextureSemantic(): String = when (this) {
    Assimp.aiTextureType_BASE_COLOR -> "baseColor"
    Assimp.aiTextureType_DIFFUSE -> "diffuse"
    Assimp.aiTextureType_NORMALS -> "normal"
    Assimp.aiTextureType_EMISSIVE -> "emissive"
    Assimp.aiTextureType_METALNESS -> "metallic"
    Assimp.aiTextureType_DIFFUSE_ROUGHNESS -> "roughness"
    else -> "texture-$this"
}
