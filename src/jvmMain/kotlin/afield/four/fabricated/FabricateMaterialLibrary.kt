package afield.four.fabricated

import org.joml.Vector3f
import org.joml.Vector4f
import java.util.IdentityHashMap

data class FabricateMaterialRecord(
    val baseColor: Vector4f,
    val metallic: Float,
    val roughness: Float,
    val occlusion: Float,
    val emissive: Vector3f,
    val alphaCutoff: Float,
    val alphaMode: FabricateAlphaMode,
    val doubleSided: Boolean,
    val textureMask: Int
)

/**
 * Stable CPU-side material table. This is the handoff shape for the future GPU material buffer:
 * scene nodes carry material objects, while render queues address compact material records by id.
 */
class FabricateMaterialLibrary {
    private val ids = IdentityHashMap<FabricateMaterial, Int>()
    private val records = mutableListOf<FabricateMaterialRecord>()

    fun idFor(material: FabricateMaterial): Int = ids.getOrPut(material) {
        val id = records.size
        records += material.toRecord()
        id
    }

    fun refresh(material: FabricateMaterial): Int {
        val id = idFor(material)
        records[id] = material.toRecord()
        return id
    }

    fun record(id: Int): FabricateMaterialRecord = records[id]

    fun records(): List<FabricateMaterialRecord> = records.toList()

    fun clear() {
        ids.clear()
        records.clear()
    }
}

fun FabricateMaterial.toRecord(): FabricateMaterialRecord {
    val standard = this as? FabricateStandardMaterial
    return FabricateMaterialRecord(
        baseColor = Vector4f(standard?.baseColor ?: Vector4f(1f, 1f, 1f, 1f)),
        metallic = standard?.metallic ?: 0f,
        roughness = standard?.roughness ?: 1f,
        occlusion = standard?.occlusion ?: 1f,
        emissive = Vector3f(standard?.emissive ?: Vector3f()),
        alphaCutoff = alphaCutoff,
        alphaMode = alphaMode,
        doubleSided = doubleSided,
        textureMask = standard?.textureMask() ?: 0
    )
}

fun FabricateStandardMaterial.textureMask(): Int {
    var mask = 0
    if (baseColorTexture?.valid == true) mask = mask or 1
    if (normalTexture?.valid == true) mask = mask or 2
    if (metallicRoughnessTexture?.valid == true) mask = mask or 4
    if (occlusionTexture?.valid == true) mask = mask or 8
    if (emissiveTexture?.valid == true) mask = mask or 16
    return mask
}
