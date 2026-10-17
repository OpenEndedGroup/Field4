// A snub dodecahedron, each face with its own vertices, so the faces can fly apart whole.
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
val positions = mesh.positions
val normals = mesh.normals!!
val indices = mesh.indices
// Per face: x/y/z sums and the number of triangle corners contributing to them.
val centres = HashMap<Int, FloatArray>()
for (t in 0 until mesh.triangleCount) {
    val c = centres.getOrPut(solid.sourceFace(t).value) { FloatArray(4) }
    for (k in 0..2) {
        val i = indices[3 * t + k]
        c[0] += positions[3 * i]
        c[1] += positions[3 * i + 1]
        c[2] += positions[3 * i + 2]
        c[3] += 1f
    }
}
m.set(3 * mesh.triangleCount, mesh.triangleCount) {
    for (t in 0 until mesh.triangleCount) {
        val face = solid.sourceFace(t).value
        val c = centres.getValue(face)
        for (k in 0..2) {
            val i = indices[3 * t + k]
            pos.add(positions[3 * i], positions[3 * i + 1], positions[3 * i + 2])
            nrm.add(normals[3 * i], normals[3 * i + 1], normals[3 * i + 2])
            cen.add(c[0] / c[3], c[1] / c[3], c[2] / c[3])
            fid.add(face.toFloat())
        }
        tri.add(3 * t, 3 * t + 1, 3 * t + 2)
    }
}

// Time, for both slots.
val started = System.nanoTime()
m.params = { vec((System.nanoTime() - started) / 1e9, 0, 0, 0) }

// The vertex shader's slot: where each face goes. Talk to it in this box's AI4 chat.
module.explode =
    """
# (start) every face where it belongs: pushed nowhere, shrunk not at all
[0,0,0,0]
"""

// The fragment shader's slot: each face's colour, and where it burns through.
module.face_decay =
    """
# (start) one warm grey on every face, nothing burned
[0.75,0.72,0.68,1]
"""
