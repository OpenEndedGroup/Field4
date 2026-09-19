@file:Suppress("unused")

package afield.four.modules

import afield.stdlib.TriMesh
import afield.stdlib.TriMeshBuilder
import afield.stdlib.Vec2
import afield.stdlib.Vec3
import afield.stdlib.linalg.Matrix4f
import org.lwjgl.system.MemoryUtil.memFree

/**
 * A rigged file, deformed at a time, as a [TriMesh] you can render, measure and look at.
 *
 * [assimpToTriMesh] is the rigid sibling of this: it applies each node's own world matrix and hands
 * back the bind pose. This one ignores the mesh node's transform — glTF 2.0 §3.7.3.2 says a skinned
 * mesh node's transform is *not* applied — and instead runs [GltfSkinning.skin] with the joint
 * palette at [seconds], so what comes back is the deformed surface in scene space.
 *
 * A mesh in the file with no skin is not dropped: it goes through the rigid path, so a scene that is
 * part character and part prop arrives whole.
 *
 * ```kotlin
 * val bent = skinnedGltfToTriMesh("dev_docs/agents/fixtures/skinning/bending_cylinder.gltf",
 *                                 clip = "bend", seconds = 1.0f)
 * showMesh(bent)
 * ```
 *
 * @param clip which animation to evaluate, by name. `null` is the rest pose — which, for a file whose
 *   first keys are the bind transforms, is the bind pose and should be identical to
 *   [assimpToTriMesh]'s output vertex for vertex.
 * @param seconds the time within the clip. Clamped at both ends; this does not loop, because a caller
 *   that wants looping wants to choose the phase itself.
 */
fun skinnedGltfToTriMesh(
    path: String,
    clip: String? = null,
    seconds: Float = 0f,
    keepColors: Boolean = true,
    options: GltfSkinning.SkinningOptions = GltfSkinning.SkinningOptions(),
): TriMesh = skinnedGltfToTriMesh(path, keepColors, options) { skeleton ->
    skeleton.palette(clip?.let { skeleton.clip(it) }, seconds)
}

/**
 * The same, with the palette chosen by the caller — for a clip blend, a pose driven by an IK solver,
 * or anything else that is not "clip *x* at time *t*".
 *
 * @param palette called once per skinned mesh with that mesh's skeleton; must return one matrix per
 *   entry of [GltfSkinning.Skeleton.bones], in that order. [GltfSkinning.Skeleton.palette] and
 *   [GltfSkinning.Skeleton.blendedPalette] are the two ready-made ones.
 */
fun skinnedGltfToTriMesh(
    path: String,
    keepColors: Boolean = true,
    options: GltfSkinning.SkinningOptions = GltfSkinning.SkinningOptions(),
    palette: (GltfSkinning.Skeleton) -> List<Matrix4f>,
): TriMesh {
    val model = GltfSupport.loadGltfBasic(path, GltfSupport.GltfLoadConfig(loadTextures = false))
    try {
        var out: TriMesh? = null
        for (instance in model.meshes) {
            val b = instance.buffers
            if (b.indexCount < 3) continue
            val floats = b.vertexData.asFloatBuffer()

            // The packed layout the loader writes: [pos.xyz][normal.xyz][uv.xy][color.rgba].
            val positions = FloatArray(b.vertexCount * 3)
            val normals = FloatArray(b.vertexCount * 3)
            var whiteEverywhere = true
            var anyUv = false
            val rows = ArrayList<FloatArray>(b.vertexCount)
            for (v in 0 until b.vertexCount) {
                val o = v * 12
                val row = FloatArray(12) { floats.get(o + it) }
                positions[v * 3] = row[0]; positions[v * 3 + 1] = row[1]; positions[v * 3 + 2] = row[2]
                normals[v * 3] = row[3]; normals[v * 3 + 1] = row[4]; normals[v * 3 + 2] = row[5]
                if (row[8] != 1f || row[9] != 1f || row[10] != 1f) whiteEverywhere = false
                if (row[6] != 0f || row[7] != 0f) anyUv = true
                rows.add(row)
            }

            val skin = instance.skin
            val deformedP: FloatArray
            val deformedN: FloatArray
            val mirrors: Boolean
            if (skin != null) {
                val skinned = GltfSkinning.skin(positions, normals, skin.influences, palette(skin.skeleton), options)
                deformedP = skinned.position
                deformedN = skinned.normal ?: normals
                // A skinned mesh's winding is decided per triangle by the blended matrix, not by one
                // scene determinant, and a joint palette of rigid transforms never flips it. Left
                // alone rather than guessed at; a mirroring *joint* would need per-triangle work.
                mirrors = false
            } else {
                // Rigid fallback, matching assimpToTriMesh exactly: the node's true world matrix, and
                // the inverse transpose for the normal.
                val m = instance.bindMatrix ?: instance.bindPose.toMatrix()
                val nm = m.get3x3(afield.stdlib.linalg.Matrix3f()).invert().transpose()
                deformedP = FloatArray(b.vertexCount * 3)
                deformedN = FloatArray(b.vertexCount * 3)
                val p = afield.stdlib.linalg.Vector3f()
                val n = afield.stdlib.linalg.Vector3f()
                for (v in 0 until b.vertexCount) {
                    p.set(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2]); m.transformPosition(p)
                    n.set(normals[v * 3], normals[v * 3 + 1], normals[v * 3 + 2]); nm.transform(n)
                    val len = kotlin.math.sqrt(n.x * n.x + n.y * n.y + n.z * n.z)
                    deformedP[v * 3] = p.x; deformedP[v * 3 + 1] = p.y; deformedP[v * 3 + 2] = p.z
                    if (len > 1e-12f) {
                        deformedN[v * 3] = n.x / len; deformedN[v * 3 + 1] = n.y / len; deformedN[v * 3 + 2] = n.z / len
                    } else {
                        deformedN[v * 3 + 1] = 1f
                    }
                }
                mirrors = m.determinant() < 0f
            }

            val emitColors = keepColors && !whiteEverywhere
            val builder = TriMeshBuilder()
            for (v in 0 until b.vertexCount) {
                val row = rows[v]
                builder.vertex(
                    deformedP[v * 3], deformedP[v * 3 + 1], deformedP[v * 3 + 2],
                    normal = Vec3(deformedN[v * 3], deformedN[v * 3 + 1], deformedN[v * 3 + 2]),
                    uv = if (anyUv) Vec2(row[6], row[7]) else null,
                    color = if (emitColors) Vec3(row[8], row[9], row[10]) else null,
                )
            }
            val indices = b.indexData
            var t = 0
            while (t + 2 < b.indexCount) {
                val a = indices.get(t); val bb = indices.get(t + 1); val c = indices.get(t + 2)
                if (a < b.vertexCount && bb < b.vertexCount && c < b.vertexCount) {
                    if (mirrors) builder.triangle(a, c, bb) else builder.triangle(a, bb, c)
                }
                t += 3
            }
            val part = builder.build()
            out = out?.plus(part) ?: part
        }
        return out ?: TriMesh.empty
    } finally {
        for (instance in model.meshes) {
            memFree(instance.buffers.vertexData)
            memFree(instance.buffers.indexData)
            instance.buffers.tangentData?.let { memFree(it) }
        }
    }
}

/** The clip names in a rigged file, in file order. Empty for a file with no animations. */
fun gltfClipNames(path: String): List<String> {
    val model = GltfSupport.loadGltfBasic(path, GltfSupport.GltfLoadConfig(loadTextures = false))
    try {
        return model.meshes.firstNotNullOfOrNull { it.skin?.skeleton?.clips?.map { c -> c.name } } ?: emptyList()
    } finally {
        for (instance in model.meshes) {
            memFree(instance.buffers.vertexData)
            memFree(instance.buffers.indexData)
            instance.buffers.tangentData?.let { memFree(it) }
        }
    }
}
