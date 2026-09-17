@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import afield.ToHTML
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * [TriMesh] — the CPU triangle mesh Field's 3D geometry family is built on, plus the builder that
 * makes one and the whole-mesh transforms that move one around.
 *
 * A [TriMesh] is *not* a renderer buffer (that is `afield.four`'s
 * [afield.four.modules.Mesh]) and *not* a graph of vertex objects. It is flat
 * `FloatArray` attribute storage plus an `IntArray` of triangle indices: the layout every algorithm
 * in this family wants, the layout a GPU upload wants, and cheap to copy. Positions are required;
 * normals, uvs and per-vertex colours are optional and either absent or complete.
 *
 * The reason the vertices are floats-in-an-array rather than `List<Vec3>` is that [Vec3] is a
 * *mutable* `Vector3f`. A mesh holding shared mutable vectors is a mesh where `mesh.position(0).y +=
 * 1` silently edits the mesh — or worse, edits three triangles because the vector was shared. So
 * every accessor here ([position], [normal], [uv], [color]) returns a **fresh** vector you own, and
 * the `…Into` variants let a hot loop reuse one.
 *
 * # The family
 *
 * | file | what it adds |
 * |---|---|
 * | `mesh.kt` | this: the type, [TriMeshBuilder] / [triMesh], transforms, concatenation |
 * | `mesh_topology.kt` | [MeshTopology] — adjacency, boundary loops, components, manifoldness, Euler characteristic / genus, [TriMesh.weld], [TriMesh.fixWinding] |
 * | `mesh_generate.kt` | [boxMesh], [uvSphereMesh], [icosphereMesh], [torusMesh], [cylinderMesh], [extrudeMesh], [revolveMesh], [tubeMesh], [heightfieldMesh], [reliefMesh] |
 * | `mesh_sdf.kt` | [ScalarField3D], the SDF primitives / combinators, and [marchingCubes] |
 * | `mesh_process.kt` | [TriMesh.loopSubdivide], [TriMesh.laplacianSmooth], [TriMesh.taubinSmooth], [TriMesh.withVertexNormals], [TriMesh.decimate] |
 * | `mesh_measure.kt` | [TriMesh.surfaceArea], [TriMesh.signedVolume], curvature, [MeshBvh] (rays, closest point, inside test) |
 * | `mesh_io.kt` | OBJ and STL, read and write |
 * | `mesh_field.kt` | the bridges: [TriMesh.toFLines], [VoronoiCell3.toTriMesh], [Box.showMesh] |
 *
 * # Conventions
 *
 * - **Winding is counter-clockwise seen from outside**, so [faceNormal] points out of a closed
 *   solid and [signedVolume] of one is positive. Everything in the family agrees on this, and
 *   [TriMesh.fixWinding] repairs a mesh that does not.
 * - Nothing here mutates: every operation returns a new [TriMesh]. The arrays are `val`s and are
 *   not copied on construction, so do not hand a builder's scratch array to the constructor and
 *   then keep writing to it — use [TriMeshBuilder.build].
 * - No dependencies beyond the Kotlin stdlib and Field's own generated linalg. No GPU, no AWT, no
 *   model-format libraries.
 *
 * ```kotlin
 * val ball = icosphereMesh(subdivisions = 3, radius = 1.0)
 * val shell = ball.scaled(1.0, 0.6, 1.0).withVertexNormals()
 * println("${shell.triangleCount} tris, volume ${shell.signedVolume()}")
 * display(shell.toFLines())            // draw its wireframe
 * ```
 */

// ==============================================================================================
// TriMesh — the value type
// ==============================================================================================

/**
 * An indexed triangle mesh: `vertexCount * 3` position floats, `triangleCount * 3` indices, and
 * optional matching normal / uv / colour arrays.
 *
 * @param positions xyz per vertex, interleaved. Length must be a multiple of 3.
 * @param indices three vertex indices per triangle, wound counter-clockwise seen from outside.
 * @param normals xyz per vertex, or `null`. Not required to be unit length, though everything that
 *   produces them here normalises; [withVertexNormals] recomputes them from the triangles.
 * @param uvs uv per vertex (2 floats), or `null`.
 * @param colors linear rgb per vertex (3 floats, nominally `0..1`), or `null`.
 */
class TriMesh(
    val positions: FloatArray,
    val indices: IntArray,
    val normals: FloatArray? = null,
    val uvs: FloatArray? = null,
    val colors: FloatArray? = null,
) : ToHTML {

    init {
        require(positions.size % 3 == 0) { "positions must be a whole number of xyz triples, got ${positions.size} floats" }
        require(indices.size % 3 == 0) { "indices must be a whole number of triangles, got ${indices.size}" }
        val n = positions.size / 3
        normals?.let { require(it.size == n * 3) { "normals must be $n xyz triples, got ${it.size / 3}" } }
        uvs?.let { require(it.size == n * 2) { "uvs must be $n uv pairs, got ${it.size / 2}" } }
        colors?.let { require(it.size == n * 3) { "colors must be $n rgb triples, got ${it.size / 3}" } }
        for (i in indices.indices) {
            require(indices[i] in 0 until n) { "indices[$i] = ${indices[i]} is outside the $n vertices" }
        }
    }

    /** Number of vertices. */
    val vertexCount: Int get() = positions.size / 3

    /** Number of triangles. */
    val triangleCount: Int get() = indices.size / 3

    /** True when there are no triangles — an empty mesh is legal and is what a degenerate march returns. */
    val isEmpty: Boolean get() = indices.isEmpty()

    /** True when this mesh carries per-vertex normals. */
    val hasNormals: Boolean get() = normals != null

    /** True when this mesh carries per-vertex texture coordinates. */
    val hasUvs: Boolean get() = uvs != null

    /** True when this mesh carries per-vertex colours. */
    val hasColors: Boolean get() = colors != null

    /** Vertex [i]'s x, without allocating. */
    fun x(i: Int): Float = positions[i * 3]

    /** Vertex [i]'s y, without allocating. */
    fun y(i: Int): Float = positions[i * 3 + 1]

    /** Vertex [i]'s z, without allocating. */
    fun z(i: Int): Float = positions[i * 3 + 2]

    /** A **fresh** [Vec3] holding vertex [i]'s position — mutating it does not touch the mesh. */
    fun position(i: Int): Vec3 = Vec3(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2])

    /** [position] into a vector you already own, for loops that must not allocate. Returns [dest]. */
    fun positionInto(i: Int, dest: Vec3): Vec3 = dest.set(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2])

    /** Vertex [i]'s normal, or `null` when the mesh has none. Fresh vector. */
    fun normal(i: Int): Vec3? = normals?.let { Vec3(it[i * 3], it[i * 3 + 1], it[i * 3 + 2]) }

    /** Vertex [i]'s texture coordinate, or `null` when the mesh has none. Fresh vector. */
    fun uv(i: Int): Vec2? = uvs?.let { Vec2(it[i * 2], it[i * 2 + 1]) }

    /** Vertex [i]'s colour, or `null` when the mesh has none. Fresh vector. */
    fun color(i: Int): Vec3? = colors?.let { Vec3(it[i * 3], it[i * 3 + 1], it[i * 3 + 2]) }

    /** Triangle [t]'s first vertex index. */
    fun ia(t: Int): Int = indices[t * 3]

    /** Triangle [t]'s second vertex index. */
    fun ib(t: Int): Int = indices[t * 3 + 1]

    /** Triangle [t]'s third vertex index. */
    fun ic(t: Int): Int = indices[t * 3 + 2]

    /** Triangle [t]'s three corner positions, as fresh vectors. */
    fun triangle(t: Int): Triple<Vec3, Vec3, Vec3> =
        Triple(position(ia(t)), position(ib(t)), position(ic(t)))

    /**
     * Triangle [t]'s geometric normal from its winding — `normalize((b-a) x (c-a))`.
     *
     * Points **out** of a correctly wound closed solid. Returns a zero vector for a degenerate
     * (zero-area) triangle rather than a `NaN` one, because degenerate triangles are legal (a UV
     * sphere has `2 * segments` of them at its poles) and callers should not have to guard.
     */
    fun faceNormal(t: Int): Vec3 {
        val a = ia(t) * 3; val b = ib(t) * 3; val c = ic(t) * 3
        val ux = positions[b] - positions[a]
        val uy = positions[b + 1] - positions[a + 1]
        val uz = positions[b + 2] - positions[a + 2]
        val vx = positions[c] - positions[a]
        val vy = positions[c + 1] - positions[a + 1]
        val vz = positions[c + 2] - positions[a + 2]
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        return if (l < 1e-20f) Vec3(0f, 0f, 0f) else Vec3(nx / l, ny / l, nz / l)
    }

    /** Triangle [t]'s area — half the length of the cross product, in double precision. */
    fun triangleArea(t: Int): Double {
        val a = ia(t) * 3; val b = ib(t) * 3; val c = ic(t) * 3
        val ux = (positions[b] - positions[a]).toDouble()
        val uy = (positions[b + 1] - positions[a + 1]).toDouble()
        val uz = (positions[b + 2] - positions[a + 2]).toDouble()
        val vx = (positions[c] - positions[a]).toDouble()
        val vy = (positions[c + 1] - positions[a + 1]).toDouble()
        val vz = (positions[c + 2] - positions[a + 2]).toDouble()
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        return 0.5 * sqrt(nx * nx + ny * ny + nz * nz)
    }

    /** All vertex positions as fresh vectors — convenient, but allocates one per vertex. */
    fun vertices(): List<Vec3> = List(vertexCount) { position(it) }

    /** Every triangle's `(a, b, c)` index triple. */
    fun triangleIndices(): List<Triple<Int, Int, Int>> = List(triangleCount) { Triple(ia(it), ib(it), ic(it)) }

    /** True when every stored float is finite — the cheapest "did my generator go wrong" check. */
    fun isFinite(): Boolean =
        positions.all { it.isFinite() } &&
            (normals?.all { it.isFinite() } ?: true) &&
            (uvs?.all { it.isFinite() } ?: true) &&
            (colors?.all { it.isFinite() } ?: true)

    /**
     * A copy with some arrays replaced. Passing `null` for an attribute **keeps** the existing one;
     * use [withoutAttributes] to drop them.
     */
    fun copy(
        positions: FloatArray? = null,
        indices: IntArray? = null,
        normals: FloatArray? = null,
        uvs: FloatArray? = null,
        colors: FloatArray? = null,
    ): TriMesh = TriMesh(
        positions ?: this.positions,
        indices ?: this.indices,
        normals ?: this.normals,
        uvs ?: this.uvs,
        colors ?: this.colors,
    )

    /** Positions and indices only — drops normals, uvs and colours. */
    fun withoutAttributes(): TriMesh = TriMesh(positions, indices)

    /** This mesh with [normals] attached (one xyz per vertex). See [withVertexNormals] to compute them. */
    fun withNormals(normals: FloatArray): TriMesh = TriMesh(positions, indices, normals, uvs, colors)

    /** This mesh with [uvs] attached (one uv per vertex). */
    fun withUvs(uvs: FloatArray): TriMesh = TriMesh(positions, indices, normals, uvs, colors)

    /** This mesh with [colors] attached (one linear rgb per vertex). */
    fun withColors(colors: FloatArray): TriMesh = TriMesh(positions, indices, normals, uvs, colors)

    /** This mesh painted a single flat colour — the fast way to make a preview readable. */
    fun withColor(r: Number, g: Number, b: Number): TriMesh {
        val c = FloatArray(vertexCount * 3)
        var i = 0
        while (i < c.size) {
            c[i] = r.toFloat(); c[i + 1] = g.toFloat(); c[i + 2] = b.toFloat(); i += 3
        }
        return withColors(c)
    }

    /** This mesh with per-vertex colours from a function of position. */
    fun coloredBy(f: (Vec3) -> Vec3): TriMesh {
        val c = FloatArray(vertexCount * 3)
        val p = Vec3()
        for (v in 0 until vertexCount) {
            val col = f(positionInto(v, p))
            c[v * 3] = col.x; c[v * 3 + 1] = col.y; c[v * 3 + 2] = col.z
        }
        return withColors(c)
    }

    /**
     * Every vertex position through [f], indices unchanged.
     *
     * Normals are **dropped**, not transformed: a general map has no inverse-transpose, and a stale
     * normal shades wrong in a way that is hard to see and easy to ship. Call [withVertexNormals]
     * after. The rigid/uniform helpers ([translated], [rotated], uniform [scaled]) keep them,
     * because for those the correct transform is known.
     */
    fun transformed(f: (Vec3) -> Vec3): TriMesh {
        val out = FloatArray(positions.size)
        val p = Vec3()
        for (v in 0 until vertexCount) {
            val q = f(positionInto(v, p))
            out[v * 3] = q.x; out[v * 3 + 1] = q.y; out[v * 3 + 2] = q.z
        }
        return TriMesh(out, indices, null, uvs, colors)
    }

    /** Translated by `(dx, dy, dz)`. Normals survive — translation does not affect them. */
    fun translated(dx: Number, dy: Number, dz: Number): TriMesh {
        val ax = dx.toFloat(); val ay = dy.toFloat(); val az = dz.toFloat()
        val out = FloatArray(positions.size)
        var i = 0
        while (i < out.size) {
            out[i] = positions[i] + ax; out[i + 1] = positions[i + 1] + ay; out[i + 2] = positions[i + 2] + az
            i += 3
        }
        return TriMesh(out, indices, normals, uvs, colors)
    }

    /** Translated by a vector. */
    fun translated(d: Vec3): TriMesh = translated(d.x, d.y, d.z)

    /**
     * Scaled about the origin, per axis.
     *
     * A **uniform positive** scale keeps the normals (they are scale-invariant once normalised);
     * anything non-uniform, or with a negative factor, drops them — the correct normal transform is
     * the inverse transpose, and a negative factor also flips the winding, which [flipped] handles.
     */
    fun scaled(sx: Number, sy: Number, sz: Number): TriMesh {
        val ax = sx.toFloat(); val ay = sy.toFloat(); val az = sz.toFloat()
        val out = FloatArray(positions.size)
        var i = 0
        while (i < out.size) {
            out[i] = positions[i] * ax; out[i + 1] = positions[i + 1] * ay; out[i + 2] = positions[i + 2] * az
            i += 3
        }
        val uniform = ax == ay && ay == az && ax > 0f
        return TriMesh(out, indices, if (uniform) normals else null, uvs, colors)
    }

    /** Uniformly scaled about the origin. */
    fun scaled(s: Number): TriMesh = scaled(s, s, s)

    /** Rotated by a [Quat]. Rigid, so the normals are rotated with the positions rather than dropped. */
    fun rotated(q: Quat): TriMesh {
        val out = FloatArray(positions.size)
        val p = Vec3()
        for (v in 0 until vertexCount) {
            val r = rotate(q, positionInto(v, p))
            out[v * 3] = r.x; out[v * 3 + 1] = r.y; out[v * 3 + 2] = r.z
        }
        val n = normals?.let { src ->
            val dst = FloatArray(src.size)
            for (v in 0 until vertexCount) {
                val r = rotate(q, p.set(src[v * 3], src[v * 3 + 1], src[v * 3 + 2]))
                dst[v * 3] = r.x; dst[v * 3 + 1] = r.y; dst[v * 3 + 2] = r.z
            }
            dst
        }
        return TriMesh(out, indices, n, uvs, colors)
    }

    /**
     * Every triangle wound the other way, and every normal negated — an inside-out copy.
     *
     * Use it when a generator produced a solid with negative [signedVolume], or to make the inner
     * surface of a shell.
     */
    fun flipped(): TriMesh {
        val idx = IntArray(indices.size)
        for (t in 0 until triangleCount) {
            idx[t * 3] = indices[t * 3]
            idx[t * 3 + 1] = indices[t * 3 + 2]
            idx[t * 3 + 2] = indices[t * 3 + 1]
        }
        val n = normals?.let { src -> FloatArray(src.size) { -src[it] } }
        return TriMesh(positions, idx, n, uvs, colors)
    }

    /**
     * Two meshes as one, [other]'s indices shifted past this one's vertices.
     *
     * The result carries an optional attribute only if **both** inputs had it — otherwise half the
     * vertices would have an invented normal or colour, which is worse than none.
     */
    operator fun plus(other: TriMesh): TriMesh {
        val n = vertexCount
        val idx = IntArray(indices.size + other.indices.size)
        indices.copyInto(idx)
        for (i in other.indices.indices) idx[indices.size + i] = other.indices[i] + n
        fun join(a: FloatArray?, b: FloatArray?): FloatArray? {
            if (a == null || b == null) return null
            val out = FloatArray(a.size + b.size)
            a.copyInto(out); b.copyInto(out, a.size)
            return out
        }
        return TriMesh(join(positions, other.positions)!!, idx, join(normals, other.normals), join(uvs, other.uvs), join(colors, other.colors))
    }

    /**
     * A mesh made of the triangles [keep] accepts, with the now-unused vertices removed and the
     * indices renumbered. Attributes follow their vertices.
     */
    fun filterTriangles(keep: (Int) -> Boolean): TriMesh {
        val kept = (0 until triangleCount).filter(keep)
        val remap = IntArray(vertexCount) { -1 }
        var next = 0
        for (t in kept) {
            for (k in 0..2) {
                val v = indices[t * 3 + k]
                if (remap[v] < 0) remap[v] = next++
            }
        }
        val pos = FloatArray(next * 3)
        val nor = if (normals != null) FloatArray(next * 3) else null
        val tex = if (uvs != null) FloatArray(next * 2) else null
        val col = if (colors != null) FloatArray(next * 3) else null
        for (v in 0 until vertexCount) {
            val d = remap[v]
            if (d < 0) continue
            for (k in 0..2) {
                pos[d * 3 + k] = positions[v * 3 + k]
                nor?.set(d * 3 + k, normals!![v * 3 + k])
                col?.set(d * 3 + k, colors!![v * 3 + k])
            }
            if (tex != null) { tex[d * 2] = uvs!![v * 2]; tex[d * 2 + 1] = uvs[v * 2 + 1] }
        }
        val idx = IntArray(kept.size * 3)
        kept.forEachIndexed { j, t ->
            idx[j * 3] = remap[indices[t * 3]]
            idx[j * 3 + 1] = remap[indices[t * 3 + 1]]
            idx[j * 3 + 2] = remap[indices[t * 3 + 2]]
        }
        return TriMesh(pos, idx, nor, tex, col)
    }

    override fun toString(): String =
        "TriMesh($vertexCount verts, $triangleCount tris" +
            (if (hasNormals) ", normals" else "") +
            (if (hasUvs) ", uvs" else "") +
            (if (hasColors) ", colors" else "") + ")"

    /**
     * Inline thumbnail for the value visualizer: the mesh actually rasterised, shaded and
     * depth-buffered, plus a one-line measurement caption. Evaluating a `TriMesh` in a box (or
     * `mesh.h`) shows the shape rather than `TriMesh@3f1a2b`.
     */
    override fun toHTML(): String = meshThumbnailHtml(this)

    companion object {
        /** The empty mesh — no vertices, no triangles. What a march of an empty field returns. */
        val empty: TriMesh = TriMesh(FloatArray(0), IntArray(0))

        /** Build from vertex list plus flat index list — the shape most hand-written meshes arrive in. */
        fun of(vertices: List<Vec3>, indices: List<Int>): TriMesh {
            val p = FloatArray(vertices.size * 3)
            vertices.forEachIndexed { i, v -> p[i * 3] = v.x; p[i * 3 + 1] = v.y; p[i * 3 + 2] = v.z }
            return TriMesh(p, indices.toIntArray())
        }

        /** Build from a list of independent triangles — welds nothing, so call [weld] after if you need shared vertices. */
        fun ofTriangles(triangles: List<Triple<Vec3, Vec3, Vec3>>): TriMesh {
            val p = FloatArray(triangles.size * 9)
            triangles.forEachIndexed { t, (a, b, c) ->
                p[t * 9] = a.x; p[t * 9 + 1] = a.y; p[t * 9 + 2] = a.z
                p[t * 9 + 3] = b.x; p[t * 9 + 4] = b.y; p[t * 9 + 5] = b.z
                p[t * 9 + 6] = c.x; p[t * 9 + 7] = c.y; p[t * 9 + 8] = c.z
            }
            return TriMesh(p, IntArray(triangles.size * 3) { it })
        }
    }
}

/** `p` rotated by `q`, into a fresh vector. Written out because the generated `Quat` API takes a dest. */
private fun rotate(q: Quat, p: Vec3): Vec3 {
    // v' = v + 2 * cross(q.xyz, cross(q.xyz, v) + q.w * v)
    val qx = q.x; val qy = q.y; val qz = q.z; val qw = q.w
    val tx = qy * p.z - qz * p.y + qw * p.x
    val ty = qz * p.x - qx * p.z + qw * p.y
    val tz = qx * p.y - qy * p.x + qw * p.z
    return Vec3(
        p.x + 2f * (qy * tz - qz * ty),
        p.y + 2f * (qz * tx - qx * tz),
        p.z + 2f * (qx * ty - qy * tx),
    )
}

// ==============================================================================================
// Building one
// ==============================================================================================

/**
 * The accumulator every generator in this family is written against: append vertices, append
 * triangles that reference them, [build].
 *
 * Attributes are opt-in but must be *consistent* — the first [vertex] call decides whether this
 * mesh has normals, and a later call that omits one (or adds one) fails immediately rather than
 * shipping a mesh whose attribute array is half garbage.
 *
 * ```kotlin
 * val quadStrip = triMesh {
 *     for (i in 0..8) {
 *         val a = vertex(vec(i, 0, 0), normal = vec(0, 0, 1))
 *         val b = vertex(vec(i, 1, 0), normal = vec(0, 0, 1))
 *         if (i > 0) quad(a - 2, a, b, b - 2)
 *     }
 * }
 * ```
 */
class TriMeshBuilder {
    private val pos = ArrayList<Float>()
    private val idx = ArrayList<Int>()
    private var nor: ArrayList<Float>? = null
    private var tex: ArrayList<Float>? = null
    private var col: ArrayList<Float>? = null
    private var decided = false
    private val marks = ArrayList<Pair<Int, String>>()

    /** How many vertices have been appended so far — also the index the next [vertex] will get. */
    val vertexCount: Int get() = pos.size / 3

    /** How many triangles have been appended so far. */
    val triangleCount: Int get() = idx.size / 3

    /**
     * Name the piece every triangle appended from here on belongs to.
     *
     * A marker, not a container: it records "triangles from index [triangleCount] onward are called
     * this" and nothing else, so it costs one pair per call and does not touch the mesh. The [TriMesh]
     * a builder produces has no idea the names exist; they come back out through
     * [afield.stdlib.groups], which turns them into a [PickParts] so that *pointing* at the model
     * reports `roof` instead of `triangle 4471`.
     *
     * Each call starts a new piece even if the name repeats — see [afield.stdlib.groups] for why.
     *
     * ```kotlin
     * val house = triMesh {
     *     group("floor"); add(box(4.0, 0.2, 3.0))
     *     group("roof");  add(box(4.2, 0.2, 3.2))
     * }
     * ```
     */
    fun group(name: String) {
        marks += triangleCount to name
    }

    /** The [group] markers, as `(first triangle, name)` pairs in call order. */
    internal fun groupMarks(): List<Pair<Int, String>> = marks.toList()

    /**
     * Append a vertex and return its index.
     *
     * @param normal supply it for every vertex or for none.
     * @param uv supply it for every vertex or for none.
     * @param color supply it for every vertex or for none.
     */
    fun vertex(p: Vec3, normal: Vec3? = null, uv: Vec2? = null, color: Vec3? = null): Int =
        vertex(p.x, p.y, p.z, normal, uv, color)

    /** [vertex] from loose floats — what the generators use, so they never allocate a `Vec3` per vertex. */
    fun vertex(x: Number, y: Number, z: Number, normal: Vec3? = null, uv: Vec2? = null, color: Vec3? = null): Int {
        if (!decided) {
            if (normal != null) nor = ArrayList()
            if (uv != null) tex = ArrayList()
            if (color != null) col = ArrayList()
            decided = true
        } else {
            require((normal != null) == (nor != null)) {
                "vertex ${vertexCount}: this mesh ${if (nor != null) "has" else "does not have"} normals; " +
                    "supply one for every vertex or for none"
            }
            require((uv != null) == (tex != null)) { "vertex ${vertexCount}: uvs must be all-or-nothing" }
            require((color != null) == (col != null)) { "vertex ${vertexCount}: colors must be all-or-nothing" }
        }
        val i = vertexCount
        pos.add(x.toFloat()); pos.add(y.toFloat()); pos.add(z.toFloat())
        normal?.let { nor!!.add(it.x); nor!!.add(it.y); nor!!.add(it.z) }
        uv?.let { tex!!.add(it.x); tex!!.add(it.y) }
        color?.let { col!!.add(it.x); col!!.add(it.y); col!!.add(it.z) }
        return i
    }

    /** Append one triangle. Counter-clockwise seen from outside. */
    fun triangle(a: Int, b: Int, c: Int) {
        require(a in 0 until vertexCount && b in 0 until vertexCount && c in 0 until vertexCount) {
            "triangle($a, $b, $c) references a vertex outside the $vertexCount added so far"
        }
        idx.add(a); idx.add(b); idx.add(c)
    }

    /** Append a quad as two triangles: `(a,b,c)` and `(a,c,d)`. Corners in order around the quad. */
    fun quad(a: Int, b: Int, c: Int, d: Int) {
        triangle(a, b, c)
        triangle(a, c, d)
    }

    /**
     * Append a convex polygon as a fan from its first corner. Non-convex polygons will self-overlap
     * — [extrudeMesh] triangulates properly via ear clipping for those.
     */
    fun polygon(corners: List<Int>) {
        require(corners.size >= 3) { "a polygon needs at least 3 corners, got ${corners.size}" }
        for (i in 1 until corners.size - 1) triangle(corners[0], corners[i], corners[i + 1])
    }

    /**
     * Append a whole mesh, offsetting its indices. Returns the index its vertex 0 landed on.
     *
     * The attributes must agree with what this builder is already carrying, for the same reason
     * [vertex] insists on it.
     */
    fun add(mesh: TriMesh): Int {
        val base = vertexCount
        for (v in 0 until mesh.vertexCount) {
            vertex(
                mesh.x(v), mesh.y(v), mesh.z(v),
                mesh.normal(v), mesh.uv(v), mesh.color(v),
            )
        }
        for (i in mesh.indices) idx.add(i + base)
        return base
    }

    /** Freeze into a [TriMesh]. The builder stays usable; the mesh gets its own arrays. */
    fun build(): TriMesh = TriMesh(
        pos.toFloatArray(),
        idx.toIntArray(),
        nor?.toFloatArray(),
        tex?.toFloatArray(),
        col?.toFloatArray(),
    )
}

/** Run [block] against a fresh [TriMeshBuilder] and [TriMeshBuilder.build] the result. */
inline fun triMesh(block: TriMeshBuilder.() -> Unit): TriMesh = TriMeshBuilder().apply(block).build()

// ==============================================================================================
// Small shared numerics
// ==============================================================================================

/** `[minX, minY, minZ, maxX, maxY, maxZ]` over the positions; a unit box for an empty mesh. */
internal fun TriMesh.boundsArray(): DoubleArray {
    if (vertexCount == 0) return doubleArrayOf(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5)
    val b = doubleArrayOf(
        Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
        -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE,
    )
    for (v in 0 until vertexCount) {
        val x = x(v).toDouble(); val y = y(v).toDouble(); val z = z(v).toDouble()
        b[0] = min(b[0], x); b[1] = min(b[1], y); b[2] = min(b[2], z)
        b[3] = max(b[3], x); b[4] = max(b[4], y); b[5] = max(b[5], z)
    }
    return b
}
