# Slug filled FLine prototype

The user guide and persisted starter are in
[Slug filled paths](../../../public/docs/slug_paths.md). The canonical lesson
**5e. Slug filled paths** is connected beneath its renderer in `metadata.edn`.
[code.kt](code.kt) mirrors that lesson for backdoor checks.

The runtime shader sources live in
[`src/jvmMain/resources/afield/slug`](../../../src/jvmMain/resources/afield/slug/).
`SlugShaders` loads these resources; the canonical lesson stores matching shader
sidecars for editing in Field. Shader-derived code retains the upstream
[MIT license](LICENSE-slughorn).

## Integration decisions

- `SlugPath` compiles an XY FLine through the pinned native helper and validates
  metrics, atlas dimensions, band lists and curve addresses before upload.
  Its words are exposed as a defensive copy.
- `SlugBuffer` owns one immutable upload, with replacement through a stable
  buffer-address supplier. Replacement retires the old allocation through
  Field's deferred GPU cleanup queue. Detach its mesh before closing.
- The push block is 96 bytes (one combined matrix), within Vulkan's minimum
  128-byte guarantee. Viewport size comes from the current target.
- The box uses straight-alpha color blending, source alpha factor ONE, and
  disabled depth writes. It releases its mesh and buffer through RunScope.

The subprocess is appropriate for code edits, not per-frame deformation. Font
shaping, strokes, JNI, shared atlas packing and VR multiview remain outside this
slice. Near-plane crossings and grazing-angle behavior are not certified.

See [VALIDATION.md](VALIDATION.md) for reproducible checks and their scope.
