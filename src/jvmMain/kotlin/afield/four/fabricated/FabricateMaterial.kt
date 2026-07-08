package afield.four.fabricated

import afield.four.texture.LoadedTexture
import org.joml.Vector3f
import org.joml.Vector4f

enum class FabricateAlphaMode {
    Opaque,
    Mask,
    Blend
}

data class FabricateTextureSlot(
    val semantic: String,
    val texture: LoadedTexture? = null,
    val uvSet: Int = 0,
    val valid: Boolean = texture != null
)

open class FabricateMaterial(
    var name: String = "material",
    var alphaMode: FabricateAlphaMode = FabricateAlphaMode.Opaque,
    var doubleSided: Boolean = false,
    var alphaCutoff: Float = 0.5f
) {
    val transparent: Boolean
        get() = alphaMode == FabricateAlphaMode.Blend
}

class FabricateStandardMaterial(
    name: String = "standard-material",
    val baseColor: Vector4f = Vector4f(1f, 1f, 1f, 1f),
    var metallic: Float = 0f,
    var roughness: Float = 0.5f,
    var occlusion: Float = 1f,
    val emissive: Vector3f = Vector3f(),
    alphaMode: FabricateAlphaMode = FabricateAlphaMode.Opaque,
    doubleSided: Boolean = false,
    alphaCutoff: Float = 0.5f
) : FabricateMaterial(name, alphaMode, doubleSided, alphaCutoff) {
    var baseColorTexture: FabricateTextureSlot? = null
    var normalTexture: FabricateTextureSlot? = null
    var metallicRoughnessTexture: FabricateTextureSlot? = null
    var occlusionTexture: FabricateTextureSlot? = null
    var emissiveTexture: FabricateTextureSlot? = null
}
