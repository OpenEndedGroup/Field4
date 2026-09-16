import afield.examples.chain.PhotoToSolidChain

// toReliefMesh is the hop that did not exist until this page: toHeightfieldMesh returns a
// *disc* — one boundary loop, chi = 1 — and Manifold refuses an open sheet.
val mount = PhotoToSolidChain.mount()
val relief = mount.relief
val topo = relief.topology()
println("relief ${relief.vertexCount} vertices, ${relief.triangleCount} triangles")
println("watertight=${topo.isWatertight} manifold=${topo.isManifold} chi=${topo.eulerCharacteristic}")
println("boundary loops=${topo.boundaryLoops.size} — a plaque has none, a heightfield has one")
println("volume ${"%.6f".format(relief.volume())}, and Manifold agrees to the last digit")

// The CPU rasteriser is AWT-free (PngEncoder), so it is safe inside a live box on macOS.
// Three angles, because a relief read from one is a picture and read from three is a shape.
fun view(yaw: Double, pitch: Double) =
    imgFromBytes(relief.renderPng(MeshView(yawDegrees = yaw, pitchDegrees = pitch, width = 760, height = 560)))

showImgGrid(
    "from above — the room, inside out" to view(0.0, 72.0),
    "three-quarter" to view(34.0, 30.0),
    "along the rim — the base slab" to view(88.0, 8.0),
    columns = 3,
    cellSide = 460,
)
