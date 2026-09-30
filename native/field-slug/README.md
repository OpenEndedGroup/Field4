# Field Slug prototype

This optional C++20 helper compiles one filled path into a Slughorn atlas. It is
invoked on geometry edits by `afield.graphics.slug.SlugPath`, leaving placement and
shading independent of atlas construction. No native helper is built or loaded
by Field's ordinary startup.

The prototype targets Slughorn commit
`409c4a2e68cafcb49e11ea0edd0145d4e255cbea`.
It requires a little-endian host and a C++20 compiler; the build script targets
Linux/macOS. Windows binary stdout/build support is not part of this slice.

## Build and verify

From the Field repository root:

```bash
bash native/field-slug/build.sh
uv run python native/field-slug/tests/check_prepare.py native/field-slug/build/field-slug
./gradlew compileKotlinJvm
```

With no argument, build.sh fetches the pinned source into its ignored build directory.
An optional source checkout argument must be clean and at that exact revision.
The helper has no graphics or font dependencies. To inspect it directly:

```bash
printf '%s\n' 'T 0.0001 M -1 -1 L 1 -1 L 1 1 L -1 1 Z' |
    native/field-slug/build/field-slug > /tmp/slug.bin
```

Input is a command stream: T sets a positive tolerance, M moves, L adds a line,
Q a quadratic, C a cubic, and Z closes a contour. Open fill contours are closed
implicitly. Output is a versioned little-endian word buffer containing a 96-byte
header, RGBA32F curve data and RG16UI band data. The Kotlin decoder validates
the format before upload.

## Canvas and live editing

Canvas authors paths and commits them to an Atlas. Atlas::build() freezes geometry.
CompositeShape layer transforms, colors and references to existing shapes remain
mutable; new composites can be registered afterward. New path geometry cannot:
addShape() silently returns once the atlas is built. Canvas::textGlyph() explicitly
warns against per-frame use.

Some Canvas transform calls bake coordinates during authoring. Those differ from
changing a finished layer transform. For edited paths, build a fresh small atlas.
Cache fonts and reusable shapes separately. Deformation changes band membership,
ordering, metrics and bounds; replacing curve texels alone is insufficient.

The helper explicitly sets the cubic tolerance in source-coordinate units.
Slughorn's current default, TOLERANCE_EXACT = max(), produces a coarse
two-quadratic approximation; the name does not mean exact cubic rendering.

## Demo and status

The [public guide](../../public/docs/slug_paths.md) links the persisted starter.
The [validation record](../../dev_docs/graphics/slug-prototype/VALIDATION.md)
separates native preparation, decoder/shader tests and real GPU checks.
