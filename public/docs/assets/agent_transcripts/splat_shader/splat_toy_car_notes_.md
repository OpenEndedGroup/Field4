# Scanlines on a model

The demo's scanlines over a mesh rather than a cloud. `SplatSet.fromGltf` reads the Khronos
ToyCar sample committed in this repository and puts one flat disc on every vertex, facing along
its normal; `fitted()` brings it to about a unit across so the stripes are the right size.

The path is relative to the repository root. If Field was started somewhere else, point `path`
at a `.glb` of your own.
