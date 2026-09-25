@file:afield.CompletionCategory("Graphics")

package afield.solidsl

import afield.stdlib.AxisAlignedBoundingBox3D
import afield.stdlib.ScalarField3D
import afield.stdlib.TriMesh
import afield.stdlib.Vec3
import afield.stdlib.marchingCubes
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lowering **(a)**: a [SolidExpr] as a signed distance field, so everything `mesh_sdf.kt`,
 * [marchingCubes] and `Solid.levelSetOf` already do keeps working unchanged.
 *
 * ```kotlin
 * val mesh = (cuboid(2, 2, 1) - cylinder(3, 0.3)).march(resolution = 96)
 * ```
 *
 * # This lowering always succeeds
 *
 * Unlike [exactSolid], this lowering always succeeds. Every node in the algebra has a field form —
 * that is where the algebra came from. What it does *not* always have is an **exact** distance
 * function, and the difference matters to a ray marcher (which steps by the field's value and
 * overshoots if the value is an overestimate) even though it does not matter to a marching cubes
 * (which only reads signs). [fieldNotes] enumerates the places where the field is sign-correct but
 * not metric-correct, so the gap is a fact rather than a surprise.
 *
 * # It is thread-safe, deliberately unlike the `sdf…` family
 *
 * `mesh_sdf.kt` is explicit that a [ScalarField3D] "must **read** the vector it is handed and not
 * write to it", and its own combinators capture a single scratch [Vec3] to transform into. That makes
 * them fast and single-threaded, which is why `Solid.levelSetOf` defaults to `parallel = false`.
 *
 * This lowering does not do that. Internally it builds a tree of [Field3] — a `fun interface` over
 * three primitive `Double`s, so there is no boxing and nothing to alias — and only wraps a [Vec3] at
 * the very top, in [toScalarField]. So the field is re-entrant, and [toLevelSetField] hands Manifold
 * a lambda it can call from ten threads at once. Measured on this machine that is worth about 2x on
 * every level-set fallback in the package.
 */

/**
 * A scalar field over three primitive doubles.
 *
 * A `fun interface` rather than `(Double, Double, Double) -> Double` because the latter is
 * `Function3<Double, Double, Double, Double>` on the JVM and **boxes all four**. At the tens of
 * millions of evaluations a convergence sweep does, that is the difference between seconds and
 * minutes.
 */
fun interface Field3 {
    fun at(x: Double, y: Double, z: Double): Double
}

/** A scalar field over two primitive doubles — [Field3]'s 2D counterpart, for [Profile]. */
fun interface Field2 {
    fun at(x: Double, y: Double): Double
}

/**
 * This shape as a [ScalarField3D], negative inside — the convention `sdfSphere` and friends use, so
 * this composes straight into `sdfUnion`, [marchingCubes] and `Solid.levelSetOf`.
 */
fun SolidExpr.toScalarField(): ScalarField3D {
    val f = toField3()
    return { p -> f.at(p.x.toDouble(), p.y.toDouble(), p.z.toDouble()).toFloat() }
}

/**
 * This shape's field in the sign convention Manifold's `levelSet` wants — **positive inside** — and
 * over primitive doubles, so no [Vec3] is ever constructed.
 *
 * `Solid.levelSetOf` exists to flip the sign for a [ScalarField3D], and it defaults to
 * `parallel = false` because a `ScalarField3D` may not be re-entrant. This one is, so
 * `Solid.levelSet(bounds, edge, parallel = true, field = expr.toLevelSetField()::at)` is both correct
 * and about twice as fast.
 */
fun SolidExpr.toLevelSetField(): Field3 {
    val f = toField3()
    return Field3 { x, y, z -> -f.at(x, y, z) }
}

/** [marchingCubes] on [toScalarField], with the shape's own [bounds] as the window. */
fun SolidExpr.march(
    resolution: Int = 64,
    bounds: AxisAlignedBoundingBox3D? = null,
    margin: Double = 0.0,
): TriMesh {
    val window = bounds ?: this.bounds()?.expandedBy(margin)
        ?: error(
            "this shape is unbounded (it contains a Gyroid, or a Union with one) so there is no " +
                "window to march — intersect it with something bounded, or pass `bounds` explicitly",
        )
    return marchingCubes(toScalarField(), window, resolution)
}

/**
 * Every place in this expression where the field lowering is **sign-correct but not a true distance
 * function**, in the order they appear.
 *
 * Marching cubes only reads signs, so none of these affect [march] beyond the accuracy of its linear
 * crossing interpolation. A ray marcher steps by the field's value, so a note here is a place where a
 * [toGlsl] preview needs a step multiplier below 1. Empty is the good case and is common.
 */
fun SolidExpr.fieldNotes(): List<String> {
    val out = mutableListOf<String>()
    fun visit(e: SolidExpr) {
        when (e) {
            is SolidExpr.Sphere, is SolidExpr.Cuboid, is SolidExpr.Cylinder, SolidExpr.Empty -> {}
            is SolidExpr.Gyroid ->
                out += "Gyroid is a trigonometric field, not a distance function; its gradient is not unit length"
            is SolidExpr.Extrude -> {
                visitProfile(e.profile, out)
                if (e.twistDegrees != 0.0) out += "Extrude(twistDegrees=${e.twistDegrees}) shears space, so the field over-estimates distance near the axis"
                if (e.scaleTop != 1.0) out += "Extrude(scaleTop=${e.scaleTop}) tapers space, so the field is only sign-correct"
            }
            is SolidExpr.Revolve -> {
                visitProfile(e.profile, out)
                if (e.degrees != 360.0) out += "Revolve(degrees=${e.degrees}) is cut by two half-spaces, exact only away from their intersection"
            }
            is SolidExpr.Translate -> visit(e.child)
            is SolidExpr.Rotate -> visit(e.child)
            is SolidExpr.Mirror -> visit(e.child)
            is SolidExpr.Scale -> {
                if (e.x != e.y || e.y != e.z) out += "Scale(${e.x}, ${e.y}, ${e.z}) is non-uniform; distances are scaled by the smallest axis, so the field under-estimates"
                visit(e.child)
            }
            is SolidExpr.Round -> visit(e.child)
            is SolidExpr.Shell -> visit(e.child)
            is SolidExpr.Union -> e.children.forEach(::visit)
            is SolidExpr.Intersect -> {
                out += "Intersect is a pointwise max, which over-estimates the distance outside a concave corner"
                e.children.forEach(::visit)
            }
            is SolidExpr.Difference -> {
                if (e.children.size > 1) out += "Difference is a pointwise max, which over-estimates the distance outside the cut"
                e.children.forEach(::visit)
            }
            is SolidExpr.SmoothUnion -> { visit(e.a); visit(e.b) }
            is SolidExpr.SmoothDifference -> {
                out += "SmoothDifference is built on a smooth max, which over-estimates outside the cut"
                visit(e.a); visit(e.b)
            }
        }
    }
    visit(this)
    return out
}

private fun visitProfile(p: Profile, out: MutableList<String>) {
    when (p) {
        is Profile.Circle, is Profile.Rect, Profile.Empty -> {}
        is Profile.Polygon -> {}
        is Profile.Offset -> {
            // `sdf - delta` is the exact offset only where `sdf` is exact, and a boolean of profiles
            // is not exact on the side the max was taken on.
            val inner = mutableListOf<String>()
            visitProfile(p.child, inner)
            if (inner.isNotEmpty()) {
                out += "Offset(${p.delta}) of a profile whose own field is inexact inherits that inexactness"
            }
            out += inner
        }
        is Profile.Translate -> visitProfile(p.child, out)
        is Profile.Rotate -> visitProfile(p.child, out)
        is Profile.Scale -> {
            if (p.x != p.y) out += "Profile.Scale(${p.x}, ${p.y}) is non-uniform, so the 2D field under-estimates"
            visitProfile(p.child, out)
        }
        is Profile.Union -> p.children.forEach { visitProfile(it, out) }
        is Profile.Intersect -> {
            out += "Profile.Intersect is a pointwise max, exact inside and an over-estimate outside"
            p.children.forEach { visitProfile(it, out) }
        }
        is Profile.Difference -> {
            if (p.children.size > 1) out += "Profile.Difference is a pointwise max, exact inside and an over-estimate outside"
            p.children.forEach { visitProfile(it, out) }
        }
    }
}

// ==============================================================================================
// The lowering itself
// ==============================================================================================

/** How far outside everything [SolidExpr.Empty] reads. Finite, so a `min` with it never yields NaN. */
private const val FAR = 1e18

/** This shape as a [Field3], negative inside. The compiled form both other entry points share. */
fun SolidExpr.toField3(): Field3 = when (this) {

    is SolidExpr.Sphere -> {
        val r = radius
        Field3 { x, y, z -> sqrt(x * x + y * y + z * z) - r }
    }

    is SolidExpr.Cuboid -> {
        val hx = x / 2; val hy = y / 2; val hz = z / 2
        Field3 { px, py, pz -> sdBox3(px, py, pz, hx, hy, hz) }
    }

    // One code path for cylinder, cone and frustum. iq's capped cone reduces *exactly* to the
    // cylinder when the two radii agree — the `cb` term collapses to (r - |xy|, 0) — so there is no
    // second implementation to keep in step with this one.
    is SolidExpr.Cylinder -> {
        val h = abs(height) / 2; val r1 = radiusLow; val r2 = radiusHigh
        Field3 { px, py, pz -> sdCappedConeZ(px, py, pz, h, r1, r2) }
    }

    is SolidExpr.Extrude -> {
        val p2 = profile.toField2()
        val h = height
        val twist = Math.toRadians(twistDegrees)
        val taper = scaleTop
        Field3 { px, py, pz ->
            val u = (pz / h).coerceIn(0.0, 1.0)
            val s = 1.0 + (taper - 1.0) * u
            // Undo the sweep: the profile at height u has been turned by `twist * u` and scaled by
            // `s`, so the point is turned back and divided before the profile is asked about it.
            val a = -twist * u
            val ca = cos(a); val sa = sin(a)
            val qx = (ca * px - sa * py) / s
            val qy = (sa * px + ca * py) / s
            // Times `s` to keep the value a distance in the *unscaled* frame rather than the
            // profile's own; without it a tapered extrusion's field is wrong by the taper.
            val d2 = p2.at(qx, qy) * s
            val dz = max(-pz, pz - h)
            min(max(d2, dz), 0.0) + hypot(max(d2, 0.0), max(dz, 0.0))
        }
    }

    // Revolution is about **Z**, with the profile's x becoming the radius in the XY plane and the
    // profile's y becoming z. That is measured, not read: `Section.revolved`'s own KDoc says "about
    // the Y axis", and `SolidSLConventionProbe` shows a 0.6-square offset to x = 1.2 revolving to
    // bounds ((-1.5, -1.5, -0.3), (1.5, 1.5, 0.3)) — a ring in the XY plane, of half-thickness 0.3
    // in z. Point-membership against `Solid.contains` then agrees on 100% of samples, which it does
    // not for any other axis assignment.
    is SolidExpr.Revolve -> {
        val p2 = profile.toField2()
        val sweep = degrees
        if (sweep >= 360.0) {
            Field3 { px, py, pz -> p2.at(hypot(px, py), pz) }
        } else {
            // A partial revolve is the full one cut by a wedge. Manifold sweeps counter-clockwise
            // from +X, so the kept region is `0 <= atan2(y, x) <= sweep`: the intersection of two
            // half-planes while the wedge is convex, and their *union* once it is reflex.
            val a = Math.toRadians(sweep)
            val sa = sin(a); val ca = cos(a)
            val reflex = sweep > 180.0
            Field3 { px, py, pz ->
                val body = p2.at(hypot(px, py), pz)
                // Inward normals (0, 1) and (sin a, -cos a); a half-space's field is -(n . p).
                val d0 = -py
                val d1 = -(sa * px - ca * py)
                val wedge = if (reflex) min(d0, d1) else max(d0, d1)
                max(body, wedge)
            }
        }
    }

    is SolidExpr.Gyroid -> {
        val s = scale; val t = thickness
        Field3 { x, y, z ->
            val gx = x * s; val gy = y * s; val gz = z * s
            abs(sin(gx) * cos(gy) + sin(gy) * cos(gz) + sin(gz) * cos(gx)) - t
        }
    }

    SolidExpr.Empty -> Field3 { _, _, _ -> FAR }

    is SolidExpr.Translate -> {
        val f = child.toField3(); val tx = x; val ty = y; val tz = z
        Field3 { px, py, pz -> f.at(px - tx, py - ty, pz - tz) }
    }

    is SolidExpr.Rotate -> {
        val f = child.toField3()
        // Evaluating the child at R⁻¹p rotates the *shape* by R. For a rotation the inverse is the
        // transpose, and `eulerMatrix` is the single place the X-then-Y-then-Z order is written down.
        val m = transpose3(eulerMatrix(x, y, z))
        Field3 { px, py, pz ->
            f.at(
                m[0] * px + m[1] * py + m[2] * pz,
                m[3] * px + m[4] * py + m[5] * pz,
                m[6] * px + m[7] * py + m[8] * pz,
            )
        }
    }

    is SolidExpr.Scale -> {
        val f = child.toField3()
        require(abs(x) > 1e-12 && abs(y) > 1e-12 && abs(z) > 1e-12) { "a scale factor must not be zero, got ($x, $y, $z)" }
        val sx = x; val sy = y; val sz = z
        // The smallest axis, so the result is a conservative *under*-estimate of distance and a ray
        // marcher using it cannot overshoot. Exact when the scale is uniform.
        val k = min(abs(sx), min(abs(sy), abs(sz)))
        Field3 { px, py, pz -> f.at(px / sx, py / sy, pz / sz) * k }
    }

    is SolidExpr.Mirror -> {
        val f = child.toField3()
        val (nx, ny, nz) = normalize3(x, y, z)
        // A reflection is its own inverse, so the same expression serves both directions.
        Field3 { px, py, pz ->
            val d = 2.0 * (px * nx + py * ny + pz * nz)
            f.at(px - d * nx, py - d * ny, pz - d * nz)
        }
    }

    is SolidExpr.Round -> {
        val f = child.toField3(); val r = radius
        Field3 { px, py, pz -> f.at(px, py, pz) - r }
    }

    is SolidExpr.Shell -> {
        val f = child.toField3(); val t = thickness
        Field3 { px, py, pz -> abs(f.at(px, py, pz)) - t }
    }

    is SolidExpr.Union -> {
        val fs = children.map { it.toField3() }.toTypedArray()
        if (fs.isEmpty()) Field3 { _, _, _ -> FAR }
        else Field3 { px, py, pz ->
            var v = FAR
            for (f in fs) v = min(v, f.at(px, py, pz))
            v
        }
    }

    is SolidExpr.Intersect -> {
        val fs = children.map { it.toField3() }.toTypedArray()
        if (fs.isEmpty()) Field3 { _, _, _ -> FAR }
        else Field3 { px, py, pz ->
            var v = -FAR
            for (f in fs) v = max(v, f.at(px, py, pz))
            v
        }
    }

    is SolidExpr.Difference -> {
        val fs = children.map { it.toField3() }.toTypedArray()
        if (fs.isEmpty()) Field3 { _, _, _ -> FAR }
        else Field3 { px, py, pz ->
            var v = fs[0].at(px, py, pz)
            for (i in 1 until fs.size) v = max(v, -fs[i].at(px, py, pz))
            v
        }
    }

    is SolidExpr.SmoothUnion -> {
        val fa = a.toField3(); val fb = b.toField3(); val kk = max(k, 1e-9)
        Field3 { px, py, pz -> smoothMin(fa.at(px, py, pz), fb.at(px, py, pz), kk) }
    }

    is SolidExpr.SmoothDifference -> {
        val fa = a.toField3(); val fb = b.toField3(); val kk = max(k, 1e-9)
        Field3 { px, py, pz -> smoothMax(fa.at(px, py, pz), -fb.at(px, py, pz), kk) }
    }
}

/** This region as a [Field2], negative inside. */
fun Profile.toField2(): Field2 = when (this) {

    is Profile.Circle -> {
        val r = radius
        Field2 { x, y -> hypot(x, y) - r }
    }

    is Profile.Rect -> {
        val hx = x / 2; val hy = y / 2
        Field2 { px, py -> sdBox2(px, py, hx, hy) }
    }

    is Profile.Polygon -> {
        if (points.size < 3) Field2 { _, _ -> FAR } else {
            val n = points.size
            val xs = DoubleArray(n) { points[it].x }
            val ys = DoubleArray(n) { points[it].y }
            Field2 { px, py -> sdPolygon(xs, ys, px, py) }
        }
    }

    // `Section.offset(d, JoinType.ROUND)` is the Minkowski sum with a disc of radius d, and for a
    // true distance field that is exactly `sdf - d`. This is the one place in the package where the
    // two lowerings agree for a *reason* rather than to a tolerance.
    is Profile.Offset -> {
        val f = child.toField2(); val d = delta
        Field2 { px, py -> f.at(px, py) - d }
    }

    is Profile.Translate -> {
        val f = child.toField2(); val tx = x; val ty = y
        Field2 { px, py -> f.at(px - tx, py - ty) }
    }

    is Profile.Rotate -> {
        val f = child.toField2()
        val a = Math.toRadians(-degrees)
        val c = cos(a); val s = sin(a)
        Field2 { px, py -> f.at(c * px - s * py, s * px + c * py) }
    }

    is Profile.Scale -> {
        val f = child.toField2()
        require(abs(x) > 1e-12 && abs(y) > 1e-12) { "a profile scale must not be zero, got ($x, $y)" }
        val sx = x; val sy = y
        val k = min(abs(sx), abs(sy))
        Field2 { px, py -> f.at(px / sx, py / sy) * k }
    }

    is Profile.Union -> {
        val fs = children.map { it.toField2() }.toTypedArray()
        if (fs.isEmpty()) Field2 { _, _ -> FAR }
        else Field2 { px, py ->
            var v = FAR
            for (f in fs) v = min(v, f.at(px, py))
            v
        }
    }

    is Profile.Intersect -> {
        val fs = children.map { it.toField2() }.toTypedArray()
        if (fs.isEmpty()) Field2 { _, _ -> FAR }
        else Field2 { px, py ->
            var v = -FAR
            for (f in fs) v = max(v, f.at(px, py))
            v
        }
    }

    is Profile.Difference -> {
        val fs = children.map { it.toField2() }.toTypedArray()
        if (fs.isEmpty()) Field2 { _, _ -> FAR }
        else Field2 { px, py ->
            var v = fs[0].at(px, py)
            for (i in 1 until fs.size) v = max(v, -fs[i].at(px, py))
            v
        }
    }

    Profile.Empty -> Field2 { _, _ -> FAR }
}

// ==============================================================================================
// The closed forms
// ==============================================================================================

/** Exact signed distance to an origin-centred box of half-extents ([hx], [hy], [hz]). */
internal fun sdBox3(px: Double, py: Double, pz: Double, hx: Double, hy: Double, hz: Double): Double {
    val qx = abs(px) - hx; val qy = abs(py) - hy; val qz = abs(pz) - hz
    val ox = max(qx, 0.0); val oy = max(qy, 0.0); val oz = max(qz, 0.0)
    return sqrt(ox * ox + oy * oy + oz * oz) + min(max(qx, max(qy, qz)), 0.0)
}

/** Exact signed distance to an origin-centred rectangle of half-extents ([hx], [hy]). */
internal fun sdBox2(px: Double, py: Double, hx: Double, hy: Double): Double {
    val qx = abs(px) - hx; val qy = abs(py) - hy
    return hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0)
}

/**
 * Exact signed distance to a capped cone along **Z**, half-height [h], radius [r1] at `z = -h` and
 * [r2] at `z = +h` — Inigo Quilez's `sdCappedCone`, with the axis swapped from Y to Z.
 */
internal fun sdCappedConeZ(px: Double, py: Double, pz: Double, h: Double, r1: Double, r2: Double): Double {
    val qx = hypot(px, py)
    val qy = pz
    val k1x = r2; val k1y = h
    val k2x = r2 - r1; val k2y = 2.0 * h
    val cax = qx - min(qx, if (qy < 0.0) r1 else r2)
    val cay = abs(qy) - h
    val k2dot = k2x * k2x + k2y * k2y
    val t = if (k2dot < 1e-30) 0.0 else (((k1x - qx) * k2x + (k1y - qy) * k2y) / k2dot).coerceIn(0.0, 1.0)
    val cbx = qx - k1x + k2x * t
    val cby = qy - k1y + k2y * t
    val s = if (cbx < 0.0 && cay < 0.0) -1.0 else 1.0
    return s * sqrt(min(cax * cax + cay * cay, cbx * cbx + cby * cby))
}

/**
 * Exact signed distance to a closed simple polygon — Inigo Quilez's `sdPolygon`.
 *
 * The sign comes from a crossing-parity test, which is **winding-independent**: a clockwise polygon
 * and its reversal give the same field. That is not true of the exact lowering, where Clipper decides
 * holes by winding, which is why `Profile.Polygon` is normalised to counter-clockwise on that side
 * rather than here.
 */
internal fun sdPolygon(xs: DoubleArray, ys: DoubleArray, px: Double, py: Double): Double {
    val n = xs.size
    var d = (px - xs[0]) * (px - xs[0]) + (py - ys[0]) * (py - ys[0])
    var s = 1.0
    var j = n - 1
    for (i in 0 until n) {
        val ex = xs[j] - xs[i]; val ey = ys[j] - ys[i]
        val wx = px - xs[i]; val wy = py - ys[i]
        val e2 = ex * ex + ey * ey
        val t = if (e2 < 1e-30) 0.0 else ((wx * ex + wy * ey) / e2).coerceIn(0.0, 1.0)
        val bx = wx - ex * t; val by = wy - ey * t
        d = min(d, bx * bx + by * by)
        val c1 = py >= ys[i]
        val c2 = py < ys[j]
        val c3 = ex * wy > ey * wx
        if ((c1 && c2 && c3) || (!c1 && !c2 && !c3)) s = -s
        j = i
    }
    return s * sqrt(d)
}

/** The polynomial smooth minimum — `sdfSmoothUnion`'s, in `Double`. */
internal fun smoothMin(a: Double, b: Double, k: Double): Double {
    val h = (0.5 + 0.5 * (b - a) / k).coerceIn(0.0, 1.0)
    return b + (a - b) * h - k * h * (1.0 - h)
}

/** The polynomial smooth maximum — `sdfSmoothIntersect`'s, in `Double`. */
internal fun smoothMax(a: Double, b: Double, k: Double): Double {
    val h = (0.5 - 0.5 * (b - a) / k).coerceIn(0.0, 1.0)
    return b + (a - b) * h + k * h * (1.0 - h)
}
