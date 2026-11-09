@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.graphics.lighting

import afield.geometry.mesh.MeshBvh
import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.withVertexNormals
import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Light that bounces: a coarse radiosity solve over a matte scene, one bounce at a time.
 *
 * Every triangle is a patch that sends back `ρ E` watts per square metre of what falls on it,
 * evenly in every direction. Each bounce gathers that light at every vertex, through the form
 * factor `cos θ · cos θ' · A / (π r²)` from the vertex to each patch it can see, and the
 * vertices' new irradiance becomes the patches' light for the next bounce. Bounce 0 is the light
 * straight from the [lights]; bounce `k` is light that has hit `k` surfaces on the way.
 *
 * The albedo `ρ` is the mesh's own colour per vertex (or [DEFAULT_ALBEDO] grey), so a red wall
 * sends red light onto a white floor. Colour the parts before joining them with `+`: a mesh carries
 * colours only when every part has them.
 *
 * ```kotlin
 * val room = floorMesh(3.0, 3.0, 24).withColor(0.8, 0.8, 0.8) + wall.withColor(0.8, 0.1, 0.1)
 * val solve = Radiosity(room, listOf(SphereLight(vec(0.0, 1.2, 0.4), 0.1, power = 30.0)), bounces = 3)
 * three { mesh(solve.lit(upTo = 3, exposure = 0.4), unlit = true, wireframe = false) }
 * ```
 *
 * Every bounce is linear in the lights' power, so a power slider is [lit]'s `scale`, not a new
 * solve. The solve is `vertices × triangles` shadow rays, done once; a few thousand of each takes a
 * second or two. The form factor is softened to `A / (π r² + A)` so a patch touching a vertex
 * does not divide by zero.
 *
 * @param occluders what blocks light between patches and from the lights; the scene itself by default.
 */
class Radiosity(
    val scene: TriMesh,
    val lights: List<LightSource>,
    val bounces: Int = 3,
    occluders: TriMesh? = scene,
) {
    private val mesh = if (scene.hasNormals) scene else scene.withVertexNormals()

    /** Irradiance per vertex (rgb, W/m²), one array per bounce: index 0 is direct light. */
    val irradiance: List<FloatArray>

    init {
        require(bounces >= 0) { "bounces must be >= 0, got $bounces" }
        val bvh = occluders?.let { MeshBvh(it) }
        val direct = scene.irradiance(lights, occluders)
        val result = ArrayList<FloatArray>(bounces + 1)
        result += direct
        if (bounces > 0) {
            val gatherMatrix = formFactors(bvh)
            var current = direct
            repeat(bounces) {
                current = bounce(current, gatherMatrix)
                result += current
            }
        }
        irradiance = result
    }

    /** The irradiance of bounces `from..upTo`, summed. */
    fun total(upTo: Int = bounces, from: Int = 0): FloatArray {
        require(upTo in 0..bounces) { "upTo must be in 0..$bounces, got $upTo" }
        val out = FloatArray(scene.vertexCount * 3)
        for (k in from..upTo) {
            val e = irradiance[k]
            for (i in out.indices) out[i] += e[i]
        }
        return out
    }

    /**
     * The scene coloured by bounces `from..upTo` ([TriMesh.withIrradiance]): `from = upTo = k`
     * shows one bounce alone. [scale] multiplies every light's power after the fact.
     */
    fun lit(upTo: Int = bounces, from: Int = 0, exposure: Double = 1.0, scale: Double = 1.0, toneMap: ToneMap = ToneMap.CLAMP): TriMesh =
        scene.withIrradiance(total(upTo, from), exposure * scale, toneMap)

    // Per vertex, the patches it sees: (triangle, form factor) pairs, flattened.
    private class Gather(val triangles: Array<IntArray>, val factors: Array<FloatArray>)

    private fun formFactors(bvh: MeshBvh?): Gather {
        val p = mesh.positions
        val nrm = mesh.normals!!
        val tCount = mesh.triangleCount
        val cx = DoubleArray(tCount); val cy = DoubleArray(tCount); val cz = DoubleArray(tCount)
        val fnx = DoubleArray(tCount); val fny = DoubleArray(tCount); val fnz = DoubleArray(tCount)
        val area = DoubleArray(tCount)
        for (t in 0 until tCount) {
            val a = mesh.ia(t) * 3; val b = mesh.ib(t) * 3; val c = mesh.ic(t) * 3
            cx[t] = (p[a] + p[b] + p[c]) / 3.0; cy[t] = (p[a + 1] + p[b + 1] + p[c + 1]) / 3.0; cz[t] = (p[a + 2] + p[b + 2] + p[c + 2]) / 3.0
            val n = mesh.faceNormal(t)
            fnx[t] = n.x.toDouble(); fny[t] = n.y.toDouble(); fnz[t] = n.z.toDouble()
            area[t] = mesh.triangleArea(t)
        }
        val vCount = mesh.vertexCount
        val tris = arrayOfNulls<IntArray>(vCount)
        val facs = arrayOfNulls<FloatArray>(vCount)
        IntStream.range(0, vCount).parallel().forEach { v ->
            val n = unit(nrm[v * 3].toDouble(), nrm[v * 3 + 1].toDouble(), nrm[v * 3 + 2].toDouble())
            val px = p[v * 3].toDouble(); val py = p[v * 3 + 1].toDouble(); val pz = p[v * 3 + 2].toDouble()
            val ox = px + n[0] * SHADOW_BIAS; val oy = py + n[1] * SHADOW_BIAS; val oz = pz + n[2] * SHADOW_BIAS
            val ts = IntArrayList(); val fs = FloatArrayList()
            for (t in 0 until tCount) {
                val dx = cx[t] - px; val dy = cy[t] - py; val dz = cz[t] - pz
                val r2 = dx * dx + dy * dy + dz * dz
                if (r2 < 1e-18) continue
                val r = sqrt(r2)
                val cosV = (dx * n[0] + dy * n[1] + dz * n[2]) / r
                if (cosV <= 1e-6) continue
                val cosT = -(dx * fnx[t] + dy * fny[t] + dz * fnz[t]) / r
                if (cosT <= 1e-6) continue
                // The patch's own small offset toward its face keeps the ray from ending inside it.
                if (bvh != null) {
                    val ex = cx[t] + fnx[t] * SHADOW_BIAS - ox
                    val ey = cy[t] + fny[t] * SHADOW_BIAS - oy
                    val ez = cz[t] + fnz[t] * SHADOW_BIAS - oz
                    if (bvh.occluded(ox, oy, oz, ex, ey, ez, maxDistance = 1.0 - 1e-6, minDistance = 1e-6)) continue
                }
                ts.add(t); fs.add((cosV * cosT * area[t] / (PI * r2 + area[t])).toFloat())
            }
            tris[v] = ts.toArray(); facs[v] = fs.toArray()
        }
        @Suppress("UNCHECKED_CAST")
        return Gather(tris as Array<IntArray>, facs as Array<FloatArray>)
    }

    private fun bounce(incoming: FloatArray, g: Gather): FloatArray {
        val albedo = scene.colors
        val tCount = mesh.triangleCount
        // Exitance per patch: the mean of ρE over its corners.
        val m = FloatArray(tCount * 3)
        for (t in 0 until tCount) {
            val corners = intArrayOf(mesh.ia(t), mesh.ib(t), mesh.ic(t))
            for (ch in 0..2) {
                var s = 0.0
                for (v in corners) s += (albedo?.get(v * 3 + ch)?.toDouble() ?: DEFAULT_ALBEDO) * incoming[v * 3 + ch]
                m[t * 3 + ch] = (s / 3.0).toFloat()
            }
        }
        val out = FloatArray(scene.vertexCount * 3)
        IntStream.range(0, scene.vertexCount).parallel().forEach { v ->
            val ts = g.triangles[v]; val fs = g.factors[v]
            var r = 0.0; var gr = 0.0; var b = 0.0
            for (i in ts.indices) {
                val t = ts[i]; val f = fs[i]
                r += f * m[t * 3]; gr += f * m[t * 3 + 1]; b += f * m[t * 3 + 2]
            }
            out[v * 3] = r.toFloat(); out[v * 3 + 1] = gr.toFloat(); out[v * 3 + 2] = b.toFloat()
        }
        return out
    }
}

private class IntArrayList {
    private var a = IntArray(64)
    var size = 0; private set
    fun add(x: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = x }
    fun toArray(): IntArray = a.copyOf(size)
}

private class FloatArrayList {
    private var a = FloatArray(64)
    var size = 0; private set
    fun add(x: Float) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = x }
    fun toArray(): FloatArray = a.copyOf(size)
}
