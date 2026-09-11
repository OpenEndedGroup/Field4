package afield.four.fabricated

import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import afield.stdlib.linalg.Matrix4f
import org.lwjgl.stb.STBImage.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.tinyexr.TinyEXR
import java.nio.ByteBuffer
import kotlin.math.*

/**
 * What the path tracer traces: triangles, materials, textures and an environment map, all as plain
 * Kotlin with no device attached.
 *
 * Nothing in this file needs a GPU, and that is the point of separating it from
 * `PathTracingRenderer.kt`. A scene is assembled by calling `triangle`, `quad`, `mesh` or `sphere`,
 * each of which appends to flat lists and returns the scene so calls chain; `material` and
 * `texture` intern a value and hand back the integer index the triangles refer to it by. `pack`
 * then flattens the result into the float and int arrays the shader expects, and building a BVH
 * over them is the renderer's job, not this file's. The consequence for a test is that scene
 * construction, indexing and packing are all checkable headlessly, which is what
 * `PathTraceTestMain` does.
 *
 * `PathTraceMaterial` is linear-light throughout — no sRGB anywhere in the struct — and carries
 * the transmission parameters as well as the metallic-roughness ones, so glass is a material rather
 * than a separate path. Its one geometric precondition is worth reading before debugging a dark
 * result: solid (non-thin-walled) glass assumes closed, outward-wound, non-overlapping meshes,
 * because the attenuation distance is measured between surface crossings.
 *
 * `PathTraceEnvironment` is both the sky and, usually, the main light. It builds the importance
 * distribution the sampler draws from, which is why an environment is a snapshot like the geometry
 * is: changing it changes a precomputed table. `PathTraceTexture` decodes through stb_image and
 * TinyEXR and keeps its pixels linear, sRGB-decoding base colour while leaving
 * metallic-roughness alone — the asymmetry glTF requires, and the one
 * `PathTraceGltfGpuSmoke` checks against known byte values.
 */

/** Linear-light surface materials. Solid glass requires closed, outward-wound, non-overlapping meshes. */
data class PathTraceMaterial(
    val red: Float = 0.8f, val green: Float = 0.8f, val blue: Float = 0.8f,
    val metallic: Float = 0f, val roughness: Float = 0.5f,
    val emissionRed: Float = 0f, val emissionGreen: Float = 0f, val emissionBlue: Float = 0f,
    val transmission: Boolean = false, val ior: Float = 1.5f, val thinWalled: Boolean = false,
    val attenuationRed: Float = 1f, val attenuationGreen: Float = 1f, val attenuationBlue: Float = 1f,
    val attenuationDistance: Float = 1f,
    val baseColorTexture: Int = -1, val metallicRoughnessTexture: Int = -1,
    val emissionTexture: Int = -1, val normalTexture: Int = -1,
    val opacity: Float = 1f, val alphaCutoff: Float = 0.5f,
    val alphaMode: AlphaMode = AlphaMode.OPAQUE, val doubleSided: Boolean = true,
    val normalScale: Float = 1f
) {
    enum class AlphaMode { OPAQUE, MASK, BLEND }
    init {
        require(opacity.isFinite() && opacity in 0f..1f && alphaCutoff.isFinite() && alphaCutoff in 0f..1f)
        require(normalScale.isFinite() && normalScale >= 0f)
        require(listOf(baseColorTexture, metallicRoughnessTexture, emissionTexture, normalTexture).all { it >= -1 })
        require(listOf(red, green, blue, metallic, roughness).all { it.isFinite() && it in 0f..1f })
        require(listOf(emissionRed, emissionGreen, emissionBlue).all { it.isFinite() && it >= 0f })
        require(ior.isFinite() && ior >= 1f && (ior * ior).isFinite())
        require(attenuationDistance.isFinite() && attenuationDistance > 0f)
        require(listOf(attenuationRed, attenuationGreen, attenuationBlue).all { it.isFinite() && (if (thinWalled) it >= 0f else it > 0f) && it <= 1f })
        require(thinWalled || listOf(attenuationRed, attenuationGreen, attenuationBlue).all {
            (-ln(it) / attenuationDistance).isFinite()
        }) { "Absorption coefficient must be finite" }
        require(!transmission || metallic == 0f) { "Transmission is dielectric; metallic must be zero" }
        require(!thinWalled || (transmission && roughness == 0f)) { "Thin sheets currently require smooth glass" }
    }
    companion object {
        internal const val STRIDE = 112
        /** Colour remaining after attenuationDistance world units inside the solid; roughness zero is ideal glass. */
        fun glass(ior: Float = 1.5f, roughness: Float = 0f,
                  attenuationColor: Vector3f = Vector3f(1f), attenuationDistance: Float = 1f) =
            PathTraceMaterial(roughness = roughness, transmission = true, ior = ior,
                attenuationRed = attenuationColor.x, attenuationGreen = attenuationColor.y,
                attenuationBlue = attenuationColor.z, attenuationDistance = attenuationDistance)
        /** Zero-thickness pane: tint is transmission per crossing, with no displacement or medium boundary. */
        fun thinGlass(ior: Float = 1.5f, tint: Vector3f = Vector3f(1f)) =
            PathTraceMaterial(roughness=0f, transmission=true, ior=ior, thinWalled=true,
                attenuationRed=tint.x, attenuationGreen=tint.y, attenuationBlue=tint.z)

        fun diffuse(r: Float, g: Float, b: Float) = PathTraceMaterial(r, g, b, roughness = 1f)
        fun plastic(r: Float, g: Float, b: Float, roughness: Float = 0.25f) = PathTraceMaterial(r, g, b, roughness = roughness)
        fun metal(r: Float, g: Float, b: Float, roughness: Float = 0.2f) = PathTraceMaterial(r, g, b, 1f, roughness)
        fun emissive(r: Float, g: Float, b: Float) = PathTraceMaterial(0f, 0f, 0f, emissionRed = r, emissionGreen = g, emissionBlue = b)
    }
}

/** Build a static world-space triangle scene, then pass it to [PathTracingRenderer]. */
class PathTraceScene {
    internal data class Triangle(val p: List<Vector3f>, val n: List<Vector3f>, val material: Int,
        val uv: List<FloatArray>? = null, val color: List<Vector4f>? = null)
    internal val textureData = mutableListOf<PathTraceTexture>()
    /** Add a linear RGBA image; returned index can be used in a material's texture fields. */
    fun texture(image: PathTraceTexture): Int = textureData.size.also { textureData += image }
    companion object {
        /** Static glTF 2.0 scene. Textures and hierarchy are copied; importing requires no renderer. */
        fun fromGltf(path: String, transform: Matrix4f = Matrix4f()): PathTraceScene =
            PathTraceScene().also { it.importGltf(path, transform) }
    }
    internal val materials = mutableListOf<PathTraceMaterial>()
    internal val triangles = mutableListOf<Triangle>()
    fun material(value: PathTraceMaterial): Int = materials.size.also { materials += value }

    fun triangle(a: Vector3f, b: Vector3f, c: Vector3f, material: Int,
                 na: Vector3f? = null, nb: Vector3f? = null, nc: Vector3f? = null): PathTraceScene {
        require(material in materials.indices) { "Unknown material $material" }
        val p = listOf(a, b, c).map(::Vector3f)
        require(p.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
        val face = Vector3f(b).sub(a).cross(Vector3f(c).sub(a))
        require(face.lengthSquared() > 1e-16f) { "Degenerate triangle" }
        face.normalize()
        val normals = listOf(na ?: face, nb ?: face, nc ?: face).map { Vector3f(it) }
        require(normals.all { it.lengthSquared().isFinite() && it.lengthSquared() > 1e-16f })
        triangles += Triangle(p, normals.map { it.normalize() }, material)
        return this
    }

    fun quad(a: Vector3f, b: Vector3f, c: Vector3f, d: Vector3f, material: Int): PathTraceScene =
        triangle(a, b, c, material).triangle(a, c, d, material)

    /** Indexed positions, with optional per-vertex normals; transforms should be applied before upload. */
    fun mesh(positions: List<Vector3f>, indices: IntArray, material: Int, normals: List<Vector3f>? = null): PathTraceScene {
        require(indices.size % 3 == 0 && indices.all { it in positions.indices })
        require(normals == null || normals.size == positions.size)
        for (i in indices.indices step 3) {
            val a = indices[i]; val b = indices[i + 1]; val c = indices[i + 2]
            triangle(positions[a], positions[b], positions[c], material, normals?.get(a), normals?.get(b), normals?.get(c))
        }
        return this
    }

    fun sphere(center: Vector3f, radius: Float, material: Int, segments: Int = 32, rings: Int = 16): PathTraceScene {
        require(radius.isFinite() && radius > 0 && segments in 3..512 && rings in 2..256)
        fun normal(y: Int, x: Int): Vector3f {
            val theta = PI * y / rings; val phi = 2 * PI * x / segments
            return Vector3f((sin(theta) * cos(phi)).toFloat(), cos(theta).toFloat(), (sin(theta) * sin(phi)).toFloat())
        }
        fun add(a: Vector3f, b: Vector3f, c: Vector3f) {
            triangle(Vector3f(a).mul(radius).add(center), Vector3f(b).mul(radius).add(center),
                Vector3f(c).mul(radius).add(center), material, a, b, c)
        }
        for (y in 0 until rings) for (x in 0 until segments) {
            val a = normal(y, x); val b = normal(y + 1, x); val c = normal(y + 1, x + 1); val d = normal(y, x + 1)
            if (y != 0) add(a, d, b)
            if (y != rings - 1) add(d, c, b)
        }
        return this
    }

    internal data class Node(val lo: Vector3f, val hi: Vector3f, var first: Int = 0, var count: Int = 0, var escape: Int = 0)
    internal class Packed(val triangles: List<Triangle>, val nodes: List<Node>, val materials: List<PathTraceMaterial>, val textures: List<PathTraceTexture>) {
        private val textureOffsets = textures.runningFold(0) { offset, t -> offset + 2 + t.width * t.height }
        val textureByteSize: Long get() = maxOf(1, textureOffsets.last()).toLong() * 16
        fun writeTextures(out: ByteBuffer) {
            if (textures.isEmpty()) { repeat(4) { out.putFloat(0f) }; return }
            textures.forEach { t ->
                out.putFloat(t.width.toFloat()).putFloat(t.height.toFloat()).putFloat(t.wrapU.toFloat()).putFloat(t.wrapV.toFloat())
                out.putFloat(if (t.nearest) 1f else 0f); repeat(3) { out.putFloat(0f) }
                t.pixels.forEach { out.putFloat(it) }
            }
        }
        fun writeTriangles(out: ByteBuffer) { triangles.forEach { t ->
            t.p.forEachIndexed { i, v -> out.putFloat(v.x).putFloat(v.y).putFloat(v.z).putFloat(if (i == 0) t.material.toFloat() else 0f) }
            t.n.forEach { v -> out.putFloat(v.x).putFloat(v.y).putFloat(v.z).putFloat(0f) }
            for (corner in 0..2) for (i in 0..7) out.putFloat(t.uv?.get(corner)?.get(i) ?: 0f)
            for (corner in 0..2) {
                val c = t.color?.get(corner)
                out.putFloat(c?.x ?: 1f).putFloat(c?.y ?: 1f).putFloat(c?.z ?: 1f).putFloat(c?.w ?: 1f)
            }
        } }
        fun writeNodes(out: ByteBuffer) { nodes.forEach { n ->
            out.putFloat(n.lo.x).putFloat(n.lo.y).putFloat(n.lo.z).putFloat(0f)
            out.putFloat(n.hi.x).putFloat(n.hi.y).putFloat(n.hi.z).putFloat(0f)
            out.putInt(n.first).putInt(n.count).putInt(n.escape).putInt(0)
        } }
        fun writeMaterials(out: ByteBuffer) { materials.forEach { m ->
            out.putFloat(m.red).putFloat(m.green).putFloat(m.blue).putFloat(m.metallic)
            out.putFloat(m.emissionRed).putFloat(m.emissionGreen).putFloat(m.emissionBlue).putFloat(m.roughness)
            out.putFloat(if (m.transmission) 1f else 0f).putFloat(m.ior).putFloat(if (m.thinWalled) 1f else 0f).putFloat(0f)
            // Solid absorption coefficients; thin-sheet transmission colour is stored directly.
            fun coefficient(c: Float) = if (m.thinWalled) c else -ln(c) / m.attenuationDistance
            out.putFloat(coefficient(m.attenuationRed)).putFloat(coefficient(m.attenuationGreen))
                .putFloat(coefficient(m.attenuationBlue)).putFloat(0f)
            for (index in listOf(m.baseColorTexture,m.metallicRoughnessTexture,m.emissionTexture,m.normalTexture)) {
                require(index == -1 || index in textures.indices) { "Unknown texture $index" }
                out.putInt(if (index < 0) -1 else textureOffsets[index])
            }
            out.putFloat(m.opacity).putFloat(m.alphaCutoff).putFloat(m.alphaMode.ordinal.toFloat()).putFloat(if (m.doubleSided) 1f else 0f)
            out.putFloat(m.normalScale); repeat(3) { out.putFloat(0f) }
        } }
    }
    internal fun pack(): Packed {
        require(triangles.isNotEmpty()) { "A path tracing scene needs at least one triangle" }
        val ordered = mutableListOf<Triangle>(); val nodes = mutableListOf<Node>()
        fun build(items: List<Triangle>) {
            val lo = Vector3f(Float.POSITIVE_INFINITY); val hi = Vector3f(Float.NEGATIVE_INFINITY)
            items.forEach { t -> t.p.forEach { p -> lo.min(p); hi.max(p) } }
            val node = Node(lo, hi); nodes += node
            if (items.size <= 4) { node.first = ordered.size; node.count = items.size; ordered += items }
            else {
                val extent = Vector3f(hi).sub(lo)
                val axis = if (extent.x >= extent.y && extent.x >= extent.z) 0 else if (extent.y >= extent.z) 1 else 2
                val sorted = items.sortedBy { t -> t.p.sumOf { p -> (if (axis == 0) p.x else if (axis == 1) p.y else p.z).toDouble() } }
                build(sorted.subList(0, sorted.size / 2)); build(sorted.subList(sorted.size / 2, sorted.size))
            }
            node.escape = nodes.size
        }
        build(triangles.toList())
        return Packed(ordered, nodes, materials.toList(), textureData.toList())
    }
}

/** Linear RGBA equirectangular image, top row = north pole; longitude atan2(z,x). */
class PathTraceEnvironment(val width: Int, val height: Int, rgba: FloatArray) {
    internal val pixels = rgba.copyOf()
    internal val distribution: FloatArray
    init {
        require(width > 0 && height > 0 && width.toLong() * height * 4 == pixels.size.toLong())
        require(pixels.all { it.isFinite() && it >= 0f })
        val weights = DoubleArray(width * height)
        val kernel = doubleArrayOf(0.125, 0.75, 0.125)
        // Integrate the bilinear footprint over a texel-sized UV cell: [1,6,1]/8 in each axis.
        // This changes only the sampling density, never the environment image itself.
        fun luminance(x: Int, y: Int): Double {
            val i = (y.coerceIn(0, height - 1) * width + Math.floorMod(x, width)) * 4
            return 0.2126 * pixels[i] + 0.7152 * pixels[i+1] + 0.0722 * pixels[i+2]
        }
        for (y in 0 until height) for (x in 0 until width) {
            var filtered = 0.0
            for (dy in -1..1) for (dx in -1..1)
                filtered += kernel[dy+1] * kernel[dx+1] * luminance(x+dx, y+dy)
            val solidAngle = 2 * PI / width * (cos(PI * y / height) - cos(PI * (y + 1) / height))
            weights[y * width + x] = filtered * solidAngle
        }
        val total = weights.sum()
        distribution = FloatArray(weights.size * 2)
        var sum = 0.0
        var previous = 0f
        weights.indices.forEach { i ->
            val y = i / width
            val uniform = (cos(PI*y/height) - cos(PI*(y+1)/height)) / (2 * width)
            sum += if (total > 0) weights[i] / total else uniform
            val cumulative = if (i == weights.lastIndex) 1f else sum.toFloat().coerceIn(previous, 1f)
            distribution[2*i] = cumulative
            // Match the actual uploaded CDF intervals, including any rounded-away tiny weights.
            // A separately sampled 5% uniform-sphere mixture supplies support everywhere.
            distribution[2*i+1] = cumulative - previous
            previous = cumulative
        }
    }
    companion object {
        /** OpenEXR, Radiance .hdr and STB-supported LDR files; STB converts LDR input to linear floats. */
        fun fromFile(path: String): PathTraceEnvironment = MemoryStack.stackPush().use { stack ->
            if (path.endsWith(".exr", ignoreCase = true)) return@use fromExr(path)
            val w = stack.mallocInt(1); val h = stack.mallocInt(1); val channels = stack.mallocInt(1)
            stbi_set_flip_vertically_on_load_thread(0)
            val data = stbi_loadf(path, w, h, channels, 4) ?: error("Cannot load environment $path: ${stbi_failure_reason()}")
            try { PathTraceEnvironment(w[0], h[0], FloatArray(w[0] * h[0] * 4).also { data.get(it) }) }
            finally { stbi_image_free(data) }
        }
        private fun fromExr(path: String): PathTraceEnvironment = MemoryStack.stackPush().use { stack ->
            val out = stack.callocPointer(1); val w = stack.mallocInt(1); val h = stack.mallocInt(1); val err = stack.callocPointer(1)
            val result = TinyEXR.nLoadEXRWithLayer(MemoryUtil.memAddress(out), MemoryUtil.memAddress(w), MemoryUtil.memAddress(h),
                MemoryUtil.memAddress(stack.UTF8(path)), MemoryUtil.NULL, MemoryUtil.memAddress(err))
            if (result != TinyEXR.TINYEXR_SUCCESS) {
                val message = if (err[0] != 0L) MemoryUtil.memUTF8(err[0]) else "unknown EXR error"
                if (err[0] != 0L) TinyEXR.nFreeEXRErrorMessage(err[0])
                error("Cannot load environment $path: $message")
            }
            try {
                val count = Math.multiplyExact(Math.multiplyExact(w[0], h[0]), 4)
                PathTraceEnvironment(w[0], h[0], FloatArray(count).also { MemoryUtil.memFloatBuffer(out[0], count).get(it) })
            } finally { MemoryUtil.nmemFree(out[0]) }
        }
        fun constant(r: Float = 1f, g: Float = r, b: Float = r) = PathTraceEnvironment(1, 1, floatArrayOf(r, g, b, 1f))
    }
}

/** CPU-owned linear RGBA texture. Wrap modes: 0 repeat, 1 clamp, 2 mirrored repeat. */
class PathTraceTexture(val width: Int, val height: Int, rgba: FloatArray,
                       val wrapU: Int = 0, val wrapV: Int = 0, val nearest: Boolean = false) {
    internal val pixels = rgba.copyOf()
    init {
        require(width > 0 && height > 0 && width.toLong()*height*4 == pixels.size.toLong())
        require(wrapU in 0..2 && wrapV in 0..2)
        require(pixels.all { it.isFinite() && it >= 0f })
    }
}
