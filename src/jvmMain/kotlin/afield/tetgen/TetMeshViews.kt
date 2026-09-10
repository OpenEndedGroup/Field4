@file:afield.CompletionCategory("Simulation")
package afield.tetgen

import afield.stdlib.TriMesh
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Ways of looking at a [TetMesh]: its element quality, a slice of it, and the triangles that draw it.
 *
 * A tetrahedral mesh has an inside, which is the whole point of it and also the reason it is awkward
 * to look at. Rendering one naively draws the boundary and hides everything the mesher actually did.
 * The three answers here are the three that get used: [boundary] for the outside, [filtered] with a
 * half-space predicate for a cutaway, and [toTriMesh] with `shrink` below 1 for a shrunken-element
 * view where each tetrahedron is visibly its own cell.
 *
 * The two quality measures are kept apart because they answer different questions, and they are
 * easily confused:
 *
 * - [radiusEdgeRatios] is what TetGen's `-q` switch bounds, and therefore the number to check when
 *   you want to know whether the mesher honoured what you asked for.
 * - [qualities] is the radius ratio, which is what a solver's conditioning cares about. TetGen does
 *   not bound it, and it is the number that detects a sliver.
 *
 * All of these read a cell's first four indices and ignore the rest, so a 10-node mesh from
 * [TetMesh.quadratic] measures and draws exactly as its 4-node original does. That is correct rather
 * than approximate: the six midside nodes sit at the midpoints of straight edges, so the region, the
 * boundary and every quality measure are the same. What the extra nodes change is the interpolation
 * inside an element, which none of these views is looking at.
 */

/**
 * Every tetrahedron's **radius ratio**, `3 r_in / r_circ` — 1.0 for a regular tetrahedron, 0 for a
 * flat one.
 *
 * This is the quality measure to report. Below about 0.05 an element is a sliver: it has a
 * respectable volume and a nearly-degenerate shape, and it is what makes a stiffness matrix
 * ill-conditioned. A degenerate element comes back as `0.0` rather than a NaN, so the array is
 * always safe to sort and to histogram.
 */
fun TetMesh.qualities(): DoubleArray = DoubleArray(cellCount) { t ->
    val v = abs(signedVolumeOf(t))
    if (v <= 0.0) return@DoubleArray 0.0
    val area = faceAreaSumOf(t)
    val circum = circumradiusOf(t)
    if (area <= 0.0 || !circum.isFinite() || circum <= 0.0) return@DoubleArray 0.0
    // r_in = 3V / A, so 3 r_in / r_circ = 9V / (A r_circ).
    (9.0 * v / (area * circum)).coerceIn(0.0, 1.0)
}

/**
 * Every tetrahedron's **radius-edge ratio**, `r_circ / shortest edge` — the quantity TetGen's `-q`
 * switch, and so [Tetgen.tetrahedralize]'s `quality`, actually bounds.
 *
 * The lower bound for any tetrahedron is `sqrt(6)/4 = 0.6124`, reached by the regular one, so a
 * mesh asked for `quality = 1.2` should come back with almost every element between those two
 * numbers. "Almost" is not "all": TetGen's guarantee excludes elements it cannot improve without
 * violating the input surface, which in practice means the ones wedged into a sharp input dihedral.
 * Reporting the count that exceeds the bound is more useful than asserting there are none.
 */
fun TetMesh.radiusEdgeRatios(): DoubleArray = DoubleArray(cellCount) { t ->
    val shortest = shortestEdgeOf(t)
    if (shortest <= 0.0) return@DoubleArray Double.POSITIVE_INFINITY
    circumradiusOf(t) / shortest
}

/** Every tetrahedron's centroid, as xyz triples in the same order as [TetMesh.cells]. */
fun TetMesh.centroids(): DoubleArray {
    val out = DoubleArray(3 * cellCount)
    for (t in 0 until cellCount) {
        for (c in 0..2) {
            var s = 0.0
            for (k in 0..3) s += positions[3 * corner(t, k) + c]
            out[3 * t + c] = s * 0.25
        }
    }
    return out
}

/**
 * The tetrahedra for which [keep] is true, as a mesh in their own right, with the nodes nobody
 * references dropped and the rest renumbered.
 *
 * `keep` is given the cell index, so the caller decides using anything it can compute — a centroid
 * on one side of a plane (which is a cutaway), a quality below a threshold (which is a picture of
 * where the bad elements are), a distance from a feature.
 *
 * The result is a legal [TetMesh] but is **not** a legal input to a solver: cutting a body in half
 * this way leaves the cut face open in the sense that nothing constrains it. It is a view, not a
 * model. It is also always 4-node, even from a quadratic mesh, for the same reason — nothing is
 * going to integrate over it.
 */
fun TetMesh.filtered(keep: (Int) -> Boolean): TetMesh {
    val kept = (0 until cellCount).filter(keep)
    val remap = IntArray(nodeCount) { -1 }
    var next = 0
    for (t in kept) for (k in 0..3) {
        val node = corner(t, k)
        if (remap[node] < 0) remap[node] = next++
    }
    val out = DoubleArray(3 * next)
    for (node in 0 until nodeCount) {
        val to = remap[node]
        if (to >= 0) for (c in 0..2) out[3 * to + c] = positions[3 * node + c]
    }
    val connectivity = IntArray(4 * kept.size)
    for ((i, t) in kept.withIndex()) for (k in 0..3) connectivity[4 * i + k] = remap[corner(t, k)]
    return TetMesh(out, connectivity)
}

/**
 * The surface of this mesh: the triangles belonging to exactly one tetrahedron, wound outwards.
 *
 * For a mesh straight out of [Tetgen.tetrahedralize] this is the input surface again — the same
 * shape, re-triangulated, plus whatever Steiner points TetGen added on it. For a [filtered] mesh it
 * is the cutaway's skin, including the newly exposed cut face, which is what makes a cutaway
 * readable: shading a solid cap reads as a slice through a body, and a wireframe of loose triangles
 * does not.
 *
 * Interior faces are dropped by counting: a face shared by two tetrahedra appears twice with
 * opposite winding, a boundary face once.
 */
fun TetMesh.boundary(): TriMesh {
    val seen = HashMap<Long, Int>(cellCount * 2)
    val faces = ArrayList<IntArray>(cellCount)
    for (t in 0 until cellCount) {
        for (face in outwardFacesOf(t)) {
            val key = faceKey(face)
            val at = seen[key]
            if (at == null) {
                seen[key] = faces.size
                faces.add(face)
            } else {
                faces[at] = EMPTY_FACE
            }
        }
    }
    val remap = IntArray(nodeCount) { -1 }
    var next = 0
    for (face in faces) for (v in face) {
        if (remap[v] < 0) remap[v] = next++
    }
    val out = FloatArray(3 * next)
    for (node in 0 until nodeCount) {
        val to = remap[node]
        if (to >= 0) for (c in 0..2) out[3 * to + c] = positions[3 * node + c].toFloat()
    }
    val live = faces.filter { it !== EMPTY_FACE }
    val indices = IntArray(3 * live.size)
    for ((i, face) in live.withIndex()) for (k in 0..2) indices[3 * i + k] = remap[face[k]]
    return TriMesh(out, indices)
}

/**
 * Every tetrahedron drawn as its own four-triangle shell, optionally shrunk towards its centroid.
 *
 * `shrink = 1.0` is the whole solid with its interior faces still in it, which is rarely what you
 * want. `shrink` around `0.8` pulls each element off its neighbours so the cells separate visually —
 * the standard way of showing that a body really is filled with tetrahedra rather than hollow.
 *
 * @param shrink scale of each element about its own centroid, in `(0, 1]`.
 * @param cellColors one linear rgb triple per cell, or `null`. Given, all four of that cell's
 *   corners take it — the corners are unshared between cells, so a per-cell colour survives without
 *   bleeding into a neighbour, which is how a quality field becomes a picture. Use
 *   `afield.Colormaps.sample` to build it.
 */
fun TetMesh.toTriMesh(shrink: Double = 1.0, cellColors: FloatArray? = null): TriMesh {
    require(shrink > 0.0 && shrink <= 1.0) { "shrink must be in (0, 1], got $shrink" }
    cellColors?.let {
        require(it.size == 3 * cellCount) { "cellColors must be $cellCount rgb triples, got ${it.size / 3}" }
    }
    val positionsOut = FloatArray(3 * 4 * cellCount)
    val colorsOut = cellColors?.let { FloatArray(3 * 4 * cellCount) }
    val indicesOut = IntArray(3 * 4 * cellCount)
    val centroid = DoubleArray(3)
    for (t in 0 until cellCount) {
        for (c in 0..2) {
            var s = 0.0
            for (k in 0..3) s += positions[3 * corner(t, k) + c]
            centroid[c] = s * 0.25
        }
        for (k in 0..3) {
            val node = corner(t, k)
            for (c in 0..2) {
                val p = positions[3 * node + c]
                positionsOut[3 * (4 * t + k) + c] = (centroid[c] + (p - centroid[c]) * shrink).toFloat()
            }
            if (cellColors != null && colorsOut != null) {
                for (c in 0..2) colorsOut[3 * (4 * t + k) + c] = cellColors[3 * t + c]
            }
        }
        val flip = signedVolumeOf(t) < 0.0
        for ((f, face) in FACE_CORNERS.withIndex()) {
            val a = 4 * t + face[0]
            val b = 4 * t + (if (flip) face[2] else face[1])
            val c = 4 * t + (if (flip) face[1] else face[2])
            indicesOut[3 * (4 * t + f)] = a
            indicesOut[3 * (4 * t + f) + 1] = b
            indicesOut[3 * (4 * t + f) + 2] = c
        }
    }
    return TriMesh(positionsOut, indicesOut, colors = colorsOut)
}

// ==================================================================================================
// The arithmetic, once
// ==================================================================================================

/**
 * The four faces of a positively oriented tetrahedron, wound so their normals point out of it.
 *
 * Checked against the reference tetrahedron `(0,0,0) (1,0,0) (0,1,0) (0,0,1)`, whose signed volume
 * is `+1/6`: the four cross products come out `-z`, `-y`, `+(1,1,1)` and `-x`, each pointing away
 * from the opposite corner. Getting one of these backwards produces a mesh that renders with holes
 * under back-face culling and looks like a broken mesher rather than a typo.
 */
private val FACE_CORNERS = arrayOf(
    intArrayOf(0, 2, 1),
    intArrayOf(0, 1, 3),
    intArrayOf(1, 2, 3),
    intArrayOf(0, 3, 2),
)

private val EMPTY_FACE = IntArray(0)

/**
 * Corner `k` of tetrahedron `t`, as a node index.
 *
 * The stride is [TetMesh.nodesPerCell] and not 4. Reading a 10-node mesh at stride 4 does not throw
 * and does not go out of bounds — every index it lands on is a real node — so it produces a wrong
 * picture and a wrong quality histogram rather than an error, which is why nothing in this file
 * indexes `cells` directly.
 */
private fun TetMesh.corner(t: Int, k: Int): Int = cells[nodesPerCell * t + k]

private fun TetMesh.coordinate(t: Int, k: Int, c: Int): Double = positions[3 * corner(t, k) + c]

private fun TetMesh.signedVolumeOf(t: Int): Double {
    val ax = coordinate(t, 1, 0) - coordinate(t, 0, 0)
    val ay = coordinate(t, 1, 1) - coordinate(t, 0, 1)
    val az = coordinate(t, 1, 2) - coordinate(t, 0, 2)
    val bx = coordinate(t, 2, 0) - coordinate(t, 0, 0)
    val by = coordinate(t, 2, 1) - coordinate(t, 0, 1)
    val bz = coordinate(t, 2, 2) - coordinate(t, 0, 2)
    val cx = coordinate(t, 3, 0) - coordinate(t, 0, 0)
    val cy = coordinate(t, 3, 1) - coordinate(t, 0, 1)
    val cz = coordinate(t, 3, 2) - coordinate(t, 0, 2)
    return (ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)) / 6.0
}

private fun TetMesh.outwardFacesOf(t: Int): List<IntArray> {
    val flip = signedVolumeOf(t) < 0.0
    return FACE_CORNERS.map { face ->
        if (flip) intArrayOf(corner(t, face[0]), corner(t, face[2]), corner(t, face[1]))
        else intArrayOf(corner(t, face[0]), corner(t, face[1]), corner(t, face[2]))
    }
}

/**
 * A winding-independent key for a triangle's three node indices.
 *
 * Sorting the three and packing them into a `Long` needs 21 bits each, which caps this at 2,097,151
 * nodes — about 12 million tetrahedra, well past anything that fits in this process. Beyond that the
 * key collides and two unrelated faces cancel, so the cap is checked rather than assumed.
 */
private fun faceKey(face: IntArray): Long {
    var a = face[0]; var b = face[1]; var c = face[2]
    if (a > b) { val s = a; a = b; b = s }
    if (b > c) { val s = b; b = c; c = s }
    if (a > b) { val s = a; a = b; b = s }
    require(c < (1 shl 21)) { "this mesh has more than ${1 shl 21} nodes, which the face key cannot pack" }
    return (a.toLong() shl 42) or (b.toLong() shl 21) or c.toLong()
}

private fun TetMesh.faceAreaSumOf(t: Int): Double {
    var total = 0.0
    for (face in FACE_CORNERS) {
        val ax = coordinate(t, face[1], 0) - coordinate(t, face[0], 0)
        val ay = coordinate(t, face[1], 1) - coordinate(t, face[0], 1)
        val az = coordinate(t, face[1], 2) - coordinate(t, face[0], 2)
        val bx = coordinate(t, face[2], 0) - coordinate(t, face[0], 0)
        val by = coordinate(t, face[2], 1) - coordinate(t, face[0], 1)
        val bz = coordinate(t, face[2], 2) - coordinate(t, face[0], 2)
        val nx = ay * bz - az * by
        val ny = az * bx - ax * bz
        val nz = ax * by - ay * bx
        total += 0.5 * sqrt(nx * nx + ny * ny + nz * nz)
    }
    return total
}

/**
 * The radius of the sphere through all four corners.
 *
 * `o = (|a|²(b×c) + |b|²(c×a) + |c|²(a×b)) / (2 a·(b×c))` places the circumcentre relative to corner
 * 0; the radius is `|o|`. The denominator is six times the signed volume, so a flat tetrahedron
 * gives an infinite radius, which is the right answer and is why the callers test for finiteness
 * rather than for a small volume.
 */
private fun TetMesh.circumradiusOf(t: Int): Double {
    val ax = coordinate(t, 1, 0) - coordinate(t, 0, 0)
    val ay = coordinate(t, 1, 1) - coordinate(t, 0, 1)
    val az = coordinate(t, 1, 2) - coordinate(t, 0, 2)
    val bx = coordinate(t, 2, 0) - coordinate(t, 0, 0)
    val by = coordinate(t, 2, 1) - coordinate(t, 0, 1)
    val bz = coordinate(t, 2, 2) - coordinate(t, 0, 2)
    val cx = coordinate(t, 3, 0) - coordinate(t, 0, 0)
    val cy = coordinate(t, 3, 1) - coordinate(t, 0, 1)
    val cz = coordinate(t, 3, 2) - coordinate(t, 0, 2)

    val bcx = by * cz - bz * cy; val bcy = bz * cx - bx * cz; val bcz = bx * cy - by * cx
    val cax = cy * az - cz * ay; val cay = cz * ax - cx * az; val caz = cx * ay - cy * ax
    val abx = ay * bz - az * by; val aby = az * bx - ax * bz; val abz = ax * by - ay * bx

    val denominator = 2.0 * (ax * bcx + ay * bcy + az * bcz)
    if (denominator == 0.0) return Double.POSITIVE_INFINITY
    val aa = ax * ax + ay * ay + az * az
    val bb = bx * bx + by * by + bz * bz
    val cc = cx * cx + cy * cy + cz * cz
    val ox = (aa * bcx + bb * cax + cc * abx) / denominator
    val oy = (aa * bcy + bb * cay + cc * aby) / denominator
    val oz = (aa * bcz + bb * caz + cc * abz) / denominator
    return sqrt(ox * ox + oy * oy + oz * oz)
}

private fun TetMesh.shortestEdgeOf(t: Int): Double {
    var shortest = Double.POSITIVE_INFINITY
    for (i in 0..2) for (j in i + 1..3) {
        var sum = 0.0
        for (c in 0..2) {
            val d = coordinate(t, i, c) - coordinate(t, j, c)
            sum += d * d
        }
        shortest = min(shortest, sqrt(sum))
    }
    return shortest
}
