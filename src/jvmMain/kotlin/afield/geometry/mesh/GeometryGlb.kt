@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.mesh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A [GeometryScene] as a binary glTF (`.glb`): one node per part, each with its positions, face normals,
 * linear vertex colours and a metallic-roughness material made from its [GeometryMaterial].
 *
 * ```kotlin
 * File("/tmp/vessel.glb").writeBytes(geometryScene { revolve(listOf(profile(1.0, 0.0), profile(0.6, 1.0)), 48) }.toGlb())
 * ```
 */
fun GeometryScene.toGlb(): ByteArray = encodeGlb(listOf(this), fps = 30.0)

/**
 * Write [toGlb] to [file], returning it. With [compress], the GLB goes through [Gltfpack] when it is
 * installed and makes the file smaller, as the toolbox's export does; otherwise it is written as it is.
 */
fun GeometryScene.writeGlb(file: File, compress: Boolean = true): File =
    file.also { it.writeBytes(if (compress) Gltfpack.compressIfSmaller(toGlb()) else toGlb()) }

/**
 * Record a [GeometryBuilder] recipe as an animated glTF: [frames] frames at [fps], from `t = 0`, stored
 * as morph targets (one per frame after the first, positions and normals) played by a weights
 * animation — what three.js, Blender and every glTF viewer play back.
 *
 * Every frame must emit the same parts with the same vertex counts — move the vertices, do not add
 * or remove triangles — or this throws, naming the first frame that changed.
 */
fun geometryAnimationGlb(frames: Int = 90, fps: Double = 30.0, block: GeometryBuilder.() -> Unit): ByteArray {
    require(frames in 2..600) { "an animation needs 2 to 600 frames, got $frames" }
    require(fps.isFinite() && fps > 0) { "fps must be positive, got $fps" }
    val scenes = List(frames) { f -> geometryScene(t = f / fps, frame = f, dt = 1 / fps, block = block) }
    val first = scenes[0]
    require(first.parts.isNotEmpty()) { "frame 0 emitted no triangles" }
    for (f in 1 until frames) {
        val s = scenes[f]
        require(s.parts.size == first.parts.size) {
            "frame $f has ${s.parts.size} parts where frame 0 has ${first.parts.size}; an animation needs a fixed topology"
        }
        for (i in s.parts.indices) require(s.parts[i].mesh.vertexCount == first.parts[i].mesh.vertexCount) {
            "frame $f changed the vertex count of part ${first.parts[i].name} " +
                "(${first.parts[i].mesh.vertexCount} → ${s.parts[i].mesh.vertexCount}); an animation needs a fixed topology"
        }
    }
    return encodeGlb(scenes, fps)
}

/** Write [geometryAnimationGlb] to [file], returning it; [compress] as in [writeGlb]. */
fun writeGlbAnimation(
    file: File, frames: Int = 90, fps: Double = 30.0, compress: Boolean = true, block: GeometryBuilder.() -> Unit,
): File = file.also {
    val glb = geometryAnimationGlb(frames, fps, block)
    it.writeBytes(if (compress) Gltfpack.compressIfSmaller(glb) else glb)
}

private class GlbBin {
    val bytes = ByteArrayOutputStream()
    val views = ArrayList<JsonObject>()
    val accessors = ArrayList<JsonObject>()

    /** Append [data] as a float accessor of [type] (`VEC3`, `SCALAR`) and return its index. */
    fun floats(data: FloatArray, type: String, minMax: Boolean): Int {
        val components = if (type == "VEC3") 3 else 1
        val offset = bytes.size()
        val buf = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        data.forEach { buf.putFloat(it) }
        bytes.write(buf.array())
        views += buildJsonObject { put("buffer", 0); put("byteOffset", offset); put("byteLength", data.size * 4) }
        accessors += buildJsonObject {
            put("bufferView", views.size - 1)
            put("componentType", 5126)
            put("count", data.size / components)
            put("type", type)
            if (minMax) {
                val min = FloatArray(components) { Float.MAX_VALUE }
                val max = FloatArray(components) { -Float.MAX_VALUE }
                for (i in data.indices) { val c = i % components; min[c] = minOf(min[c], data[i]); max[c] = maxOf(max[c], data[i]) }
                if (data.isEmpty()) { min.fill(0f); max.fill(0f) }
                putJsonArray("min") { min.forEach { add(it) } }
                putJsonArray("max") { max.forEach { add(it) } }
            }
        }
        return accessors.size - 1
    }
}

private fun encodeGlb(scenes: List<GeometryScene>, fps: Double): ByteArray {
    val base = scenes[0]
    val bin = GlbBin()
    val animated = scenes.size > 1
    val meshes = ArrayList<JsonObject>()
    val materials = ArrayList<JsonObject>()
    val nodes = ArrayList<JsonObject>()
    var usesEmissiveStrength = false

    base.parts.forEachIndexed { i, part ->
        val m = part.mesh
        val position = bin.floats(m.positions, "VEC3", minMax = true)
        val normal = bin.floats(checkNotNull(m.normals), "VEC3", minMax = false)
        val color = bin.floats(checkNotNull(m.colors), "VEC3", minMax = false)
        val targets = if (!animated) emptyList() else (1 until scenes.size).map { f ->
            val later = scenes[f].parts[i].mesh
            val dp = FloatArray(m.positions.size) { later.positions[it] - m.positions[it] }
            val dn = FloatArray(m.positions.size) { later.normals!![it] - m.normals!![it] }
            bin.floats(dp, "VEC3", minMax = true) to bin.floats(dn, "VEC3", minMax = false)
        }

        val mat = part.material
        val lc = mat.linearColor
        val le = mat.linearEmissive
        val glow = le.map { it * mat.emissiveIntensity.toFloat() }
        val strength = glow.maxOrNull()?.takeIf { it > 1f } ?: 1f
        if (strength > 1f) usesEmissiveStrength = true
        materials += buildJsonObject {
            put("name", "${part.name}-material")
            putJsonObject("pbrMetallicRoughness") {
                putJsonArray("baseColorFactor") { lc.forEach { add(it) }; add(mat.opacity.toFloat()) }
                put("metallicFactor", mat.metalness)
                put("roughnessFactor", mat.roughness)
            }
            putJsonArray("emissiveFactor") { glow.forEach { add(it / strength) } }
            if (strength > 1f) putJsonObject("extensions") {
                putJsonObject("KHR_materials_emissive_strength") { put("emissiveStrength", strength) }
            }
            put("doubleSided", mat.side == GeometrySide.DOUBLE)
            if (mat.opacity < 1.0) put("alphaMode", "BLEND")
        }
        meshes += buildJsonObject {
            put("name", part.name)
            putJsonArray("primitives") {
                addJsonObject {
                    putJsonObject("attributes") { put("POSITION", position); put("NORMAL", normal); put("COLOR_0", color) }
                    put("material", i)
                    put("mode", 4)
                    if (targets.isNotEmpty()) putJsonArray("targets") {
                        targets.forEach { (p, n) -> addJsonObject { put("POSITION", p); put("NORMAL", n) } }
                    }
                }
            }
            if (targets.isNotEmpty()) putJsonArray("weights") { repeat(targets.size) { add(0f) } }
        }
        nodes += buildJsonObject { put("name", part.name); put("mesh", i) }
    }

    val animations = if (!animated) null else {
        val frames = scenes.size
        val times = bin.floats(FloatArray(frames) { (it / fps).toFloat() }, "SCALAR", minMax = true)
        val targets = frames - 1
        // Frame f shows target f - 1 at full weight and every other at zero; frame 0 is the base mesh.
        val weights = bin.floats(FloatArray(frames * targets) { k -> if (k / targets == k % targets + 1) 1f else 0f }, "SCALAR", minMax = false)
        buildJsonArray {
            addJsonObject {
                put("name", "geometry-vertex-animation")
                putJsonArray("samplers") {
                    base.parts.indices.forEach { _ -> addJsonObject { put("input", times); put("output", weights); put("interpolation", "LINEAR") } }
                }
                putJsonArray("channels") {
                    base.parts.indices.forEach { i ->
                        addJsonObject { put("sampler", i); putJsonObject("target") { put("node", i); put("path", "weights") } }
                    }
                }
            }
        }
    }

    val binBytes = bin.bytes.toByteArray()
    val json = buildJsonObject {
        putJsonObject("asset") { put("version", "2.0"); put("generator", "Field GeometryBuilder") }
        if (usesEmissiveStrength) putJsonArray("extensionsUsed") { add("KHR_materials_emissive_strength") }
        put("scene", 0)
        putJsonArray("scenes") { addJsonObject { put("name", "geometry"); putJsonArray("nodes") { nodes.indices.forEach { add(it) } } } }
        put("nodes", JsonArray(nodes))
        put("meshes", JsonArray(meshes))
        put("materials", JsonArray(materials))
        put("accessors", JsonArray(bin.accessors))
        put("bufferViews", JsonArray(bin.views))
        putJsonArray("buffers") { addJsonObject { put("byteLength", binBytes.size) } }
        animations?.let { put("animations", it) }
    }.toString().toByteArray(Charsets.UTF_8)

    fun pad(n: Int) = (4 - n % 4) % 4
    val jsonLen = json.size + pad(json.size)
    val binLen = binBytes.size + pad(binBytes.size)
    val total = 12 + 8 + jsonLen + 8 + binLen
    val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(0x46546C67).putInt(2).putInt(total)
    out.putInt(jsonLen).putInt(0x4E4F534A).put(json)
    repeat(pad(json.size)) { out.put(' '.code.toByte()) }
    out.putInt(binLen).putInt(0x004E4942).put(binBytes)
    repeat(pad(binBytes.size)) { out.put(0) }
    return out.array()
}
