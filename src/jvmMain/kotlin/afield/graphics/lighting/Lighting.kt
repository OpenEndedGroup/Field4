@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.graphics.lighting

import afield.geometry.mesh.MeshBvh
import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.icosphereMesh
import afield.geometry.mesh.planeMesh
import afield.geometry.mesh.triMesh
import afield.geometry.mesh.withVertexNormals
import afield.math.linalg.Vec3
import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Light falling on a mesh, computed on the CPU: how much power per square metre arrives at each
 * vertex from point, sphere and rectangular lights, how much of each light a vertex can see past the
 * rest of the scene, and what a matte surface looks like under it.
 *
 * The result is a mesh with per-vertex colours, drawn unlit, so the picture *is* the computed light
 * rather than three.js's own shading:
 *
 * ```kotlin
 * val scene = floorMesh(4.0, 4.0, divisions = 60) + panelBoxMesh(0.6, 0.6, 0.6).translated(0.0, 0.3, 0.0)
 * val lamp = RectLight(vec(0.0, 1.5, 0.0), width = 1.0, height = 0.6, power = 40.0)
 * three {
 *     mesh(scene.lit(lamp, exposure = 0.5), unlit = true, wireframe = false)
 *     mesh(lamp.mesh(), unlit = true, wireframe = false)
 * }
 * ```
 *
 * #### Units and the physics
 *
 * A light's [LightSource.power] is radiant power in watts, and its colour weights it per channel.
 * Irradiance `E` is in W/m²: a point light of power `Φ` at distance `d`, seen at angle `θ` from the
 * surface normal, gives `E = Φ cos θ / (4π d²)` — the inverse-square law times the cosine factor.
 * Sphere and rectangle lights are Lambertian emitters: their surface has the same radiance
 * `L = Φ / (π A)` everywhere and in every direction, and `E` sums `L cos θ cos θ' dA / r²` over
 * sample points on them. The same power spread over a bigger emitter therefore lights an open
 * floor about as brightly, and casts a softer shadow, because a point near the shadow's edge sees
 * part of the light rather than all or none of it.
 *
 * A matte (Lambertian) surface of albedo `ρ` sends back radiance `ρ E / π`; [lit] multiplies that by
 * an exposure and passes it through a [ToneMap] to get a displayable linear colour. [Radiosity] adds
 * the light that bounces between surfaces.
 *
 * Coordinates are y-up, as in a `three { }` view. Samples sit on a fixed lattice, so the same scene
 * always gives the same colours, and a slider moving a light does not make its shadow shimmer.
 */
sealed class LightSource(
    /** Radiant power in watts. */
    val power: Double,
    /** Linear rgb weights on [power]; white is `(1, 1, 1)`. */
    val color: Vec3,
) {
    init {
        require(power.isFinite() && power >= 0.0) { "a light's power must be a finite number of watts >= 0, got $power" }
    }

    /**
     * Fill [out] with sample points: x, y, z, then the emitter's normal (all zero for a point that
     * shines equally every way), then a weight `w` in W/sr such that one sample adds
     * `w · cosθ_receiver · cosθ_emitter / r²` to the irradiance. Returns the number of samples.
     */
    internal abstract fun sampleArray(): DoubleArray

    internal val sampleCache: DoubleArray by lazy { sampleArray() }

    /** A mesh to draw the light itself with, for instance `mesh(light.mesh(), unlit = true)`. */
    abstract fun mesh(): TriMesh
}

/**
 * A point light at [position] that shines equally in every direction: `Φ / (4π)` watts per steradian.
 * Its shadows are perfectly sharp.
 */
class PointLight(
    val position: Vec3,
    power: Double = 100.0,
    color: Vec3 = Vec3(1f, 1f, 1f),
) : LightSource(power, color) {
    override fun sampleArray(): DoubleArray = doubleArrayOf(
        position.x.toDouble(), position.y.toDouble(), position.z.toDouble(), 0.0, 0.0, 0.0, power / (4 * PI),
    )

    /** A small sphere at the light's position. */
    override fun mesh(): TriMesh = icosphereMesh(2, 0.03).translated(position).withColor(color.x, color.y, color.z)
}

/**
 * A glowing ball of [radius] at [center]: a Lambertian emitter with radiance `Φ / (4π² r²)`.
 *
 * Seen from outside it lights a surface exactly as a point light of the same power at its centre
 * would, until something comes between them: then the size shows as a soft shadow edge.
 *
 * @param samples points on its surface, on a Fibonacci lattice; about half face any given receiver.
 */
class SphereLight(
    val center: Vec3,
    val radius: Double,
    power: Double = 100.0,
    color: Vec3 = Vec3(1f, 1f, 1f),
    val samples: Int = 256,
) : LightSource(power, color) {
    init {
        require(radius > 0.0) { "a SphereLight needs a positive radius, got $radius" }
        require(samples >= 1) { "a SphereLight needs at least one sample" }
    }

    override fun sampleArray(): DoubleArray {
        val out = DoubleArray(samples * 7)
        // L = Φ / (π · 4πr²); each sample stands for 4πr²/N of the surface, so w = L·dA = Φ / (πN).
        val w = power / (PI * samples)
        val golden = PI * (3.0 - sqrt(5.0))
        for (i in 0 until samples) {
            val y = 1.0 - 2.0 * (i + 0.5) / samples
            val ring = sqrt(1.0 - y * y)
            val a = golden * i
            val nx = cos(a) * ring; val nz = sin(a) * ring
            out[i * 7] = center.x + radius * nx
            out[i * 7 + 1] = center.y + radius * y
            out[i * 7 + 2] = center.z + radius * nz
            out[i * 7 + 3] = nx; out[i * 7 + 4] = y; out[i * 7 + 5] = nz
            out[i * 7 + 6] = w
        }
        return out
    }

    override fun mesh(): TriMesh = icosphereMesh(3, radius).translated(center).withColor(color.x, color.y, color.z)
}

/**
 * A flat rectangular panel of [width] × [height] at [center], emitting from the side its [normal]
 * points to (straight down by default), with radiance `Φ / (π A)`.
 *
 * @param samplesPerSide the emitter is sampled at the centres of a `samplesPerSide²` grid.
 */
class RectLight(
    val center: Vec3,
    val width: Double,
    val height: Double,
    power: Double = 100.0,
    color: Vec3 = Vec3(1f, 1f, 1f),
    val normal: Vec3 = Vec3(0f, -1f, 0f),
    val samplesPerSide: Int = 12,
) : LightSource(power, color) {
    init {
        require(width > 0.0 && height > 0.0) { "a RectLight needs a positive width and height, got $width × $height" }
        require(samplesPerSide >= 1) { "a RectLight needs at least one sample per side" }
    }

    private val frame: DoubleArray by lazy { tangentFrame(normal) }

    override fun sampleArray(): DoubleArray {
        val n = samplesPerSide
        val out = DoubleArray(n * n * 7)
        val f = frame
        // One-sided Lambertian: L = Φ / (π A); each sample stands for A / N², so w = Φ / (π N²).
        val w = power / (PI * n * n)
        var k = 0
        for (j in 0 until n) for (i in 0 until n) {
            val s = ((i + 0.5) / n - 0.5) * width
            val t = ((j + 0.5) / n - 0.5) * height
            out[k] = center.x + s * f[0] + t * f[3]
            out[k + 1] = center.y + s * f[1] + t * f[4]
            out[k + 2] = center.z + s * f[2] + t * f[5]
            out[k + 3] = f[6]; out[k + 4] = f[7]; out[k + 5] = f[8]
            out[k + 6] = w
            k += 7
        }
        return out
    }

    /** The panel as a quad, wound to face the way it shines. */
    override fun mesh(): TriMesh {
        val f = frame
        fun corner(s: Double, t: Double) = Vec3(
            (center.x + s * width / 2 * f[0] + t * height / 2 * f[3]).toFloat(),
            (center.y + s * width / 2 * f[1] + t * height / 2 * f[4]).toFloat(),
            (center.z + s * width / 2 * f[2] + t * height / 2 * f[5]).toFloat(),
        )
        val c = Vec3(color.x, color.y, color.z)
        return triMesh {
            val nn = Vec3(f[6].toFloat(), f[7].toFloat(), f[8].toFloat())
            vertex(corner(-1.0, -1.0), normal = nn, color = c)
            vertex(corner(1.0, -1.0), normal = nn, color = c)
            vertex(corner(1.0, 1.0), normal = nn, color = c)
            vertex(corner(-1.0, 1.0), normal = nn, color = c)
            quad(0, 1, 2, 3)
        }
    }
}

/** How [lit] turns a radiance into a colour channel in `0..1`. */
enum class ToneMap {
    /** `min(x, 1)`: linear until it clips. */
    CLAMP,

    /** `1 - e^(-x)`: linear for dim light, rolling off smoothly toward 1. */
    EXPONENTIAL;

    /** This curve at [x]. */
    fun apply(x: Double): Double = when (this) {
        CLAMP -> x.coerceIn(0.0, 1.0)
        EXPONENTIAL -> if (x <= 0.0) 0.0 else 1.0 - exp(-x)
    }
}

/** The albedo a mesh without colours is given: a light grey that reflects 80 % of what falls on it. */
const val DEFAULT_ALBEDO: Double = 0.8

/** How far a shadow ray starts off the surface, along its normal, so it does not hit the surface itself. */
const val SHADOW_BIAS: Double = 1e-3

/**
 * Irradiance in W/m² (one value per colour channel) at [point] on a surface facing [normal], from
 * [lights], with shadows cast by [occluders] (none when `null`).
 */
fun irradianceAt(point: Vec3, normal: Vec3, lights: List<LightSource>, occluders: MeshBvh? = null): Vec3 {
    val e = DoubleArray(3)
    val n = unit(normal.x.toDouble(), normal.y.toDouble(), normal.z.toDouble())
    for (light in lights) {
        val v = gather(point.x.toDouble(), point.y.toDouble(), point.z.toDouble(), n[0], n[1], n[2], light, occluders)
        e[0] += v * light.color.x; e[1] += v * light.color.y; e[2] += v * light.color.z
    }
    return Vec3(e[0].toFloat(), e[1].toFloat(), e[2].toFloat())
}

/** [irradianceAt] for one light. */
fun irradianceAt(point: Vec3, normal: Vec3, light: LightSource, occluders: MeshBvh? = null): Vec3 =
    irradianceAt(point, normal, listOf(light), occluders)

/**
 * The fraction of [light]'s unshadowed irradiance that reaches [point]: 1 in full light, 0 in the
 * umbra, in between in a penumbra. A point the light cannot reach at all, being behind it or facing
 * away, counts as fully visible: nothing is in the way of nothing.
 */
fun visibilityAt(point: Vec3, normal: Vec3, light: LightSource, occluders: MeshBvh): Double {
    val n = unit(normal.x.toDouble(), normal.y.toDouble(), normal.z.toDouble())
    val px = point.x.toDouble(); val py = point.y.toDouble(); val pz = point.z.toDouble()
    val open = gather(px, py, pz, n[0], n[1], n[2], light, null)
    if (open <= 0.0) return 1.0
    return gather(px, py, pz, n[0], n[1], n[2], light, occluders) / open
}

/**
 * Irradiance at every vertex, rgb interleaved like [TriMesh.colors], in W/m². Vertex normals are
 * computed first if the mesh has none; shadows come from [occluders], the mesh itself by default.
 */
fun TriMesh.irradiance(lights: List<LightSource>, occluders: TriMesh? = this): FloatArray {
    val mesh = if (hasNormals) this else withVertexNormals()
    val bvh = occluders?.let { MeshBvh(it) }
    val nrm = mesh.normals!!
    val out = FloatArray(vertexCount * 3)
    IntStream.range(0, vertexCount).parallel().forEach { v ->
        val n = unit(nrm[v * 3].toDouble(), nrm[v * 3 + 1].toDouble(), nrm[v * 3 + 2].toDouble())
        val px = positions[v * 3].toDouble(); val py = positions[v * 3 + 1].toDouble(); val pz = positions[v * 3 + 2].toDouble()
        for (light in lights) {
            val e = gather(px, py, pz, n[0], n[1], n[2], light, bvh)
            out[v * 3] += (e * light.color.x).toFloat()
            out[v * 3 + 1] += (e * light.color.y).toFloat()
            out[v * 3 + 2] += (e * light.color.z).toFloat()
        }
    }
    return out
}

/** [irradiance] for one light. */
fun TriMesh.irradiance(light: LightSource, occluders: TriMesh? = this): FloatArray = irradiance(listOf(light), occluders)

/** [visibilityAt] at every vertex: one value per vertex in `0..1`. */
fun TriMesh.visibility(light: LightSource, occluders: TriMesh = this): FloatArray {
    val mesh = if (hasNormals) this else withVertexNormals()
    val bvh = MeshBvh(occluders)
    val nrm = mesh.normals!!
    val out = FloatArray(vertexCount)
    IntStream.range(0, vertexCount).parallel().forEach { v ->
        out[v] = visibilityAt(
            Vec3(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2]),
            Vec3(nrm[v * 3], nrm[v * 3 + 1], nrm[v * 3 + 2]), light, bvh,
        ).toFloat()
    }
    return out
}

/**
 * This mesh coloured by the light a matte surface sends back: for each vertex,
 * `tone(exposure · albedo · E / π)`, where the albedo is the mesh's own colour (or
 * [DEFAULT_ALBEDO] grey) and `E` is [irradiance] from [lights] plus any [extra] irradiance (the
 * bounced light from a [Radiosity], say). Draw it with `unlit = true`.
 */
fun TriMesh.lit(
    lights: List<LightSource>,
    occluders: TriMesh? = this,
    exposure: Double = 1.0,
    toneMap: ToneMap = ToneMap.CLAMP,
    extra: FloatArray? = null,
): TriMesh = withIrradiance(irradiance(lights, occluders), exposure, toneMap, extra)

/** [lit] for any number of lights, each casting shadows from the mesh itself. */
fun TriMesh.lit(
    vararg lights: LightSource,
    exposure: Double = 1.0,
    toneMap: ToneMap = ToneMap.CLAMP,
): TriMesh = lit(lights.toList(), this, exposure, toneMap)

/**
 * This mesh coloured as a matte surface under a given per-vertex [irradiance] (rgb, W/m²): the
 * last step of [lit], for an irradiance you computed, scaled, or summed yourself.
 */
fun TriMesh.withIrradiance(
    irradiance: FloatArray,
    exposure: Double = 1.0,
    toneMap: ToneMap = ToneMap.CLAMP,
    extra: FloatArray? = null,
): TriMesh {
    require(irradiance.size == vertexCount * 3) { "irradiance needs ${vertexCount * 3} floats (rgb per vertex), got ${irradiance.size}" }
    extra?.let { require(it.size == vertexCount * 3) { "extra irradiance needs ${vertexCount * 3} floats, got ${it.size}" } }
    val albedo = colors
    val out = FloatArray(vertexCount * 3)
    for (i in out.indices) {
        val rho = albedo?.get(i)?.toDouble() ?: DEFAULT_ALBEDO
        val e = irradiance[i] + (extra?.get(i) ?: 0f)
        out[i] = toneMap.apply(exposure * rho * e / PI).toFloat()
    }
    return withColors(out)
}

/**
 * A floor: a `width × depth` grid of [divisions] quads a side in the `xz` plane at height [y],
 * facing up (`+y`), with normals. Lighting is computed per vertex, so [divisions] sets how finely a
 * shadow edge can be drawn.
 */
fun floorMesh(width: Double = 4.0, depth: Double = 4.0, divisions: Int = 48, y: Double = 0.0): TriMesh {
    val plane = planeMesh(divisions, divisions, width, depth)
    // planeMesh faces +z; (x, y, z) -> (x, z, -y) turns +z into +y and keeps the winding.
    val p = plane.positions
    val out = FloatArray(p.size)
    val n = FloatArray(p.size)
    for (v in 0 until plane.vertexCount) {
        out[v * 3] = p[v * 3]
        out[v * 3 + 1] = p[v * 3 + 2] + y.toFloat()
        out[v * 3 + 2] = -p[v * 3 + 1]
        n[v * 3 + 1] = 1f
    }
    return TriMesh(out, plane.indices, n, plane.uvs)
}

/**
 * A solid box, `sizeX × sizeY × sizeZ` centred on the origin, made of six separate faces of
 * `divisions × divisions` quads with their own flat normals: the box to light, where [boxMesh]'s
 * eight shared corners would smear one face's light into the next.
 *
 * [boxMesh]: afield.geometry.mesh.boxMesh
 */
fun panelBoxMesh(sizeX: Double = 1.0, sizeY: Double = 1.0, sizeZ: Double = 1.0, divisions: Int = 8): TriMesh {
    val hx = sizeX / 2; val hy = sizeY / 2; val hz = sizeZ / 2
    // Each face: its normal, and two in-plane axes u, v with u × v = normal so the quads face out.
    val faces = listOf(
        doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0),
        doubleArrayOf(-1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0),
        doubleArrayOf(0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, -1.0),
        doubleArrayOf(0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0),
        doubleArrayOf(0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0),
        doubleArrayOf(0.0, 0.0, -1.0, -1.0, 0.0, 0.0, 0.0, 1.0, 0.0),
    )
    val h = doubleArrayOf(hx, hy, hz)
    return triMesh {
        for (f in faces) {
            val normal = Vec3(f[0].toFloat(), f[1].toFloat(), f[2].toFloat())
            val base = vertexCount
            for (j in 0..divisions) for (i in 0..divisions) {
                val s = 2.0 * i / divisions - 1.0
                val t = 2.0 * j / divisions - 1.0
                vertex(
                    (f[0] + s * f[3] + t * f[6]) * h[0],
                    (f[1] + s * f[4] + t * f[7]) * h[1],
                    (f[2] + s * f[5] + t * f[8]) * h[2],
                    normal = normal,
                )
            }
            fun at(i: Int, j: Int) = base + j * (divisions + 1) + i
            for (j in 0 until divisions) for (i in 0 until divisions) quad(at(i, j), at(i + 1, j), at(i + 1, j + 1), at(i, j + 1))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The one integral everything above shares.
// ---------------------------------------------------------------------------------------------

/** Irradiance (per unit of the light's colour) at p with unit normal n, from [light]'s samples. */
internal fun gather(
    px: Double, py: Double, pz: Double,
    nx: Double, ny: Double, nz: Double,
    light: LightSource,
    occluders: MeshBvh?,
): Double {
    val s = light.sampleCache
    val ox = px + nx * SHADOW_BIAS; val oy = py + ny * SHADOW_BIAS; val oz = pz + nz * SHADOW_BIAS
    var e = 0.0
    var k = 0
    while (k < s.size) {
        val dx = s[k] - px; val dy = s[k + 1] - py; val dz = s[k + 2] - pz
        val r2 = dx * dx + dy * dy + dz * dz
        if (r2 > 1e-18) {
            val r = sqrt(r2)
            val cosR = (dx * nx + dy * ny + dz * nz) / r
            if (cosR > 0.0) {
                val lnx = s[k + 3]; val lny = s[k + 4]; val lnz = s[k + 5]
                val cosE = if (lnx == 0.0 && lny == 0.0 && lnz == 0.0) 1.0 else -(dx * lnx + dy * lny + dz * lnz) / r
                if (cosE > 0.0) {
                    val blocked = occluders != null && run {
                        val tx = s[k] - ox; val ty = s[k + 1] - oy; val tz = s[k + 2] - oz
                        occluders.occluded(ox, oy, oz, tx, ty, tz, maxDistance = 1.0 - 1e-6, minDistance = 1e-6)
                    }
                    if (!blocked) e += s[k + 6] * cosR * cosE / r2
                }
            }
        }
        k += 7
    }
    return e
}

internal fun unit(x: Double, y: Double, z: Double): DoubleArray {
    val l = sqrt(x * x + y * y + z * z)
    return if (l < 1e-20) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(x / l, y / l, z / l)
}

/** Two unit tangents and the unit normal, as nine doubles: u, v, n with u × v = n. */
internal fun tangentFrame(normal: Vec3): DoubleArray {
    val n = unit(normal.x.toDouble(), normal.y.toDouble(), normal.z.toDouble())
    // Pick the world axis least parallel to n to start from.
    val a = if (abs(n[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 0.0, 1.0)
    // v = n × a, u = v × n, so u × v = n.
    val v = unit(n[1] * a[2] - n[2] * a[1], n[2] * a[0] - n[0] * a[2], n[0] * a[1] - n[1] * a[0])
    val u = doubleArrayOf(v[1] * n[2] - v[2] * n[1], v[2] * n[0] - v[0] * n[2], v[0] * n[1] - v[1] * n[0])
    return doubleArrayOf(u[0], u[1], u[2], v[0], v[1], v[2], n[0], n[1], n[2])
}
