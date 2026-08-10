// PLY is what photogrammetry and scanners emit, and the reason is per-vertex colour, which
// neither OBJ nor STL can carry. Four corners, two triangles, small enough to read.
// Change the six colour triples and re-run.
val dir = java.nio.file.Files.createTempDirectory("ply-demo").toFile()
val file = java.io.File(dir, "square.ply")
file.writeText(
    """
    ply
    format ascii 1.0
    element vertex 4
    property float x
    property float y
    property float z
    property uchar red
    property uchar green
    property uchar blue
    element face 2
    property list uchar int vertex_indices
    end_header
    0 0 0 255 40 40
    1 0 0 40 255 40
    1 1 0 60 60 255
    0 1 0 255 235 40
    3 0 1 2
    3 0 2 3
    """.trimIndent() + "\n"
)

// PlySupport.loadPly is parsing plus memAlloc: no renderer, no Vulkan, no Box context. Only
// applyToMesh (the GPU path) needs any of that.
val model = PlySupport.loadPly(file.path)
val b = model.buffers
println("vertices ${b.vertexCount}, indices ${b.indexCount}, colours ${b.hasColors}, " +
    "normals in the file ${b.hasNormals}, point cloud ${b.isPointCloud}")
println("packed as ${b.vertexData.capacity() / (b.vertexCount * 4)} floats per vertex: " +
    "[pos.xyz][normal.xyz][color.rgba] — ordinary host memory, which is why the bridge below")
println("is an unpack rather than a second parser")
println()

val mesh = model.toTriMesh()
println("as a TriMesh: ${mesh.summary()}")
println("colours survived: ${mesh.hasColors}; vertex 0 is ${mesh.color(0)}")
println("normals dropped: ${!mesh.hasNormals}")
println()
println("that last line is a decision, not an accident: loadPly writes (0, 0, 1) for every")
println("vertex of a file with no nx/ny/nz, which is a WRONG normal rather than a missing")
println("one — it would shade the whole model as though it faced the camera. toTriMesh drops")
println("them when the file had none, and the render bridges crease-shade instead.")

showMesh(
    mesh,
    view = MeshView(yawDegrees = 0.0, pitchDegrees = 0.0, width = 420, height = 315),
    title = "square.ply — per-vertex colours",
)
dir.deleteRecursively()
