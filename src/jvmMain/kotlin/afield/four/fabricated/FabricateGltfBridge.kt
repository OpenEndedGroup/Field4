package afield.four.fabricated

import afield.four.modules.GltfSupport
import org.lwjgl.assimp.Assimp

/**
 * The glTF side of the fabricated scene model: one call that turns a loaded `GltfSupport.GltfModel`
 * into a [FabricateScene], and two conversions beside it.
 *
 * There are two routes from a loaded model into the PBR renderer and they differ in what they
 * touch. `FabricateGltfBridge.fromModel` builds a scene graph and stops, so a caller can read or
 * rewrite the imported nodes before any GPU resource exists. `StandardRenderer.Bundle.registerGltf`
 * does that same import and then registers a pulling mesh per node, which is what the renderer
 * draws from and what a tool inspecting node names does not need.
 *
 * Nothing here loads a file. `GltfSupport.loadGltfBasic` is the loader and `MeshFromDisk.kt`
 * describes the route in; this is the conversion at the end of it.
 */

/** Converts the existing Assimp/GLTF loader model into the fabricated scene/material data model. */
object FabricateGltfBridge {
    /**
     * A [FabricateScene] holding [model]'s meshes, with no renderer and no GPU resources attached.
     *
     * [name] is the root node's name and the prefix on every imported node's.
     * `FabricateScene.importGltf` is this same import with its material classification open as a
     * parameter, and `StandardRenderer.Bundle.registerGltf` is the import plus the pulling meshes a
     * renderer draws from.
     */
    fun fromModel(model: GltfSupport.GltfModel, name: String = "gltf"): FabricateScene {
        val root = FabricateNode(name)
        val scene = FabricateScene(root)
        scene.importGltf(model, namePrefix = name)
        return scene
    }
}

/**
 * `FabricateMaterial.fromGltf` as an extension, for a glTF material already in hand.
 *
 * Nothing in the tree calls it. The import path reaches `fromGltf` through
 * `FabricateScene.importGltf`'s default material lambda instead.
 */
fun GltfSupport.MaterialTextured.toFabricateMaterial(): FabricateMaterial =
    FabricateMaterial.fromGltf(this)

/**
 * An Assimp texture-type constant as the glTF name for that slot: `baseColor`, `normal`,
 * `metallicRoughness` and so on, with an unrecognised type rendered as `texture-` and its number
 * rather than dropped.
 *
 * A label, not a lookup key, and nothing in the tree calls it. `FabricateMaterial.fromGltf` picks
 * its textures by matching the Assimp constants directly and never consults this, so the
 * `baseColor` returned here and the `baseColor` push constant `standardRenderer.kt` seeds are one
 * word for two unrelated things.
 */
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
