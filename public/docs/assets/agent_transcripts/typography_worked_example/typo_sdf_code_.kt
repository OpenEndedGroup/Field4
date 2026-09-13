// toSdf builds a 2D signed-distance grid over the layout, samples it bilinearly and extrudes
// it in z with the exact prism formula. Negative inside the ink, per mesh_sdf.kt's
// convention. From there letters are just another ScalarField3D, which is the point.
val layout = layoutText("SDF", typeface("SansSerif", 170.0))
val sdf = layout.toSdf(depth = 0.24, gridResolution = 340)
val grid = sdf.distanceField
println("grid ${grid.nx}x${grid.ny} over ${"%.0f".format(grid.bounds.w)}x${"%.0f".format(grid.bounds.h)} units; " +
    "deepest inside the ink ${"%.1f".format(grid.deepestInside())} units")

val marched = sdf.march(resolution = 130)
println("marchingCubes: ${marched.triangleCount} triangles, genus ${marched.genus()}")

// And now the composition that having letters in this family buys you.
val melted = marchingCubes(sdfSmoothUnion(sdf.field, sdfSphere(0.6, vec(0.0, 0.0, 0.0)), 0.18), sdf.bounds, 130)
val hollow = marchingCubes(sdf.field.shell(0.02), sdf.bounds, 150)
val wrung = marchingCubes(sdf.field.twisted(0.5), sdf.bounds, 130)
println("smooth union with a sphere: ${melted.triangleCount} triangles; " +
    "shelled: ${hollow.triangleCount}; twisted: ${wrung.triangleCount}")

displayOpen("sdf")
showImg(
    grid.toImg(), channel = "sdf",
    caption = "the 2D signed distance field — mid grey is the outline"
)
showMeshGrid(
    "marchingCubes on the letters" to marched,
    "sdfSmoothUnion with a sphere" to melted,
    "field.shell(0.02)" to hollow,
    "field.twisted(0.5)" to wrung,
    columns = 2, cellWidth = 400,
    view = MeshView(yawDegrees = 18.0, pitchDegrees = 12.0, distance = 2.6)
)
