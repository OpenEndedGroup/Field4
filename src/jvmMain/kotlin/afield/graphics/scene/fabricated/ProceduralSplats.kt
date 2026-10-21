package afield.graphics.scene.fabricated

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The six procedural clouds of the madp splat-shader demo, as [SplatSet]s: built from code,
 * deterministic, and the same splat for splat as the demo's (same order, so `index` in an insert
 * means the same splat in both).
 *
 * Each is a few thousand flat, oriented splats — beads, petals, shell plates — coloured with HSL
 * the way three.js colours them: the HSL is read as sRGB and converted to linear, which is what
 * the demo hands Spark. Feed one to a [SplatShader]:
 *
 * ```
 * var module = computeModuleHere {}
 * SplatShader(module, ProceduralSplats.torusBraid())
 * ```
 *
 * All six sit roughly in the box −1..1 with +y up; a [SplatShader] frames them anyway.
 */
object ProceduralSplats {

    /** The six clouds by the demo's names, in the demo's order. */
    val all: Map<String, () -> SplatSet> = linkedMapOf(
        "icosphere shell" to { icosphereShell() },
        "fern plume" to { fernPlume() },
        "nautilus shell" to { nautilusShell() },
        "coral branches" to { coralBranches() },
        "phyllotaxis bloom" to { phyllotaxisBloom() },
        "torus braid" to { torusBraid() }
    )

    /**
     * Points on a subdivided octahedron pushed out onto a sphere, each a thin disc facing out and
     * coloured by longitude: Spark's `constructSpherePoints` with radius 1.05, depth 4. 1 026
     * splats.
     */
    fun icosphereShell(
        radius: Double = 1.05,
        depth: Int = 4,
        pointRadius: Double = 0.024,
        pointThickness: Double = 0.003
    ): SplatSet {
        val points = LinkedHashMap<Triple<Double, Double, Double>, V3>()
        fun add(p: V3) {
            // JS keys these by "x,y,z", which merges -0 with 0; adding 0.0 does the same here
            points.putIfAbsent(Triple(p.x + 0.0, p.y + 0.0, p.z + 0.0), p)
        }
        fun recurse(d: Int, p0: V3, p1: V3, p2: V3) {
            add(p0); add(p1); add(p2)
            if (d >= depth) return
            val p01 = (p0 + p1).normalized()
            val p12 = (p1 + p2).normalized()
            val p20 = (p2 + p0).normalized()
            recurse(d + 1, p0, p01, p20)
            recurse(d + 1, p01, p1, p12)
            recurse(d + 1, p20, p12, p2)
            recurse(d + 1, p01, p12, p20)
        }
        for (x in listOf(-1.0, 1.0)) for (y in listOf(-1.0, 1.0)) for (z in listOf(-1.0, 1.0))
            recurse(0, V3(x, 0.0, 0.0), V3(0.0, y, 0.0), V3(0.0, 0.0, z))

        val set = SplatSet(points.size)
        for (p in points.values) {
            val q = quaternionFromUnitVectors(0.0, 0.0, -1.0, p.x, p.y, p.z)
            val hue = (atan2(p.z, p.x) / (PI * 2) + 1) % 1
            val c = hsl(hue, 0.78, 0.58 + p.y * 0.12)
            set.push(p.x * radius, p.y * radius, p.z * radius, pointRadius, pointRadius, pointThickness,
                q[0], q[1], q[2], q[3], 1.0, c[0], c[1], c[2])
        }
        return set
    }

    /**
     * The Barnsley fern played as the chaos game for 7 200 steps, each point after the first 28
     * a small leaf-shaped splat, arched and given depth, darker green at the stem.
     */
    fun fernPlume(): SplatSet {
        val set = SplatSet(7200)
        var x = 0.0
        var y = 0.0
        for (i in 0 until 7200) {
            val r = previewHash(i * 1.37 + 0.19)
            val nextX = x
            val nextY = y
            when {
                r < 0.01 -> { x = 0.0; y = 0.16 * nextY }
                r < 0.86 -> { x = 0.85 * nextX + 0.04 * nextY; y = -0.04 * nextX + 0.85 * nextY + 1.6 }
                r < 0.93 -> { x = 0.2 * nextX - 0.26 * nextY; y = 0.23 * nextX + 0.22 * nextY + 1.6 }
                else -> { x = -0.15 * nextX + 0.28 * nextY; y = 0.26 * nextX + 0.24 * nextY + 0.44 }
            }
            if (i < 28) continue
            val growth = min(1.0, y / 10.4)
            val arch = 0.16 * sin(y * 0.52)
            val direction = V3(0.2 * sin(y * 0.82), 0.55 + 0.45 * growth, 1.0 - 0.1 * abs(x)).normalized()
            val q = quaternionFromUnitVectors(0.0, 0.0, -1.0, direction.x, direction.y, direction.z)
            val width = 0.011 + 0.015 * (1 - growth)
            val c = hsl(0.22 + 0.11 * previewHash(i * 0.71), 0.78, 0.24 + 0.44 * growth)
            set.push(x * 0.17 + arch, y * 0.12 - 1.12, 0.2 * sin(x * 3.2 + y * 0.7),
                width, width * 1.7, 0.0034, q[0], q[1], q[2], q[3], 0.98, c[0], c[1], c[2])
        }
        return set
    }

    /**
     * A logarithmic spiral tube rising 2.8 turns, fourteen plates around each of 240 rings,
     * warm ochre fading lighter toward the open end. 3 360 splats.
     */
    fun nautilusShell(): SplatSet {
        val rings = 240
        val sides = 14
        val set = SplatSet(rings * sides)
        var previous = V3(0.0, 0.0, 0.0)
        for (i in 0 until rings) {
            val u = i / (rings - 1.0)
            val angle = u * PI * 5.6
            val radius = 0.12 * exp(u * 1.7)
            val centreline = V3(cos(angle) * radius, -0.96 + u * 1.84, sin(angle) * radius)
            if (i == 0) previous = centreline + V3(0.0, -0.01, 0.0)
            val tangent = (centreline - previous).normalized()
            previous = centreline
            val radial = V3(cos(angle), 0.18, sin(angle)).normalized()
            var side = tangent.cross(radial)
            if (side.lengthSq() < 1e-6) side = V3(0.0, 0.0, 1.0)
            side = side.normalized()

            val tube = 0.22 * (1 - u).pow(0.72) + 0.02
            for (j in 0 until sides) {
                val v = (j.toDouble() / sides) * PI * 2
                val centre = centreline + radial * (cos(v) * tube) + side * (sin(v) * tube * 0.72)
                val direction = (radial * cos(v) + side * (sin(v) * 0.72)).normalized()
                val q = quaternionFromUnitVectors(0.0, 0.0, -1.0, direction.x, direction.y, direction.z)
                val bead = 0.012 + 0.02 * (1 - u)
                val c = hsl(0.08 + 0.05 * u + 0.03 * sin(v), 0.75, 0.42 + 0.2 * (1 - u))
                set.push(centre.x, centre.y, centre.z, bead, bead * 1.28, 0.0032 + 0.002 * (1 - u),
                    q[0], q[1], q[2], q[3], 0.96, c[0], c[1], c[2])
            }
        }
        return set
    }

    /**
     * A branching coral grown recursively four levels deep from one trunk, each branch a wavy row
     * of discs facing along it, thinning toward the tips, red-orange shading to coral.
     */
    fun coralBranches(): SplatSet {
        val set = SplatSet(4096)
        val worldUp = V3(0.0, 1.0, 0.0)
        fun branch(start: V3, dir: V3, length: Double, depth: Int, seed: Double) {
            val steps = 8 + depth * 4
            var side = dir.cross(worldUp)
            if (side.lengthSq() < 1e-6) side = V3(1.0, 0.0, 0.0)
            side = side.normalized()
            val lift = side.cross(dir).normalized()
            val q = quaternionFromUnitVectors(0.0, 0.0, -1.0, dir.x, dir.y, dir.z)
            for (i in 0..steps) {
                val u = i.toDouble() / steps
                val centre = start + dir * (length * u) +
                    side * (0.05 * length * sin(u * PI * (2 + depth) + seed * 3.1)) +
                    lift * (0.035 * length * cos(u * PI * 2 + seed * 5.7))
                val thickness = 0.05 * 0.7.pow(4 - depth) * (0.9 - 0.55 * u)
                val c = hsl(0.03 + 0.04 * depth + 0.02 * u, 0.8, 0.38 + 0.15 * u)
                set.push(centre.x, centre.y, centre.z, thickness * 1.25, thickness * 1.25, maxOf(thickness * 0.22, 0.003),
                    q[0], q[1], q[2], q[3], 0.96, c[0], c[1], c[2])
            }
            if (depth <= 0) return
            val endpoint = start + dir * length
            val children = if (depth >= 3) 2 else 3
            for (child in 0 until children) {
                val noise = previewHash(seed * 11.3 + child * 7.1 + depth * 3.7)
                val childDir = dir.rotatedAbout(side, 0.35 + noise * 0.55)
                    .rotatedAbout(lift, -0.65 + child * 0.65 + noise * 0.4)
                    .normalized()
                branch(endpoint, childDir, length * (0.56 + 0.1 * noise), depth - 1, seed + child * 1.73 + 0.41)
            }
        }
        branch(V3(0.0, -0.96, 0.0), V3(0.0, 1.0, 0.0), 1.0, 4, 0.2)
        return set
    }

    /**
     * 2 800 petals on the golden angle, rising and opening like a flower head, warm yellow-green
     * at the base to gold at the rim.
     */
    fun phyllotaxisBloom(count: Int = 2800): SplatSet {
        val set = SplatSet(count)
        val golden = PI * (3 - sqrt(5.0))
        for (i in 0 until count) {
            val u = i / (count - 1.0)
            val angle = i * golden
            val bloom = (1 - u).pow(0.22)
            val radius = 0.08 + 0.92 * sqrt(u) * bloom
            val direction = V3(cos(angle), 0.3 + 0.95 * (1 - u), sin(angle)).normalized()
            val q = quaternionFromUnitVectors(0.0, 0.0, -1.0, direction.x, direction.y, direction.z)
            val petal = 0.012 + 0.024 * (1 - u).pow(0.85)
            val c = hsl(0.08 + 0.18 * (1 - u) + 0.05 * sin(angle * 0.2), 0.74, 0.46 + 0.16 * (1 - u))
            set.push(cos(angle) * radius, -0.95 + u * 1.84 + 0.18 * sin(angle * 4.0) * (1 - u).pow(1.5), sin(angle) * radius,
                petal, petal * 1.65, 0.0035 + 0.007 * (1 - u), q[0], q[1], q[2], q[3], 0.97, c[0], c[1], c[2])
        }
        return set
    }

    /**
     * Ten strands of beads braided around a (2, 3) torus knot, cyan through violet. 3 400 splats.
     */
    fun torusBraid(): SplatSet {
        val strands = 10
        val steps = 340
        val set = SplatSet(strands * steps)
        var previous = V3(0.0, 0.0, 0.0)
        for (i in 0 until steps) {
            val u = i / (steps - 1.0)
            val a = u * PI * 2
            val curve = V3((0.88 + 0.26 * cos(3 * a)) * cos(2 * a), 0.42 * sin(3 * a), (0.88 + 0.26 * cos(3 * a)) * sin(2 * a))
            if (i == 0) previous = curve + V3(0.0, -0.01, 0.0)
            val tangent = (curve - previous).normalized()
            previous = curve
            val radial = V3(cos(2 * a), 0.0, sin(2 * a)).normalized()
            var side = tangent.cross(radial)
            if (side.lengthSq() < 1e-6) side = V3(0.0, 1.0, 0.0)
            side = side.normalized()
            for (strand in 0 until strands) {
                val v = (strand.toDouble() / strands) * PI * 2 + a * 3.0
                val centre = curve + radial * (0.12 * cos(v)) + side * (0.12 * sin(v))
                val direction = (radial * cos(v) + side * sin(v)).normalized()
                val q = quaternionFromUnitVectors(0.0, 0.0, -1.0, direction.x, direction.y, direction.z)
                val bead = 0.012 + 0.003 * sin(v * 2.0)
                val c = hsl((0.52 + 0.12 * sin(v) + u * 0.18 + 1) % 1, 0.7, 0.48 + 0.12 * cos(v))
                set.push(centre.x, centre.y, centre.z, bead, bead * 1.22, 0.003,
                    q[0], q[1], q[2], q[3], 0.94, c[0], c[1], c[2])
            }
        }
        return set
    }

    /** The demo's `previewHash`: the classic `fract(sin(x) * 43758.5453)` hash, 0..1. */
    fun previewHash(seed: Double): Double {
        val x = sin(seed * 127.1 + 311.7) * 43758.5453123
        return x - floor(x)
    }

    /**
     * The rotation taking unit vector `from` to unit vector `to`, as `(x, y, z, w)` — three.js's
     * `Quaternion.setFromUnitVectors`, including its choice of axis when the two are opposite.
     */
    fun quaternionFromUnitVectors(fx: Double, fy: Double, fz: Double, tx: Double, ty: Double, tz: Double): DoubleArray {
        var r = fx * tx + fy * ty + fz * tz + 1
        val q: DoubleArray
        if (r < Math.ulp(1.0)) {
            r = 0.0
            q = if (abs(fx) > abs(fz)) doubleArrayOf(-fy, fx, 0.0, r) else doubleArrayOf(0.0, -fz, fy, r)
        } else {
            q = doubleArrayOf(fy * tz - fz * ty, fz * tx - fx * tz, fx * ty - fy * tx, r)
        }
        val length = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        return if (length == 0.0) doubleArrayOf(0.0, 0.0, 0.0, 1.0) else DoubleArray(4) { q[it] / length }
    }

    /**
     * three.js's `Color.setHSL`: [h] wraps, [s] and [l] clamp to 0..1, and the result is
     * converted from sRGB to linear, as three.js's colour management does by default.
     */
    fun hsl(h: Double, s: Double, l: Double): DoubleArray {
        val hue = ((h % 1) + 1) % 1
        val sat = s.coerceIn(0.0, 1.0)
        val light = l.coerceIn(0.0, 1.0)
        val rgb = if (sat == 0.0) doubleArrayOf(light, light, light) else {
            val p = if (light <= 0.5) light * (1 + sat) else light + sat - light * sat
            val q = 2 * light - p
            doubleArrayOf(hueToRgb(q, p, hue + 1.0 / 3), hueToRgb(q, p, hue), hueToRgb(q, p, hue - 1.0 / 3))
        }
        return DoubleArray(3) { srgbToLinear(rgb[it]) }
    }

    private fun hueToRgb(p: Double, q: Double, t0: Double): Double {
        var t = t0
        if (t < 0) t += 1
        if (t > 1) t -= 1
        return when {
            t < 1.0 / 6 -> p + (q - p) * 6 * t
            t < 1.0 / 2 -> q
            t < 2.0 / 3 -> p + (q - p) * 6 * (2.0 / 3 - t)
            else -> p
        }
    }

    private fun srgbToLinear(c: Double): Double =
        if (c < 0.04045) c * 0.0773993808 else (c * 0.9478672986 + 0.0521327014).pow(2.4)

    /** Just enough of three.js's `Vector3` to port the builders without changing their shape. */
    private data class V3(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
        operator fun times(s: Double) = V3(x * s, y * s, z * s)
        fun lengthSq() = x * x + y * y + z * z
        fun normalized(): V3 {
            val l = sqrt(lengthSq())
            return if (l == 0.0) this else V3(x / l, y / l, z / l)
        }
        fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

        /** `applyAxisAngle`: rotate about the unit vector [axis] by [angle] radians. */
        fun rotatedAbout(axis: V3, angle: Double): V3 {
            val h = angle / 2
            val s = sin(h)
            val qx = axis.x * s; val qy = axis.y * s; val qz = axis.z * s; val qw = cos(h)
            // three.js Vector3.applyQuaternion
            val tx = 2 * (qy * z - qz * y)
            val ty = 2 * (qz * x - qx * z)
            val tz = 2 * (qx * y - qy * x)
            return V3(
                x + qw * tx + qy * tz - qz * ty,
                y + qw * ty + qz * tx - qx * tz,
                z + qw * tz + qx * ty - qy * tx
            )
        }
    }
}
