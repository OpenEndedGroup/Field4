# PLY File Loader

**If you are here because you have a mesh file and want to see it in Field, you want
[`public/docs/geometry_from_disk.md`](../public/docs/geometry_from_disk.md) instead** — the route from
any format to pixels, with worked examples and a measured account of what each format loses. This
page is the developer note for the PLY loader specifically.

## Where it lives, and what it produces

`src/jvmMain/kotlin/afield/four/modules/simplePLYSupport.kt` — `object PlySupport`, ~1170 lines, no
dependency beyond LWJGL's `memAlloc` and (for the point-cloud quad path) an HNSW index.

`loadPly(path)` returns a `PlyModel` whose `buffers: PlyMeshBuffers` hold **off-heap host memory**:
a `ByteBuffer` of `[pos.xyz][normal.xyz][color.rgba]` — ten floats, 40 bytes per vertex — and an
`IntBuffer` of triangle indices (`null` for a point cloud). Two things follow from that and are easy
to get wrong:

- **It needs no renderer.** `loadPly` is parsing plus `memAlloc`; it runs in a plain function, a test
  or a headless script. Only `applyToMesh` / `applyToMeshAsQuads` touch Vulkan.
- **The caller owns the memory.** Those buffers are not garbage-collected. The GPU path frees them
  after upload; anything else must `memFree` them or leak 40 bytes per vertex.

## The two ways out

**To the GPU**, which is what the file was written for: the `plyModelHere` / `plyPointCloudHere` box
macros build a module wired to the four bundled shaders (`plyVertexShader`, `plyFragmentShader`,
`plyPointCloudVertexShader`, `plyPointCloudFragmentShader`) and hand you the loaded model.

```kotlin
val (module, model) = plyModelHere("model.ply") { /* optional Parameters */ }
```

**To the CPU**, added 2026-08-10 in `afield/four/modules/MeshFromDisk.kt`:
`PlyModel.toTriMesh()` unpacks the ten floats into a `TriMesh`, so everything in
[the mesh family](MESH_GEOMETRY.md) applies — `showMesh`, `renderPng`, topology, welding, decimation,
OBJ/STL export. `meshFromAnyFile(path)` does the load, the bridge and the `memFree` in one call.

```kotlin
showMesh("scan.ply")                       // straight to a rendered output area
val m = meshFromAnyFile("scan.ply")        // or keep the mesh
```

Two defaulting decisions the bridge makes, because the loader's own defaults are lossy in a way that
shows:

- A file with **no** `nx`/`ny`/`nz` gets `(0, 0, 1)` written for every vertex by `loadPly`. That is a
  *wrong* normal, not a missing one, and it shades the whole model as though it faced the camera.
  `toTriMesh()` drops normals when `hasNormals` is false, and the render bridges crease-shade instead.
- A file with no colour gets white for every vertex. An all-white colour array is dropped, so the
  renderer's own shading shows through rather than being overridden with flat white.

## What the parser handles

Recorded here rather than only in KDoc, because "does it read *my* PLY" is the question this page gets
asked.

- **Formats:** ASCII, binary little-endian, binary big-endian. Detected from the header.
- **Elements:** `vertex` (required) and `face` (optional — its absence means a point cloud, reported
  as `PlyMeshBuffers.isPointCloud`). Other elements are skipped.
- **Vertex properties:** `x`/`y`/`z` required; `nx`/`ny`/`nz` optional; `red`/`green`/`blue` and the
  `diffuse_*` aliases optional, `uchar` 0–255 normalised to 0–1 and float values passed through;
  `alpha`/`diffuse_alpha` optional, defaulting to 1. Property order is free. List properties on the
  *vertex* element are skipped.
- **Faces:** `list … vertex_indices`, fan-triangulated, so quads and n-gons load.
- **Scalar types:** `char`/`int8`, `uchar`/`uint8`, `short`/`int16`, `ushort`/`uint16`, `int`/`int32`,
  `uint`/`uint32`, `float`/`float32`, `double`/`float64` (see the `readValue` type switch).

Not handled: comments carrying camera or transform metadata, `element edge`, custom per-face
properties (including per-face colour), and the ASCII/binary hybrids some tools emit.

## Tests

`src/jvmMain/kotlin/afield/four/modules/PlyLoaderUnitTest.kt` and
`src/jvmMain/kotlin/afield/examples/four/PlyLoaderTest.kt` cover the parser.
`src/jvmTest/kotlin/afield/stdlib/MeshFromDiskTest.kt` covers the `TriMesh` bridge — the ten-float
layout, colour survival, normal dropping, and the point-cloud refusal — and every number the public
page quotes.

Reference: [PLY File Format Specification](http://paulbourke.net/dataformats/ply/)
