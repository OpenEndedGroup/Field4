// A datum drag on a 200-part scene: each slider step reshapes three parts, moves five rigidly and leaves the
// other 192 alone. `three-drag-bench.js` times every step from the slider update to the redrawn view.
import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.icosphereMesh

val benchParts = 200
val benchBase = icosphereMesh(3, 0.4)
fun benchPlace(i: Int) = Triple((i % 20) * 1.0f, (i / 20) * 1.0f, 0f)
val benchStatic = (0 until benchParts).map { i -> val (x, y, z) = benchPlace(i); benchBase.translated(x, y, z) }
fun benchStretched(i: Int, t: Double): TriMesh {
    val (x, y, z) = benchPlace(i); val s = (1.0 + t).toFloat()
    val p = benchBase.positions.copyOf(); for (k in 2 until p.size step 3) p[k] *= s
    return TriMesh(p, benchBase.indices).translated(x, y, z)
}
fun benchMoved(i: Int, t: Double): TriMesh {
    val (x, y, z) = benchPlace(i); val a = t * 3.0; val c = Math.cos(a).toFloat(); val s = Math.sin(a).toFloat()
    val p = benchBase.positions.copyOf()
    for (k in 0 until p.size step 3) { val px = p[k]; val py = p[k + 1]; p[k] = c * px - s * py; p[k + 1] = s * px + c * py }
    return TriMesh(p, benchBase.indices).translated(x, y, z + (t * 2).toFloat())
}
var benchFrame = 0
fun benchScene(t: Double) {
    three(name = "bench", width = 900, height = 560) {
        for (i in 0 until benchParts) {
            val m = when (i) { in 0..2 -> benchStretched(i, t); in 3..7 -> benchMoved(i, t); else -> benchStatic[i] }
            mesh(m, id = "p$i", wireframe = false)
        }
        camera(yaw = 20.0, pitch = 35.0)
        note("frame $benchFrame")
    }
}
benchScene(0.0)
slider("datum", 0.0, 1.0, 0.001, 0.0) { t -> benchFrame++; benchScene(t) }
