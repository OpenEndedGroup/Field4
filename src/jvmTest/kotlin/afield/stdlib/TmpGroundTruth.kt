package afield.stdlib
import afield.manifold.isValidSolid
import kotlin.test.Test

/** PROBE: how planar are my holes really, and what does repair cost against ground truth? */
class TmpGroundTruth {
    private fun err(a: Double, b: Double) = if (maxOf(kotlin.math.abs(a), kotlin.math.abs(b)) == 0.0) 0.0
        else kotlin.math.abs(a - b) / maxOf(kotlin.math.abs(a), kotlin.math.abs(b))

    private fun row(name: String, truth: TriMesh, broken: TriMesh) {
        val d = broken.diagnose()
        val loops = d.boundaryLoops
        val r = runCatching { broken.repairToSolid() }.getOrNull()
        val fixed = r?.mesh
        val vErr = if (fixed == null) Double.NaN else err(truth.signedVolume(), fixed.signedVolume())
        val aErr = if (fixed == null) Double.NaN else err(truth.surfaceArea(), fixed.surfaceArea())
        println("%-44s loops=%d maxPlanarity=%.4f simple=%s | solid=%s volErr=%.5f areaErr=%.5f genus %s->%s methods=%s"
            .format(name, loops.size, loops.maxOfOrNull { it.planarity } ?: 0.0,
                loops.all { it.isSimple },
                r?.isSolid, vErr, aErr, truth.genus(), fixed?.genus(),
                r?.holeFills?.groupingBy { it.method }?.eachCount()))
    }

    @Test fun probe() {
        val cube = cubeMesh(1.0); val ico = icosphereMesh(2, 1.0); val ico3 = icosphereMesh(3, 1.0)
        val torus = torusMesh(24, 12); val cap = capsuleMesh(24, 8, 0.4, 1.0)
        println("=== existing table's holes ===")
        row("cube less 1 face", cube, MeshDamage.deleteFaces(cube, 0, 1))
        row("cube less 2 faces (a whole flat face)", cube, MeshDamage.deleteFaces(cube, 0, 2))
        row("ico less 1 face", ico, MeshDamage.deleteFaces(ico, 0, 1))
        row("ico less 6 faces", ico, MeshDamage.deleteFaces(ico, 0, 6))
        row("cylinder no caps", cylinderMesh(16, 0.5, 1.0, caps = true), cylinderMesh(16, 0.5, 1.0, caps = false))
        println("=== candidate HARD holes ===")
        for (n in listOf(8, 16, 24, 40, 64)) {
            row("torus less $n faces", torus, MeshDamage.deleteFaces(torus, 0, n))
        }
        for (n in listOf(20, 40, 80)) {
            row("ico3 less $n faces", ico3, MeshDamage.deleteFaces(ico3, 0, n))
        }
        for (n in listOf(24, 48)) {
            row("capsule less $n faces", cap, MeshDamage.deleteFaces(cap, 0, n))
        }
        // a deliberately non-planar rim: displace alternate rim vertices along the loop normal
        run {
            val open = cylinderMesh(16, 0.5, 1.0, caps = false)
            val closed = cylinderMesh(16, 0.5, 1.0, caps = true)
            val loops = open.diagnose().boundaryLoops
            val pos = open.positions.copyOf()
            for (l in loops) for ((i, v) in l.vertices.withIndex()) if (i % 2 == 0) {
                pos[v*3] += l.normal.x * 0.35f; pos[v*3+1] += l.normal.y * 0.35f; pos[v*3+2] += l.normal.z * 0.35f
            }
            row("crowned cylinder rims (non-planar)", closed, TriMesh(pos, open.indices))
        }
    }
}
