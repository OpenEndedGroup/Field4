// Field has four things that read geometry off disk — stdlib's OBJ/STL reader, PlySupport,
// the Assimp-backed glTF loader, and the Fabricate PBR path — and one renderer that can
// rasterise a mesh with no GPU. meshFromAnyFile is the join: it dispatches by extension and
// always hands back a TriMesh, which is the one type that renderer takes.
//
// Change this to any mesh file you have. .glb .gltf .fbx .dae .3ds all go through Assimp,
// .ply through PlySupport, .obj and .stl through the dependency-free stdlib reader.
val path = "public/docs/assets/fabricate_demo/ToyCar.glb"

val file = java.io.File(path)
if (!file.isFile) {
    println("no such file: ${file.absolutePath}")
    println("point `path` at a mesh file of your own — this default is relative to the")
    println("repository root, so it resolves when Field is started from there.")
} else {
    println("${file.name}: ${file.length()} bytes on disk")

    val mesh = meshFromAnyFile(path)
    println(mesh.summary())
    println()

    // Read those numbers rather than trusting the picture:
    //  - 108,936 triangles over three mesh/material buckets (ToyCar, Fabric, Glass), each
    //    baked through its node's world transform, so the model arrives assembled.
    //  - extent 0.073 x 0.029 x 0.074 — the file is in metres and it is a 7 cm toy.
    //    MeshView.distance is in bounding-sphere radii, so the camera frames it anyway.
    //  - chi = 1909, open, 2782 boundary loops. NOT a defect: an artist's model is a shell,
    //    and every UV seam and hard edge is a real cut in the surface. Only reach for
    //    repaired() if you actually need watertightness (a volume, a boolean, a printer).
    val b = mesh.bounds()
    println("extent %.4f x %.4f x %.4f metres".format(
        b.max.x - b.min.x, b.max.y - b.min.y, b.max.z - b.min.z))
    println("watertight ${mesh.isWatertight()} — expect false, and see the comment above")
    println()
    println("try: wireframe = true below, and watch 108,936 triangles become a grey mat —")
    println("     which is why the crease set, not the wireframe, is the readable drawing")

    showMesh(
        mesh,
        view = MeshView(width = 560, height = 420, wireframe = false),
        title = file.name,
    )
}
