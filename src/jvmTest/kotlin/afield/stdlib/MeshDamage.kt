package afield.stdlib

import kotlin.random.Random

/**
 * Deterministic, *named* ways to break a known-good mesh.
 *
 * Every entry here damages a mesh from [afield.stdlib] `mesh_generate.kt` in one specific way, so a
 * test can say "this mesh has exactly a hole in it" rather than "this mesh is bad somehow". That
 * precision is the whole point: a repair pipeline tested against meshes whose defects are only
 * approximately known can only be tested for "did it get better", and "did it get better" is how a
 * pipeline that silently throws away geometry passes its tests.
 *
 * Nothing here downloads, and nothing here uses a random seed it does not fix, so the table a test
 * prints is the same table on every machine.
 */
object MeshDamage {

    /** Delete [count] triangles starting at [from], opening a hole with a single boundary loop. */
    fun deleteFaces(mesh: TriMesh, from: Int = 0, count: Int = 1): TriMesh {
        val drop = (from until from + count).toHashSet()
        return mesh.filterTriangles { it !in drop }
    }

    /** Reverse the winding of every [stride]-th triangle, leaving the mesh inconsistently oriented. */
    fun flipSubsetWinding(mesh: TriMesh, stride: Int = 3): TriMesh {
        val idx = mesh.indices.copyOf()
        for (t in 0 until mesh.triangleCount) {
            if (t % stride != 0) continue
            val tmp = idx[t * 3 + 1]; idx[t * 3 + 1] = idx[t * 3 + 2]; idx[t * 3 + 2] = tmp
        }
        return TriMesh(mesh.positions, idx)
    }

    /** Reverse *every* triangle: consistent, but inside out — negative signed volume. */
    fun invertWinding(mesh: TriMesh): TriMesh = flipSubsetWinding(mesh, stride = 1)

    /**
     * Give every triangle its own three vertices — what an STL is, and the classic "looks closed,
     * is a pile of triangles" input.
     */
    fun splitSharedVertices(mesh: TriMesh): TriMesh {
        val pos = FloatArray(mesh.triangleCount * 9)
        val idx = IntArray(mesh.triangleCount * 3)
        for (t in 0 until mesh.triangleCount) {
            for ((k, v) in listOf(mesh.ia(t), mesh.ib(t), mesh.ic(t)).withIndex()) {
                val d = (t * 3 + k) * 3
                pos[d] = mesh.x(v); pos[d + 1] = mesh.y(v); pos[d + 2] = mesh.z(v)
                idx[t * 3 + k] = t * 3 + k
            }
        }
        return TriMesh(pos, idx)
    }

    /**
     * Split only the vertices used by triangle [t] — a *local* crack, so the mesh is closed
     * everywhere except one triangle's worth of seam. Harder than [splitSharedVertices], because the
     * defect is small enough to miss.
     */
    fun crackOneFace(mesh: TriMesh, t: Int = 0): TriMesh {
        val pos = mesh.positions.copyOf(mesh.positions.size + 9)
        val idx = mesh.indices.copyOf()
        val base = mesh.vertexCount
        for ((k, v) in listOf(mesh.ia(t), mesh.ib(t), mesh.ic(t)).withIndex()) {
            val d = (base + k) * 3
            pos[d] = mesh.x(v); pos[d + 1] = mesh.y(v); pos[d + 2] = mesh.z(v)
            idx[t * 3 + k] = base + k
        }
        return TriMesh(pos, idx)
    }

    /** Append [count] zero-area triangles (a repeated corner) to the index buffer. */
    fun insertDegenerate(mesh: TriMesh, count: Int = 3): TriMesh {
        val idx = mesh.indices.copyOf(mesh.indices.size + count * 3)
        for (i in 0 until count) {
            val v = i % mesh.vertexCount
            val w = (i + 1) % mesh.vertexCount
            val d = mesh.indices.size + i * 3
            idx[d] = v; idx[d + 1] = w; idx[d + 2] = v
        }
        return TriMesh(mesh.positions, idx)
    }

    /** Append an exact copy of triangle [t] — same corners, same winding. */
    fun duplicateFace(mesh: TriMesh, t: Int = 0): TriMesh {
        val idx = mesh.indices.copyOf(mesh.indices.size + 3)
        idx[mesh.indices.size] = mesh.ia(t)
        idx[mesh.indices.size + 1] = mesh.ib(t)
        idx[mesh.indices.size + 2] = mesh.ic(t)
        return TriMesh(mesh.positions, idx)
    }

    /**
     * Append a third triangle along the edge of triangle [t] — a "fin". The shared edge now has three
     * incident faces, which is the textbook non-manifold edge and the one no existing repair touches.
     */
    fun nonManifoldFin(mesh: TriMesh, t: Int = 0): TriMesh {
        val a = mesh.ia(t); val b = mesh.ib(t)
        val pa = mesh.position(a); val pb = mesh.position(b)
        // A tip off to one side, well away from the surface, so the fin is unambiguously extra.
        val mid = Vec3((pa.x + pb.x) / 2f, (pa.y + pb.y) / 2f, (pa.z + pb.z) / 2f)
        val n = mesh.faceNormal(t)
        val len = pa.distance(pb)
        val tip = Vec3(mid.x + n.x * len, mid.y + n.y * len, mid.z + n.z * len)
        val pos = mesh.positions.copyOf(mesh.positions.size + 3)
        pos[mesh.positions.size] = tip.x; pos[mesh.positions.size + 1] = tip.y; pos[mesh.positions.size + 2] = tip.z
        val idx = mesh.indices.copyOf(mesh.indices.size + 3)
        idx[mesh.indices.size] = a; idx[mesh.indices.size + 1] = b; idx[mesh.indices.size + 2] = mesh.vertexCount
        return TriMesh(pos, idx)
    }

    /** Merge [mesh] with a second, disjoint copy of [other] translated clear of it. */
    fun secondComponent(mesh: TriMesh, other: TriMesh, dx: Double = 5.0): TriMesh =
        concat(mesh, other.translated(dx, 0.0, 0.0))

    /** Jitter every position by up to [amount], enough to defeat an exact-match weld. */
    fun jitter(mesh: TriMesh, amount: Double = 1e-7, seed: Long = 7L): TriMesh {
        val r = Random(seed)
        val pos = mesh.positions.copyOf()
        for (i in pos.indices) pos[i] += ((r.nextDouble() - 0.5) * 2.0 * amount).toFloat()
        return TriMesh(pos, mesh.indices)
    }

    /** Index-buffer concatenation of two meshes, keeping positions and indices only. */
    fun concat(a: TriMesh, b: TriMesh): TriMesh {
        val pos = a.positions.copyOf(a.positions.size + b.positions.size)
        System.arraycopy(b.positions, 0, pos, a.positions.size, b.positions.size)
        val idx = a.indices.copyOf(a.indices.size + b.indices.size)
        for (i in b.indices.indices) idx[a.indices.size + i] = b.indices[i] + a.vertexCount
        return TriMesh(pos, idx)
    }
}
