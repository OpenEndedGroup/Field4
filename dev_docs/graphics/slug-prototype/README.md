# Slug FLine rendering prototype

This draft integrates the earlier Slug prototype into Field's repository layout.
It supplies a native path compiler, an FLine adapter, and a Vulkan shader pair.
It is not yet a verified Field rendering integration.

## Try it

Build the helper using [its instructions](../../../native/field-slug/README.md)
and compile Field. Create a new box beneath an existing renderer box, following
`public/docs/assets/canonical_graphics/LESSONS.md`.
Set the box's vertex and fragment properties to [vertex.glsl](vertex.glsl) and
[fragment.glsl](fragment.glsl), and its code to [code.kt](code.kt). Launch it.

The script uploads the Slughorn curve/band data to one addressable buffer and
draws six generated vertices. Camera motion and procedural fill changes leave
the atlas data unchanged. Edit the FLine and re-run the box to prepare new geometry.

The shader uses a 160-byte push block: two matrices, a data address, framebuffer
dimensions and a tint. Bounds expand using the MVP and framebuffer size; curve
coordinates interpolate with perspective correction. The separate shade()
function is the intended attachment point for Field's shader DSL.

Output uses straight alpha. Configure source-alpha / one-minus-source-alpha RGB
blending, and one / one-minus-source-alpha alpha blending. For a premultiplied
target, multiply RGB by output alpha and use the corresponding blend factors.
The initial example uses an opaque tint. Multi-layer Porter-Duff compositing
is a later step.

## Scope

Supported: filled XY FLine paths, multiple contours using nonzero winding, one
shape per atlas, and 512-wide Slughorn atlases. Nonzero z coordinates are rejected
rather than silently flattened; place the resulting plane in 3D through its
model/view transform.

The first slice uses a subprocess per preparation call. It is for code edits,
not a per-frame geometry pipeline. FreeType glyph loading, shaping, strokes,
composite rendering, direct Forth shader words, incremental atlas packing,
JNI, automatic GPU-safe retirement, and VR multiview wiring remain future work.

The example follows Field's module/mesh idiom, but resource ownership on re-run
still needs a real runtime check. Do not treat its upload and camera script as
evidence that the Vulkan integration has already run.

## Before merging

1. Run ./gradlew compileKotlinJvm, compile the shaders, and run the actual Vulkan box.
2. Check holes, tight cubics, subpixel sizes, perspective, viewport resizing and alpha
   over an HDR target. Near-plane and nearly edge-on behavior need particular attention.
3. Establish resource cleanup on box re-run and GPU-safe retirement for atlas replacement.
4. Add Kotlin coverage for FLine conversion and packed-data validation.

A subsequent SlugDrawable should separate geometry and appearance revisions,
prepare edits off the render thread, and publish them on the render thread.
Do not rely solely on FLine.rev: direct control-point/vector mutations can bypass it.
Keep reusable glyph atlases independent of edited paths and retain Field's existing
text shaping/layout when adding FreeType.

## Sources and verification

- [Slughorn](https://github.com/AlphaPixel/slughorn), pinned to
  409c4a2e68cafcb49e11ea0edd0145d4e255cbea.
- [Lengyel's dynamic dilation description](https://terathon.com/blog/decade-slug.html).
- Shader core adapted from Slughorn's GLFW example; [MIT notice](LICENSE-slughorn).
- [Native preparation validation and outstanding checks](VALIDATION.md).
