# Slug filled paths

Slug renders a filled `FLine` as analytic curve coverage on the GPU. The path can
contain straight edges, cubic curves and holes. Its six-vertex bounding quad moves
with the camera; the fragment shader evaluates the actual outline within it.

Use this experimental path when you want a filled planar shape whose boundary
stays smooth as its size changes. It does not replace FLine strokes or triangulation.

## Start with the example

Build the optional helper from the repository root. This needs Git, a C++20
compiler and network access on the first build. The script fetches the pinned
Slughorn revision; it is not part of ordinary Field startup.

```diffstatic {data-lang=bash}
bash native/field-slug/build.sh
```

For a small self-contained demo, drag [slug_starter.edn](assets/slug_starter.edn)
into Field, launch **Slug renderer**, then **5e. Slug filled paths**. It embeds the
code and both shaders, so importing this single file needs no source sidecars.

For the full curriculum, open **Welcome → Starting points → canonical_graphics**,
launch its **window** box, then **5e. Slug filled paths**. The lesson draws a curved
outline with a square hole. Focus the renderer window and use WASD/arrow keys to
move, E/C to rise or fall, and R to reset the camera. There is no automatic orbit.

The lesson uses `"field-slug".file`. FileManager registers the helper relative to
Field's loaded installation/checkout; a document or project `files/field-slug` can
override it. This is the compiler executable; `SlugPath.compile` creates and
cleans up its own temporary input/output files.

The complete [box code](assets/canonical_graphics/628f3f96-42d0-48dd-93ea-ea4d9f03b865_code_.kt),
[vertex shader](assets/canonical_graphics/628f3f96-42d0-48dd-93ea-ea4d9f03b865_vertex_.glsl)
and [fragment shader](assets/canonical_graphics/628f3f96-42d0-48dd-93ea-ea4d9f03b865_fragment_.glsl)
are a starting point for your own box. Keep it connected beneath the renderer.

## Geometry and appearance

`SlugPath.compile` prepares immutable curve and band data using a small native
subprocess. Its tolerance is measured in the FLine's own coordinate units.
Smaller tolerance subdivides cubics more finely and increases preparation work.
The fill rule is **nonzero winding**: reverse an inner contour to make a hole.
Open contours are closed for filling. All coordinates, including cubic controls,
must be finite and lie in the XY plane (`z = 0`).

```diffstatic {data-lang=kotlin}
val prepared = SlugPath.compile(path, "field-slug".file,
    tolerance = 1e-4f)
val data = SlugBuffer(renderer, prepared)
```

Camera motion and tint changes leave this data alone. To change geometry, prepare
a fresh `SlugPath`, then call `data.replace(newPath)` on the render thread. The
stable address supplier starts using the new allocation; Field retires the previous
one after pending frames finish. Preparation starts a process, so keep it out of
the frame loop. For frequent edits, prepare away from rendering and publish the
finished result on the render thread.

The demo removes its consuming mesh before closing the buffer when the box stops
or runs again. Follow that ordering in your own code. `close()` is idempotent,
but a closed buffer cannot be bound or replaced.

## Shading and transparency

Edit `shade()` in the fragment shader to change the fill. Its input is the path's
local XY coordinate. Coverage controls alpha independently of the procedural color.
The demo uses straight-alpha RGB blending and `ONE` for the source alpha factor,
so a half-transparent interior has alpha 0.5 rather than 0.25. Depth writes are
disabled: transparent layers still need an appropriate drawing order.

The shared push block occupies 96 bytes: a combined model/view/projection matrix,
buffer address, current framebuffer dimensions and tint. Framebuffer dimensions
must follow the render target, particularly when resizing or rendering offscreen.

## Limits

This is one shape per atlas, with 512-wide packed textures. It is a Linux/macOS
native preparation prototype, not a Windows distribution or a font shaping API.
Strokes, glyph loading, multiview VR and incremental atlas packing are outside this
slice. Near-plane crossings and nearly edge-on planes need further investigation;
keep the example's filled plane comfortably in front of the camera.

The automated GPU regression uses actual render-target readback to check holes,
edge coverage, alpha, scale changes and atlas replacement. Decoder and shader tests
also run without a Vulkan device. These checks do not certify every projection,
HDR composite or extreme curve configuration.
