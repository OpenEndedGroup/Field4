# Penrose Tilings in Three Dimensions

`deBruijnTiling3D(...)` fills space with rhombohedra that never repeat. It uses the
multigrid behind [Penrose and Other Quasiperiodic Tilings](debruijn_tilings.md), one dimension up:
families of parallel *planes* instead of lines, and one cell wherever three planes cross. With six
plane families facing the six 5-fold axes of an icosahedron, the cells are Robert Ammann's two
golden rhombohedra, a long *prolate* one and a flat *oblate* one, and the result is the
three-dimensional Penrose tiling.

The three-dimensional grid method is J. E. S. Socolar and P. J. Steinhardt's "generalized dual
method" ("Quasicrystals with arbitrary orientational symmetry", *Phys. Rev. B* 32, 5547, 1985).
P. Kramer and R. Neri reached the same tiling a year earlier by projecting a six-dimensional cubic
lattice ("On periodic and non-periodic space fillings of E^m obtained by projection", *Acta Cryst.*
A40, 580, 1984).

## Twenty rhombohedra make a triacontahedron

Keep one plane per family and there are exactly twenty crossings, one for each way of choosing
three of the six families. Their twenty cells, ten prolate and ten oblate, pack into a rhombic
triacontahedron, the thirty-faced solid the six directions span. The box below pulls the cells
apart so the packing shows, then asks Manifold for their union and, as a control, for the convex
hull of every corner. If the cells overlapped or left a gap, the two volumes would disagree.

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings_3d/triacontahedron.png" wait="4" capture="box" open-outputs="true" padding="20" requires="manifold" w="760" name="debruijn3d-triacontahedron"}
import afield.geometry.solid.manifold.*

// One plane per family: six planes, twenty triple crossings, twenty rhombohedra.
val t = deBruijnTiling3D(planes = 0..0, scale = 10.0)
val counts = t.shapeCounts()

val (union, hull) =
    Solid.scope {
        val cells = Solid.union(t.cells.map { Solid.hullOfPointsD(it.cornerArray()) })
        // The control: the convex hull of every corner, which is the triacontahedron.
        val corners = Solid.hullOfPointsD(t.cells.flatMap { it.cornerArray().toList() }.toDoubleArray())
        cells.toTriMesh() to corners.volume
    }

showMeshGrid(
    "${counts[1]} prolate + ${counts[2]} oblate, pulled apart" to t.toTriMesh(shrink = 0.8),
    "their Manifold union · cells %.1f · hull %.1f".format(t.volume(), hull) to union.withColor(0.75, 0.78, 0.84),
    columns = 2,
    cellWidth = 370,
)
```

Every corner of every cell sits at a sum of the six directions with integer coefficients, so two
cells that meet compute their shared corners to the same bits; that is what lets Manifold weld
twenty solids into one closed thirty-faced one. `planes = 0 until m` keeps *m* planes per family
and fills a triacontahedron *m* times as wide with 20*m*³ cells.

## A patch of the 3D Penrose tiling

Drop `planes` and the grid runs on: every crossing within `radius` plane spacings of the origin
becomes a cell. Here are the cells nearest the centre, prolate in yellow and oblate in blue,
shrunk a little each so the stack can be read. `shapeCounts()` counts each kind. There are about
φ ≈ 1.618 prolate cells for every oblate one, the same golden ratio as their volumes, and the count
closes in on φ as the radius grows.

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings_3d/patch.png" wait="4" capture="box" open-outputs="true" padding="20" w="760" name="debruijn3d-patch"}
// prolate / oblate against the radius of the patch, beside the golden ratio.
val phi = (1 + sqrt(5.0)) / 2
val ratios =
    [2, 4, 6, 8].joinToString(" · ") { r ->
        val counts = deBruijnTiling3D(radius = r.toDouble()).shapeCounts()
        "radius $r: %.4f".format(counts.getValue(1).toDouble() / counts.getValue(2))
    }

val penrose3 = deBruijnTiling3D(radius = 2.0, scale = 10.0)
// The cells whose crossing is nearest the middle of the grid.
val middle = penrose3.cells.filter { it.crossing.length() < 1.3 }
showMesh(
    penrose3.toTriMesh(only = middle, shrink = 0.88),
    title = "${middle.size} cells of the 3D Penrose tiling, each shrunk to 88%",
    caption = "yellow prolate, blue oblate · prolate/oblate $ratios · φ = %.4f".format(phi),
    view = MeshView(yawDegrees = 25.0, pitchDegrees = 22.0, width = 720, height = 520),
)
```

## Slices

A plane through the tiling cuts each cell it meets in a triangle, quadrilateral, pentagon or
hexagon, and neighbouring cells' pieces share their edges, so every flat cut is itself a
quasiperiodic tiling of the plane. `slice(normal, offset)` returns those polygons in the plane's
own coordinates, and `lines()` draws them as FLines coloured by the cell they came from. Cut
across a 5-fold, a 3-fold and a 2-fold axis of the icosahedron and the patterns show that axis's
symmetry locally: ten-pointed stars across the 5-fold axis, three-armed ones across the 3-fold, and
mirror rows across the 2-fold. Each caption counts the polygons by their number of sides.

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings_3d/slices.png" wait="3" capture="box" open-outputs="true" w="760" padding="14" name="debruijn3d-slices"}
val t = deBruijnTiling3D(radius = 5.0, scale = 10.0)
val phi = (1 + sqrt(5.0)) / 2
val window = Rect(-70.0, -70.0, 140.0, 140.0)

val panel = { title: String, normal: Vector3d ->
    val s = t.slice(normal, offset = 0.5)
    val sides = s.sideCounts().entries.joinToString(" ") { (n, count) -> "$n-gons $count" }
    "$title\n$sides" to s.lines()
}

showPlaneGrid(
    panel("across a 5-fold axis", Vector3d(0.0, 1.0, phi)),
    panel("across a 3-fold axis", Vector3d(1.0, 1.0, 1.0)),
    panel("across a 2-fold axis", Vector3d(0.0, 0.0, 1.0)),
    columns = 3,
    cellWidth = 250,
    cellHeight = 250,
    window = window,
)
```

## Sliding a family of planes

As on the flat page, moving one family's offset slides its planes through the others. Most cells
stay exactly where they were; the ones a moving plane passes a crossing of flip into a new
arrangement. With `recenter = false` an unchanged cell keeps its position, so each panel below can
count how many of the central cells survive from the unshifted tiling, which is the control at
offset +0.

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings_3d/offset_sweep.png" wait="3" capture="box" open-outputs="true" w="760" padding="14" name="debruijn3d-offset-sweep"}
val base = defaultDeBruijnOffsets(6)
val build = { shift: Double ->
    val offsets = base.toMutableList().also { it[0] += shift }
    deBruijnTiling3D(offsets = offsets, radius = 4.0, scale = 10.0, recenter = false)
}
// A cell is the same cell if it has the same corner lift and the same three families.
val identity = { c: DeBruijnCell -> c.lift(0).toList() + c.families }
val central = { t: DeBruijnTiling3D -> t.cells.filter { it.center.length() < 35.0 } }
val before = central(build(0.0)).map(identity).toSet()

val panels =
    [0.0, 0.1, 0.3, 0.6].map { shift ->
        val t = build(shift)
        val kept = central(t).count { identity(it) in before }
        val title = "family 0 offset +%.1f".format(shift)
        "$title\n$kept of ${central(t).size} central cells unchanged" to t.slice(t.directions[0], offset = 0.5).lines()
    }

showPlaneGrid(*panels.toTypedArray(), columns = 2, cellWidth = 360, cellHeight = 360, window = Rect(-60.0, -60.0, 120.0, 120.0))
```

## Other directions: cubic against icosahedral

The construction takes any set of directions. `DeBruijnStar` names five: the 4-, 3- and 2-fold
axes of a cube and the 5- and 3-fold axes of an icosahedron. Cube axes have rational coordinates,
so their tilings repeat; icosahedral axes do not, so theirs never do. The three-axis cubic grid is
just the cubic lattice, one cell shape and every slice a lattice of squares and
parallelograms. Every panel below is cut along the same plane.

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings_3d/stars.png" wait="4" capture="box" open-outputs="true" w="760" padding="14" name="debruijn3d-stars"}
val cut = Vector3d(0.31, 0.52, 0.80)

val panels =
    DeBruijnStar.entries.map { star ->
        val t = deBruijnTiling3D(star, radius = if (star.directions.size > 6) 3.0 else 4.0, scale = 10.0)
        val shapes = t.shapeCounts().values.joinToString(" : ")
        val name = star.name.lowercase().replace('_', ' ')
        "$name, ${t.directions.size} families\ncell shapes ${t.shapeVolumes.size}, counts $shapes" to t.slice(cut, offset = 0.5).lines()
    }

showPlaneGrid(*panels.toTypedArray(), columns = 3, cellWidth = 240, cellHeight = 240, window = Rect(-55.0, -55.0, 110.0, 110.0))
```

## A printable slab, through Manifold

`oriented(up)` turns the tiling so that `up` points along +z, without moving any cell relative to
its neighbours. Turn a 5-fold axis upright, keep the cells whose centres lie in a thin slab, and
Manifold welds them into one solid whose top is a terrain of golden-rhombus faces. Trimming the
bottom flat makes it something a printer can stand on. The welded volume is printed beside the sum
of the cell volumes it came from: equal, because the cells neither overlap nor leave gaps.

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings_3d/slab.png" wait="5" capture="box" open-outputs="true" padding="20" requires="manifold" w="760" name="debruijn3d-slab"}
import afield.geometry.solid.manifold.*

// Stand a 5-fold axis upright, then keep a slab 18 units thick and 100 across.
val t = deBruijnTiling3D(radius = 4.5, scale = 10.0).oriented(DeBruijnStar.ICOSAHEDRAL.directions[0])
val slab = t.cells.filter { abs(it.center.z) < 9.0 && hypot(it.center.x, it.center.y) < 50.0 }

val (welded, relief) =
    Solid.scope {
        val union = Solid.union(slab.map { Solid.hullOfPointsD(it.cornerArray()) })
        // Keep everything above z = -6: a flat bottom to print from.
        union.volume to union.trimByPlane(0.0, 0.0, 1.0, -6.0)
    }

showMesh(
    relief.toTriMesh().withColor(0.85, 0.80, 0.68),
    title = "a slab of the 3D Penrose tiling, ${slab.size} cells, 5-fold axis up",
    caption =
        "sum of cell volumes %.1f · welded by Manifold %.1f · bottom trimmed flat at z = -6".format(
            slab.sumOf { it.volume },
            welded,
        ),
    view = MeshView(yawDegrees = 20.0, pitchDegrees = 55.0, width = 760, height = 520),
)
```

`relief.toTriMesh().saveStl(File("penrose3d.stl"))` writes it out for a slicer.

## Where to go next

- [Penrose and Other Quasiperiodic Tilings](debruijn_tilings.md) has the flat construction with
  its grid drawn beside the tiles, ribbons, plotter outlines and a live offset slider.
- [Solids & Booleans](manifold.md) covers `Solid.union`, `trimByPlane`, `slice` and `saveStl`.
  `Solid.slice(z)` on the slab gives the same cross sections as `slice(...)` here, as Manifold
  `Section`s ready to offset into toolpaths.
- [Field 4 graphics](afield_four_graphics.md): `toTriMesh()` is an ordinary `TriMesh`, so a patch
  can go to a live Vulkan view and be turned in the hand.
- The demo document [debruijn_tilings_3d_demo](agent_transcripts.md#debruijn_tilings_3d_demo)
  holds every box on this page.
- Source: [`DeBruijnTiling3D.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/geometry/path/DeBruijnTiling3D.kt).

??? aside "What the tests check"
    `DeBruijnTiling3DTest` builds tilings from all five stars and from moved offsets, and checks
    that every cell's outward faces enclose its volume, no face belongs to more than two cells,
    every face of an interior cell belongs to two, shared corners are bit-identical, and no
    cell's centre, or any point nine tenths of the way to one of its corners, lies inside another
    cell. For the icosahedral star it checks the two shapes, their volume ratio φ, a
    prolate/oblate count within 0.01 of φ at radius 7, and that the cells fill eight times the
    volume of the crossing ball (by Cauchy–Binet, 8 = det of the sum of e eᵀ over the six axes).
    One plane per family must give ten and ten cells of total volume 4√(5 + 2√5), the
    triacontahedron's, with thirty boundary faces. A slice must cover a disk once at every sample
    point.
