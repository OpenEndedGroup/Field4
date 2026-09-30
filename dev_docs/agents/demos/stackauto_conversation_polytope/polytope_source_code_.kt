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

val solid = canonicalConway("sD").polyhedron
val topology = solid.topology
val xyz = solid.realization!!.vertexCoordinates
val centres = solid.faceCentroids
// Centre-to-perimeter fans give a polygon radius, not a circular mask.
val fanCount = topology.faces.sumOf { topology.faceCycle(it.id).size }
m.set(3 * fanCount, fanCount) {
    var t = 0
    topology.faces.forEachIndexed { f, face ->
        val c = centres[f]
        val n = solid.faceNormal(face.id)
        val ring = topology.faceCycle(face.id).map { xyz[topology.vertexMap.getValue(it)] }
        for (k in ring.indices) {
            for ((p, r) in listOf(c to 0f, ring[k] to 1f, ring[(k + 1) % ring.size] to 1f)) {
                pos.add(p.x, p.y, p.z); nrm.add(n.x, n.y, n.z); cen.add(c.x, c.y, c.z)
                fid.add(face.id.value.toFloat()); faceRadius.add(r)
            }
            tri.add(3 * t, 3 * t + 1, 3 * t + 2); t++
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
