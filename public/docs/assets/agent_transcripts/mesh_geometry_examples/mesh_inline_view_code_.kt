// Every other box here pushes a picture to its output on purpose, with showMeshGrid. This one
// does not: it just *ends on a TriMesh*, and the value visualizer draws it. That is the whole
// point — a mesh is a value you can look at, the way a number or an Img is.
//
// What the card shows: the mesh through the same CPU rasterizer the docs gallery uses, from a
// fixed three-quarter view, with a depth-tested wireframe over two-light lambert shading; and
// beside it the facts that decide whether a mesh is usable — counts, whether it is closed and
// manifold, chi and genus, area, signed volume, and the bounding box. Back faces are culled as
// a GPU would cull them, so an OPEN mesh wound inside-out reads as holes. A closed one wound
// inside-out looks fine and reports a NEGATIVE volume instead; try `.flipped()` below.
//
// Above 40k triangles the picture is drawn from a decimation, and says so, while the numbers
// stay the real mesh's.

// Build something worth looking at: a sphere with a hole drilled through it, so the card has a
// genus to report, then smooth it so the drill rim is not a hard edge.
val drilled = marchingCubes(
    sdfSubtract(sdfSphere(0.8), sdfCylinder(0.32, 2.0)),
    boundsAround(radius = 1.0),
    48,
).taubinSmooth(12)

println(drilled.summary())
println("the card below reports the same numbers; the drill added a handle, so genus is 1")
println()
println("try: replace the last line with `drilled.flipped()` — the picture will look identical")
println("     and the volume will go negative, which is the only thing that tells you")

// The last expression is the value the box shows. No display(), no showMesh, no lines().
drilled
