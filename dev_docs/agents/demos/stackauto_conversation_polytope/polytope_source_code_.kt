import afield.four.Renderer
import afield.four.modules.fromContext
import afield.stdlib.polytope.*

// A snub dodecahedron, each face with its own vertices, so the faces can fly apart whole.
val renderer: Renderer = fromContext()
val module = moduleHere()
val m = Mesh()
module += m named "polytope"
renderer += module

val camera = Camera()
camera.at = vec(0, 0.5, 2.9)
camera.target = vec(0, 0, 0)
m.p = camera.p
m.m = camera.m

val solid = canonicalConway("sD").polyhedron.triangulate(normals = NormalPolicy.Flat)
val mesh = solid.mesh
val P = mesh.positions; val N = mesh.normals!!; val I = mesh.indices
val centres = HashMap<Int, FloatArray>()
// Cancel internal triangulation edges; keep each original polygon's perimeter.
val boundaries = LinkedHashMap<Int, LinkedHashMap<String, Pair<Int, Int>>>()
fun pointKey(i: Int) = "${P[3*i]},${P[3*i+1]},${P[3*i+2]}"
for (t in 0 until mesh.triangleCount) {
    val face = solid.sourceFace(t).value
    val c = centres.getOrPut(face) { FloatArray(4) }
    val edges = boundaries.getOrPut(face) { LinkedHashMap() }
    for (k in 0..2) {
        val i = I[3 * t + k]
        c[0] += P[3 * i]; c[1] += P[3 * i + 1]; c[2] += P[3 * i + 2]; c[3] += 1f
        val j = I[3 * t + (k + 1) % 3]
        val a = pointKey(i); val b = pointKey(j)
        val key = if (a < b) "$a|$b" else "$b|$a"
        if (edges.containsKey(key)) edges.remove(key) else edges[key] = Pair(i, j)
    }
}
// Centre-to-perimeter fans give a polygon radius, not a circular mask.
val fanCount = boundaries.values.sumOf { it.size }
m.set(3 * fanCount, fanCount) {
    var t = 0
    for ((face, edges) in boundaries) {
        val c = centres.getValue(face)
        val cx = c[0] / c[3]; val cy = c[1] / c[3]; val cz = c[2] / c[3]
        for ((a, b) in edges.values) {
            for (k in 0..2) {
                if (k == 0) pos.add(cx, cy, cz) else {
                    val i = if (k == 1) a else b
                    pos.add(P[3*i], P[3*i+1], P[3*i+2])
                }
                nrm.add(N[3*a], N[3*a+1], N[3*a+2])
                cen.add(cx, cy, cz)
                fid.add(face.toFloat())
                faceRadius.add(if (k == 0) 0f else 1f)
            }
            tri.add(3*t, 3*t+1, 3*t+2)
            t++
        }
    }
}

// Time, for the animated explode slot; face_decay follows its travel.
val started = System.nanoTime()
m.push.bind("params") { vec((System.nanoTime() - started) / 1e9, 0, 0, 0) }

// The vertex shader's slot: where each face goes.
module.explode = """# Repeating 4.8-second phase; higher face centres lead the wave
input w
4.8 /
input y
0.222817 *
+
fract
# Fast attack: reach full extension in 0.384 seconds
dup
0.08 /
# Slow return: back home at phase 0.58, then rest
swap
-1 *
0.58 +
0.5 /
min
# Clamp and ease both ends so the faces rejoin cleanly
0 max
1 min
smooth
# Synchronized outward travel and shrink about each face centre
[0.65,0.25,0,0] *"""

// The fragment shader's slot: heat and polygon-shaped, ragged burn-through.
module.face_decay = """# Heat follows outward travel
input z
0.65 /
0 max
1 min
smooth
# Dark iron to incandescent orange
[1.355,0.23,-0.037,0] *
[0.045,0.05,0.055,0] +
# Host polygon radius: zero at centre, one at the real perimeter
input w
# Deep, stable tears in the remaining rim
input xy
13 *
fbm
0.42 *
+
0.015 +
# Eat most of the interior while far out; fully heal on return
input z
0.65 /
0.55 -
0.30 /
0 max
1 min
smooth
1.07 *
-
# Keep a narrow outer thread even at the deepest tears
input w
0.975 -
max
# Host discards alpha below 0.5
10 *
0.5 +
[0,0,0,1] *
+"""
