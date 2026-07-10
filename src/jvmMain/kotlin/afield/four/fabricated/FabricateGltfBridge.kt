package afield.four.fabricated

import afield.four.modules.GltfSupport
import org.lwjgl.assimp.Assimp

/** Converts the existing Assimp/GLTF loader model into the fabricated scene/material data model. */
object FabricateGltfBridge {
    fun fromModel(model: GltfSupport.GltfModel, name: String = "gltf"): FabricateScene {
        val root = FabricateNode(name)
        val scene = FabricateScene(root)
        scene.importGltf(model, namePrefix = name)
        return scene
    }
}

fun GltfSupport.MaterialTextured.toFabricateMaterial(): FabricateMaterial =
    FabricateMaterial.fromGltf(this)

fun Int.toFabricateTextureSemantic(): String = when (this) {
    Assimp.aiTextureType_BASE_COLOR -> "baseColor"
    Assimp.aiTextureType_DIFFUSE -> "diffuse"
    Assimp.aiTextureType_NORMALS -> "normal"
    Assimp.aiTextureType_EMISSIVE -> "emissive"
    Assimp.aiTextureType_METALNESS -> "metallic"
    Assimp.aiTextureType_DIFFUSE_ROUGHNESS -> "roughness"
    Assimp.aiTextureType_GLTF_METALLIC_ROUGHNESS -> "metallicRoughness"
    Assimp.aiTextureType_AMBIENT_OCCLUSION -> "occlusion"
    Assimp.aiTextureType_LIGHTMAP -> "lightmap"
    Assimp.aiTextureType_CLEARCOAT -> "clearcoat"
    else -> "texture-$this"
}
