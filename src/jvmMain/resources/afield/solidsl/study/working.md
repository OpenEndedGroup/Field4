# Working on a SolidSL study

## What the box holds

The study box holds Kotlin. Its last expression is the solid: a `SolidExpr`, or
`solid.shaded(glsl)` (a `SolidScene`) when the solid has GLSL shading of its own. Nothing is
computed when the Kotlin runs: `cuboid(4, 2, 1) - cylinder(3, 0.4)` builds a tree that *is* the
shape, and the study draws that tree by sphere tracing its distance field in a fragment shader on
the GPU of the window above the box. Every accepted edit is a revision; the window turns the current
one, and the box's output shows it from three sides.

`afield.lang.solidsl.*`, `afield.lang.solidsl.study.*` and `kotlin.math.*` are imported for you;
keep the import lines the starter has so the box also runs as ordinary Kotlin.

## Coordinates

- **z is up.** The camera orbits the solid's centre (the middle of its bounds) on a turntable.
- Primitives are centred on the origin. `cuboid(x, y, z)` takes full extents, not half.
- `cylinder(height, radius)` and `cone(height, radius)` run along z; `torus(R, r)` lies in the
  XY plane. `Profile.extruded(h)` runs from z = 0 to z = h (not centred). `Profile.revolved()`
  turns about z, the profile's x the radius and its y the height, at x ≥ 0.
- `turned(x, y, z)` is in degrees, applied x then y then z, about the origin: turn a part first,
  then move it with `at(x, y, z)`.

## Vocabulary

Solids: `sphere(r)`, `cuboid(x, y, z)` / `cuboid(s)`, `cylinder(h, r)` / `cylinder(h, r, rTop)`,
`cone(h, r)`, `torus(R, r)`, `gyroid(scale, thickness)` (unbounded: always intersect it with
something finite). Profiles: `Profile.circle(r)`, `Profile.rect(w, h)`, `Profile.star(n, outer,
inner)`, `Profile.polygon(listOf(P2(x, y), …))`, then `.offset(d)`, `.translated(x, y)`,
`.turned(deg)`, `.sized(s)`, `+ - and`, then `.extruded(h, twistDegrees = …, scaleTop = …)` or
`.revolved(deg)`. Transforms: `.at`, `.turned`, `.sized(s)` / `.sized(x, y, z)`,
`.mirrored(nx, ny, nz)`. Booleans: `a + b` (union), `a - b`, `a and b` (intersection),
`unionOf(list)`, `differenceOf(list)`, `intersectionOf(list)`, `smoothUnion(a, b, k)`,
`smoothDifference(a, b, k)`. Field operations: `.rounded(r)` grows the boundary outward by r,
`.shelled(t)` leaves a wall 2t thick.

## Idioms that hold

```kotlin
// A ring of twelve fins: build one, then copy it round with List and unionOf.
val fin = cuboid(0.08, 0.6, 1.2).at(0.0, 1.1, 0.0)
val fins = unionOf(List(12) { i -> fin.turned(0, 0, i * 30.0) })

// Smooth joins read as cast or grown; hard booleans read as machined.
val body = smoothUnion(sphere(0.9), cylinder(1.6, 0.35).at(0, 0, -0.8), k = 0.25)

// A lattice: a gyroid sheet trimmed by a finite shape.
val shell = sphere(1.0) and gyroid(6.0, 0.12)
```

- **One named `val` per part**, with a comment saying what it is, and the solid as the last line.
  A person reads the Kotlin; `knobs` (the typed numbers) are what `SetSolidNumbers` and the sweeps
  change, so a number typed once and reused through a `val` is one knob.
- **Repeat with `List(n) { i -> … }`** and `unionOf`: rings, spirals (`turned` and `at` both using
  `i`), stacks. Loops of a few dozen parts are fine; the shader grows with each copy, so prefer
  fewer, larger parts to hundreds of small ones.
- **`smoothUnion` / `smoothDifference` blend over `k`**; `k` about a tenth of the parts' size reads as
  a fillet. Smooth operations and `rounded` are field operations: they draw fine, but have no exact
  (Manifold) form — `DescribeSolidStudy(exact = true)` says whether a printable mesh is possible.
- **Check the march.** `DescribeSolidStudy` reports `stepScale` and `fieldNotes`: twist, taper,
  non-uniform scale and gyroids are not true distances, so the march is slowed down, and a scene with
  many of them can show speckled holes or a soft, smeared surface. Fewer such nodes, or a smaller
  twist, fixes it.

## Shading

`solid.shaded(glsl)` replaces the default lighting with GLSL of your own. The string defines

```glsl
vec3 shade(vec3 p, vec3 n, vec3 rd, float t)
```

returning the **linear** colour (the window encodes it for display) of the surface point `p`, with
unit normal `n`, seen along the unit ray `rd`, at time `t` seconds. It can call `sdScene(p)` (the
solid's distance), `softShadow(p, l)` (0..1, how lit `p` is from direction `l`), `occlusion(p, n)`
(0..1, darker in creases), and read `CENTRE` and `RADIUS`. Write it as a Kotlin raw string
(`"""…"""`); a `$` in GLSL must be written `${'$'}`. Shading is where colour, banding by height
(`p.z`), stripes, fresnel and metal go; geometry belongs in the solid. A shader the GPU refuses is a
failed check, with the compiler's message, and nothing is published. `background = 0xRRGGBB`
(sRGB) on `shaded` sets the colour behind the solid.

## Looking at it

- **Observe is the turntable**: a frame at t seconds is the camera at yaw 35 + 30t degrees, pitch 24,
  so seconds 0, 3, 6, 9 are four sides. Every accepted edit previews like that against its parent.
- **OrbitSolidStudy points the camera**: yaw 0 looks from −y (the front), 90 from +x, 180 from behind;
  pitch 90 looks straight down, negative from below; distance 2.2 frames the whole solid, 1.3 is a
  close-up of the part nearest the camera. `section` `x`, `y` or `z` cuts it open through the centre
  on the camera's side — the way to see a shell's wall, a bore, or what is inside a lattice. Look from
  above, below and in section before claiming a part is hollow, centred, or symmetric.
- `renderedBy` says what drew a picture. Without a window it is a CPU reference: the same solid with
  the default lighting, and your `shade()` is not drawn — say so rather than judging colour from it.

## The person

They see the window turning and the three views in the box's output, and they may type into the box.
`InspectSolidStudy` says when the editor holds an unchecked edit (`dirty`) and which line their
cursor is on (`personAt`); build on what they typed. Describe what you changed in their terms — what
the shape now does — and show it from the side that proves it.
