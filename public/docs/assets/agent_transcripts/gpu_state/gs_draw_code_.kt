var drawModule = moduleHere { primitiveType = triangles }
var quads = Mesh()
drawModule += "quads" to quads
renderer += "gpu-state-draw" to drawModule

val N = 40000

// The same parity question the sim box asked, asked the same way and from the same source.
// Read the OTHER half and the picture is one frame stale; ask a private counter instead and
// the two boxes disagree whenever their loops start in different frames.
quads.state = { if (renderer.frameCounter % 2L == 0L) gsStateA else gsStateB }
quads.heat  = { gsHeat }
quads.aspectRatio = { window.width / window.height.toFloat() }

// Six vertices per particle with an empty writer: the vertex shader synthesizes each quad's
// corners from gl_VertexIndex, so there is nothing per-particle for the CPU to do.
quads.set(N * 2 * 3, 0) {}

println("draw launched")
