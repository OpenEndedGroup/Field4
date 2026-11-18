# Optional SolidCraft FFM trial

Field keeps its existing Manifold binding. This trial adds a separate, optional path to SolidCraft's
document and B-rep engine at pinned upstream release v0.44.1 (commit `e151d5ea6866fb055e011379153fb1cae0566f22`;
the pin lives in `tools/build_solidcraft_native.py`).
Ordinary `compileKotlinJvm`, `check`, and Field startup do not download or load SolidCraft.

## Try it

Requires Rust/Cargo and Field's normal JDK. From this checkout:

```sh
./gradlew smokeSolidCraft
```

This explicitly clones the pinned SolidCraft revision into ignored `build/solidcraft-src`, stages
`native/field-solidcraft` inside that workspace, compiles a local dynamic library into
`build/native/solidcraft`, and runs an in-process JVM smoke. The smoke opens a saved design,
measures and tessellates its body, edits a parameter, exports STEP and binary STL, saves and
reopens the design, and checks that a failed edit preserves the previous model.

The adapter must build *inside* the SolidCraft workspace. That repository patches upstream truck
crates at the workspace root; a Cargo Git dependency outside the workspace would not inherit
those patches. An existing source checkout at another revision (an earlier pin) is moved to this one; the build
refuses only if it has local edits to upstream files. Cargo.lock is restored first, since building
the staged bridge rewrites it.

## Kotlin API

```kotlin
import afield.geometry.solid.solidcraft.SolidCraftDesign
import afield.geometry.solid.solidcraft.SolidCraftLibrary

if (SolidCraftLibrary.isAvailable) {
    SolidCraftDesign.open(java.nio.file.Path.of("part.solidcraft")).use { part ->
        part.setParameter("width", "50 mm")
        val info = part.summary()             // name, body volumes, bounds, topology, recomputation counts
        val mesh = part.mesh(0, 0.05)          // double xyz, triangle indices, B-rep face IDs
        val fieldMesh = mesh.toTriMesh()       // display copy in Field's float mesh format
        val step = part.exportStep()
        val stl = part.exportStl()
        val editedDesign = part.saveDesign()
    }
}
```

The bridge can also be selected with `-Dafield.solidcraft.library=/absolute/path/to/library`
or `AFIELD_SOLIDCRAFT_LIBRARY`. `SolidCraftLibrary.describe()` reports the selected path or
installation hint. Native loading is lazy; calling the API with no library produces a clear
error. Design, mesh, and byte buffers have explicit ownership on the Rust side. Kotlin copies
mesh and export buffers before releasing the native handles. A design owns its evaluator and
must be closed. API edits are synchronized per design; the bridge is otherwise a local, in-process
call, so expensive evaluations block the calling thread.

## Building designs from Kotlin

`solidCraft { ... }` (`SolidCraftBuilder.kt`) builds a design instead of opening one: sketches of
lines, arcs, circles, splines and 3D wires (`wire(fline)` samples a Field `FLine`), then extrude,
revolve, sweep (twist, scale, guide rails with `scale`/`stretch` fit), loft (closing to an apex
point), pipe, fillet, chamfer (equal, two distances, distance and angle), offset faces, and
the surface sweep (with rails), loft (end continuity G0–G3), revolve, boundary patch and thicken.
`feature(type, json)` adds any other `FeatureKind` by its JSON fields.

```kotlin
solidCraft("Flared") {
    val section = sketch("XY") { rectangle(-4.0, -4.0, 4.0, 4.0) }
    val path = sketch("XZ") { line(0.0, 0.0, 0.0, 60.0) }
    val rail = sketch("XZ") { line(4.0, 0.0, 12.0, 60.0) }
    sweep(section, path, rails = listOf(rail))
}.open().use { part -> part.mesh().toTriMesh() }
```

The builder writes SolidCraft's own design JSON and opens it through the same C ABI, so the
engine validates it, failures name the feature, and the result saves, edits and exports like a
loaded file. No new native entry points were needed. `smokeSolidCraft` runs one design per
feature (`SolidCraftBuilderSmokeTest`) and checks volumes, areas or bounds against closed forms.

Facts found doing so, rechecked at v0.41.0, where sweeps go through SolidCraft's SMLib backend:

- A sweep keeps the profile at the angle it was drawn to the path. A path that leaves the
  profile 27° off square gives a section `cos 27°` of the drawn one; start the path square to the
  profile for a true tube (`planeSquareTo(fline)`).
- A sweep with guide rails takes no twist or end scale (the rails set both); the builder refuses
  the combination before SolidCraft does.
- A sweep round a closed path joins its end to its start with no caps: a full-circle sweep is
  exactly a torus (area to 1e-10, watertight, genus 1). Before v0.41 it capped both ends, and a
  ring had to be two half sweeps joined, which still works.
- Knotted paths do not close yet. A closed trefoil sweep fails in solidcore's boolean
  ("no radial sector of the edge takes the new edge use"), and joining two freeform halves of the
  knot is refused by SMLib's check of the result. Both fail in seconds; at v0.15.0 the join ran
  for minutes and returned "no result". An open knot sweeps as a capped tube.
- SMLib refuses a sweep along a densely sampled wire: a 2.5 mm tube along an `FLine` trefoil or
  helix fails with `SMLib failed (status 1001)` at `wire(line, spacing = 0.5f)` and builds at
  1 mm (the default), 1.5 mm and 2 mm, within 0.1% of πr²L. It is the spacing and not the point
  count: half the knot at 0.5 mm (~230 points) fails where the whole knot at 1 mm (~230) builds.
- Chamfers and face offsets are exact (v0.44.1). On a 20 × 10 × 10 block, a chamfer of one edge,
  of one edge by 1 × 2, by 1 at 30° (the second leg is `tan 30°`, measured from the face facing
  up most), and of the four top edges mitred at the corners (`d²(L + W) − 4d³/3` removed) all
  match their closed forms to 1e-9; so does a base edge of a pyramid, where the faces meet at
  atan 2 rather than 90°.
- An offset face moves the plane and re-intersects its neighbours, so the result is the exact
  half-space intersection even where vertices change. A square pyramid (apex four planes at a
  point) with one side, two opposite sides or all four moved ±1 matches Manifold's half-space
  volume to 1e-6. One side or two opposite sides open the apex into a ridge (six vertices); two
  adjacent sides moved alike still meet the fixed two at one point on the diagonal, so the apex
  slides and stays one vertex. Field's Manifold `insetFaces`, which moves vertices by least
  squares, can only approximate the ridge cases; the smoke prints both volumes.
- `surfaceBoundary` builds from three curves as well as two; the curves must run the same way.
- Sketch points the builder draws are fixed and it adds no constraints; parametric dimensions go
  through expressions (`param`) in feature fields, not sketch constraints.

## Boundary and scope

The Rust adapter exposes one small versioned C ABI. Kotlin calls it through the JDK FFM API,
following Field's Manifold binding style. `summary()` currently returns JSON; the other
operations expose scalar and flat buffer shapes. It covers only opening a `.solidcraft` design,
editing a named parameter, evaluating its feature tree, reading body statistics and tessellation,
and exporting STEP, STL, or the native design; designs are built through the same entry point
(above). It does not expose GPU upload or a live Field UI. It is a first vertical slice through
SolidCraft L0–L3, with a small handwritten ABI; generating headers/bindings can follow if the slice proves
useful.

The example design in `src/jvmMain/resources/afield/solidcraft/` is copied from SolidCraft's
`apps/solidcraft-cli/tests/corpus/m0-box-fillet-hole.solidcraft` at the pinned revision. It is
bundled with Field so the `solidcraft_intro` starting point needs no path into the checkout.
