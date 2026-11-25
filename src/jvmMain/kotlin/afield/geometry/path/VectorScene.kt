package afield.geometry.path

import afield.geometry.mesh.TriMesh
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small 3D vector scenes drawn as flat `FLine`s, in the manner of Zdog and Zpup.
 *
 * A scene is a tree of round-capped primitives (`dot`, `line`, `ellipse`, `rect`, `box`,
 * `cylinder`, `cone`, `hemisphere`), whole triangle meshes (`mesh`) and 3D strokes (`polyline`)
 * under `group { }` transforms. [VectorScene.render] turns the
 * camera, projects every face orthographically (or with a simple perspective), shades it flat or
 * "soft", and returns filled `FLine`s in painter's order, ready for `lines(...)`, the drawing layer
 * or a plotter path.
 *
 * The draw order is the interesting part. [DepthOrder.CENTROID] sorts faces by mean depth, as Zdog
 * does. [DepthOrder.PAIRWISE] follows Zpup (https://github.com/rxolve/zpup, MIT): for every two
 * faces whose screen footprints overlap, it clips one footprint against the other, compares their
 * depths at the centre of the overlap, and records "draw this one first". A topological sort of
 * those constraints gives the order; when the constraints form a cycle, the farthest remaining face
 * (by centroid) is drawn next, which breaks the cycle without splitting faces. Candidate pairs come
 * from a uniform screen grid, so a mesh of tens of thousands of triangles orders in about a second
 * rather than comparing every pair.
 * [VectorScene.wrongOrderShare] measures how many covered pixels each order gets wrong against a
 * per-pixel depth reference.
 *
 * ```kotlin
 * val scene = vectorScene {
 *     cylinder(radius = 30.0, length = 160.0, color = vec(0.93, 0.92, 0.88, 1.0))
 *     group({ translate(0.0, 170.0, 0.0) }) {
 *         cone(radius = 38.0, length = 40.0, color = vec(0.8, 0.2, 0.2, 1.0))
 *     }
 * }
 * lines(scene.render(yaw = 0.6, pitch = -0.3), w = 400.0, h = 300.0, fitToSize = true)
 * ```
 */
class VectorScene internal constructor(internal val facets: List<Facet>) {

    /** One drawable, convex, planar face (or a round-capped stroke, when [radius] > 0). */
    class Facet(
        /** world-space corners; one point for a dot, two for a stroke, three or more for a face */
        val points: List<DoubleArray>,
        /** base colour before shading */
        val color: Vec4,
        /** stroke radius for dots and lines; 0 for faces */
        val radius: Double,
        /** true when this face belongs to a closed solid and may be culled when facing away */
        val solid: Boolean,
        /** depth lift toward the viewer in scene units, so a stroke lying on a surface is drawn over it */
        val bias: Double = 0.0,
    )

    /** One projected face: screen footprint (convex, counter-clockwise) plus depth data. */
    class Projected internal constructor(
        val facet: Facet,
        /** convex footprint in screen space as x0,y0,x1,y1,... */
        val footprint: DoubleArray,
        internal val core: List<DoubleArray>,
        val centroidDepth: Double,
        internal val normalZ: Double,
        internal val shade: Double,
    ) {
        internal val minX = footprint.filterIndexed { i, _ -> i % 2 == 0 }.min()
        internal val maxX = footprint.filterIndexed { i, _ -> i % 2 == 0 }.max()
        internal val minY = footprint.filterIndexed { i, _ -> i % 2 == 1 }.min()
        internal val maxY = footprint.filterIndexed { i, _ -> i % 2 == 1 }.max()

        /** depth (larger is farther) of this face at screen point (x, y) */
        fun depthAt(x: Double, y: Double): Double = coreDepth(core, x, y)
    }

    /** Projects every visible face for the given camera; order is the scene's declaration order. */
    fun project(
        yaw: Double, pitch: Double, scale: Double = 1.0, perspective: Double = 0.0,
        light: DoubleArray = doubleArrayOf(-0.4, 0.7, 0.6),
    ): List<Projected> {
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val ll = norm(light)
        fun rot(p: DoubleArray): DoubleArray {
            val x1 = cy * p[0] + sy * p[2]; val z1 = -sy * p[0] + cy * p[2]
            // negative pitch looks down on the scene from above
            return doubleArrayOf(x1, cp * p[1] + sp * z1, -sp * p[1] + cp * z1)
        }
        fun cam(p: DoubleArray): DoubleArray {
            val (x1, y2, z2) = rot(p)
            val f = if (perspective > 0) perspective / (perspective - z2) else 1.0
            // screen y grows downward; depth grows away from the viewer
            return doubleArrayOf(x1 * scale * f, -y2 * scale * f, -z2 * scale)
        }
        val out = ArrayList<Projected>()
        for (f in facets) {
            val c = f.points.map(::cam).let { cs -> if (f.bias == 0.0) cs else cs.map { doubleArrayOf(it[0], it[1], it[2] - f.bias * scale) } }
            val n = if (c.size >= 3) faceNormal(f.points) else null
            // normal in camera terms: z of the rotated normal (toward the viewer when positive)
            val nz = if (n == null) 1.0 else rot(n)[2]
            if (f.solid && nz <= 1e-9) continue
            val footprint = when (c.size) {
                1 -> capsule(c[0], c[0], f.radius * scale)
                2 -> capsule(c[0], c[1], f.radius * scale)
                else -> ccw(c)
            }
            if (c.size >= 3 && abs(area(footprint)) < 1e-6 * scale * scale) continue
            val shade = if (n == null) 1.0 else {
                val d = n[0] * ll[0] + n[1] * ll[1] + n[2] * ll[2]
                // a face seen from behind is lit from its visible side
                if (nz < 0) -d else d
            }
            out += Projected(f, footprint, c, c.sumOf { it[2] } / c.size, nz, shade)
        }
        return out
    }

    /**
     * Returns faces in draw order (farthest first). [DepthOrder.PAIRWISE] builds the occlusion graph;
     * [DepthOrder.CENTROID] sorts by mean depth.
     */
    fun order(faces: List<Projected>, mode: DepthOrder): List<Projected> = when (mode) {
        DepthOrder.CENTROID -> faces.sortedByDescending { it.centroidDepth }
        DepthOrder.PAIRWISE -> pairwiseOrder(faces).first
    }

    /** Projects, orders and shades the scene into filled `FLine`s, back to front. */
    fun render(
        yaw: Double = 0.6, pitch: Double = -0.35, scale: Double = 1.0,
        order: DepthOrder = DepthOrder.PAIRWISE, shading: Shading = Shading.SOFT,
        perspective: Double = 0.0, outline: Vec4? = null, outlineWidth: Double = 1.5,
    ): List<FLine> = order(project(yaw, pitch, scale, perspective), order).map { p ->
        val base = p.facet.color
        val k = when (shading) {
            Shading.NONE -> 1.0
            Shading.FLAT -> if (p.core.size < 3) 1.0 else 0.35 + 0.65 * max(0.0, p.shade)
            Shading.SOFT -> if (p.core.size < 3) 1.0 else 0.55 + 0.45 * (p.shade * 0.5 + 0.5)
        }
        val col = vec(base.x * k, base.y * k, base.z * k, base.w.toDouble())
        val fp = p.footprint
        val shape = FLine().apply {
            moveTo(fp[0], fp[1])
            var i = 2
            while (i < fp.size) { lineTo(fp[i], fp[i + 1]); i += 2 }
            close()
            color = col
            filled = true
            stroked = true
            // a hairline of the fill colour hides seams between neighbouring faces
            thickness = 0.75
        }
        if (outline == null) listOf(shape)
        else listOf(shape, shape.copy().apply { filled = false; color = Vec4(outline); thickness = outlineWidth })
    }.flatten()

    /**
     * Fraction of covered pixels on a [w]×[h] grid where the painter's [mode] order shows a
     * different face than a per-pixel nearest-depth reference. Returns (wrong, covered).
     */
    fun wrongOrderShare(
        yaw: Double, pitch: Double, mode: DepthOrder, w: Int = 240, h: Int = 240, scale: Double = 1.0,
    ): Pair<Int, Int> {
        val faces = project(yaw, pitch, scale)
        if (faces.isEmpty()) return 0 to 0
        val ordered = order(faces, mode)
        val rank = HashMap<Projected, Int>().apply { ordered.forEachIndexed { i, p -> put(p, i) } }
        val x0 = faces.minOf { it.minX }; val x1 = faces.maxOf { it.maxX }
        val y0 = faces.minOf { it.minY }; val y1 = faces.maxOf { it.maxY }
        // bucket faces by the pixel tiles their bounds cover, so large meshes stay tractable
        val tile = 8
        val tw = (w + tile - 1) / tile; val th = (h + tile - 1) / tile
        val buckets = Array(tw * th) { ArrayList<Projected>() }
        val sx = w / max(1e-12, x1 - x0); val sy = h / max(1e-12, y1 - y0)
        for (p in faces) {
            val i0 = (((p.minX - x0) * sx).toInt() / tile).coerceIn(0, tw - 1); val i1 = (((p.maxX - x0) * sx).toInt() / tile).coerceIn(0, tw - 1)
            val j0 = (((p.minY - y0) * sy).toInt() / tile).coerceIn(0, th - 1); val j1 = (((p.maxY - y0) * sy).toInt() / tile).coerceIn(0, th - 1)
            for (jj in j0..j1) for (ii in i0..i1) buckets[jj * tw + ii] += p
        }
        var wrong = 0; var covered = 0
        for (j in 0 until h) for (i in 0 until w) {
            val x = x0 + (i + 0.5) / w * (x1 - x0); val y = y0 + (j + 0.5) / h * (y1 - y0)
            var near: Projected? = null; var nearD = Double.MAX_VALUE
            var top: Projected? = null; var topR = -1
            for (p in buckets[(j / tile) * tw + i / tile]) {
                if (x < p.minX || x > p.maxX || y < p.minY || y > p.maxY) continue
                if (!inside(p.footprint, x, y)) continue
                val d = p.depthAt(x, y)
                if (d < nearD) { nearD = d; near = p }
                val r = rank[p]!!
                if (r > topR) { topR = r; top = p }
            }
            if (near == null) continue
            covered++
            // a pixel is wrong only when the visible face differs and is genuinely at another depth
            if (top !== near && abs(top!!.depthAt(x, y) - nearD) > 1e-6 * max(1.0, scale)) wrong++
        }
        return wrong to covered
    }

    companion object {
        /**
         * Pairwise occlusion order. Returns the order and the number of cycle breaks taken.
         */
        fun pairwiseOrder(faces: List<Projected>): Pair<List<Projected>, Int> {
            val n = faces.size
            val after = Array(n) { ArrayList<Int>() }
            val indeg = IntArray(n)
            if (n == 0) return emptyList<Projected>() to 0
            // a uniform grid over the screen bounds, about one face per cell; each overlapping pair
            // is examined once, in the cell holding the low corner of the two bounds' intersection
            val gx0 = faces.minOf { it.minX }; val gy0 = faces.minOf { it.minY }
            val gw = max(1e-9, faces.maxOf { it.maxX } - gx0); val gh = max(1e-9, faces.maxOf { it.maxY } - gy0)
            val g = max(1, min(512, ceil(sqrt(n.toDouble())).toInt()))
            fun cx(x: Double) = ((x - gx0) / gw * g).toInt().coerceIn(0, g - 1)
            fun cy(y: Double) = ((y - gy0) / gh * g).toInt().coerceIn(0, g - 1)
            val cells = Array(g * g) { IntArrayList() }
            for (i in 0 until n) {
                val p = faces[i]
                for (yy in cy(p.minY)..cy(p.maxY)) for (xx in cx(p.minX)..cx(p.maxX)) cells[yy * g + xx].add(i)
            }
            for (cell in cells.indices) {
                val members = cells[cell]
                for (ia in 0 until members.size) for (ib in ia + 1 until members.size) {
                val a = members[ia]; val b = members[ib]
                val p = faces[a]; val q = faces[b]
                if (p.maxX <= q.minX || q.maxX <= p.minX || p.maxY <= q.minY || q.maxY <= p.minY) continue
                if (cy(max(p.minY, q.minY)) * g + cx(max(p.minX, q.minX)) != cell) continue
                val ov = clipConvex(p.footprint, q.footprint) ?: continue
                val ar = abs(area(ov))
                if (ar < 1e-4 * min(abs(area(p.footprint)), abs(area(q.footprint))) + 1e-9) continue
                val (cx, cy) = centroid(ov)
                val dp = p.depthAt(cx, cy); val dq = q.depthAt(cx, cy)
                if (abs(dp - dq) < 1e-9) continue
                // the farther face is drawn first
                if (dp > dq) { after[a] += b; indeg[b]++ } else { after[b] += a; indeg[a]++ }
                }
            }
            val done = BooleanArray(n)
            val out = ArrayList<Projected>(n)
            var breaks = 0
            // candidates kept farthest-first so independent faces fall back to centroid order
            val byDepth = (0 until n).sortedByDescending { faces[it].centroidDepth }
            val rank = IntArray(n).also { r -> byDepth.forEachIndexed { k, i -> r[i] = k } }
            val ready = java.util.PriorityQueue<Int>(compareBy { rank[it] })
            for (i in 0 until n) if (indeg[i] == 0) ready += i
            var next = 0
            while (out.size < n) {
                var pick = -1
                while (ready.isNotEmpty()) { val c = ready.poll(); if (!done[c]) { pick = c; break } }
                if (pick < 0) {
                    while (done[byDepth[next]]) next++
                    pick = byDepth[next]; breaks++
                }
                done[pick] = true
                out += faces[pick]
                for (b in after[pick]) if (--indeg[b] == 0 && !done[b]) ready += b
            }
            return out to breaks
        }
    }
}

/** How [VectorScene.render] orders faces. */
enum class DepthOrder {
    /** mean depth per face, farthest first (Zdog's approach) */
    CENTROID,
    /** pairwise comparison of overlapping footprints, then a topological sort (Zpup's approach) */
    PAIRWISE,
}

/** How [VectorScene.render] lights faces. */
enum class Shading {
    /** base colours only */
    NONE,
    /** Lambert: unlit faces go dark */
    FLAT,
    /** half-Lambert: unlit faces stay readable, for a softer toy-like look */
    SOFT,
}

/** Builds a [VectorScene]; see [vectorScene]. Lengths are scene units, Y is up. */
class VectorSceneBuilder internal constructor(private val m: DoubleArray, private val sink: MutableList<VectorScene.Facet>) {
    private var segments = 24

    /** Polygon resolution for round primitives. */
    fun segments(n: Int) { segments = max(3, n) }

    /** Children drawn under the transform built by [transform] (applied in call order, local first). */
    fun group(transform: Transform.() -> Unit, body: VectorSceneBuilder.() -> Unit) {
        val t = Transform().apply(transform)
        VectorSceneBuilder(mul(m, t.m), sink).also { it.segments = segments }.body()
    }

    /** A round dot of [radius] at ([x], [y], [z]). */
    fun dot(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0, radius: Double = 4.0, color: Vec4) {
        sink += VectorScene.Facet(listOf(tx(x, y, z)), color, radius, false)
    }

    /** A round-capped stroke of [width] from a to b. */
    fun line(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double, width: Double = 4.0, color: Vec4) {
        sink += VectorScene.Facet(listOf(tx(ax, ay, az), tx(bx, by, bz)), color, width / 2, false)
    }

    /** A filled ellipse (a disc when rx == ry) in the local XY plane, facing +Z. */
    fun ellipse(rx: Double, ry: Double = rx, color: Vec4) {
        face((0 until segments).map { i -> val a = 2 * PI * i / segments; doubleArrayOf(rx * cos(a), ry * sin(a), 0.0) }, color, false)
    }

    /** A filled [w]×[h] rectangle in the local XY plane, centred on the origin. */
    fun rect(w: Double, h: Double, color: Vec4) {
        face(listOf(doubleArrayOf(-w / 2, -h / 2, 0.0), doubleArrayOf(w / 2, -h / 2, 0.0), doubleArrayOf(w / 2, h / 2, 0.0), doubleArrayOf(-w / 2, h / 2, 0.0)), color, false)
    }

    /** A closed box centred on the origin; [top] and [sides] override the colour of those faces. */
    fun box(w: Double, h: Double, d: Double, color: Vec4, top: Vec4 = color, sides: Vec4 = color) {
        val x = w / 2; val y = h / 2; val z = d / 2
        fun p(a: Double, b: Double, c: Double) = doubleArrayOf(a, b, c)
        face(listOf(p(-x, -y, z), p(x, -y, z), p(x, y, z), p(-x, y, z)), color, true)
        face(listOf(p(x, -y, -z), p(-x, -y, -z), p(-x, y, -z), p(x, y, -z)), color, true)
        face(listOf(p(-x, y, z), p(x, y, z), p(x, y, -z), p(-x, y, -z)), top, true)
        face(listOf(p(-x, -y, -z), p(x, -y, -z), p(x, -y, z), p(-x, -y, z)), color, true)
        face(listOf(p(x, -y, z), p(x, -y, -z), p(x, y, -z), p(x, y, z)), sides, true)
        face(listOf(p(-x, -y, -z), p(-x, -y, z), p(-x, y, z), p(-x, y, -z)), sides, true)
    }

    /** A closed cylinder along +Y from y = 0 to y = [length]; [top] colours the upper cap. */
    fun cylinder(radius: Double, length: Double, color: Vec4, top: Vec4 = color, topRadius: Double = radius) {
        val ring = { r: Double, y: Double -> (0 until segments).map { i -> val a = 2 * PI * i / segments; doubleArrayOf(r * cos(a), y, r * sin(a)) } }
        val lo = ring(radius, 0.0); val hi = ring(topRadius, length)
        for (i in 0 until segments) {
            val j = (i + 1) % segments
            face(listOf(lo[i], hi[i], hi[j], lo[j]), color, true)
        }
        face(hi.reversed(), top, true)
        face(lo, color, true)
    }

    /** A closed cone along +Y: base at y = 0, apex at y = [length]. */
    fun cone(radius: Double, length: Double, color: Vec4) {
        val lo = (0 until segments).map { i -> val a = 2 * PI * i / segments; doubleArrayOf(radius * cos(a), 0.0, radius * sin(a)) }
        val apex = doubleArrayOf(0.0, length, 0.0)
        for (i in 0 until segments) face(listOf(lo[i], apex, lo[(i + 1) % segments]), color, true)
        face(lo, color, true)
    }

    /** A closed dome over the local XZ plane, rising to y = [radius]. */
    fun hemisphere(radius: Double, color: Vec4, base: Vec4 = color) {
        val rings = max(2, segments / 4)
        val grid = (0..rings).map { k ->
            val phi = PI / 2 * k / rings
            (0 until segments).map { i -> val a = 2 * PI * i / segments; doubleArrayOf(radius * cos(phi) * cos(a), radius * sin(phi), radius * cos(phi) * sin(a)) }
        }
        for (k in 0 until rings) for (i in 0 until segments) {
            val j = (i + 1) % segments
            if (k == rings - 1) face(listOf(grid[k][i], grid[k + 1][0], grid[k][j]), color, true)
            else face(listOf(grid[k][i], grid[k + 1][i], grid[k + 1][j], grid[k][j]), color, true)
        }
        face(grid[0], base, true)
    }

    /**
     * Every triangle of [mesh] as a flat face. Colour comes from the mesh's vertex colours (the mean
     * of the three corners, so `colouredBy(...)` bands survive) unless [color] is given; a mesh with
     * neither is drawn light grey. [solid] culls triangles facing away, which is right for a closed
     * mesh wound counter-clockwise seen from outside; pass `false` for an open sheet seen from both
     * sides.
     */
    fun mesh(mesh: TriMesh, color: Vec4? = null, solid: Boolean = true) {
        val cols = mesh.colors
        val p = mesh.positions
        for (t in 0 until mesh.triangleCount) {
            val i = mesh.indices[3 * t]; val j = mesh.indices[3 * t + 1]; val k = mesh.indices[3 * t + 2]
            val c = color ?: if (cols == null) vec(0.85, 0.85, 0.85, 1.0) else vec(
                (cols[3 * i] + cols[3 * j] + cols[3 * k]) / 3.0,
                (cols[3 * i + 1] + cols[3 * j + 1] + cols[3 * k + 1]) / 3.0,
                (cols[3 * i + 2] + cols[3 * j + 2] + cols[3 * k + 2]) / 3.0, 1.0,
            )
            sink += VectorScene.Facet(
                listOf(tx(p[3 * i].toDouble(), p[3 * i + 1].toDouble(), p[3 * i + 2].toDouble()),
                    tx(p[3 * j].toDouble(), p[3 * j + 1].toDouble(), p[3 * j + 2].toDouble()),
                    tx(p[3 * k].toDouble(), p[3 * k + 1].toDouble(), p[3 * k + 2].toDouble())),
                c, 0.0, solid,
            )
        }
    }

    /**
     * A round-capped 3D stroke through [points], one segment per facet so faces in front of part of
     * it hide just that part. [lift] moves it toward the viewer for the depth test only, so a curve
     * lying on a surface (an isoline, a toolpath) is drawn over that surface rather than fighting it;
     * keep it small next to the distance between the surface and anything that should hide the curve.
     */
    fun polyline(points: List<Vec3>, width: Double = 2.0, color: Vec4, lift: Double = width) {
        for (s in 0 until points.size - 1) {
            val a = points[s]; val b = points[s + 1]
            sink += VectorScene.Facet(
                listOf(tx(a.x.toDouble(), a.y.toDouble(), a.z.toDouble()), tx(b.x.toDouble(), b.y.toDouble(), b.z.toDouble())),
                color, width / 2, false, lift,
            )
        }
    }

    private fun face(local: List<DoubleArray>, color: Vec4, solid: Boolean) {
        sink += VectorScene.Facet(local.map { tx(it[0], it[1], it[2]) }, color, 0.0, solid)
    }

    private fun tx(x: Double, y: Double, z: Double) = doubleArrayOf(
        m[0] * x + m[1] * y + m[2] * z + m[3],
        m[4] * x + m[5] * y + m[6] * z + m[7],
        m[8] * x + m[9] * y + m[10] * z + m[11],
    )

    /** An affine transform built from calls applied to children in order (the first call is outermost). */
    class Transform internal constructor() {
        internal var m = identity()
        fun translate(x: Double, y: Double, z: Double) { m = mul(m, doubleArrayOf(1.0, 0.0, 0.0, x, 0.0, 1.0, 0.0, y, 0.0, 0.0, 1.0, z, 0.0, 0.0, 0.0, 1.0)) }
        fun rotateX(a: Double) { val c = cos(a); val s = sin(a); m = mul(m, doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, c, -s, 0.0, 0.0, s, c, 0.0, 0.0, 0.0, 0.0, 1.0)) }
        fun rotateY(a: Double) { val c = cos(a); val s = sin(a); m = mul(m, doubleArrayOf(c, 0.0, s, 0.0, 0.0, 1.0, 0.0, 0.0, -s, 0.0, c, 0.0, 0.0, 0.0, 0.0, 1.0)) }
        fun rotateZ(a: Double) { val c = cos(a); val s = sin(a); m = mul(m, doubleArrayOf(c, -s, 0.0, 0.0, s, c, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)) }
        fun scale(k: Double) { m = mul(m, doubleArrayOf(k, 0.0, 0.0, 0.0, 0.0, k, 0.0, 0.0, 0.0, 0.0, k, 0.0, 0.0, 0.0, 0.0, 1.0)) }
    }
}

/** Builds a [VectorScene] from a tree of primitives and `group { }` transforms. */
fun vectorScene(body: VectorSceneBuilder.() -> Unit): VectorScene {
    val sink = ArrayList<VectorScene.Facet>()
    VectorSceneBuilder(identity(), sink).body()
    return VectorScene(sink)
}

// ---- geometry helpers ----

/** A growable int list without boxing, for the pairwise grid. */
private class IntArrayList {
    var data = IntArray(4)
    var size = 0
    fun add(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
    operator fun get(i: Int) = data[i]
}

private fun identity() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

private fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(16) { k ->
    val r = k / 4; val c = k % 4
    (0 until 4).sumOf { a[r * 4 + it] * b[it * 4 + c] }
}

private fun norm(v: DoubleArray): DoubleArray { val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return doubleArrayOf(v[0] / l, v[1] / l, v[2] / l) }

private fun faceNormal(p: List<DoubleArray>): DoubleArray {
    // Newell's method; robust for any planar polygon
    var x = 0.0; var y = 0.0; var z = 0.0
    for (i in p.indices) {
        val a = p[i]; val b = p[(i + 1) % p.size]
        x += (a[1] - b[1]) * (a[2] + b[2]); y += (a[2] - b[2]) * (a[0] + b[0]); z += (a[0] - b[0]) * (a[1] + b[1])
    }
    val l = sqrt(x * x + y * y + z * z)
    return if (l < 1e-12) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(x / l, y / l, z / l)
}

internal fun area(p: DoubleArray): Double {
    var s = 0.0; val n = p.size / 2
    for (i in 0 until n) { val j = (i + 1) % n; s += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1] }
    return s / 2
}

private fun ccw(c: List<DoubleArray>): DoubleArray {
    val a = DoubleArray(c.size * 2) { if (it % 2 == 0) c[it / 2][0] else c[it / 2][1] }
    if (area(a) >= 0) return a
    return DoubleArray(a.size) { k -> val i = c.size - 1 - k / 2; if (k % 2 == 0) a[2 * i] else a[2 * i + 1] }
}

private fun capsule(a: DoubleArray, b: DoubleArray, r: Double, n: Int = 8): DoubleArray {
    val dx = b[0] - a[0]; val dy = b[1] - a[1]
    val base = if (dx * dx + dy * dy < 1e-18) 0.0 else kotlin.math.atan2(dy, dx)
    val out = ArrayList<Double>()
    // half circle around b, then half circle around a: convex and counter-clockwise
    for (i in 0..n) { val t = base - PI / 2 + PI * i / n; out += b[0] + r * cos(t); out += b[1] + r * sin(t) }
    for (i in 0..n) { val t = base + PI / 2 + PI * i / n; out += a[0] + r * cos(t); out += a[1] + r * sin(t) }
    return out.toDoubleArray()
}

internal fun inside(p: DoubleArray, x: Double, y: Double): Boolean {
    val n = p.size / 2
    for (i in 0 until n) {
        val j = (i + 1) % n
        val cr = (p[2 * j] - p[2 * i]) * (y - p[2 * i + 1]) - (p[2 * j + 1] - p[2 * i + 1]) * (x - p[2 * i])
        if (cr < 0) return false
    }
    return true
}

/** Sutherland–Hodgman clip of [subject] by convex counter-clockwise [clip]; null when empty. */
internal fun clipConvex(subject: DoubleArray, clip: DoubleArray): DoubleArray? {
    var cur = subject.toList()
    val m = clip.size / 2
    for (e in 0 until m) {
        if (cur.size < 6) return null
        val ax = clip[2 * e]; val ay = clip[2 * e + 1]
        val bx = clip[2 * ((e + 1) % m)]; val by = clip[2 * ((e + 1) % m) + 1]
        if ((bx - ax) * (bx - ax) + (by - ay) * (by - ay) < 1e-20) continue
        fun side(x: Double, y: Double) = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
        val next = ArrayList<Double>()
        val k = cur.size / 2
        for (i in 0 until k) {
            val px = cur[2 * i]; val py = cur[2 * i + 1]
            val qx = cur[2 * ((i + 1) % k)]; val qy = cur[2 * ((i + 1) % k) + 1]
            val sp = side(px, py); val sq = side(qx, qy)
            if (sp >= 0) { next += px; next += py }
            if ((sp >= 0) != (sq >= 0)) {
                val t = sp / (sp - sq)
                next += px + t * (qx - px); next += py + t * (qy - py)
            }
        }
        cur = next
    }
    return if (cur.size < 6) null else cur.toDoubleArray()
}

private fun centroid(p: DoubleArray): Pair<Double, Double> {
    var cx = 0.0; var cy = 0.0; var a = 0.0; val n = p.size / 2
    for (i in 0 until n) {
        val j = (i + 1) % n
        val cr = p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1]
        a += cr; cx += (p[2 * i] + p[2 * j]) * cr; cy += (p[2 * i + 1] + p[2 * j + 1]) * cr
    }
    if (abs(a) < 1e-12) return p.filterIndexed { i, _ -> i % 2 == 0 }.average() to p.filterIndexed { i, _ -> i % 2 == 1 }.average()
    return cx / (3 * a) to cy / (3 * a)
}

private fun coreDepth(c: List<DoubleArray>, x: Double, y: Double): Double {
    if (c.size == 1) return c[0][2]
    if (c.size == 2) {
        val a = c[0]; val b = c[1]
        val dx = b[0] - a[0]; val dy = b[1] - a[1]; val l = dx * dx + dy * dy
        val t = if (l < 1e-18) 0.0 else ((x - a[0]) * dx + (y - a[1]) * dy) / l
        val tt = t.coerceIn(0.0, 1.0)
        return a[2] + tt * (b[2] - a[2])
    }
    // plane through the face, from the three corners spanning the largest screen triangle
    val a = c[0]
    var best = 0.0; var bi = 1; var bj = 2
    for (i in 1 until c.size) for (j in i + 1 until c.size) {
        val cr = abs((c[i][0] - a[0]) * (c[j][1] - a[1]) - (c[j][0] - a[0]) * (c[i][1] - a[1]))
        if (cr > best) { best = cr; bi = i; bj = j }
    }
    val b = c[bi]; val d = c[bj]
    val det = (b[0] - a[0]) * (d[1] - a[1]) - (d[0] - a[0]) * (b[1] - a[1])
    if (abs(det) < 1e-12) return c.sumOf { it[2] } / c.size
    val u = ((x - a[0]) * (d[1] - a[1]) - (d[0] - a[0]) * (y - a[1])) / det
    val v = ((b[0] - a[0]) * (y - a[1]) - (x - a[0]) * (b[1] - a[1])) / det
    return a[2] + u * (b[2] - a[2]) + v * (d[2] - a[2])
}
