package afield.graphics.render

import afield.lang.rectsl.clipConvexToBox
import afield.lang.rectsl.polygonArea
import afield.media.image.Img
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Exact per-pixel coverage of a triangle: how much of a pixel a triangle covers, computed in closed
 * form rather than by sampling the pixel at its centre.
 *
 * Two quantities are computed, both exact up to floating-point arithmetic:
 *
 * - **Box coverage** ([boxCoverage]): the area of the triangle inside the pixel square. The
 *   triangle is clipped to the pixel (Sutherland–Hodgman, shared with
 *   [afield.lang.rectsl.clipConvexToBox]) and the clipped polygon's area is summed by Green's
 *   theorem, one term per clipped side.
 * - **Window coverage** ([windowCoverage]): the integral over the pixel of a smooth window that is
 *   1 at the triangle's incentre and falls to 0 on its edges, `w(p) = (d(p) / r)^sigma`, where
 *   `d(p)` is the distance from `p` to the nearest edge and `r` is the inradius. `sigma = 0` is the
 *   hard triangle again; larger `sigma` softens it towards its centre.
 *
 * The window integral follows the method of *ATS: Anti-Aliased Triangle Splatting with Exact
 * Per-Primitive Integrals* (arXiv 2611.22512); this is an independent implementation from the
 * method's description, not a port of its code. The angle bisectors split a triangle into three
 * sub-triangles, each the set of points nearest one edge, so inside sub-triangle `i` the window is a
 * power of one linear function, the distance to edge `i`. In that edge's frame, `u` across and `v`
 * along, Green's theorem turns the area integral of `(u / r)^sigma` into a line integral of its
 * antiderivative `u^(sigma+1) / ((sigma+1) r^sigma)` with respect to `v`, and along a straight side
 * that line integral has a closed form ([sideTerm]). Clip each sub-triangle to the pixel, add one
 * term per side, and the pixel integral is exact for any `sigma`.
 *
 * The whole-triangle integral is closed form too ([windowTotal] = `2A / ((sigma+1)(sigma+2))`), and
 * no pixel can receive more than that. A triangle whose total is below half an 8-bit code value can
 * therefore be culled before it touches a pixel, without changing any pixel by more than that
 * ([TriangleSplat.cullThreshold]). That is conservative: it never culls a triangle that could show.
 *
 * [GLSL] is the same computation for a shader, served as `#include "coverage/triangle.glsl"`.
 */
object TriangleCoverage {

    /** The include path a shader uses for [GLSL]: `#include "coverage/triangle.glsl"`. */
    const val INCLUDE_PATH = "coverage/triangle.glsl"

    /** Twice the signed area of `a, b, c`; positive when counterclockwise in a y-up frame. */
    fun twiceSignedArea(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double =
        (bx - ax) * (cy - ay) - (cx - ax) * (by - ay)

    /**
     * The area of triangle `a, b, c` that lies inside the box `[x0, x1] x [y0, y1]`.
     *
     * For a unit pixel this is the fraction of the pixel covered. Either winding is accepted.
     */
    fun boxCoverage(
        ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double,
        x0: Double, y0: Double, x1: Double, y1: Double,
    ): Double {
        // Quick reject and quick accept on the bounding box, before any clipping.
        if (max(ax, max(bx, cx)) <= x0 || min(ax, min(bx, cx)) >= x1) return 0.0
        if (max(ay, max(by, cy)) <= y0 || min(ay, min(by, cy)) >= y1) return 0.0
        val clipped = clipConvexToBox(doubleArrayOf(ax, ay, bx, by, cx, cy), x0, y0, x1, y1)
        // The shoelace sum is Green's theorem with one term per clipped side.
        return polygonArea(clipped)
    }

    /** The inradius of triangle `a, b, c`: twice its area over its perimeter. */
    fun inradius(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double {
        val perimeter = hypot(bx - ax, by - ay) + hypot(cx - bx, cy - by) + hypot(ax - cx, ay - cy)
        if (perimeter <= 0.0) return 0.0
        return abs(twiceSignedArea(ax, ay, bx, by, cx, cy)) / perimeter
    }

    /**
     * The window `(d / r)^sigma` at the point `(x, y)`: 1 at the incentre, 0 on the edges and
     * outside. This is what a point-sampled renderer evaluates at the pixel centre.
     */
    fun window(
        ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double,
        sigma: Double, x: Double, y: Double,
    ): Double {
        val s = twiceSignedArea(ax, ay, bx, by, cx, cy)
        if (s == 0.0) return 0.0
        val sign = if (s > 0) 1.0 else -1.0
        // Signed distances to the three edges, positive inside.
        val d0 = sign * twiceSignedArea(ax, ay, bx, by, x, y) / hypot(bx - ax, by - ay)
        val d1 = sign * twiceSignedArea(bx, by, cx, cy, x, y) / hypot(cx - bx, cy - by)
        val d2 = sign * twiceSignedArea(cx, cy, ax, ay, x, y) / hypot(ax - cx, ay - cy)
        val d = min(d0, min(d1, d2))
        if (d <= 0.0) return 0.0
        val r = inradius(ax, ay, bx, by, cx, cy)
        return (min(1.0, d / r)).pow(sigma)
    }

    /** The integral of the window over the whole plane: `2A / ((sigma+1)(sigma+2))`. */
    fun windowTotal(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, sigma: Double): Double {
        require(sigma >= 0.0) { "sigma must be >= 0, got $sigma" }
        return abs(twiceSignedArea(ax, ay, bx, by, cx, cy)) / ((sigma + 1.0) * (sigma + 2.0))
    }

    /**
     * The integral of the window over the box `[x0, x1] x [y0, y1]`.
     *
     * For a unit pixel and `sigma = 0` this equals [boxCoverage]; `TriangleCoverageTest` checks that
     * identity and checks every `sigma` against heavy supersampling.
     */
    fun windowCoverage(
        ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double,
        sigma: Double, x0: Double, y0: Double, x1: Double, y1: Double,
    ): Double {
        require(sigma >= 0.0) { "sigma must be >= 0, got $sigma" }
        if (max(ax, max(bx, cx)) <= x0 || min(ax, min(bx, cx)) >= x1) return 0.0
        if (max(ay, max(by, cy)) <= y0 || min(ay, min(by, cy)) >= y1) return 0.0
        val la = hypot(cx - bx, cy - by)
        val lb = hypot(ax - cx, ay - cy)
        val lc = hypot(bx - ax, by - ay)
        val perimeter = la + lb + lc
        if (perimeter <= 0.0) return 0.0
        val r = abs(twiceSignedArea(ax, ay, bx, by, cx, cy)) / perimeter
        if (r <= 0.0) return 0.0
        // The incentre is where the bisectors meet: the vertices weighted by opposite side lengths.
        val ix = (la * ax + lb * bx + lc * cx) / perimeter
        val iy = (la * ay + lb * by + lc * cy) / perimeter
        return subTriangle(ax, ay, bx, by, ix, iy, r, sigma, x0, y0, x1, y1) +
            subTriangle(bx, by, cx, cy, ix, iy, r, sigma, x0, y0, x1, y1) +
            subTriangle(cx, cy, ax, ay, ix, iy, r, sigma, x0, y0, x1, y1)
    }

    /** The window integral over the part of the box inside sub-triangle `(p, q, incentre)`. */
    private fun subTriangle(
        px: Double, py: Double, qx: Double, qy: Double, ix: Double, iy: Double,
        r: Double, sigma: Double, x0: Double, y0: Double, x1: Double, y1: Double,
    ): Double {
        val ring = clipConvexToBox(doubleArrayOf(px, py, qx, qy, ix, iy), x0, y0, x1, y1)
        val n = ring.size / 2
        if (n < 3) return 0.0
        // The edge frame: v along p -> q, u across it towards the incentre.
        val len = hypot(qx - px, qy - py)
        val tx = (qx - px) / len
        val ty = (qy - py) / len
        var nx = -ty
        var ny = tx
        if ((ix - px) * nx + (iy - py) * ny < 0) {
            nx = -nx
            ny = -ny
        }
        var sum = 0.0
        for (k in 0 until n) {
            val ax = ring[2 * k] - px
            val ay = ring[2 * k + 1] - py
            val bx = ring[2 * ((k + 1) % n)] - px
            val by = ring[2 * ((k + 1) % n) + 1] - py
            sum += sideTerm(
                max(0.0, ax * nx + ay * ny), ax * tx + ay * ty,
                max(0.0, bx * nx + by * ny), bx * tx + by * ty,
                r, sigma,
            )
        }
        // The ring's winding is whatever the clip preserved; the integrand is non-negative.
        return abs(sum)
    }

    /**
     * Green's-theorem term for one straight side from `(u0, v0)` to `(u1, v1)` in an edge frame:
     * the line integral of `G(u) dv` with `G(u) = u^(sigma+1) / ((sigma+1) r^sigma)`, in closed form.
     */
    fun sideTerm(u0: Double, v0: Double, u1: Double, v1: Double, r: Double, sigma: Double): Double {
        val dv = v1 - v0
        if (dv == 0.0) return 0.0
        val scale = 1.0 / ((sigma + 1.0) * r.pow(sigma))
        val du = u1 - u0
        // Along a side parallel to the edge u is constant, and the general form divides by zero;
        // below this separation the midpoint value is exact to rounding.
        if (abs(du) <= 1e-9 * max(r, abs(u0) + abs(u1))) {
            val um = 0.5 * (u0 + u1)
            return dv * scale * um.pow(sigma + 1.0)
        }
        val e = sigma + 2.0
        return dv * scale * (u1.pow(e) - u0.pow(e)) / (e * du)
    }

    /**
     * The same window-coverage function for a shader, served as [INCLUDE_PATH].
     *
     * `tcWindowCoverage(a, b, c, sigma, boxMin, boxMax)` and `tcBoxCoverage(a, b, c, boxMin, boxMax)`
     * return the integral over the box; `tcWindowTotal(a, b, c, sigma)` is the whole-triangle
     * integral, for culling; `tcWindow(a, b, c, sigma, p)` is the window at one point, the
     * point-sampled answer. Single precision: on pixel-sized boxes the GPU result agrees with the
     * Kotlin one to about `1e-5` of a pixel.
     */
    val GLSL: String = """
        // Exact per-pixel triangle coverage (box filter and the ATS bisector window), after
        // arXiv 2611.22512. Kotlin reference: afield.graphics.render.TriangleCoverage.
        #ifndef FIELD_TRIANGLE_COVERAGE
        #define FIELD_TRIANGLE_COVERAGE

        float tcCross(vec2 a, vec2 b, vec2 c) { return (b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y); }

        // Sutherland-Hodgman against one half-plane dot(n, p) >= k, in place; at most 8 vertices.
        void tcClipPlane(inout vec2 poly[8], inout int count, vec2 n, float k) {
            vec2 outp[8];
            int m = 0;
            for (int i = 0; i < 8; i++) {
                if (i >= count) break;
                vec2 a = poly[i];
                vec2 b = poly[(i + 1) % count];
                float da = dot(n, a) - k;
                float db = dot(n, b) - k;
                if (da >= 0.0 && m < 8) { outp[m] = a; m++; }
                if ((da >= 0.0) != (db >= 0.0) && m < 8) { outp[m] = mix(a, b, da / (da - db)); m++; }
            }
            for (int i = 0; i < 8; i++) poly[i] = outp[i];
            count = m;
        }

        int tcClipToBox(inout vec2 poly[8], int count, vec2 lo, vec2 hi) {
            tcClipPlane(poly, count, vec2(1.0, 0.0), lo.x);
            tcClipPlane(poly, count, vec2(-1.0, 0.0), -hi.x);
            tcClipPlane(poly, count, vec2(0.0, 1.0), lo.y);
            tcClipPlane(poly, count, vec2(0.0, -1.0), -hi.y);
            return count;
        }

        float tcBoxCoverage(vec2 a, vec2 b, vec2 c, vec2 lo, vec2 hi) {
            if (max(a.x, max(b.x, c.x)) <= lo.x || min(a.x, min(b.x, c.x)) >= hi.x) return 0.0;
            if (max(a.y, max(b.y, c.y)) <= lo.y || min(a.y, min(b.y, c.y)) >= hi.y) return 0.0;
            vec2 poly[8];
            poly[0] = a; poly[1] = b; poly[2] = c;
            int n = tcClipToBox(poly, 3, lo, hi);
            float s = 0.0;
            for (int i = 0; i < 8; i++) {
                if (i >= n) break;
                vec2 p = poly[i];
                vec2 q = poly[(i + 1) % n];
                s += p.x * q.y - q.x * p.y;
            }
            return abs(s) * 0.5;
        }

        float tcWindowTotal(vec2 a, vec2 b, vec2 c, float sigma) {
            return abs(tcCross(a, b, c)) / ((sigma + 1.0) * (sigma + 2.0));
        }

        // The window at one point: what a single sample per pixel sees.
        float tcWindow(vec2 a, vec2 b, vec2 c, float sigma, vec2 p) {
            float s = tcCross(a, b, c);
            if (s == 0.0) return 0.0;
            float g = sign(s);
            float d = min(g * tcCross(a, b, p) / length(b - a),
                min(g * tcCross(b, c, p) / length(c - b), g * tcCross(c, a, p) / length(a - c)));
            if (d <= 0.0) return 0.0;
            float r = abs(s) / (length(c - b) + length(a - c) + length(b - a));
            return sigma == 0.0 ? 1.0 : pow(min(1.0, d / r), sigma);
        }

        float tcSideTerm(float u0, float v0, float u1, float v1, float r, float sigma) {
            float dv = v1 - v0;
            float scale = 1.0 / ((sigma + 1.0) * pow(r, sigma));
            float du = u1 - u0;
            if (abs(du) <= 1e-5 * max(r, u0 + u1)) return dv * scale * pow(0.5 * (u0 + u1), sigma + 1.0);
            float e = sigma + 2.0;
            return dv * scale * (pow(u1, e) - pow(u0, e)) / (e * du);
        }

        float tcSubTriangle(vec2 p, vec2 q, vec2 inc, float r, float sigma, vec2 lo, vec2 hi) {
            vec2 poly[8];
            poly[0] = p; poly[1] = q; poly[2] = inc;
            int n = tcClipToBox(poly, 3, lo, hi);
            if (n < 3) return 0.0;
            vec2 t = normalize(q - p);
            vec2 nn = vec2(-t.y, t.x);
            if (dot(inc - p, nn) < 0.0) nn = -nn;
            float s = 0.0;
            for (int i = 0; i < 8; i++) {
                if (i >= n) break;
                vec2 x = poly[i] - p;
                vec2 y = poly[(i + 1) % n] - p;
                s += tcSideTerm(max(0.0, dot(x, nn)), dot(x, t), max(0.0, dot(y, nn)), dot(y, t), r, sigma);
            }
            return abs(s);
        }

        float tcWindowCoverage(vec2 a, vec2 b, vec2 c, float sigma, vec2 lo, vec2 hi) {
            if (max(a.x, max(b.x, c.x)) <= lo.x || min(a.x, min(b.x, c.x)) >= hi.x) return 0.0;
            if (max(a.y, max(b.y, c.y)) <= lo.y || min(a.y, min(b.y, c.y)) >= hi.y) return 0.0;
            float la = length(c - b);
            float lb = length(a - c);
            float lc = length(b - a);
            float per = la + lb + lc;
            float r = abs(tcCross(a, b, c)) / per;
            if (!(r > 0.0)) return 0.0;
            vec2 inc = (la * a + lb * b + lc * c) / per;
            return tcSubTriangle(a, b, inc, r, sigma, lo, hi)
                + tcSubTriangle(b, c, inc, r, sigma, lo, hi)
                + tcSubTriangle(c, a, inc, r, sigma, lo, hi);
        }

        #endif
    """.trimIndent()
}

/**
 * One coloured triangle for [TriangleSplat], in the unit square (`0..1` on both axes, y down).
 *
 * @param ax first vertex x
 * @param ay first vertex y
 * @param bx second vertex x
 * @param by second vertex y
 * @param cx third vertex x
 * @param cy third vertex y
 * @param red red, `0..1`
 * @param green green, `0..1`
 * @param blue blue, `0..1`
 * @param opacity peak opacity, reached at the incentre when `sigma > 0`
 */
data class CoverageTriangle(
    val ax: Double, val ay: Double,
    val bx: Double, val by: Double,
    val cx: Double, val cy: Double,
    val red: Double = 1.0, val green: Double = 1.0, val blue: Double = 1.0,
    val opacity: Double = 1.0,
)

/** How [TriangleSplat.render] turns a triangle into a pixel's weight. */
enum class CoverageSampling {
    /** The window at the pixel centre: what a rasterizer with one sample per pixel does. */
    POINT,

    /** The window integrated exactly over the pixel square. */
    EXACT,
}

/**
 * The image and the counts from one [TriangleSplat.render].
 *
 * @param image the RGB result
 * @param drawn triangles that were not culled
 * @param culled triangles culled because their whole-triangle integral was below the threshold
 * @param visible triangles that changed at least one pixel
 */
data class TriangleSplatResult(val image: Img, val drawn: Int, val culled: Int, val visible: Int)

/**
 * A small CPU splatter for windowed triangles, composited back to front with Over, so point-sampled
 * and exact coverage can be put side by side at any resolution.
 *
 * Triangles live in the unit square; [render] maps it onto `width x height` pixels, so lowering the
 * resolution is the same as zooming out. A triangle smaller than a pixel lands between pixel centres
 * and vanishes under [CoverageSampling.POINT]; under [CoverageSampling.EXACT] it still contributes
 * its area.
 */
object TriangleSplat {

    /**
     * The default cull threshold, in pixel-areas of opacity: half an 8-bit code value. A triangle
     * whose whole-triangle integral is below it cannot move any pixel by more than this.
     */
    const val cullThreshold: Double = 0.5 / 255.0

    /** Render [triangles] (first is furthest) over a black background. */
    fun render(
        triangles: List<CoverageTriangle>,
        width: Int,
        height: Int,
        sampling: CoverageSampling = CoverageSampling.EXACT,
        sigma: Double = 0.0,
        cull: Double = cullThreshold,
    ): TriangleSplatResult {
        require(width >= 1 && height >= 1) { "raster must be at least 1x1, got ${width}x$height" }
        val img = Img.zeros(width, height, 3)
        var drawn = 0
        var culled = 0
        var visible = 0
        for (t in triangles) {
            val ax = t.ax * width; val ay = t.ay * height
            val bx = t.bx * width; val by = t.by * height
            val cx = t.cx * width; val cy = t.cy * height
            if (t.opacity * TriangleCoverage.windowTotal(ax, ay, bx, by, cx, cy, sigma) < cull) {
                culled++
                continue
            }
            drawn++
            val i0 = max(0, floor(min(ax, min(bx, cx))).toInt())
            val i1 = min(width - 1, ceil(max(ax, max(bx, cx))).toInt())
            val j0 = max(0, floor(min(ay, min(by, cy))).toInt())
            val j1 = min(height - 1, ceil(max(ay, max(by, cy))).toInt())
            var touched = false
            for (j in j0..j1) for (i in i0..i1) {
                val w = when (sampling) {
                    CoverageSampling.POINT -> TriangleCoverage.window(ax, ay, bx, by, cx, cy, sigma, i + 0.5, j + 0.5)
                    CoverageSampling.EXACT -> TriangleCoverage.windowCoverage(
                        ax, ay, bx, by, cx, cy, sigma, i.toDouble(), j.toDouble(), i + 1.0, j + 1.0,
                    )
                }
                val alpha = (t.opacity * w).toFloat()
                if (alpha <= 0f) continue
                touched = true
                val k = (j * width + i) * 3
                img.data[k] = img.data[k] * (1 - alpha) + t.red.toFloat() * alpha
                img.data[k + 1] = img.data[k + 1] * (1 - alpha) + t.green.toFloat() * alpha
                img.data[k + 2] = img.data[k + 2] * (1 - alpha) + t.blue.toFloat() * alpha
            }
            if (touched) visible++
        }
        return TriangleSplatResult(img, drawn, culled, visible)
    }
}
