package afield.server

import afield.four.modules.GltfSupport.GltfModel
import afield.four.modules.GltfSupport.MaterialTextured
import afield.four.modules.GltfSupport.MeshBuffers
import afield.four.modules.GltfSupport.MeshInstance
import afield.four.modules.GltfSupport.TRS
import afield.four.modules.GltfSupport.TextureSlot
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.assimp.AIAnimation
import org.lwjgl.assimp.AIColor4D
import org.lwjgl.assimp.AIMaterial
import org.lwjgl.assimp.AIMatrix4x4
import org.lwjgl.assimp.AIMesh
import org.lwjgl.assimp.AINode
import org.lwjgl.assimp.AINodeAnim
import org.lwjgl.assimp.AIQuatKey
import org.lwjgl.assimp.AIScene
import org.lwjgl.assimp.AIVectorKey
import org.lwjgl.assimp.Assimp
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

private val assimpAdaptersRegistered = AtomicBoolean(false)

/** One-time registration of Browsable adapters for AssImp + GLTF helpers. */
fun registerAssimpBrowsingAdapters() {
    if (!assimpAdaptersRegistered.compareAndSet(false, true)) return

    // GLTF high-level views ------------------------------------------------------------------------------

    BrowsableAdapters.register(GltfModel::class) { model, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "GLTF Model"
            override fun dir(): Map<String, Any?> {
                val meshes = linkedMapOf<String, Any?>()
                model.meshes.withIndex().forEach { (i, m) ->
                    meshes["mesh[$i] ${m.name}"] = m
                }
                return meshes
            }

            override fun html(): String {
                val textured = model.meshes.count { m -> m.material.textures.any { it.valid } }
                val animated = model.meshes.count { it.animationSamples != null }
                val verts = model.meshes.sumOf { it.buffers.vertexCount }
                val tris = model.meshes.sumOf { it.buffers.indexCount / 3 }
                val rows = listOf(
                    "meshes" to model.meshes.size.toString(),
                    "vertices" to verts.toString(),
                    "triangles" to tris.toString(),
                    "textured" to "$textured/${model.meshes.size}",
                    "animated" to "$animated/${model.meshes.size}"
                )
                return card(heading(), rows, accent = "#60a5fa")
            }
        }
    }

    BrowsableAdapters.register(MeshInstance::class) { mesh, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: mesh.name.ifBlank { "Mesh ${mesh.meshIndex}" }
            override fun dir(): Map<String, Any?> {
                val out = linkedMapOf<String, Any?>(
                    "buffers" to mesh.buffers,
                    "material" to mesh.material,
                    "bindPose" to mesh.bindPose
                )
                mesh.animationSamples?.let { out["animation"] = it }
                return out
            }

            override fun html(): String {
                val textured = mesh.material.textures.count { it.valid }
                val rows = listOf(
                    "mesh index" to mesh.meshIndex.toString(),
                    "name" to mesh.name.ifBlank { "(unnamed)" },
                    "vertices" to mesh.buffers.vertexCount.toString(),
                    "triangles" to (mesh.buffers.indexCount / 3).toString(),
                    "buffers" to "${fmtBytes(mesh.buffers.vertexData.remaining().toLong())} / ${fmtBytes(mesh.buffers.indexData.remaining().toLong() * Int.SIZE_BYTES)}",
                    "textures" to "$textured/${mesh.material.textures.size}",
                    "animation" to (mesh.animationSamples?.let { "${it.size} samples" } ?: "none")
                )
                return card(heading(), rows, accent = "#a78bfa")
            }
        }
    }

    BrowsableAdapters.register(MeshBuffers::class) { buffers, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "MeshBuffers"
            override fun dir(): Map<String, Any?>? = null
            override fun html(): String {
                val stride = if (buffers.vertexCount > 0) buffers.vertexData.remaining() / buffers.vertexCount else 0
                val rows = listOf(
                    "vertices" to buffers.vertexCount.toString(),
                    "indices" to buffers.indexCount.toString(),
                    "vertex bytes" to fmtBytes(buffers.vertexData.remaining().toLong()),
                    "index bytes" to fmtBytes(buffers.indexData.remaining().toLong() * Int.SIZE_BYTES),
                    "stride" to "$stride bytes"
                )
                return card(heading(), rows, accent = "#34d399")
            }
        }
    }

    BrowsableAdapters.register(MaterialTextured::class) { mat, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "Material"
            override fun dir(): Map<String, Any?> = linkedMapOf(
                "textures" to mat.textures
            )

            override fun html(): String {
                val active = mat.textures.count { it.valid }
                val rows = listOf(
                    "baseColor" to "rgba(%.2f, %.2f, %.2f, %.2f)".format(
                        mat.baseColor.x, mat.baseColor.y, mat.baseColor.z, mat.baseColor.w
                    ),
                    "textures" to "$active/${mat.textures.size}"
                )
                return card(heading(), rows, accent = "#f59e0b")
            }
        }
    }

    BrowsableAdapters.register(TextureSlot::class) { slot, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: textureTypeName(slot.type)
            override fun dir(): Map<String, Any?> = mapOf("texture" to slot.texture)
            override fun html(): String {
                val rows = listOf(
                    "type" to textureTypeName(slot.type),
                    "uv set" to slot.uvSet.toString(),
                    "valid" to slot.valid.toString()
                )
                return card(heading(), rows, accent = if (slot.valid) "#38bdf8" else "#9ca3af")
            }
        }
    }

    BrowsableAdapters.register(TRS::class) { trs, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "Transform"
            override fun dir(): Map<String, Any?>? = null
            override fun html(): String {
                val rows = listOf(
                    "translate" to vec3(trs.t),
                    "scale" to vec3(trs.s),
                    "rotation" to quat(trs.r)
                )
                return card(heading(), rows, accent = "#67e8f9")
            }
        }
    }

    // Raw Assimp views -----------------------------------------------------------------------------------

    BrowsableAdapters.register(AIScene::class) { scene, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "Assimp Scene"
            override fun dir(): Map<String, Any?> {
                val meshes = linkedMapOf<String, Any?>()
                for (i in 0 until scene.mNumMeshes()) {
                    meshes["mesh[$i]"] = MeshWithMaterial(
                        i,
                        AIMesh.create(scene.mMeshes()!![i]),
                        scene.materialAtMeshIndex(i)
                    )
                }

                val materials = linkedMapOf<String, Any?>()
                for (i in 0 until scene.mNumMaterials()) {
                    materials["material[$i]"] = AIMaterial.create(scene.mMaterials()!![i])
                }

                val animations = linkedMapOf<String, Any?>()
                for (i in 0 until scene.mNumAnimations()) {
                    animations["anim[$i]"] = AIAnimation.create(scene.mAnimations()!![i])
                }

                return linkedMapOf(
                    "root" to scene.mRootNode()?.let { NodeView(it, scene, path = it.mName().dataString().ifBlank { "/" }) },
                    "meshes" to meshes,
                    "materials" to materials,
                    "animations" to animations
                ).filterValues { it != null }
            }

            override fun html(): String {
                val rows = listOf(
                    "meshes" to scene.mNumMeshes().toString(),
                    "materials" to scene.mNumMaterials().toString(),
                    "animations" to scene.mNumAnimations().toString(),
                    "textures" to scene.mNumTextures().toString(),
                    "flags" to "0x${scene.mFlags().toString(16)}"
                )
                return card(heading(), rows, accent = "#22d3ee")
            }
        }
    }

    BrowsableAdapters.register(NodeView::class) { holder, _, title ->
        val node = holder.node
        val trs = node.mTransformation()?.let { toTrs(it) }
        object : Browsable {
            override fun heading(): String = title ?: node.mName().dataString().ifBlank { holder.path }
            override fun dir(): Map<String, Any?> {
                val meshMap = linkedMapOf<String, Any?>()
                node.mMeshes()?.let { idxBuf ->
                    for (i in 0 until idxBuf.remaining()) {
                        val meshIdx = idxBuf[i]
                        meshMap["mesh[$meshIdx]"] = holder.mesh(meshIdx)
                    }
                }

                val childMap = linkedMapOf<String, Any?>()
                node.mChildren()?.let { buf ->
                    for (i in 0 until buf.remaining()) {
                        val child = AINode.create(buf[i]) ?: continue
                        val name = child.mName().dataString().ifBlank { "child[$i]" }
                        childMap[name] = NodeView(child, holder.scene, childPath(holder.path, name))
                    }
                }

                val out = linkedMapOf<String, Any?>()
                if (trs != null) out["transform"] = trs
                if (meshMap.isNotEmpty()) out["meshes"] = meshMap
                if (childMap.isNotEmpty()) out["children"] = childMap
                return out
            }

            override fun html(): String {
                val rows = listOf(
                    "path" to holder.path,
                    "meshes" to (node.mMeshes()?.remaining() ?: 0).toString(),
                    "children" to (node.mChildren()?.remaining() ?: 0).toString(),
                    "translate" to (trs?.let { vec3(it.t) } ?: "-")
                )
                return card(heading(), rows, accent = "#c084fc")
            }
        }
    }

    BrowsableAdapters.register(MeshWithMaterial::class) { wrap, _, title ->
        val mesh = wrap.mesh ?: return@register null
        object : Browsable {
            override fun heading(): String = title ?: mesh.mName().dataString().ifBlank { "mesh[${wrap.meshIndex}]" }
            override fun dir(): Map<String, Any?> = linkedMapOf(
                "mesh" to mesh,
                "material" to wrap.material
            )

            override fun html(): String {
                val indexCount = mesh.indexCount()
                val bounds = calcBounds(mesh)
                val matIdx = mesh.mMaterialIndex()
                val rows = listOf(
                    "vertices" to mesh.mNumVertices().toString(),
                    "triangles" to (indexCount / 3).toString(),
                    "material" to if (wrap.material != null && matIdx >= 0) "material[$matIdx]" else "none",
                    "attributes" to attributes(mesh),
                    "bounds" to (bounds?.let { "${vec3(it.min)} – ${vec3(it.max)}" } ?: "n/a")
                )
                return card(heading(), rows, accent = "#10b981")
            }
        }
    }

    BrowsableAdapters.register(AIMesh::class) { mesh, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: mesh.mName().dataString().ifBlank { "Mesh" }
            override fun dir(): Map<String, Any?>? = null
            override fun html(): String {
                val indexCount = mesh.indexCount()
                val bounds = calcBounds(mesh)
                val rows = listOf(
                    "vertices" to mesh.mNumVertices().toString(),
                    "triangles" to (indexCount / 3).toString(),
                    "material index" to mesh.mMaterialIndex().toString(),
                    "attributes" to attributes(mesh),
                    "bounds" to (bounds?.let { "${vec3(it.min)} – ${vec3(it.max)}" } ?: "n/a")
                )
                return card(heading(), rows, accent = "#4ade80")
            }
        }
    }

    BrowsableAdapters.register(AIMaterial::class) { mat, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "Material"
            override fun dir(): Map<String, Any?>? = null
            override fun html(): String {
                val texCounts = textureTypes().map { t ->
                    textureTypeName(t) to Assimp.aiGetMaterialTextureCount(mat, t).toString()
                }
                val base = AIColor4D.calloc()
                val colorRes = Assimp.aiGetMaterialColor(mat, "\$clr.base", 0, 0, base)
                val colorStr = if (colorRes == Assimp.aiReturn_SUCCESS) {
                    "rgba(%.2f, %.2f, %.2f, %.2f)".format(base.r(), base.g(), base.b(), base.a())
                } else {
                    "n/a"
                }
                base.free()

                val rows = listOf("baseColor" to colorStr) + texCounts
                return card(heading(), rows, accent = "#f97316")
            }
        }
    }

    BrowsableAdapters.register(AIAnimation::class) { anim, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: anim.mName().dataString().ifBlank { "Animation" }
            override fun dir(): Map<String, Any?> {
                val channels = linkedMapOf<String, Any?>()
                anim.mChannels()?.let { buf ->
                    for (i in 0 until anim.mNumChannels()) {
                        channels["channel[$i]"] = AINodeAnim.create(buf[i])
                    }
                }
                return channels
            }

            override fun html(): String {
                val ticks = if (anim.mTicksPerSecond() != 0.0) anim.mTicksPerSecond() else 25.0
                val duration = if (ticks > 0.0) anim.mDuration() / ticks else 0.0
                val (posTimes, rotTimes, scaleTimes) = aggregateTimes(anim)
                val rows = listOf(
                    "name" to anim.mName().dataString().ifBlank { "(unnamed)" },
                    "channels" to anim.mNumChannels().toString(),
                    "duration(s)" to "%.2f".format(duration),
                    "ticks/s" to "%.2f".format(ticks)
                )
                val card = card(heading(), rows, accent = "#fb7185")
                val timeline = renderTimeline(posTimes, rotTimes, scaleTimes, duration)
                return card + (timeline ?: "")
            }
        }
    }

    BrowsableAdapters.register(AINodeAnim::class) { chan, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: chan.mNodeName().dataString().ifBlank { "Channel" }
            override fun dir(): Map<String, Any?>? = null
            override fun html(): String {
                val rows = listOf(
                    "node" to chan.mNodeName().dataString(),
                    "positions" to chan.mNumPositionKeys().toString(),
                    "rotations" to chan.mNumRotationKeys().toString(),
                    "scales" to chan.mNumScalingKeys().toString()
                )
                val card = card(heading(), rows, accent = "#60a5fa")
                val posTimes = keyTimes(chan.mPositionKeys(), chan.mNumPositionKeys())
                val rotTimes = keyTimes(chan.mRotationKeys(), chan.mNumRotationKeys())
                val scaleTimes = keyTimes(chan.mScalingKeys(), chan.mNumScalingKeys())
                val timeline = renderTimeline(posTimes, rotTimes, scaleTimes, null)
                return card + (timeline ?: "")
            }
        }
    }
}

// Helpers -----------------------------------------------------------------------------------------------------------

private data class NodeView(val node: AINode, val scene: AIScene, val path: String)
private data class MeshWithMaterial(val meshIndex: Int, val mesh: AIMesh?, val material: AIMaterial?)
private data class Bounds(val min: Vector3f, val max: Vector3f)

private fun childPath(parent: String, name: String): String = if (parent == "/") "/$name" else "$parent/$name"

private fun AIScene.materialAtMeshIndex(meshIndex: Int): AIMaterial? {
    val mesh = AIMesh.create(mMeshes()!![meshIndex]) ?: return null
    val idx = mesh.mMaterialIndex()
    if (idx < 0 || idx >= mNumMaterials()) return null
    return AIMaterial.create(mMaterials()!![idx])
}

private fun NodeView.mesh(idx: Int): MeshWithMaterial {
    val mesh = AIMesh.create(scene.mMeshes()!![idx])
    val mat = scene.materialAtMeshIndex(idx)
    return MeshWithMaterial(idx, mesh, mat)
}

private fun calcBounds(mesh: AIMesh): Bounds? {
    val verts = mesh.mVertices() ?: return null
    if (mesh.mNumVertices() <= 0) return null
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    for (i in 0 until mesh.mNumVertices()) {
        val v = verts[i]
        minX = min(minX, v.x())
        minY = min(minY, v.y())
        minZ = min(minZ, v.z())
        maxX = max(maxX, v.x())
        maxY = max(maxY, v.y())
        maxZ = max(maxZ, v.z())
    }
    return Bounds(Vector3f(minX, minY, minZ), Vector3f(maxX, maxY, maxZ))
}

private fun AIMesh.indexCount(): Int {
    var indexCount = 0
    val faces = mFaces()
    if (faces != null) {
        for (i in 0 until mNumFaces()) {
            indexCount += faces[i].mNumIndices()
        }
    }
    return indexCount
}

private fun toTrs(m: AIMatrix4x4): TRS {
    val mat = Matrix4f(
        m.a1(), m.b1(), m.c1(), m.d1(),
        m.a2(), m.b2(), m.c2(), m.d2(),
        m.a3(), m.b3(), m.c3(), m.d3(),
        m.a4(), m.b4(), m.c4(), m.d4()
    )
    val t = Vector3f()
    val r = org.joml.Quaternionf()
    val s = Vector3f()
    mat.getTranslation(t)
    mat.getUnnormalizedRotation(r)
    mat.getScale(s)
    return TRS(t, r.normalize(), s)
}

private fun attributes(mesh: AIMesh): String {
    val attrs = mutableListOf<String>()
    if (mesh.mNormals() != null) attrs += "normals"
    if (mesh.mTextureCoords(0) != null) attrs += "uv0"
    if (mesh.mColors(0) != null) attrs += "color0"
    if (mesh.mTangents() != null && mesh.mBitangents() != null) attrs += "tangent"
    if (attrs.isEmpty()) attrs += "position"
    return attrs.joinToString(", ")
}

private fun textureTypes(): List<Int> = listOf(
    Assimp.aiTextureType_BASE_COLOR,
    Assimp.aiTextureType_DIFFUSE,
    Assimp.aiTextureType_NORMALS,
    Assimp.aiTextureType_EMISSIVE,
    Assimp.aiTextureType_METALNESS,
    Assimp.aiTextureType_DIFFUSE_ROUGHNESS
)

private fun textureTypeName(type: Int): String = when (type) {
    Assimp.aiTextureType_BASE_COLOR -> "baseColor"
    Assimp.aiTextureType_DIFFUSE -> "diffuse"
    Assimp.aiTextureType_NORMALS -> "normal"
    Assimp.aiTextureType_EMISSIVE -> "emissive"
    Assimp.aiTextureType_METALNESS -> "metallic"
    Assimp.aiTextureType_DIFFUSE_ROUGHNESS -> "roughness"
    else -> "type $type"
}

private fun vec3(v: Vector3f): String = "(%.3f, %.3f, %.3f)".format(v.x, v.y, v.z)
private fun quat(q: org.joml.Quaternionf): String =
    "(%.3f, %.3f, %.3f, %.3f)".format(q.x, q.y, q.z, q.w)

private fun fmtBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val kb = n / 1024.0
    if (kb < 1024) return String.format("%.1f KiB", kb)
    val mb = kb / 1024.0
    return String.format("%.2f MiB", mb)
}

private fun keyTimes(buf: AIVectorKey.Buffer?, count: Int): List<Double> {
    if (buf == null || count <= 0) return emptyList()
    val out = ArrayList<Double>(count)
    for (i in 0 until count) out += buf[i].mTime()
    return out
}

private fun keyTimes(buf: AIQuatKey.Buffer?, count: Int): List<Double> {
    if (buf == null || count <= 0) return emptyList()
    val out = ArrayList<Double>(count)
    for (i in 0 until count) out += buf[i].mTime()
    return out
}

private fun aggregateTimes(anim: AIAnimation): Triple<List<Double>, List<Double>, List<Double>> {
    val pos = mutableListOf<Double>()
    val rot = mutableListOf<Double>()
    val scale = mutableListOf<Double>()
    anim.mChannels()?.let { buf ->
        for (i in 0 until anim.mNumChannels()) {
            val ch = AINodeAnim.create(buf[i]) ?: continue
            pos += keyTimes(ch.mPositionKeys(), ch.mNumPositionKeys())
            rot += keyTimes(ch.mRotationKeys(), ch.mNumRotationKeys())
            scale += keyTimes(ch.mScalingKeys(), ch.mNumScalingKeys())
        }
    }
    return Triple(pos, rot, scale)
}

private fun renderTimeline(
    positionKeys: List<Double>,
    rotationKeys: List<Double>,
    scaleKeys: List<Double>,
    durationSeconds: Double?
): String? {
    val all = positionKeys + rotationKeys + scaleKeys
    if (all.isEmpty()) return null

    val minT = all.minOrNull() ?: 0.0
    val maxT = (all.maxOrNull() ?: 0.0).let { m ->
        // Prefer provided duration if longer
        if (durationSeconds != null && durationSeconds > m) durationSeconds else m
    }
    val range = (maxT - minT).coerceAtLeast(1e-6)

    val tracks = listOf(
        Triple("pos", positionKeys, "#38bdf8"),
        Triple("rot", rotationKeys, "#a78bfa"),
        Triple("scl", scaleKeys, "#f97316")
    )

    val width = 240.0
    val height = 70
    val leftPad = 6.0

    fun x(t: Double): Double = leftPad + ((t - minT) / range) * width

    val legend = tracks.joinToString(" · ") { (label, keys, color) ->
        "<span style='color:$color;'>$label:${keys.size}</span>"
    }

    val trackLines = tracks.mapIndexed { idx, (label, keys, color) ->
        val y = 20.0 + idx * 16
        val markers = keys.joinToString("") { t ->
            val cx = x(t)
            "<circle cx='${"%.1f".format(cx)}' cy='${"%.1f".format(y)}' r='3' fill='$color' />"
        }
        """
        <line x1="${leftPad}" y1="${"%.1f".format(y)}" x2="${leftPad + width}" y2="${"%.1f".format(y)}" stroke="#374151" stroke-width="1"/>
        $markers
        <text x="${leftPad + width + 6}" y="${"%.1f".format(y + 4)}" fill="#9ca3af" font-size="10" font-family="JetBrains Mono, monospace">$label</text>
        """.trimIndent()
    }.joinToString("\n")

    val startLabel = "%.2f".format(minT)
    val endLabel = "%.2f".format(maxT)

    return """
    <div style="padding:4px 0;">
      <div style="color:#9ca3af;font-size:11px;letter-spacing:.05em;text-transform:uppercase;margin-bottom:2px;">timeline · $legend</div>
      <svg width="${(leftPad + width + 80).toInt()}" height="$height" viewBox="0 0 ${leftPad + width + 80} $height" style="background:#0b1220;border:1px solid #111827;border-radius:6px;">
        <text x="${leftPad}" y="12" fill="#6b7280" font-size="10" font-family="JetBrains Mono, monospace">$startLabel</text>
        <text x="${leftPad + width - 26}" y="12" fill="#6b7280" font-size="10" font-family="JetBrains Mono, monospace">$endLabel</text>
        $trackLines
      </svg>
    </div>
    """.trimIndent()
}

private fun card(title: String, rows: List<Pair<String, String>>, accent: String = "#9ca3af"): String {
    val body = rows.joinToString("") { (k, v) ->
        """
        <div style="display:flex;gap:6px;align-items:flex-start;padding:1px 0;">
          <div style="flex:0 0 80px;text-align:right;color:#6b7280;font-size:11px;letter-spacing:.05em;text-transform:uppercase;">${escapeHtml(k)}</div>
          <div style="flex:0 0 6px;text-align:center;color:$accent;">|</div>
          <div style="flex:1;">${ToStringHighlighter.render(v)}</div>
        </div>
        """.trimIndent()
    }
    return """
      <div style="padding:4px 0;font-family:inherit;">
        $body
      </div>
    """.trimIndent()
}

private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
