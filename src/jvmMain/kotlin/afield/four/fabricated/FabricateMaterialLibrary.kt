package afield.four.fabricated

import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import java.util.IdentityHashMap

/**
 * The flattened per-material record a render queue carries, and the table that hands out its ids.
 *
 * [FabricateMaterial] is the authoring shape: nineteen fields, six of them texture handles.
 * [FabricateMaterialRecord] is nine fields wide, and `toRecord` is the conversion. It keeps
 * base colour, metallic, roughness, alpha cutoff, alpha mode and double-sidedness as they are,
 * turns `receiveAo` into a 0-or-1 occlusion scale, and leaves the textures, clearcoat, the UV
 * transform, `receiveShadow` and `sortOrder` behind. Two of the record's fields are placeholders
 * rather than conversions: `emissive` is written as zero instead of the material's
 * `emissiveFactor`, and `textureMask` as 0.
 *
 * [FabricateMaterialLibrary] keys materials by identity rather than by equality, so a material
 * shared between nodes gets one id and two materials with matching values get two. An id is a
 * position in a dense list, which is why `record(id)` is a plain index and `clear()` invalidates
 * every id handed out before it. `idFor` converts a material the first time it sees one and
 * returns the existing id afterwards without re-reading it; `refresh` re-reads into the same slot.
 * [FabricateScenePlanner] keeps a library and calls `refresh` for each visible renderable as it
 * plans, so [FabricateRenderPlan]'s `materials` list is current for the frame it was planned in,
 * and each item's `materialId` indexes it.
 */

/** One material flattened to nine fields, addressed by id from a render queue. */
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
