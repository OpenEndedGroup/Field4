@file:afield.runtime.completion.CompletionCategory("Simulation")

/**
 * A small CPU ray tracer for camera figures: what a sensor records through a pinhole or a thin lens.
 *
 * [SensorCamera] traces, for every sensor pixel, a fan of rays through points of its aperture into an
 * [OpticsScene], and adds up the light each one brings back. A small aperture gives a sharp, dim image; a
 * wide one a bright, blurred one; a lens bends each ray so that one plane of the scene comes back
 * sharp. The result is a [SensorImage] of linear irradiance, which becomes a PNG for a box output:
 *
 *     import afield.simulation.optics.*
 *     import afield.media.image.util.PngEncoder
 *
 *     val camera = SensorCamera(z = -6.0, sensorDistance = 1.0, apertureDiameter = 0.05)
 *     val image = camera.render(OpticsScene.testScene(), 160, 106)
 *     display("sensor", PngEncoder.encode(image.toArgb(exposure = 1.0 / image.reference), 160, 106))
 *
 * #### Units and frames
 *
 * Any unit of length, used consistently. The camera looks along its own +z from its aperture, which sits
 * at the camera's position; the sensor is the plane `sensorDistance` behind it. A pixel's irradiance is
 * `∑ L · cosθ · cosθ' / r² · dA` over the aperture, so it falls with aperture area and with `cos⁴` off
 * the axis, as a real camera's does. [SensorImage.toArgb] rotates the image half a turn by default, so a
 * picture reads upright, as a camera's screen shows it.
 *
 * Sampling is deterministic: a golden-angle spiral over the aperture, turned by a different angle for
 * each pixel, so a wide aperture shows smooth blur with fine grain rather than ghost copies.
 */

package afield.simulation.optics

import afield.geometry.mesh.TriMesh
import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.thickness
import afield.math.linalg.Vec4
import afield.geometry.mesh.uvSphereMesh
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A ray: an origin and a unit direction. */
data class OpticsRay(val ox: Double, val oy: Double, val oz: Double, val dx: Double, val dy: Double, val dz: Double) {
    /** The point [t] units along the ray, as `[x, y, z]`. */
    fun at(t: Double): DoubleArray = doubleArrayOf(ox + t * dx, oy + t * dy, oz + t * dz)
}

/**
 * A sphere in an [OpticsScene].
 *
 * @param r,g,b its colour: the fraction of light it reflects, or for an emitter the colour it glows.
 * @param emission radiance it gives off on its own. `0` for an ordinary diffuse sphere lit by the scene;
 * above `0` the sphere is a light and is not shaded.
 */
data class OpticsSphere(
    val x: Double, val y: Double, val z: Double, val radius: Double,
    val r: Double = 0.8, val g: Double = 0.8, val b: Double = 0.8,
    val emission: Double = 0.0,
) {
    /** Distance along [ray] to the near surface, or `-1` for a miss. */
    fun hit(ray: OpticsRay): Double {
        val px = ray.ox - x; val py = ray.oy - y; val pz = ray.oz - z
        val b = px * ray.dx + py * ray.dy + pz * ray.dz
        val c = px * px + py * py + pz * pz - radius * radius
        val disc = b * b - c
        if (disc < 0) return -1.0
        val s = sqrt(disc)
        val t0 = -b - s
        if (t0 > 1e-9) return t0
        val t1 = -b + s
        return if (t1 > 1e-9) t1 else -1.0
    }
}

/**
 * What a [SensorCamera] photographs: diffuse spheres over an optional checkerboard floor, lit by a sun and
 * by a uniformly bright sky.
 *
 * @param floorY height of the floor plane, or `null` for no floor.
 * @param floorHalfSize the checkerboard is a square this far out from the origin on each side; beyond it
 * a ray sees the sky.
 * @param check side of one checker square.
 * @param sky the sky's radiance, rgb. It is the light every diffuse surface also receives from above.
 * @param sun the sun's strength, and [sunX], [sunY], [sunZ] the direction towards it.
 */
class OpticsScene(
    val spheres: List<OpticsSphere>,
    val floorY: Double? = 0.0,
    val floorHalfSize: Double = 4.0,
    val check: Double = 1.0,
    val checkLight: DoubleArray = doubleArrayOf(0.85, 0.85, 0.85),
    val checkDark: DoubleArray = doubleArrayOf(0.12, 0.12, 0.14),
    val sky: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0),
    val sun: Double = 0.9,
    sunX: Double = -0.4, sunY: Double = 1.0, sunZ: Double = -0.6,
    /** The fraction of the sky's light a surface receives; the rest of its light is the sun's. */
    val ambient: Double = 0.45,
) {
    private val sunDir = normalize(sunX, sunY, sunZ)

    /** Radiance arriving back along [ray], written into [out] as rgb. */
    fun radiance(ray: OpticsRay, out: DoubleArray) {
        var best = Double.MAX_VALUE
        var hitSphere: OpticsSphere? = null
        for (s in spheres) {
            val t = s.hit(ray)
            if (t > 0 && t < best) { best = t; hitSphere = s }
        }
        val fy = floorY
        var floorT = -1.0
        if (fy != null && ray.dy < -1e-12) {
            val t = (fy - ray.oy) / ray.dy
            if (t > 1e-9 && t < best) {
                val hx = ray.ox + t * ray.dx; val hz = ray.oz + t * ray.dz
                if (abs(hx) <= floorHalfSize && abs(hz) <= floorHalfSize) { floorT = t; best = t; hitSphere = null }
            }
        }
        if (hitSphere != null) {
            val s = hitSphere
            if (s.emission > 0) {
                out[0] = s.r * s.emission; out[1] = s.g * s.emission; out[2] = s.b * s.emission
                return
            }
            val hx = ray.ox + best * ray.dx; val hy = ray.oy + best * ray.dy; val hz = ray.oz + best * ray.dz
            val nx = (hx - s.x) / s.radius; val ny = (hy - s.y) / s.radius; val nz = (hz - s.z) / s.radius
            val light = lightAt(hx, hy, hz, nx, ny, nz, s)
            out[0] = s.r * light * sky[0]; out[1] = s.g * light * sky[1]; out[2] = s.b * light * sky[2]
            return
        }
        if (floorT > 0) {
            val hx = ray.ox + floorT * ray.dx; val hz = ray.oz + floorT * ray.dz
            val light = lightAt(hx, fy!!, hz, 0.0, 1.0, 0.0, null)
            val cell = (floor(hx / check).toInt() + floor(hz / check).toInt()) and 1
            val c = if (cell == 0) checkLight else checkDark
            out[0] = c[0] * light * sky[0]; out[1] = c[1] * light * sky[1]; out[2] = c[2] * light * sky[2]
            return
        }
        out[0] = sky[0]; out[1] = sky[1]; out[2] = sky[2]
    }

    private fun lightAt(x: Double, y: Double, z: Double, nx: Double, ny: Double, nz: Double, self: OpticsSphere?): Double {
        // Sky light, less where the surface faces down; the sun's, unless something is in the way.
        var light = ambient * (0.6 + 0.4 * ny)
        val cosSun = nx * sunDir[0] + ny * sunDir[1] + nz * sunDir[2]
        if (cosSun > 0) {
            val shadow = OpticsRay(x + nx * 1e-6, y + ny * 1e-6, z + nz * 1e-6, sunDir[0], sunDir[1], sunDir[2])
            if (spheres.none { it !== self && it.emission <= 0 && it.hit(shadow) > 0 }) light += sun * cosSun
        }
        return light
    }

    /**
     * The scene as one triangle mesh with per-vertex colours, for a `three { mesh(...) }` view of the same
     * objects the camera photographs. Emitters are drawn at their glow colour.
     */
    fun mesh(sphereSegments: Int = 32): TriMesh {
        var all: TriMesh? = null
        for (s in spheres) {
            val m = uvSphereMesh(sphereSegments, sphereSegments / 2, s.radius).translated(s.x, s.y, s.z).withColor(s.r, s.g, s.b)
            all = all?.plus(m) ?: m
        }
        val fy = floorY
        if (fy != null) {
            val n = max(1, (2 * floorHalfSize / check).toInt())
            val step = 2 * floorHalfSize / n
            val pos = FloatArray(n * n * 12); val nor = FloatArray(n * n * 12); val col = FloatArray(n * n * 12)
            val idx = IntArray(n * n * 6)
            for (i in 0 until n) for (j in 0 until n) {
                val q = i * n + j
                val x0 = -floorHalfSize + i * step; val z0 = -floorHalfSize + j * step
                val cell = (floor((x0 + step / 2) / check).toInt() + floor((z0 + step / 2) / check).toInt()) and 1
                val c = if (cell == 0) checkLight else checkDark
                val corners = arrayOf(doubleArrayOf(x0, z0), doubleArrayOf(x0, z0 + step), doubleArrayOf(x0 + step, z0 + step), doubleArrayOf(x0 + step, z0))
                for (k in 0 until 4) {
                    val v = q * 4 + k
                    pos[v * 3] = corners[k][0].toFloat(); pos[v * 3 + 1] = fy.toFloat(); pos[v * 3 + 2] = corners[k][1].toFloat()
                    nor[v * 3 + 1] = 1f
                    col[v * 3] = c[0].toFloat(); col[v * 3 + 1] = c[1].toFloat(); col[v * 3 + 2] = c[2].toFloat()
                }
                val b = q * 4
                intArrayOf(b, b + 1, b + 2, b, b + 2, b + 3).copyInto(idx, q * 6)
            }
            val floorMesh = TriMesh(pos, idx, nor, null, col)
            all = all?.plus(floorMesh) ?: floorMesh
        }
        return all ?: TriMesh(FloatArray(0), IntArray(0))
    }

    companion object {
        /**
         * The scene every camera figure photographs: a red sphere and a green one on a checkerboard,
         * under a bright sky. The spheres sit either side of the origin, the green one further from a camera
         * on the -z side, which is where every [SensorCamera] starts.
         */
        fun testScene(): OpticsScene = OpticsScene(
            listOf(
                OpticsSphere(-0.9, 0.7, -0.8, 0.7, 0.85, 0.12, 0.1),
                OpticsSphere(1.0, 0.6, 1.3, 0.6, 0.15, 0.75, 0.2),
            ),
        )

        /** One small glowing point (a sphere of [radius]) on the axis at depth [z], against a black sky. */
        fun pointLight(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0, radius: Double = 0.02, emission: Double = 1.0): OpticsScene =
            OpticsScene(listOf(OpticsSphere(x, y, z, radius, 1.0, 1.0, 1.0, emission)), floorY = null, sky = doubleArrayOf(0.0, 0.0, 0.0), sun = 0.0)

        /** A sky of even brightness and nothing else, which shows a camera's own falloff. */
        fun uniformSky(radiance: Double = 1.0): OpticsScene =
            OpticsScene(emptyList(), floorY = null, sky = doubleArrayOf(radiance, radiance, radiance), sun = 0.0)
    }
}

/**
 * A camera: an aperture at ([x], [y], [z]) looking towards ([lookX], [lookY], [lookZ]), with a sensor
 * [sensorDistance] behind it.
 *
 * With [focalLength] infinite (the default) the aperture is a plain hole, a pinhole camera. A finite
 * [focalLength] puts a thin lens in the hole, which bends every ray so that the plane at
 * [ThinLens.objectDistance] of the sensor distance comes back sharp.
 *
 * @param apertureDiameter the hole's (or lens opening's) diameter.
 * @param blades `0` for a round opening; `3` or more for a regular polygon, the shape iris blades make,
 * turned by [bladeRotation] radians. Out-of-focus points take this shape.
 */
class SensorCamera(
    val x: Double = 0.0, val y: Double = 1.2, val z: Double = -6.0,
    val lookX: Double = 0.0, val lookY: Double = 0.5, val lookZ: Double = 0.0,
    val sensorWidth: Double = 1.5,
    val sensorHeight: Double = 1.0,
    val sensorDistance: Double = 1.0,
    val apertureDiameter: Double = 0.05,
    val focalLength: Double = Double.POSITIVE_INFINITY,
    val blades: Int = 0,
    val bladeRotation: Double = 0.0,
) {
    private val forward = normalize(lookX - x, lookY - y, lookZ - z)
    private val right = normalize(cross(forward, doubleArrayOf(0.0, 1.0, 0.0)))
    private val up = cross(right, forward)

    /** The irradiance, relative to [SensorImage.toArgb]'s `exposure = 1`, of an even sky of radiance 1 on the axis. */
    val reference: Double get() = apertureArea / (sensorDistance * sensorDistance)

    /** The opening's area: a disc, or the inscribed regular polygon for [blades] `>= 3`. */
    val apertureArea: Double get() {
        val a = apertureDiameter / 2
        return if (blades >= 3) blades / 2.0 * a * a * sin(2 * PI / blades) else PI * a * a
    }

    /** Where camera coordinates ([cx] right, [cy] up, [cz] forward) are in the world, as `[x, y, z]`. */
    fun toWorld(cx: Double, cy: Double, cz: Double): DoubleArray = doubleArrayOf(
        x + cx * right[0] + cy * up[0] + cz * forward[0],
        y + cx * right[1] + cy * up[1] + cz * forward[1],
        z + cx * right[2] + cy * up[2] + cz * forward[2],
    )

    /**
     * The ray that leaves the camera when light from the sensor point ([sx], [sy]) passes the aperture
     * point ([ax], [ay]), both in camera coordinates (the sensor point is in the plane behind the hole).
     * A pinhole leaves its direction alone; a thin lens bends it by `-a / f` in each direction.
     */
    fun ray(sx: Double, sy: Double, ax: Double = 0.0, ay: Double = 0.0): OpticsRay {
        val d = sensorDistance
        var ux = (ax - sx) / d
        var uy = (ay - sy) / d
        if (focalLength.isFinite()) { ux -= ax / focalLength; uy -= ay / focalLength }
        val o = toWorld(ax, ay, 0.0)
        val dir = normalize(
            ux * right[0] + uy * up[0] + forward[0],
            ux * right[1] + uy * up[1] + forward[1],
            ux * right[2] + uy * up[2] + forward[2],
        )
        return OpticsRay(o[0], o[1], o[2], dir[0], dir[1], dir[2])
    }

    /** Where the world point ([wx], [wy], [wz]) is in camera coordinates (right, up, forward), as `[x, y, z]`. */
    fun toCamera(wx: Double, wy: Double, wz: Double): DoubleArray {
        val px = wx - x; val py = wy - y; val pz = wz - z
        return doubleArrayOf(
            px * right[0] + py * right[1] + pz * right[2],
            px * up[0] + py * up[1] + pz * up[2],
            px * forward[0] + py * forward[1] + pz * forward[2],
        )
    }

    /**
     * Where on the sensor, in world coordinates, the world point ([wx], [wy], [wz]) is imaged: the end of
     * the straight line from it through the middle of the aperture. In focus, every other ray from the
     * point lands there too.
     */
    fun imageOf(wx: Double, wy: Double, wz: Double): DoubleArray {
        val c = toCamera(wx, wy, wz)
        val k = sensorDistance / c[2]
        return toWorld(-c[0] * k, -c[1] * k, -sensorDistance)
    }

    /**
     * The camera drawn as a box: the edges of a box from the aperture plane back to the sensor, as large
     * as the sensor, and a ring for the opening, for a `three { lines(...) }` view.
     */
    fun outline(color: Vec4 = Vec4(1f, 1f, 1f, 0.35f), thickness: Double = 1.5): List<FLine> {
        val w = sensorWidth / 2; val h = sensorHeight / 2; val d = sensorDistance
        fun line(vararg pts: DoubleArray) = FLine().also { f ->
            pts.forEachIndexed { i, p -> if (i == 0) f.moveTo(p[0], p[1], p[2]) else f.lineTo(p[0], p[1], p[2]) }
            f.color = color; f.thickness = thickness
        }
        val corners = listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0)
        val back = corners.map { (a, b) -> toWorld(a * w, b * h, -d) }
        val front = corners.map { (a, b) -> toWorld(a * w, b * h, 0.0) }
        val out = mutableListOf(line(*(back + back[0]).toTypedArray()), line(*(front + front[0]).toTypedArray()))
        for (k in 0 until 4) out += line(back[k], front[k])
        val ring = (0..48).map { k ->
            val t = 2 * PI * k / 48
            toWorld(apertureDiameter / 2 * cos(t), apertureDiameter / 2 * sin(t), 0.0)
        }
        out += line(*ring.toTypedArray()).also { it.color = Vec4(1f, 0.6f, 0.2f, 1f); it.thickness = 2.0 }
        return out
    }

    /**
     * The sensor's picture as it lies on the back wall of the camera: one flat-coloured quad per pixel of
     * [image], at its place in the world, so a `three` view shows the inverted image where it forms. Draw
     * it with `unlit = true, cullBackFaces = false`.
     */
    fun sensorMesh(image: SensorImage, exposure: Double = 1.0 / image.reference): TriMesh {
        val px = image.toArgb(exposure, upright = false)
        val n = image.width * image.height
        val pos = FloatArray(n * 12); val col = FloatArray(n * 12); val idx = IntArray(n * 6)
        val pw = sensorWidth / image.width; val ph = sensorHeight / image.height
        for (j in 0 until image.height) for (i in 0 until image.width) {
            val q = j * image.width + i
            val x0 = -sensorWidth / 2 + i * pw; val y0 = sensorHeight / 2 - j * ph
            val corners = arrayOf(toWorld(x0, y0, -sensorDistance), toWorld(x0 + pw, y0, -sensorDistance),
                toWorld(x0 + pw, y0 - ph, -sensorDistance), toWorld(x0, y0 - ph, -sensorDistance))
            val argb = px[q]
            // toArgb is gamma-encoded for display; a mesh colour is linear.
            val r = (((argb shr 16) and 255) / 255.0).pow(2.2); val g = (((argb shr 8) and 255) / 255.0).pow(2.2); val b = ((argb and 255) / 255.0).pow(2.2)
            for (k in 0 until 4) {
                val v = q * 4 + k
                for (c in 0 until 3) pos[v * 3 + c] = corners[k][c].toFloat()
                col[v * 3] = r.toFloat(); col[v * 3 + 1] = g.toFloat(); col[v * 3 + 2] = b.toFloat()
            }
            val b0 = q * 4
            intArrayOf(b0, b0 + 1, b0 + 2, b0, b0 + 2, b0 + 3).copyInto(idx, q * 6)
        }
        return TriMesh(pos, idx, null, null, col)
    }

    /** Whether the aperture point ([ax], [ay]) is inside the opening. */
    fun insideAperture(ax: Double, ay: Double): Boolean {
        val a = apertureDiameter / 2
        val rr = sqrt(ax * ax + ay * ay)
        if (rr > a) return false
        if (blades < 3) return true
        val sector = 2 * PI / blades
        var phi = kotlin.math.atan2(ay, ax) - bladeRotation
        phi -= sector * floor(phi / sector)
        return rr <= a * cos(PI / blades) / cos(phi - sector / 2)
    }

    /**
     * What the sensor records: a [width] × [height] image of irradiance, each pixel the sum of [samples]
     * rays through the aperture (a polygon keeps those that fall inside it).
     */
    fun render(scene: OpticsScene, width: Int, height: Int, samples: Int = 64): SensorImage {
        val out = FloatArray(width * height * 3)
        val a = apertureDiameter / 2
        val d = sensorDistance
        val baseX = DoubleArray(samples); val baseY = DoubleArray(samples)
        val golden = PI * (3 - sqrt(5.0))
        for (k in 0 until samples) {
            val rho = sqrt((k + 0.5) / samples)
            baseX[k] = rho * cos(k * golden); baseY[k] = rho * sin(k * golden)
        }
        val l = DoubleArray(3)
        for (j in 0 until height) for (i in 0 until width) {
            val sx = ((i + 0.5) / width - 0.5) * sensorWidth
            val sy = (0.5 - (j + 0.5) / height) * sensorHeight
            val turn = hash(i, j) * 2 * PI
            val ct = cos(turn); val st = sin(turn)
            var er = 0.0; var eg = 0.0; var eb = 0.0
            var used = 0
            for (k in 0 until samples) {
                val ax = a * (baseX[k] * ct - baseY[k] * st)
                val ay = a * (baseX[k] * st + baseY[k] * ct)
                if (blades >= 3 && !insideAperture(ax, ay)) continue
                used++
                scene.radiance(ray(sx, sy, ax, ay), l)
                val r2 = (ax - sx) * (ax - sx) + (ay - sy) * (ay - sy) + d * d
                val w = d * d / (r2 * r2)
                er += l[0] * w; eg += l[1] * w; eb += l[2] * w
            }
            if (used > 0) {
                val scale = apertureArea / used
                val p = (j * width + i) * 3
                out[p] = (er * scale).toFloat(); out[p + 1] = (eg * scale).toFloat(); out[p + 2] = (eb * scale).toFloat()
            }
        }
        return SensorImage(width, height, out, reference)
    }

    /**
     * What the sensor records with nothing in front of it: each detector, lying where the aperture is,
     * takes light from its whole half of the sky, sampled in [samples] cosine-weighted directions. The
     * image's [SensorImage.reference] is `π`, the irradiance of an even sky of radiance 1, so the default
     * exposure shows each detector's average view.
     */
    fun renderBare(scene: OpticsScene, width: Int, height: Int, samples: Int = 128): SensorImage {
        val out = FloatArray(width * height * 3)
        val l = DoubleArray(3)
        val golden = PI * (3 - sqrt(5.0))
        for (j in 0 until height) for (i in 0 until width) {
            val sx = ((i + 0.5) / width - 0.5) * sensorWidth
            val sy = (0.5 - (j + 0.5) / height) * sensorHeight
            val o = toWorld(sx, sy, 0.0)
            val turn = hash(i, j) * 2 * PI
            var er = 0.0; var eg = 0.0; var eb = 0.0
            for (k in 0 until samples) {
                // A point on the unit disc lifted onto the hemisphere is a cosine-weighted direction.
                val rho = sqrt((k + 0.5) / samples)
                val a = k * golden + turn
                val cx = rho * cos(a); val cy = rho * sin(a); val cz = sqrt(max(0.0, 1 - rho * rho))
                val dx = cx * right[0] + cy * up[0] + cz * forward[0]
                val dy = cx * right[1] + cy * up[1] + cz * forward[1]
                val dz = cx * right[2] + cy * up[2] + cz * forward[2]
                scene.radiance(OpticsRay(o[0], o[1], o[2], dx, dy, dz), l)
                er += l[0]; eg += l[1]; eb += l[2]
            }
            val p = (j * width + i) * 3
            val k = PI / samples
            out[p] = (er * k).toFloat(); out[p + 1] = (eg * k).toFloat(); out[p + 2] = (eb * k).toFloat()
        }
        return SensorImage(width, height, out, PI)
    }

    private fun hash(i: Int, j: Int): Double {
        var h = i * 374761393 + j * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216.0
    }
}

/**
 * A sensor's record: linear rgb irradiance per pixel, row by row from the top.
 *
 * @param reference the irradiance an even sky of radiance 1 gives on the axis; `exposure = 1 / reference`
 * in [toArgb] shows such a sky as white whatever the aperture. Leave the exposure fixed instead to see the
 * image dim as the hole shrinks.
 */
class SensorImage(val width: Int, val height: Int, val rgb: FloatArray, val reference: Double = 1.0) {
    init { require(rgb.size == width * height * 3) { "rgb must hold ${width * height} rgb triples, got ${rgb.size / 3}" } }

    /** Channel [c] (0 red, 1 green, 2 blue) of pixel ([i], [j]). */
    operator fun get(i: Int, j: Int, c: Int): Float = rgb[(j * width + i) * 3 + c]

    /** Mean brightness over every pixel and channel. */
    fun mean(): Double = rgb.sumOf { it.toDouble() } / rgb.size

    /** Brightness-weighted centre, `[x, y]` in pixels. */
    fun centroid(): DoubleArray {
        var sx = 0.0; var sy = 0.0; var sw = 0.0
        for (j in 0 until height) for (i in 0 until width) {
            val w = luminance(i, j)
            sx += w * (i + 0.5); sy += w * (j + 0.5); sw += w
        }
        return if (sw > 0) doubleArrayOf(sx / sw, sy / sw) else doubleArrayOf(width / 2.0, height / 2.0)
    }

    /**
     * How spread out the light is: the brightness-weighted root-mean-square distance from [centroid], in
     * pixels. For the image of one point it is the blur; a uniform disc of diameter `c` gives `c / (2√2)`.
     */
    fun spotRadius(): Double {
        val c = centroid()
        var s = 0.0; var sw = 0.0
        for (j in 0 until height) for (i in 0 until width) {
            val w = luminance(i, j)
            val dx = i + 0.5 - c[0]; val dy = j + 0.5 - c[1]
            s += w * (dx * dx + dy * dy); sw += w
        }
        return if (sw > 0) sqrt(s / sw) else 0.0
    }

    private fun luminance(i: Int, j: Int): Double {
        val p = (j * width + i) * 3
        return (rgb[p] + rgb[p + 1] + rgb[p + 2]).toDouble()
    }

    /** This image with every value multiplied by [k]. */
    fun scaled(k: Double): SensorImage = SensorImage(width, height, FloatArray(rgb.size) { (rgb[it] * k).toFloat() }, reference)

    /**
     * Pixels for [afield.media.image.util.PngEncoder.encode]: each value times [exposure], clipped at
     * white, gamma-encoded for display. [upright] turns the image half a turn, which is how a lens or a
     * pinhole camera's inverted image is shown to a person. [scale] repeats each pixel into a
     * `scale × scale` block, for a small sensor drawn large; the result is `width·scale × height·scale`.
     */
    fun toArgb(exposure: Double = 1.0 / reference, upright: Boolean = true, scale: Int = 1): IntArray {
        val w = width * scale; val h = height * scale
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var i = x / scale; var j = y / scale
            if (upright) { i = width - 1 - i; j = height - 1 - j }
            val p = (j * width + i) * 3
            px[y * w + x] = (0xFF shl 24) or (encode(rgb[p] * exposure) shl 16) or (encode(rgb[p + 1] * exposure) shl 8) or encode(rgb[p + 2] * exposure)
        }
        return px
    }

    /**
     * What a sensor with a Bayer filter records: at each pixel only the channel its filter passes, in the
     * 2 × 2 pattern red, green / green, blue. The other two channels are zero.
     */
    fun mosaic(): SensorImage {
        val out = FloatArray(rgb.size)
        for (j in 0 until height) for (i in 0 until width) {
            val c = bayerChannel(i, j)
            val p = (j * width + i) * 3
            out[p + c] = rgb[p + c]
        }
        return SensorImage(width, height, out, reference)
    }

    /**
     * A full-colour image from a [mosaic]: each missing channel is the mean of the neighbours (within one
     * pixel) whose filter recorded it.
     */
    fun demosaic(): SensorImage {
        val out = FloatArray(rgb.size)
        for (j in 0 until height) for (i in 0 until width) {
            val p = (j * width + i) * 3
            for (c in 0 until 3) {
                if (bayerChannel(i, j) == c) { out[p + c] = rgb[p + c]; continue }
                var s = 0.0; var n = 0
                for (dj in -1..1) for (di in -1..1) {
                    val ii = i + di; val jj = j + dj
                    if (ii !in 0 until width || jj !in 0 until height || bayerChannel(ii, jj) != c) continue
                    s += rgb[(jj * width + ii) * 3 + c]; n++
                }
                out[p + c] = if (n > 0) (s / n).toFloat() else 0f
            }
        }
        return SensorImage(width, height, out, reference)
    }

    /**
     * Light arriving as photons: each channel's value times [photonsPerUnit] is the expected count, and
     * the count each pixel actually collects is drawn from a Poisson distribution, the grain of a dim or
     * short exposure. The result holds counts; [seed] makes it repeatable.
     */
    fun photons(photonsPerUnit: Double, seed: Long = 1): SensorImage {
        val random = kotlin.random.Random(seed)
        val out = FloatArray(rgb.size) { poisson(rgb[it] * photonsPerUnit, random).toFloat() }
        return SensorImage(width, height, out, reference * photonsPerUnit)
    }

    companion object {
        /** Which channel the Bayer filter over pixel ([i], [j]) passes: red at even/even, blue at odd/odd, green elsewhere. */
        fun bayerChannel(i: Int, j: Int): Int = when {
            i % 2 == 0 && j % 2 == 0 -> 0
            i % 2 == 1 && j % 2 == 1 -> 2
            else -> 1
        }

        private fun encode(v: Double): Int = (255.0 * min(1.0, max(0.0, v)).pow(1 / 2.2) + 0.5).toInt()

        private fun poisson(mean: Double, random: kotlin.random.Random): Int {
            if (mean <= 0) return 0
            if (mean > 60) return max(0, (mean + sqrt(mean) * gaussian(random) + 0.5).toInt())
            val limit = exp(-mean)
            var k = 0; var p = random.nextDouble()
            while (p > limit) { k++; p *= random.nextDouble() }
            return k
        }

        private fun gaussian(random: kotlin.random.Random): Double {
            val u = max(1e-12, random.nextDouble()); val v = random.nextDouble()
            return sqrt(-2 * kotlin.math.ln(u)) * cos(2 * PI * v)
        }
    }
}

/**
 * The thin-lens relations: a lens of focal length `f` brings the plane at distance `o` in front of it to a
 * sharp image at distance `i` behind it when `1/o + 1/i = 1/f`.
 */
object ThinLens {
    /** How far behind the lens the plane at [objectDistance] comes to focus; infinite when it is at the focal point. */
    fun imageDistance(focalLength: Double, objectDistance: Double): Double = 1.0 / (1.0 / focalLength - 1.0 / objectDistance)

    /** Which plane in front of the lens is sharp on a sensor at [imageDistance]. */
    fun objectDistance(focalLength: Double, imageDistance: Double): Double = 1.0 / (1.0 / focalLength - 1.0 / imageDistance)

    /**
     * The diameter of the blur circle a point at [objectDistance] makes on a sensor at [sensorDistance],
     * through an opening of [apertureDiameter]. `0` when the point is in focus. With [focalLength]
     * infinite this is a pinhole's blur, `aperture · (1 + sensorDistance / objectDistance)`.
     */
    fun blurDiameter(focalLength: Double, apertureDiameter: Double, objectDistance: Double, sensorDistance: Double): Double {
        if (!focalLength.isFinite()) return apertureDiameter * (1 + sensorDistance / objectDistance)
        val i = imageDistance(focalLength, objectDistance)
        return apertureDiameter * abs(sensorDistance - i) / abs(i)
    }

    /** The f-number: focal length over aperture diameter. Each stop up, ×√2, halves the light. */
    fun fNumber(focalLength: Double, apertureDiameter: Double): Double = focalLength / apertureDiameter

    /**
     * The near and far limits of acceptable sharpness, `[near, far]`, for a lens focused at
     * [focusDistance], where a blur circle up to [acceptableBlur] on the sensor still reads as sharp. `far`
     * is infinite past the hyperfocal distance.
     */
    fun depthOfField(focalLength: Double, apertureDiameter: Double, focusDistance: Double, acceptableBlur: Double): DoubleArray {
        val i = imageDistance(focalLength, focusDistance)
        // A point images at i' where the blur A·|i − i'|/i' equals the acceptable blur c.
        val nearImage = i * apertureDiameter / (apertureDiameter - acceptableBlur)
        val farImage = i * apertureDiameter / (apertureDiameter + acceptableBlur)
        val near = objectDistance(focalLength, nearImage)
        val far = if (acceptableBlur >= apertureDiameter) Double.POSITIVE_INFINITY else objectDistance(focalLength, farImage)
        return doubleArrayOf(near, if (far <= 0 || far.isNaN()) Double.POSITIVE_INFINITY else far)
    }
}

/** Refraction and reflection at the boundary between two transparent materials. */
object Refraction {
    /**
     * Snell's law, `n₁ sin θ₁ = n₂ sin θ₂`: the angle from the normal at which a ray arriving at [theta]
     * radians leaves, or `NaN` when it cannot leave at all (total internal reflection).
     */
    fun snellAngle(theta: Double, n1: Double, n2: Double): Double {
        val s = n1 / n2 * sin(theta)
        return if (abs(s) > 1) Double.NaN else asin(s)
    }

    /** The angle past which light in [n1] cannot cross into a thinner [n2]; `NaN` when [n2] is not thinner. */
    fun criticalAngle(n1: Double, n2: Double): Double = if (n2 >= n1) Double.NaN else asin(n2 / n1)

    /** The fraction of unpolarised light reflected at [theta] radians (the Fresnel equations); `1` past the critical angle. */
    fun reflectance(theta: Double, n1: Double, n2: Double): Double {
        val t = snellAngle(theta, n1, n2)
        if (t.isNaN()) return 1.0
        val ci = cos(theta); val ct = cos(t)
        val rs = (n1 * ci - n2 * ct) / (n1 * ci + n2 * ct)
        val rp = (n1 * ct - n2 * ci) / (n1 * ct + n2 * ci)
        return (rs * rs + rp * rp) / 2
    }

    /**
     * The direction a ray travelling along ([dx], [dy]) takes past a boundary with unit normal ([nx], [ny])
     * (either side), going from index [n1] into [n2], as a unit `[x, y]`; `null` for total internal reflection.
     */
    fun refract(dx: Double, dy: Double, nx: Double, ny: Double, n1: Double, n2: Double): DoubleArray? {
        val len = sqrt(dx * dx + dy * dy)
        val ux = dx / len; val uy = dy / len
        var mx = nx; var my = ny
        var cosI = -(ux * mx + uy * my)
        if (cosI < 0) { mx = -mx; my = -my; cosI = -cosI }
        val eta = n1 / n2
        val k = 1 - eta * eta * (1 - cosI * cosI)
        if (k < 0) return null
        val c = eta * cosI - sqrt(k)
        return doubleArrayOf(eta * ux + c * mx, eta * uy + c * my)
    }

    /** ([dx], [dy]) mirrored in a boundary with unit normal ([nx], [ny]). */
    fun reflect(dx: Double, dy: Double, nx: Double, ny: Double): DoubleArray {
        val k = 2 * (dx * nx + dy * ny)
        return doubleArrayOf(dx - k * nx, dy - k * ny)
    }

    /**
     * The crests of a plane wave crossing from air into glass, for a side-view drawing.
     *
     * The picture is [width] × [height] with y downwards; the glass fills it below [surfaceY]. The wave
     * travels down and to the right at [angle] radians from the perpendicular, its crests [wavelength]
     * apart in air, and has gone [travelled] since it started (so crests move as it grows). In the glass
     * the crests are `wavelength / index` apart and turned to Snell's angle, and each crest's two parts
     * meet on the surface: the phase along the surface is the same on both sides, which is what makes the
     * wave turn. Each [WaveCrest] holds one crest's segment in air and in glass, either `null` where it is
     * outside the picture, as `[x0, y0, x1, y1]` clipped to the picture.
     */
    fun crossingCrests(angle: Double, index: Double, wavelength: Double, travelled: Double,
                       width: Double, height: Double, surfaceY: Double): List<WaveCrest> {
        val turned = snellAngle(angle, 1.0, index)
        val ox = width / 2
        val phase = travelled - wavelength * floor(travelled / wavelength)
        // Phase distance along the wave's direction, at each corner of the picture.
        val corners = listOf(0.0 to 0.0, width to 0.0, 0.0 to height, width to height)
        val reach = corners.map { (x, y) -> abs((x - ox) * sin(angle) + (y - surfaceY) * cos(angle)) + abs((x - ox) * sin(turned) + (y - surfaceY) * cos(turned)) * index }.max()
        val m = (reach / wavelength).toInt() + 2
        return (-m..m).mapNotNull { k ->
            val s = k * wavelength + phase
            val air = clipCrest(ox, surfaceY, s, angle, width, 0.0, surfaceY)
            val glass = clipCrest(ox, surfaceY, s / index, turned, width, surfaceY, height)
            if (air == null && glass == null) null else WaveCrest(air, glass)
        }
    }

    // The line of points p with (p - o)·(sin θ, cos θ) = s, clipped to 0 ≤ x ≤ width, top ≤ y ≤ bottom.
    private fun clipCrest(ox: Double, oy: Double, s: Double, theta: Double, width: Double, top: Double, bottom: Double): DoubleArray? {
        val px = ox + s * sin(theta); val py = oy + s * cos(theta)
        val dx = cos(theta); val dy = -sin(theta)
        var t0 = Double.NEGATIVE_INFINITY; var t1 = Double.POSITIVE_INFINITY
        fun slab(p: Double, d: Double, lo: Double, hi: Double): Boolean {
            if (abs(d) < 1e-12) return p in lo..hi
            val a = (lo - p) / d; val b = (hi - p) / d
            t0 = max(t0, min(a, b)); t1 = min(t1, max(a, b))
            return true
        }
        if (!slab(px, dx, 0.0, width) || !slab(py, dy, top, bottom) || t1 - t0 < 1e-9) return null
        return doubleArrayOf(px + t0 * dx, py + t0 * dy, px + t1 * dx, py + t1 * dy)
    }

    /** A glass's refractive index at [wavelengthNm] from Cauchy's equation `a + b / λ²`; the defaults are close to crown glass. */
    fun cauchyIndex(wavelengthNm: Double, a: Double = 1.5046, b: Double = 4200.0): Double = a + b / (wavelengthNm * wavelengthNm)

    /** Roughly the colour of light of [wavelengthNm] (380–780 nm), as rgb in `0..1`, for drawing a spectrum. */
    fun wavelengthColor(wavelengthNm: Double): DoubleArray {
        val w = wavelengthNm
        val (r, g, b) = when {
            w < 440 -> Triple((440 - w) / 60, 0.0, 1.0)
            w < 490 -> Triple(0.0, (w - 440) / 50, 1.0)
            w < 510 -> Triple(0.0, 1.0, (510 - w) / 20)
            w < 580 -> Triple((w - 510) / 70, 1.0, 0.0)
            w < 645 -> Triple(1.0, (645 - w) / 65, 0.0)
            else -> Triple(1.0, 0.0, 0.0)
        }
        val edge = when { w < 420 -> 0.3 + 0.7 * (w - 380) / 40; w > 700 -> 0.3 + 0.7 * (780 - w) / 80; else -> 1.0 }
        return doubleArrayOf(min(1.0, max(0.0, r * edge)), min(1.0, max(0.0, g * edge)), min(1.0, max(0.0, b * edge)))
    }
}

/** One crest from [Refraction.crossingCrests]: its segment in air and in glass, `[x0, y0, x1, y1]` or `null`. */
class WaveCrest(val air: DoubleArray?, val glass: DoubleArray?)

/**
 * Where a ray leaves a [width] × [height] picture: from ([x], [y]) inside it along ([dx], [dy]), the point
 * `[x, y]` on the picture's edge. A side-view diagram ends its rays here, so a steep ray does not reach
 * far outside the frame and make `lines(...)` zoom out to fit it.
 */
fun frameExit(x: Double, y: Double, dx: Double, dy: Double, width: Double, height: Double): DoubleArray {
    var t = Double.MAX_VALUE
    if (dx > 1e-12) t = min(t, (width - x) / dx) else if (dx < -1e-12) t = min(t, -x / dx)
    if (dy > 1e-12) t = min(t, (height - y) / dy) else if (dy < -1e-12) t = min(t, -y / dy)
    if (t == Double.MAX_VALUE) t = 0.0
    return doubleArrayOf(x + max(0.0, t) * dx, y + max(0.0, t) * dy)
}

private fun normalize(x: Double, y: Double, z: Double): DoubleArray {
    val l = sqrt(x * x + y * y + z * z)
    return doubleArrayOf(x / l, y / l, z / l)
}

private fun normalize(v: DoubleArray): DoubleArray = normalize(v[0], v[1], v[2])

private fun cross(a: DoubleArray, b: DoubleArray): DoubleArray =
    doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
