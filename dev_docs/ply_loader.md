# PLY File Loader

The PLY loader is documented via KDoc on `object PlySupport` in
`src/jvmMain/kotlin/afield/four/modules/simplePLYSupport.kt`. See there for:

- Supported PLY elements / properties / data types
- `loadPly(path)`, `PlyModel.applyToMesh` / `applyToMeshAsQuads`, and the
  `plyModelHere` / `plyPointCloudHere` box macros
- The packed vertex layout (`[pos.xyz][normal.xyz][color.rgba]`, 10 floats/vertex)
  and the bundled GLSL shaders (`plyVertexShader`, `plyFragmentShader`,
  `plyPointCloudVertexShader`, `plyPointCloudFragmentShader`)

Reference: [PLY File Format Specification](http://paulbourke.net/dataformats/ply/)
