# SolidCraft: build a part from code

[Parametric CAD (SolidCraft)](solidcraft.md) opens and edits a saved design. `solidCraft { }` builds
one instead, from sketches and features written in a box: lines, arcs, circles, splines and 3D
curves, then extrude, revolve, sweep, loft and pipe, and the surface features that make open
sheets — surface sweep, loft, revolve and boundary patch. The result is a real SolidCraft design,
so its parameters stay editable and it exports to STEP or STL like any other.

It needs the optional SolidCraft bridge; [Install the optional bridge](solidcraft.md#install-the-optional-bridge)
covers the one-time build.

## Three parts

The box below builds three designs and shows their meshes side by side. The first sweeps a square
up a straight path while a guide rail runs from 4 to 12 mm off the path; the rail's default `SCALE` fit grows the
whole square evenly with it, from 8 mm across to 24. The second draws a trefoil knot as a Field
`FLine`, turns it into a 3D wire with `wire(knot)`, and sweeps a circle along it into a knotted tube.
The sweep caps both ends, so where the knot returns to its start the tube meets itself at a seam
rather than fusing into a ring: the facts read genus 0, not the torus's genus 1. For a plain ring,
sweep two halves and `JOIN` the second; the join removes the caps where they touch. In the
SolidCraft version Field pins, that join works on arc paths but fails on a knot's freeform halves. `planeSquareTo(knot)` puts the circle's sketch square to the knot where it starts. The third is a surface: a loft through three spline
sections, which leaves an open, wavy sheet rather than a solid.

```diffstatic_runner {data-lang=kotlin screenshot="assets/solidcraft/building-three-parts.png" alt="An output panel of three grey triangle meshes side by side, each with a fact line beneath it. Left, 'sweep with a flaring rail': a square-sectioned solid that widens evenly from 8 to 24 mm as it rises 60 mm, extent 24 × 24 × 60, volume 16640. Middle, 'trefoil knot from an FLine': a round tube tied in a three-lobed knot, passing over and under itself, its two capped ends meeting at a seam at the bottom; watertight, genus 0, volume 4507.8. Right, 'surface loft': a single wavy open sheet." wait="4" capture="box" open-outputs="true" padding="20" requires="solidcraft" name="building-three-parts" embed=true}
import afield.geometry.solid.solidcraft.solidCraft
import afield.geometry.path.FLine
import afield.geometry.mesh.showMeshGrid
import afield.geometry.mesh.weld
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

val flared = solidCraft("Flared") {
    val section = sketch("XY") { rectangle(-4.0, -4.0, 4.0, 4.0) }
    val path = sketch("XZ") { line(0.0, 0.0, 0.0, 60.0) }
    val rail = sketch("XZ") { line(4.0, 0.0, 12.0, 60.0) }
    sweep(section, path, rails = [rail])
}

val knot = FLine().moveTo(0, -8, 0)
for (i in 1..240) {
    val t = i / 240.0 * 2 * PI
    knot.lineTo(8 * (sin(t) + 2 * sin(2 * t)), 8 * (cos(t) - 2 * cos(2 * t)), -8 * sin(3 * t))
}
val tube = solidCraft("Trefoil") {
    val section = sketch(planeSquareTo(knot)) { circle(0.0, 0.0, 2.5) }
    sweep(section, sketch("XY") { wire(knot, spacing = 0.5f) })
}

val sheet = solidCraft("Sheet") {
    val a = sketch("XY") { spline(-20.0, 0.0, 0.0, 6.0, 20.0, 0.0) }
    val b = sketch(offsetPlane("XY", "20")) { spline(-20.0, 0.0, 0.0, -6.0, 20.0, 0.0) }
    val c = sketch(offsetPlane("XY", "40")) { spline(-20.0, 0.0, 0.0, 6.0, 20.0, 0.0) }
    surfaceLoft([a, b, c])
}

fun mesh(design: afield.geometry.solid.solidcraft.SolidCraftBuilder) =
    design.open().use { it.mesh(tolerance = 0.05).toTriMesh().weld() }

showMeshGrid(
    "sweep with a flaring rail" to mesh(flared),
    "trefoil knot from an FLine" to mesh(tube),
    "surface loft" to mesh(sheet),
    columns = 3,
)
```

Each `sketch` returns a handle that features take: `sweep(section, path)` sweeps the closed
profiles of `section` along every curve of `path`. Drawing calls share endpoints that coincide,
so consecutive lines and splines join into one chain. Lengths and angles are SolidCraft
expressions — `"20"` in the design's units, `"90 deg"`, or a formula over `param`s — and
`open()` hands back a [SolidCraftDesign](solidcraft.md#use-it-in-a-box): with
`param("twist", "90 deg")` and `sweep(section, path, twist = "twist")`,
`setParameter("twist", "30 deg")` re-evaluates the part.

## What a sweep does with its profile

A sweep keeps the profile at the angle it was drawn to the start of the path. Start the path
square to the profile for a tube of the drawn size; for a path from an `FLine`, draw the profile on
`planeSquareTo(line)`. A path that leaves it 27° off square gives a
section about `cos 27°` of the drawn one all the way along.

Guide rails reshape the profile's far side to follow the rail: `railFit = SCALE` (the default)
grows the whole section evenly, `STRETCH` only across to the rail. The rails then decide the
profile's turn and size, so a railed sweep takes no `twist` or `scale`.

## Surfaces

The surface features make open sheets, measured by area rather than volume:
`surfaceSweep` (with rails, as `sweep`), `surfaceLoft` (`start`/`end` continuity `G0` to `G3`),
`surfaceRevolve`, and `surfaceBoundary`. `thicken` turns a sheet into a solid.

!!! note "A boundary patch bridges two curves"
    In the SolidCraft version Field pins (v0.15.0), `surfaceBoundary` builds a patch between two
    curves; three or four report "not supported yet". Draw both curves in the same direction:
    the patch joins start to start, so curves running opposite ways fold it over itself.

Any SolidCraft feature the builder does not name — shell, draft, chamfer, pattern, and the rest —
can be added by its JSON fields with `feature("shell", JSONObject()...)`.
