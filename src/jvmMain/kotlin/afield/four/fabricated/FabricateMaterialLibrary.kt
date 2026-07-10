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
    return FabricateMaterialRecord(
        baseColor = Vector4f(baseColor),
        metallic = metallic,
        roughness = roughness,
        occlusion = if (receiveAo) 1f else 0f,
        emissive = Vector3f(),
        alphaCutoff = alphaCutoff,
        alphaMode = alphaMode,
        doubleSided = doubleSided,
        textureMask = 0
    )
}
