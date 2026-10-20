package afield.graphics.scene.fabricated

import afield.graphics.scene.GltfSupport
import afield.graphics.scene.PlyFloatTable
import afield.graphics.scene.readPlyAsFloatTable
import afield.math.linalg.Matrix4f
import afield.math.linalg.Vector3f
import afield.math.linalg.Vector4f
import java.io.File
import kotlin.math.sqrt

/**
 * Gaussian splats on the CPU in their *parametric* form — centre, radii, rotation, colour and
 * opacity — rather than the covariance [GaussianSplatRenderer] draws from.
 *
 * This is the form a [SplatShader] insert edits: `scales` and `quat` only mean something while
 * the splat still has them, so a set is uploaded as is and the covariance is built on the GPU
 * after the insert has run. Each splat is [GaussianSplatRenderer.SPLAT_FLOAT_STRIDE] floats,
 * four vec4s:
 *
 *     (centre.xyz, opacity)  (r, g, b, 0)  (scale.xyz, 0)  (quat.x, quat.y, quat.z, quat.w)
 *
 * which is what `#include "splat/insert"` reads. Colours are what the renderer will encode for
 * display (it raises them to `colorGamma` before blending, and the target encodes them back), so
 * a colour written here is the colour you see where a splat is opaque.
 *
 * Build one with [push] (or take one of [ProceduralSplats]), load one with [fromPly] or
 * [fromGltf]. [fit] is the demo's robust framing: the median centre and the 90th-percentile
 * radius, which a few far-flung splats cannot drag around.
 */
class SplatSet(initialCapacity: Int = 1024) {
    /** The packed splats; only the first [count] × 16 floats are live. */
    var floats: FloatArray = FloatArray(initialCapacity.coerceAtLeast(1) * STRIDE)
        private set

    /** How many splats have been pushed. */
    var count: Int = 0
        private set

    /** Append one splat. [qx]..[qw] need not be normalised; a zero quaternion means no rotation. */
    fun push(
        x: Double, y: Double, z: Double,
        sx: Double, sy: Double, sz: Double,
        qx: Double, qy: Double, qz: Double, qw: Double,
        opacity: Double,
        r: Double, g: Double, b: Double
    ): SplatSet {
        if ((count + 1) * STRIDE > floats.size) floats = floats.copyOf(floats.size * 2)
        val o = count * STRIDE
        floats[o + 0] = x.toFloat(); floats[o + 1] = y.toFloat(); floats[o + 2] = z.toFloat()
        floats[o + 3] = opacity.toFloat()
        floats[o + 4] = r.toFloat(); floats[o + 5] = g.toFloat(); floats[o + 6] = b.toFloat()
        floats[o + 7] = 0f
        floats[o + 8] = sx.toFloat(); floats[o + 9] = sy.toFloat(); floats[o + 10] = sz.toFloat()
        floats[o + 11] = 0f
        floats[o + 12] = qx.toFloat(); floats[o + 13] = qy.toFloat(); floats[o + 14] = qz.toFloat()
        floats[o + 15] = qw.toFloat()
        count++
        return this
    }

    /** The centre of splat [index]. */
    fun centre(index: Int): Vector3f {
        val o = index * STRIDE
        return Vector3f(floats[o], floats[o + 1], floats[o + 2])
    }

    /** The smallest box holding every splat centre; both corners are zero for an empty set. */
    fun bounds(): Pair<Vector3f, Vector3f> {
        if (count == 0) return Vector3f() to Vector3f()
        val min = FloatArray(3) { Float.POSITIVE_INFINITY }
        val max = FloatArray(3) { Float.NEGATIVE_INFINITY }
        for (i in 0 until count) {
            val o = i * STRIDE
            for (axis in 0..2) {
                val v = floats[o + axis]
                if (!v.isFinite()) continue
                if (v < min[axis]) min[axis] = v
                if (v > max[axis]) max[axis] = v
            }
        }
        return Vector3f(min[0], min[1], min[2]) to Vector3f(max[0], max[1], max[2])
    }

    /**
     * Where the cloud is and how big, measured so that outliers do not count: [Fit.centre] is the
     * per-axis median of the splat centres and [Fit.radius90] the distance from it that nine
     * splats in ten fall within. Non-finite centres are skipped. An empty set fits the unit ball.
     */
    fun fit(): Fit {
        val xs = ArrayList<Float>(count)
        val ys = ArrayList<Float>(count)
        val zs = ArrayList<Float>(count)
        for (i in 0 until count) {
            val o = i * STRIDE
            val x = floats[o]; val y = floats[o + 1]; val z = floats[o + 2]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
            xs += x; ys += y; zs += z
        }
        if (xs.isEmpty()) return Fit(Vector3f(), 1f)
        val centre = Vector3f(percentile(xs.sorted(), 0.5), percentile(ys.sorted(), 0.5), percentile(zs.sorted(), 0.5))
        val distances = xs.indices.map {
            val dx = xs[it] - centre.x; val dy = ys[it] - centre.y; val dz = zs[it] - centre.z
            sqrt(dx * dx + dy * dy + dz * dz)
        }.sorted()
        return Fit(centre, maxOf(percentile(distances, 0.9), 1e-4f))
    }

    /**
     * A robust centre and radius for a cloud; see [SplatSet.fit].
     */
    data class Fit(val centre: Vector3f, val radius90: Float) {
        /**
         * The model matrix that moves [centre] to the origin and scales [radius90] to [radius],
         * the demo's framing (`1.08`, times its size slider).
         */
        fun model(radius: Float = 1.08f): Matrix4f {
            val s = radius / radius90
            return Matrix4f().scale(s).translate(-centre.x, -centre.y, -centre.z)
        }
    }

    companion object {
        /** Floats per splat. */
        const val STRIDE = GaussianSplatRenderer.SPLAT_FLOAT_STRIDE

        // The demo's percentileFromSorted: index floor((n - 1) p) into the sorted values.
        private fun percentile(sorted: List<Float>, p: Double): Float =
            sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]

        /**
         * Load a Gaussian-splat `.ply` (the 3DGS layout, or PlayCanvas's compressed one), decoded
         * exactly as [GaussianSplatRenderer.pack] decodes it: log scales, logit opacity and
         * degree-zero spherical-harmonic colour, taken out of their storage forms.
         */
        fun fromPly(path: String, config: GaussianSplatRenderer.Config = GaussianSplatRenderer.Config()): SplatSet =
            GaussianSplatRenderer.parametric(readPlyAsFloatTable(path), config)

        /** [fromPly] for a table already in memory. */
        fun fromTable(table: PlyFloatTable, config: GaussianSplatRenderer.Config = GaussianSplatRenderer.Config()): SplatSet =
            GaussianSplatRenderer.parametric(table, config)

        /**
         * A mesh as splats: one flat disc per vertex, facing along the vertex normal, the way the
         * demo turns a dropped `.glb` into a cloud. Every node transform is applied, so the cloud
         * sits where the meshes do. A disc is coloured by the vertex colour where the file has
         * one and by the material's base colour otherwise; textures are not sampled.
         *
         * The disc radius comes from how densely the vertices cover the surface — the square root
         * of the total triangle area per vertex, times 0.55 — clamped to between 0.08% and 3% of
         * the bounding-box diagonal, and its thickness is an eighth of that, as in the demo.
         */
        fun fromGltf(path: String): SplatSet {
            require(File(path).exists()) { "glTF file not found: $path" }
            val model = GltfSupport.loadGltfBasic(
                path, GltfSupport.GltfLoadConfig(includeVertexColors = true, loadTextures = false, loadSkins = false)
            )

            class Vertex(val p: Vector3f, val n: Vector3f, val color: Vector4f)
            val vertices = ArrayList<Vertex>()
            var area = 0.0
            for (mesh in model.meshes) {
                val world = mesh.bindMatrix ?: mesh.bindPose.toMatrix()
                val normalMatrix = Matrix4f(world).invert().transpose()
                val data = mesh.buffers.vertexData.duplicate().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                val first = vertices.size
                for (i in 0 until mesh.buffers.vertexCount) {
                    val o = i * 12
                    val p = world.transform(Vector4f(data[o], data[o + 1], data[o + 2], 1f))
                    val n = normalMatrix.transform(Vector4f(data[o + 3], data[o + 4], data[o + 5], 0f))
                    val normal = Vector3f(n.x, n.y, n.z).let { if (it.lengthSquared() < 1e-10f) Vector3f(0f, 1f, 0f) else it.normalize() }
                    val base = mesh.material.baseColor
                    // the loader writes white where the file has no COLOR_0, so this is the base colour then
                    val c = Vector4f(data[o + 8] * base.x, data[o + 9] * base.y, data[o + 10] * base.z, data[o + 11] * base.w)
                    vertices += Vertex(Vector3f(p.x, p.y, p.z), normal, c)
                }
                val indices = mesh.buffers.indexData.duplicate()
                var t = 0
                while (t + 2 < mesh.buffers.indexCount) {
                    val a = vertices[first + indices[t]].p
                    val b = vertices[first + indices[t + 1]].p
                    val c = vertices[first + indices[t + 2]].p
                    area += 0.5 * Vector3f(b).sub(a).cross(Vector3f(c).sub(a)).length()
                    t += 3
                }
            }

            val set = SplatSet(vertices.size)
            if (vertices.isEmpty()) return set
            val min = Vector3f(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            val max = Vector3f(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            for (v in vertices) { min.min(v.p); max.max(v.p) }
            val diagonal = maxOf(Vector3f(max).sub(min).length().toDouble(), 0.001)
            val spacing = if (area > 0.0) sqrt(area / vertices.size) else diagonal / maxOf(sqrt(vertices.size.toDouble()), 1.0)
            val radius = (spacing * 0.55).coerceIn(maxOf(diagonal * 0.0008, 0.0016), maxOf(diagonal * 0.03, 0.012))
            val thickness = (radius * 0.12).coerceIn(maxOf(diagonal * 0.00015, 0.0008), maxOf(radius * 0.42, 0.0024))
            for (v in vertices) {
                val q = ProceduralSplats.quaternionFromUnitVectors(0.0, 0.0, -1.0, v.n.x.toDouble(), v.n.y.toDouble(), v.n.z.toDouble())
                set.push(
                    v.p.x.toDouble(), v.p.y.toDouble(), v.p.z.toDouble(),
                    radius, radius, thickness,
                    q[0], q[1], q[2], q[3],
                    v.color.w.toDouble().coerceIn(0.0, 1.0),
                    v.color.x.toDouble(), v.color.y.toDouble(), v.color.z.toDouble()
                )
            }
            return set
        }
    }
}
