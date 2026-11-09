# Penrose and Other Quasiperiodic Tilings

Cover a plane with rhombi. Some patterns repeat; others, like Penrose's, never quite do.
`deBruijnTiling(...)` makes Penrose's tiling of thin and thick rhombs, the Ammann–Beenker tiling
of squares and 45° rhombi, and tilings at other symmetries from 3 to 12 and beyond. Every tile is a
closed `FLine`: draw it with `lines(...)`, export it as SVG, or send it to a pen plotter.

The method is N. G. de Bruijn's *multigrid* construction ("Algebraic theory of Penrose's
non-periodic tilings of the plane", 1981). This implementation follows Aatish Bhatia's
[Pattern Collider](https://aatishb.com/patterncollider/)
([source](https://github.com/aatishb/patterncollider), MIT licence), an interactive explorer of
these tilings that is worth playing with.

After drawing a flat tiling, we'll build a [3D Penrose tiling](#penrose-tilings-in-three-dimensions)
out of rhombohedra. You can print either one through Manifold: as a
[relief](#a-relief-through-manifold) or a [slab](#a-printable-slab-through-manifold). Then we'll
connect the flat tiling's corners into a graph. Its [spectrum](#the-tiling-as-a-graph) puts about
one state in ten at zero energy. Give the graph springs and you can
[strike it like a drum](#striking-the-tiling) and hear those states.

## A Penrose tiling

Ask for 5-fold symmetry and you get a Penrose rhomb tiling. `typeCounts()` counts each kind of
rhombus: type 1 is the thin one (36°), type 2 the thick one (72°). There are about φ ≈ 1.618 thick
rhombs for every thin one, and the ratio closes in on φ as the tiling grows.

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings/penrose.png" wait="2.0" capture="box" open-outputs="true" w="760" padding="14"}
val penrose = deBruijnTiling(symmetry = 5, radius = 7.0, scale = 14.0)
val counts = penrose.typeCounts()
lines(penrose.lines(), w = 560.0, h = 560.0, fitToSize = true)
"thin ${counts[1]}, thick ${counts[2]}, thick/thin %.4f".format(counts.getValue(2).toDouble() / counts.getValue(1))
```

## How the construction works

Lay down `symmetry` families of evenly spaced parallel lines, each family turned by the same angle
from the last. Every point where two lines cross becomes one rhombus, with its edges perpendicular
to the two lines. Where that rhombus goes is set by how many lines of every *other* family lie
between it and the origin: corner position = Σ *K*ₖ **e**ₖ, where *K*ₖ is the strip of family *k* the
crossing sits in and **e**ₖ is that family's direction. The rhombi fit edge to edge with no gaps and
no overlaps.

Below, on the left, is the five-family grid; on the right is the tiling it makes. One crossing's
two lines are drawn thick in orange, and the rhombus that crossing becomes is filled in orange on
the right. Its edges are perpendicular to those two lines.

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings/construction.png" wait="2.0" capture="box" open-outputs="true" w="760" padding="14" alt="Left: five families of parallel grid lines clipped to a disk, one family per colour, with two lines drawn thick in orange; right: the Penrose rhombus tiling built from that grid, with the rhombus made by the orange lines' crossing filled orange"}
val t = deBruijnTiling(symmetry = 5, radius = 3.0, scale = 20.0)
// The crossing nearest the middle of the grid, and the two lines that made it.
val picked = t.tiles.minBy { it.crossing.length() }
val grid = t.gridLines(scale = 60.0, thickness = 1.2, highlight = setOf(picked.first, picked.second))
val orange = vec(1.0, 0.35, 0.2, 1.0)
val tiles =
    t.lines(palette = [vec(0.30, 0.30, 0.36, 1.0)]) +
        picked.outline().apply {
            color = orange
            filled = true
        }
// Grid on the left, tiling to its right.
val shift = 60.0 * 3.0 + 100.0 + 20.0 * 5.0
lines(
    grid + tiles.map { it.copy().apply { transform { v -> v.add(shift.toFloat(), 0f, 0f) } } },
    w = 680.0,
    h = 340.0,
    fitToSize = true,
)
"crossing of ${picked.first} and ${picked.second} -> a type-${picked.type} rhombus (${picked.acuteDegrees(5).toInt()} degrees)"
```

The parameters are the ones Pattern Collider exposes:

| Parameter | Meaning |
| --- | --- |
| `symmetry` | the number of line families; 5 is Penrose, 8 is Ammann–Beenker |
| `offsets` | how far each family is shifted (one value per family). `defaultDeBruijnOffsets(n)` gives a set that sums to zero, which is what makes 5-fold a true Penrose tiling |
| `disorder` | moves every line by a random amount up to `disorder / 2` of the spacing (0 to 0.99), seeded by `seed`. The result still tiles, but loses its long-range order |
| `radius` | how far from the centre crossings are kept, in line spacings |
| `scale` | the rhombus edge length in drawing units |
| `recenter` | move the mean tile centre to `centerX`, `centerY` (default true); turn it off when animating |
| `onDegenerate` | where three lines meet at one point there is no tiling. `NUDGE` (the default) shifts the offsets by about a millionth of a spacing and tries again; `ERROR` throws `DeBruijnDegenerateException` naming the three lines |

Each `DeBruijnTile` carries its four `corners`, its `type` (the acute angle is
`type * 180 / symmetry` degrees), its `center`, and the two grid lines `first` and `second` whose
crossing made it, as `DeBruijnLine(family, index)`.

## Other symmetries, and disorder

The same call at 8, 7 and 12-fold, and at 6-fold with its lines jittered. An *n*-fold tiling has
*n* / 2 kinds of rhombus (rounded down); 8-fold has squares among its four. `symmetry = 4` gives
the Ammann–Beenker tiling, of squares and one 45° rhombus. `lines()` colours each kind from a
palette, which you can replace.

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings/gallery.png" wait="2.0" capture="box" open-outputs="true" w="760" padding="14"}
// The same construction at four symmetries, one with its grid lines jittered.
val panel = { title: String, t: DeBruijnTiling ->
    val counts =
        t.typeCounts().entries.joinToString("  ") { (type, n) -> "%.1f°: %d".format(type * 180.0 / t.symmetry, n) }
    "$title\n$counts" to t.lines()
}

showPlaneGrid(
    panel("8-fold", deBruijnTiling(symmetry = 8, radius = 5.0, scale = 10.0)),
    panel("7-fold", deBruijnTiling(symmetry = 7, radius = 5.0, scale = 10.0)),
    panel("12-fold", deBruijnTiling(symmetry = 12, radius = 4.0, scale = 8.0)),
    panel("6-fold, disorder 0.6", deBruijnTiling(symmetry = 6, radius = 6.0, scale = 10.0, disorder = 0.6)),
    columns = 2,
    cellWidth = 380,
    cellHeight = 380,
)
```

## One grid line's ribbon

All the tiles made by crossings on one grid line form a connected chain, a *ribbon* (de Bruijn's
"worm"), in which every tile shares an edge with the next and all those shared edges are parallel.
`ribbon(line)` returns them, and `lines(highlight = line)` draws them in a colour of their own.

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings/ribbon.png" wait="2.0" capture="box" open-outputs="true" w="760" padding="14"}
// Every tile on one grid line's ribbon shares an edge with the next: the line threads a chain of
// rhombi whose shared edges are all parallel. Line 0 of family 0 is drawn in red.
val penrose = deBruijnTiling(symmetry = 5, radius = 7.0, scale = 14.0)
val line = DeBruijnLine(family = 0, index = 0)
val ribbon = penrose.ribbon(line)

val grey = [vec(0.32, 0.34, 0.40, 1.0), vec(0.22, 0.24, 0.30, 1.0)]
lines(penrose.lines(palette = grey, highlight = line), w = 560.0, h = 560.0, fitToSize = true)

"${ribbon.size} tiles on family ${line.family}, line ${line.index}"
```

## Outlines for a plotter, and the dual graph

`lines(fill = false)` gives one stroked, closed outline per rhombus. Shared edges are drawn
twice, once by each neighbour, so dedupe them if your plotter minds. `adjacency()` lists, for
every tile, the indices of the tiles across its edges: this is the tiling's dual graph, handy for
colouring regions or walking from tile to tile.

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings/plotter.png" wait="2.0" capture="box" open-outputs="true" w="760" padding="14"}
// Outlines only, one closed FLine per rhombus: what a pen plotter wants. Each tile's two parallel
// pairs of edges are perpendicular to the two grid lines that made it.
val tiling = deBruijnTiling(symmetry = 5, radius = 5.0, scale = 16.0)
val outlines = tiling.lines(fill = false, outlineThickness = 1.2, palette = [vec(0.92, 0.92, 0.88, 1.0)])
lines(outlines, w = 480.0, h = 480.0, fitToSize = true)
"${outlines.size} closed outlines, ${tiling.adjacency().sumOf { it.size } / 2} shared edges"
```

## Sliding the grid

Moving one family's offset slides its lines. Most tiles stay where they are; small groups flip
into a new arrangement as lines pass through crossings. Run this box and drag the sliders.

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings/slider.png" trim=true wait="2.0" capture="box" open-outputs="true" w="760" padding="14"}
// Drag the slider: every grid line of family 0 moves by `offset`, and the tiling re-forms.
// Tiles flip in small groups ("phason flips") while the rest stay put.
var offset = 0.0
var symmetry = 5.0

val draw = {
    val n = symmetry.toInt()
    val offsets = defaultDeBruijnOffsets(n).toMutableList()
    offsets[0] += offset
    val t = deBruijnTiling(symmetry = n, offsets = offsets, radius = 5.0, scale = 12.0)
    lines(t.lines(), w = 480.0, h = 480.0, fitToSize = true)
}

slider("family 0 offset", min = -1.0, max = 1.0, step = 0.01, initialValue = 0.0, channel = "offset") {
    offset = it
    draw()
}
slider("symmetry", min = 4.0, max = 12.0, step = 1.0, initialValue = 5.0, channel = "symmetry") {
    symmetry = it
    draw()
}
draw()
```

## Animating the offsets

The same idea as a loop: `animate { }` rebuilds the tiling every frame while family 0's lines
slide through one full spacing. `recenter = false` keeps unchanged tiles exactly where they were,
so only the flips move. Line 0's ribbon, in red, is carried across with its line.

```diffstatic_runner {data-lang=kotlin video="assets/debruijn_tilings/offset_sweep.mp4" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" execution="launch" duration="6" fps="20" timeout="240" w="760" embed=true name="debruijn-offset-sweep" alt="a Penrose tiling of yellow thin and blue thick rhombi with one red ribbon of tiles running top to bottom; as the caption's offset rises from 0 to 1, small groups of tiles flip while the rest hold still"}
/*
Slide every line of family 0 through one full spacing over six seconds. Most tiles hold still;
small groups flip as each line passes a crossing. At offset +1 the lines are back where they
started, relabelled, so the loop has no seam. The red ribbon is line 0 of family 0.
*/
val base = defaultDeBruijnOffsets(5)
val line = DeBruijnLine(family = 0, index = 0)
val window = Rect(-190.0, -190.0, 380.0, 380.0)
val seconds = 6.0
val started = AnimationClock.nowSeconds()

animate {
    val shift = ((AnimationClock.nowSeconds() - started) / seconds) % 1.0
    val offsets = base.toMutableList().also { it[0] += shift }
    // recenter = false: unchanged tiles stay put rather than shifting with the mean.
    val t = deBruijnTiling(symmetry = 5, offsets = offsets, radius = 5.0, scale = 12.0, recenter = false)
    showPlane(
        t.lines(highlight = line),
        title = "family 0 offset +%.2f".format(shift),
        caption = "thin ${t.typeCounts()[1]}, thick ${t.typeCounts()[2]}",
        width = 560,
        height = 560,
        window = window,
    )
}
```

## Penrose tilings in three dimensions

`deBruijnTiling3D(...)` fills space with rhombohedra that never repeat. It uses the multigrid
above, one dimension up: families of parallel *planes* instead of lines, and one cell wherever three planes cross. With six
plane families facing the six 5-fold axes of an icosahedron, the cells are Robert Ammann's two
golden rhombohedra, a long *prolate* one and a flat *oblate* one, and the result is the
three-dimensional Penrose tiling.

The three-dimensional grid method is J. E. S. Socolar and P. J. Steinhardt's "generalized dual
method" (["Quasicrystals with arbitrary orientational symmetry"](https://doi.org/10.1103/PhysRevB.32.5547),
*Phys. Rev. B* 32, 5547, 1985;
[free PDF from Steinhardt's site](https://paulsteinhardt.org/wp-content/uploads/2020/10/ArbSymQC.pdf)).
P. Kramer and R. Neri reached the same tiling a year earlier by projecting a six-dimensional cubic
lattice
(["On periodic and non-periodic space fillings of E^m obtained by projection"](https://doi.org/10.1107/S0108767384001203),
*Acta Cryst.* A40, 580, 1984). Socolar and Steinhardt's follow-up,
["Quasicrystals. II. Unit-cell configurations"](https://doi.org/10.1103/PhysRevB.34.617)
(*Phys. Rev. B* 34, 617, 1986;
[free PDF](https://paulsteinhardt.org/wp-content/uploads/2020/10/QuasiPartII.pdf)), works through the arrangements of cells these methods
produce.

The boxes in this part make up the
[3D tiling starting point](agent_transcripts.md#debruijn_tilings_3d_demo).

### Twenty rhombohedra make a triacontahedron

Keep one plane per family and there are exactly twenty crossings, one for each way of choosing
three of the six families. Their twenty cells, ten prolate and ten oblate, pack into a rhombic
triacontahedron, the thirty-faced solid the six directions span. The box below pulls the cells
apart so the packing shows, then welds them into one solid with `toSolid()`, which unions them
through Manifold. As a control it takes the convex hull of every corner: if the cells overlapped
or left a gap, the two volumes would disagree.

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings_3d/triacontahedron.png" wait="4" capture="box" open-outputs="true" padding="20" requires="manifold" w="760" name="debruijn3d-triacontahedron"}
import afield.geometry.solid.manifold.*

// One plane per family: six planes, twenty triple crossings, twenty rhombohedra.
val t = deBruijnTiling3D(planes = 0..0, scale = 10.0)
val counts = t.shapeCounts()

val (union, hull) = Solid.scope {
    // The control: the convex hull of every corner, which is the triacontahedron.
    val corners = Solid.hullOfPointsD(t.cells.flatMap { it.cornerArray().toList() }.toDoubleArray())
    t.toSolid().toTriMesh() to corners.volume
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

### A patch of the 3D Penrose tiling

Drop `planes` and the grid runs on: every crossing within `radius` plane spacings of the origin
becomes a cell. Here are the cells nearest the centre, prolate in yellow and oblate in blue,
shrunk a little each so the stack can be read. `shapeCounts()` counts each kind. There are about
φ ≈ 1.618 prolate cells for every oblate one, the same golden ratio as their volumes. The caption
gives the count's ratio for patches of four sizes: 1.59 in the smallest, and within 0.003 of φ
from radius 4 on.

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings_3d/patch.png" wait="4" capture="box" open-outputs="true" padding="20" w="760" name="debruijn3d-patch"}
// prolate / oblate against the radius of the patch, beside the golden ratio.
val phi = (1 + sqrt(5.0)) / 2
val ratios =
    [2, 4, 8, 12].joinToString(" · ") { r ->
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

### Slices

A plane through the tiling cuts each cell it meets in a triangle, quadrilateral, pentagon or
hexagon, and neighbouring cells' pieces share their edges, so every flat cut is itself a
quasiperiodic tiling of the plane. `slice(normal, offset)` returns those polygons in the plane's
own coordinates, and `lines()` draws them as FLines coloured by the cell they came from. Cut
across a 5-fold, a 3-fold and a 2-fold axis of the icosahedron and the three patterns differ:
rosettes of ten triangles across the 5-fold axis, mostly quadrilaterals across the 3-fold, and rows
with a mirror line across the 2-fold. Each caption counts the polygons by their number of sides.

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

### Sliding a family of planes

As in [Sliding the grid](#sliding-the-grid), moving one family's offset slides its planes through the others. Most cells
stay exactly where they were. When a moving plane passes a crossing of other planes, the cells
there flip into a new arrangement. With `recenter = false` an unchanged cell keeps its position,
so each panel can count how many central cells survive from the unshifted tiling at offset +0.

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

showPlaneGrid(
    *panels.toTypedArray(),
    columns = 2,
    cellWidth = 360,
    cellHeight = 360,
    window = Rect(-60.0, -60.0, 120.0, 120.0),
)
```

### Other directions: cubic against icosahedral

The construction takes any set of directions. `DeBruijnStar` names five: the 4-, 3- and 2-fold
axes of a cube and the 5- and 3-fold axes of an icosahedron. Cube axes have rational coordinates,
so their tilings repeat; icosahedral axes do not, so theirs never do. The three-axis cubic grid is
just the cubic lattice of cubes, so its slice repeats; the body diagonals give a single
rhombohedron and the face diagonals two. The icosahedral 3-fold axes, ten families, give five cell
shapes. Every panel below is cut along the same plane, and its caption counts the cells of each
shape.

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings_3d/stars.png" wait="4" capture="box" open-outputs="true" w="760" padding="14" name="debruijn3d-stars"}
val cut = Vector3d(0.31, 0.52, 0.80)

val panels =
    DeBruijnStar.entries.map { star ->
        val t = deBruijnTiling3D(star, radius = if (star.directions.size > 6) 3.0 else 4.0, scale = 10.0)
        val shapes = t.shapeCounts().values.joinToString(" : ")
        val name = star.name.lowercase().replace('_', ' ')
        "$name, ${t.directions.size} families\ncell shapes ${t.shapeVolumes.size}, counts $shapes" to
            t.slice(cut, offset = 0.5).lines()
    }

showPlaneGrid(
    *panels.toTypedArray(),
    columns = 3,
    cellWidth = 240,
    cellHeight = 240,
    window = Rect(-55.0, -55.0, 110.0, 110.0),
)
```

## Printable solids, through Manifold

Both tilings become closed solids that `saveStl` writes out for a printer: the flat one raised
tile by tile, the 3D one as a slab of whole cells. [Solids that cannot fail: Manifold in
Field](manifold.md) covers the calls.

### A relief, through Manifold

Each flat tile's outline is a closed, counter-clockwise `FLine`, so it goes straight into a Manifold
`Section`. Here every rhombus is inset, extruded to a height set by its type, and unioned with a
base into one closed solid, ready for `saveStl`. See [Solids & Booleans](manifold.md).

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings/relief.png" wait="4" capture="box" open-outputs="true" padding="20" requires="manifold" w="760" name="debruijn-relief"}
import afield.geometry.solid.manifold.*

/*
A Penrose relief: every rhombus inset by 0.6 and extruded, thin ones taller than thick ones,
then all unioned into one closed solid. Each tile's outline is a closed FLine, so it goes
straight into a Manifold Section.
*/
val tiling = deBruijnTiling(symmetry = 5, radius = 3.5, scale = 10.0)
val relief = Solid.scope {
    val prisms =
        tiling.tiles.map { tile ->
            Section.ofFLines([tile.outline()], sampleDistance = 10.0)
                .offset(-0.6, JoinType.MITER)
                .extruded(if (tile.type == 1) 6.0 else 3.0)
        }
    val base =
        Section.ofFLines(tiling.tiles.map { it.outline() }, sampleDistance = 10.0)
            .offset(0.5, JoinType.MITER)
            .extruded(1.5)
    Solid.union(prisms + base)
}
val mesh = relief.toTriMesh()
showMesh(
    mesh,
    title = "Penrose relief, ${tiling.tiles.size} rhombi",
    caption =
        "thin 36° tiles 6 high, thick 72° tiles 3 high · genus ${relief.genus} · volume %.0f".format(relief.volume),
    view = MeshView(yawDegrees = 20.0, pitchDegrees = 50.0, width = 760, height = 520),
)
```

### A printable slab, through Manifold

`oriented(up)` turns the tiling so that `up` points along +z, without moving any cell relative to
its neighbours. `between(family, from, to)` keeps the cells whose crossings lie between two planes
of one family. Their top and bottom are surfaces made of whole golden-rhombus faces, so
`toSolid(...)` welds them into one clean solid. Below, a 5-fold axis stands upright and the cells
between planes 0 and 2 of that family are welded, cut to a disk and trimmed flat underneath. That
leaves a disk with a terrain of rhombic faces on top and a flat base to print from. The caption
prints the welded volume beside the sum of the cell volumes it came from. They agree, because
the cells neither overlap nor leave gaps.

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings_3d/slab.png" wait="5" capture="box" open-outputs="true" padding="20" requires="manifold" w="760" name="debruijn3d-slab"}
import afield.geometry.solid.manifold.*

// Stand a 5-fold axis upright and keep two layers: the cells between planes 0 and 2 of family 0.
val t = deBruijnTiling3D(radius = 4.0, scale = 10.0).oriented(DeBruijnStar.ICOSAHEDRAL.directions[0])
val slab = t.between(family = 0, from = 0, to = 2).filter { hypot(it.center.x, it.center.y) < 60.0 }
val floor = slab.sumOf { it.center.z } / slab.size

val (welded, relief) = Solid.scope {
    val union = t.toSolid(slab)
    // A disk 90 across, trimmed flat at the slab's middle height.
    val disk = Solid.cylinder(400.0, 45.0, segments = 96, center = true)
    union.volume to (union and disk).trimByPlane(0.0, 0.0, 1.0, floor)
}

showMesh(
    relief.toTriMesh().withColor(0.85, 0.80, 0.68),
    title = "two layers of the 3D Penrose tiling, 5-fold axis up · genus ${relief.genus}",
    caption =
        "${slab.size} cells · sum of cell volumes %.1f · welded by Manifold %.1f"
            .format(
                slab.sumOf { it.volume },
                welded,
            ),
    view = MeshView(yawDegrees = 20.0, pitchDegrees = 50.0, width = 760, height = 520),
)
```

`relief.toTriMesh().saveStl(File("penrose3d.stl"))` writes it out for a slicer.

## The tiling as a graph

The flat tiling's corners and edges make a graph, and `vertexGraph()` returns it. Put one site on
every vertex and let a particle hop to any vertex one edge away, all hops equally likely. The
energies of this tight-binding model, the graph's spectrum, are the eigenvalues of its adjacency
matrix A. Kohmoto and Sutherland (*Phys. Rev. Lett.* 56, 2740, 1986) studied this model on the
Penrose tiling and found a macroscopic number of states at exactly E = 0. Arai, Tokihiro, Fujiwara and Kohmoto
(*Phys. Rev. B* 38, 1621, 1988) showed that those states are *strictly localised*: each one is zero
everywhere outside a finite cluster of vertices. They also proved that the zero states make up at
least 81 − 50τ ≈ 0.0983 of all states, with τ the golden ratio, and conjectured that this is the
exact fraction. Flicker, Simon and Parameswaran (*Phys. Rev. X* 10, 011005, 2020) later found the
same number as the share of vertices that any maximum dimer covering of the Penrose tiling leaves
uncovered.

Below, we count those states and draw them on the tiles. Then we treat the tiling as a
network of masses and springs, strike it like the [eigendrum](eigendrum.md), and listen to them.
The boxes in this part make up the [spectra starting point](agent_transcripts.md#penrose_spectra).

Four calls do the work. All of them are in `afield.geometry.path` beside `deBruijnTiling`:

- `tiling.vertexGraph()` turns a tiling into a `TilingGraph`, with one vertex per corner and one
  edge per tile edge.
- `graph.zeroModeCount()` counts the E = 0 states using integer elimination modulo two large
  primes, avoiding floating-point rounding. It handles thirty thousand vertices in a fraction of
  a second; [below](#how-the-spectrum-is-computed) we look at the remaining qualification.
- `graph.spectrum()` returns every energy and state, solved densely, for a patch of up to a couple
  of thousand vertices.
- `graph.confinedState(v)` finds a strictly localised state through vertex `v`, if there is one.

### Counting the zero states

The box below builds Penrose patches of growing radius and counts their zero states. It sets them
beside two controls with the same number of vertices. One is a square lattice, which is periodic.
The other is the same 5-fold construction with every grid line jittered by up to a tenth of a
spacing. A patch has a ragged rim that can hold zero states of its own, so the last line fits
`zeros = bulk·N + rim·√N` over the three largest patches to separate the two contributions.

```diffstatic_runner {data-lang=kotlin screenshot="assets/penrose_spectra/count.png" name="penrose-spectra-count" embed=true alt="A table of five Penrose patches from 934 to 31,662 vertices whose E = 0 fraction stays between 0.0977 and 0.0985, beside a square lattice falling from 0.037 to 0.006 and a jittered tiling near 0.055; below it the bulk fit 0.0983 against 81 − 50τ = 0.0983"}
// Exact counts of E = 0 states on Penrose patches, beside a periodic square lattice and a
// jittered 5-fold tiling with the same number of vertices.
val radii = [6.0, 14.0, 20.0, 28.0, 36.0]
val penrose = radii.map { deBruijnTiling(symmetry = 5, radius = it, scale = 1.0).vertexGraph() }
val square = penrose.map { TilingGraph.squareLattice(sqrt(it.vertexCount / PI)) }
val jittered = radii.map { deBruijnTiling(symmetry = 5, radius = it, scale = 1.0, disorder = 0.1).vertexGraph() }
val counts = table(
    "vertices" to penrose.map { it.vertexCount },
    "E = 0 states" to penrose.map { it.zeroModeCount() },
    "Penrose" to penrose.map { it.zeroModeFraction() },
    "square lattice" to square.map { it.zeroModeFraction() },
    "jittered 0.1" to jittered.map { it.zeroModeFraction() },
).apply { title = "fraction of states at E = 0" }
["Penrose", "square lattice", "jittered 0.1"].forEach { col -> counts.format(col) { (it as Double).f4 } }
display("counts", counts)
val fit = ZeroModeFit.of(penrose.takeLast(3))
val tau = (1 + sqrt(5.0)) / 2
markdown(
    "fit",
    "bulk fit over the three largest: **%.4f** · square lattice: **%.4f** · 81 − 50τ = **%.4f**"
        .format(fit.bulk, ZeroModeFit.of(square.takeLast(3)).bulk, 81 - 50 * tau),
)
```

The Penrose fraction holds near 0.098 at every size, and the fit over the largest patches lands
on 81 − 50τ. The square lattice has zero states too, but on a disk of radius R there are exactly
2R + 1 of them, a count that grows with the radius rather than the area. They become a vanishing
share as the patch grows, and the fit gives a bulk fraction of zero. Jittering the grid lines by a tenth of a spacing keeps
the tiling a valid rhombus tiling, but about half the zero states disappear.

### Where the confined states live

`spectrum.zeroWeights()` gives, for every vertex, how much of the E = 0 states lies on it. The
weight does not depend on which basis the solver picked for the degenerate level, and it sums to
the number of zero states. On the left each vertex gets a disc sized by its weight. On the right,
enlarged, is the smallest strictly localised state `confinedState` finds near the middle of the
patch, orange where the amplitude is positive and blue where it is negative. The box checks that
A·ψ is zero at every vertex.

```diffstatic_runner {data-lang=kotlin screenshot="assets/penrose_spectra/states.png" name="penrose-spectra-states" embed=true alt="On the left a grey Penrose tiling with orange discs of different sizes on many of its vertices; on the right an enlarged patch of the same tiling with one small ring of alternating orange and blue discs and nothing outside it"}
// Left: how much of the E = 0 level sits on each vertex. Right, enlarged: the smallest strictly
// localised state found near the middle of the patch.
val tiling = deBruijnTiling(symmetry = 5, radius = 6.0, scale = 1.0)
val graph = tiling.vertexGraph()
val spectrum = graph.spectrum()
val weight = spectrum.zeroWeights()
val grey = [vec(0.30, 0.31, 0.36, 1.0), vec(0.22, 0.23, 0.28, 1.0)]
val (cx, cy) = graph.centre
// Grow a confined state at each of the twelve confined vertices nearest the centre; keep the smallest.
val tried =
    graph
        .interior(0.4 * graph.extent)
        .filter { weight[it] > 1e-6 }
        .sortedBy { hypot(graph.x[it] - cx, graph.y[it] - cy) }
        .take(12)
val psi = tried.mapNotNull { graph.confinedState(it) }.minBy { s -> s.count { abs(it) > 1e-9 } }
val support = (0 until graph.vertexCount).filter { abs(psi[it]) > 1e-9 }
val residual = (0 until graph.vertexCount).maxOf { v -> abs(graph.neighbours[v].sumOf { psi[it] }) }
// The enlarged view: the tiles within 3.5 edges of the state, 2.5 times larger, to the right.
val sx = support.map { graph.x[it] }.average()
val sy = support.map { graph.y[it] }.average()
val zoom = 2.5
val dx = 1.1 * graph.extent + 3.6 * zoom
val enlarge = { f: FLine ->
    f.transform { v -> Vec3(((v.x - sx) * zoom + dx).toFloat(), ((v.y - sy) * zoom).toFloat(), 0f) }
}
val near = tiling.tiles.filter { hypot(it.center.x - sx, it.center.y - sy) < 3.5 }
val nearTiles = DeBruijnTiling(tiling.symmetry, tiling.offsets, tiling.directions, near).lines(palette = grey)
val right = (nearTiles + graph.vertexDots(psi, maxRadius = 0.32)).map(enlarge)
lines(tiling.lines(palette = grey) + graph.vertexDots(weight) + right, w = 700.0, h = 330.0, fitToSize = true)
val byDegree =
    (0 until graph.vertexCount)
        .groupBy { graph.degree(it) }
        .toSortedMap()
        .entries
        .joinToString(", ") { (d, vs) -> "%d-edge %.3f".format(d, vs.sumOf { weight[it] } / vs.size) }
markdown(
    "measure",
    "${spectrum.zeroCount()} zero states on ${graph.vertexCount} vertices. Mean weight by vertex: $byDegree. " +
        "The state on the right: ${support.size} vertices, max |A·ψ| = %.1e.".format(residual),
)
```

The weight is concentrated on vertices with three edges. Four- and five-edged vertices carry
little, and the six- and seven-edged ones carry none at all. The states `confinedState` finds
cover between 10 and 59 vertices each, and the same few shapes recur across the tiling.

### A gap around zero

The integrated density of states N(E) counts the share of states at or below each energy. Zero
states show up as a vertical jump, and a flat stretch is a gap with no states in it. The grey
curve counts every state of a radius-8 patch. The orange curve weights each state by how much of
it lies in the inner 60% of the patch, which removes states that live on the rim. The right panel
enlarges the energies from −0.5 to 0.5.

```diffstatic_runner {data-lang=kotlin screenshot="assets/penrose_spectra/idos.png" name="penrose-spectra-idos" embed=true alt="Two panels of rising curves, grey and orange. On the left the whole spectrum, with a vertical jump in the middle. On the right the energies near zero enlarged: a tall vertical jump at a white tick, flat on either side of it"}
// N(E) for a radius-8 Penrose patch: every state (grey), and states weighted by their share in
// the patch's inner 60% (orange). Left, the whole spectrum; right, E from -0.5 to 0.5.
val graph = deBruijnTiling(symmetry = 5, radius = 8.0, scale = 1.0).vertexGraph()
val spectrum = graph.spectrum()
val inner = graph.interior(0.6 * graph.extent)
val bulk = spectrum.weightOn(inner)
val grey = vec(0.62, 0.62, 0.68, 1.0)
val orange = vec(1.0, 0.55, 0.15, 1.0)
// One panel: both curves over [from, to], with a white tick at E = 0, shifted right by dx.
fun panel(from: Double, to: Double, dx: Double): List<FLine> {
    val all = spectrum.idosLine(300.0, 300.0, from = from, to = to).apply {
        color = grey
        thickness = 3.0
    }
    val weighted = spectrum.idosLine(300.0, 300.0, weights = bulk, from = from, to = to).apply {
        color = orange
        thickness = 1.5
    }
    val zeroX = -from / (to - from) * 300.0
    val axis = fline {
        moveTo(0.0, 0.0)
        lineTo(300.0, 0.0)
        moveTo(zeroX, 0.0)
        lineTo(zeroX, 10.0)
    }
        .apply { color = vec(1.0, 1.0, 1.0, 0.7) }
    return [all, weighted, axis].map { it.transform { v -> Vec3(v.x + dx.toFloat(), v.y, 0f) } }
}
lines(
    panel(spectrum.energies.first(), spectrum.energies.last(), 0.0) + panel(-0.5, 0.5, 360.0),
    w = 660.0,
    h = 330.0,
    fitToSize = true,
)
val gap = spectrum.gaps(2, weights = bulk, minWeight = 0.3 * inner.size / graph.vertexCount)
markdown(
    "measure",
    "E from %.3f to %.3f; jump at 0 of %.4f; nearest bulk states at %.3f and %.3f; nearest of all at ±%.3f"
        .format(
            spectrum.energies.first(),
            spectrum.energies.last(),
            spectrum.zeroCount().toDouble() / graph.vertexCount,
            gap.minOf { it.first },
            gap.maxOf { it.second },
            spectrum.energies.filter { abs(it) > 1e-8 }.minOf { abs(it) },
        ),
)
```

The jump at E = 0 holds 9.3% of this patch's states. On either side, the nearest states that
belong to the bulk of the patch are about 0.16 away. The few states that fall inside that gap are
weighted out, so they live on the rim. The spectrum is symmetric about zero because every rhombus
tiling is bipartite: its vertices split into two classes, and every edge joins one class to the
other.

### Zero states at other symmetries

The same counts at every symmetry `deBruijnTiling` makes, with a fit over four patch sizes each
(about 1,600 to 14,500 vertices), and then two ways of breaking the Penrose tiling: jittering its
grid lines, and moving the sum of its offsets away from zero. A 5-fold grid makes a true Penrose
tiling only when its offsets sum to an integer. Other sums give generalized Penrose tilings,
built from the same two rhombi but with different vertex neighbourhoods. The box takes a few
seconds.

```diffstatic_runner {data-lang=kotlin screenshot="assets/penrose_spectra/sweep.png" name="penrose-spectra-sweep" embed=true alt="Three tables: zero-state fraction by symmetry, 0.33 at 3-fold falling through 0.08 at 4-fold and 0.10 at 5-fold to about 0.02 at 12-fold; by disorder, from 0.099 down to about 0.03; and by offset sum, 0.099 at zero dropping to 0.045 and climbing back to about 0.1 at one half"}
// Bulk E = 0 fraction (fit over four patch sizes) at every symmetry, then the Penrose tiling
// with jittered grid lines, then with its offsets moved off a zero sum.
fun fit(graphs: List<TilingGraph>) = ZeroModeFit.of(graphs)
val symmetries = [3, 4, 5, 6, 7, 8, 9, 10, 12]
val bySymmetry = symmetries.map { n ->
    val base = 40.0 / n
    fit([1.0, 1.5, 2.2, 3.0].map { deBruijnTiling(symmetry = n, radius = base * it, scale = 1.0).vertexGraph() })
}
val sizes = [10.0, 16.0, 24.0]
val disorders = [0.0, 0.02, 0.05, 0.1, 0.2, 0.4, 0.8]
val byDisorder = disorders.map { d ->
    fit(sizes.map { deBruijnTiling(symmetry = 5, radius = it, scale = 1.0, disorder = d).vertexGraph() })
}
val sums = [0.0, 0.1, 0.2, 0.3, 0.4, 0.5]
val bySum = sums.map { s ->
    val offsets = defaultDeBruijnOffsets(5).map { it + s / 5 }
    fit(sizes.map { deBruijnTiling(symmetry = 5, offsets = offsets, radius = it, scale = 1.0).vertexGraph() })
}
fun fmt(t: Table, vararg cols: String) = t.apply { cols.forEach { c -> format(c) { (it as Double).f4 } } }
display(
    "by symmetry",
    fmt(
        table(
            "symmetry" to symmetries,
            "largest patch" to bySymmetry.map { it.counts.last().first },
            "raw fraction" to bySymmetry.map { it.counts.last().second.toDouble() / it.counts.last().first },
            "bulk fit" to bySymmetry.map { it.bulk },
        ),
        "raw fraction",
        "bulk fit",
    ).heat("bulk fit"),
)
display(
    "by disorder",
    fmt(table("disorder" to disorders, "bulk fit" to byDisorder.map { it.bulk }), "bulk fit").heat("bulk fit"),
)
display(
    "by offset sum",
    fmt(table("offset sum" to sums, "bulk fit" to bySum.map { it.bulk }), "bulk fit").heat("bulk fit"),
)
```

Read the numbers with their fits in mind. Each is a two-parameter fit to four or fewer patch
sizes, and at the higher symmetries the counts still wander by ten or so states from the fit, so
the third decimal is not settled. The 3-fold case is periodic: it is the *dice lattice*, which has
two vertices of three edges for every vertex of six, and a flat band holding exactly a third of
its states, which the fit finds. The 4-fold tiling (Ammann–Beenker, squares and 45° rhombi) has
about 0.08. Unlike the Penrose tiling, its matching deficit stays below 0.01, so most of its zero
states are not forced by a mismatch between the two vertex classes. Above 5-fold the fraction
falls to between 0.02 and 0.06. A jitter of 0.02 of a line spacing already removes a fifth of the
Penrose zero states, and stronger disorder levels off near 0.03. Moving the offset sum away from zero drops the fraction to
0.045, and it climbs back to about 0.1 at a sum of one half.

### Drag it yourself

Two sliders rebuild the tiling at any symmetry and disorder, count its E = 0 states, and draw
their weight on the vertices. The patch radius shrinks as the symmetry grows, so the vertex
count stays near 650 and each update takes under a second. Run this box and drag the
sliders.

```diffstatic_runner {data-lang=kotlin screenshot="assets/penrose_spectra/slider.png" name="penrose-spectra-slider" trim=true embed=true alt="Two sliders, symmetry at 5 and disorder at 0, above a grey Penrose tiling with orange discs on many of its vertices; below it the line 5-fold, disorder 0.00: 62 E = 0 states of 646 vertices, fraction 0.0960"}
// Drag the sliders: the tiling is rebuilt, its E = 0 states counted exactly and their weight drawn
// on the vertices. 5-fold with no disorder is the Penrose tiling, at about 0.098.
var symmetry = 5.0
var disorder = 0.0

val draw = {
    val tiling = deBruijnTiling(symmetry = symmetry.toInt(), radius = 25.0 / symmetry, scale = 1.0, disorder = disorder)
    val graph = tiling.vertexGraph()
    val weight = graph.spectrum().zeroWeights()
    val grey = [vec(0.30, 0.31, 0.36, 1.0), vec(0.22, 0.23, 0.28, 1.0)]
    lines(tiling.lines(palette = grey) + graph.vertexDots(weight), w = 480.0, h = 480.0, fitToSize = true)
    markdown(
        "measure",
        "%d-fold, disorder %.2f: **%d** E = 0 states of %d vertices, fraction **%.4f** (Penrose: 81 − 50τ = 0.0983)"
            .format(
                symmetry.toInt(),
                disorder,
                graph.zeroModeCount(),
                graph.vertexCount,
                graph.zeroModeFraction(),
            ),
    )
}

slider("symmetry", min = 3.0, max = 12.0, step = 1.0, initialValue = 5.0, channel = "symmetry") {
    symmetry = it
    draw()
}
slider("disorder", min = 0.0, max = 0.8, step = 0.01, initialValue = 0.0, channel = "disorder") {
    disorder = it
    draw()
}
draw()
```

### Striking the tiling

To hear the spectrum, make the tiling a mechanical network. Put a unit mass on every vertex and a
unit spring along every edge. Then give every vertex a spring to the ground, sized so that its
springs add up to the same stiffness *k* at every vertex. The stiffness matrix is then *k*·I − A,
so the network's vibration modes are exactly the tight-binding states, and mode *i* rings at
ω² = *k* − *E*ᵢ. All the E = 0 states, confined or not, ring at a single pitch, ω = √*k*.
`graph.springModes(k)` builds this from `afield.audio.modal`, and its `render` is the eigendrum's
modal synthesis, with one decaying sinusoid per mode.

A strike is heard where it lands, so mode *i* sounds with amplitude ψᵢ(v)². Summed over the E = 0
level, that is exactly the vertex's zero weight from above. The box strikes three places: the
Penrose vertex with the most confined weight, a seven-edged Penrose vertex with none, and the
centre of a square lattice of about the same size. All three are built with the same *k*, so the
E = 0 pitch is 220 Hz in each. The bars show how the strike's energy spreads over pitch, and a
white tick marks 220 Hz. Under the picture are the three strikes themselves, in the order of
the rows.

```diffstatic_runner {data-lang=kotlin screenshot="assets/penrose_spectra/strike.png" name="penrose-spectra-strike" trim=true embed=true alt="Three rows of orange bars along a pitch axis, a white tick under each row at 220 Hz. In the first row one bar at the tick stands several times taller than the rest; in the second the bar at the tick is missing; in the third the bars spread fairly evenly, none much taller than the rest. Below, the shares at 220 Hz: 31.5%, 0.0% and 7.2%"}
import afield.audio.modal.*

// A Penrose patch and a square lattice as spring networks with equal stiffness, so E = 0 rings
// at 220 Hz in both. Each row, top to bottom: strike energy against pitch for one struck vertex.
val penrose = PenroseSpectraDocs.penrose()
val square = PenroseSpectraDocs.square()
val pm = penrose.springModes(PenroseSpectraDocs.STIFFNESS)
val sm = square.springModes(PenroseSpectraDocs.STIFFNESS)
val strikes = [
    Triple("Penrose, most confined weight", pm, PenroseSpectraDocs.strikeVertex(penrose, pm, true)),
    Triple("Penrose, seven-edged vertex", pm, PenroseSpectraDocs.strikeVertex(penrose, pm, false)),
    Triple("square lattice, centre", sm, square.nearest(square.centre.first, square.centre.second)),
]
val out = ArrayList<FLine>()
val lo = 120.0
val hi = 340.0
strikes.forEachIndexed { row, (_, modes, v) ->
    val a = modes.strikeVertex(v)
    val f = modes.frequencies(220.0)
    val bins = DoubleArray(110)
    for (k in a.indices) bins[((f[k] - lo) / (hi - lo) * bins.size).toInt().coerceIn(0, bins.size - 1)] += a[k]
    val y0 = row * 1.3
    bins.forEachIndexed { b, e ->
        if (e > 0)
            out +=
                fline {
                    moveTo(b * 0.05, y0)
                    lineTo(b * 0.05, y0 - sqrt(e))
                }
                    .apply {
                        color = vec(1.0, 0.55, 0.15, 1.0)
                        thickness = 3.0
                    }
    }
    out +=
        fline {
            moveTo((220.0 - lo) / (hi - lo) * 5.5, y0 + 0.05)
            lineTo((220.0 - lo) / (hi - lo) * 5.5, y0 + 0.2)
        }
            .apply {
                color = vec(1.0, 1.0, 1.0, 1.0)
                thickness = 2.0
            }
}
lines(out, w = 600.0, h = 300.0, fitToSize = true)
markdown(
    "measure",
    strikes.joinToString(" · ") { (name, modes, v) -> "$name: %.1f%% at 220 Hz".format(100 * modes.zeroShare(v)) },
)
```

**The confined vertex**, 31.5% of its strike at 220 Hz:

```diffstatic_runner {data-lang=kotlin audio="assets/penrose_spectra/penrose-confined.wav" audio-spec="penrose-confined" audio-renderer="afield.audio.modal.PenroseSpectraDocs" embed=true hidden=true name="penrose-spectra-confined"}
// offline-rendered by PenroseSpectraDocs.renderWav("penrose-confined", ...): the vertex with the most confined weight
```

**A seven-edged vertex**, 0.0% at 220 Hz:

```diffstatic_runner {data-lang=kotlin audio="assets/penrose_spectra/penrose-open.wav" audio-spec="penrose-open" audio-renderer="afield.audio.modal.PenroseSpectraDocs" embed=true hidden=true name="penrose-spectra-open"}
// offline-rendered by PenroseSpectraDocs.renderWav("penrose-open", ...): a seven-edged vertex with no confined weight
```

**The square lattice, struck at its centre**, 7.2% at 220 Hz:

```diffstatic_runner {data-lang=kotlin audio="assets/penrose_spectra/square.wav" audio-spec="square" audio-renderer="afield.audio.modal.PenroseSpectraDocs" embed=true hidden=true name="penrose-spectra-square"}
// offline-rendered by PenroseSpectraDocs.renderWav("square", ...): the square lattice struck at its centre
```

The confined vertex puts almost a third of its strike energy into a single pitch, a steady
220 Hz note over the spread of the other modes. The seven-edged vertex has nothing at 220 Hz. The
square lattice spreads its energy over a broad band around 220 Hz. Its 7% at exactly E = 0 comes
from the 2R + 1 zero states counted above, and their average weight per vertex, (2R + 1)/N, shrinks
as the lattice grows.

### A live tiling

`ModalDrum` plays a spring network exactly as it plays a drum: return it from a box and launch the
box. Its modes run on the audio thread, and `strike(x, y)` from any box or callback adds a strike
at the start of the next block. `fundamentalFor(220.0)` keeps the E = 0 pitch at 220 Hz. With all
934 modes of this patch, a 512-sample block takes about 2 ms to render.

```diffstatic {data-lang=kotlin}
import afield.audio.modal.*

// The radius-6 Penrose patch as a live voice. It strikes itself every 1.5 s at the vertex with
// the most confined weight; call drum.strike(x, y) to hit it somewhere else. Launch this box.
val graph = deBruijnTiling(symmetry = 5, radius = 6.0, scale = 1.0).vertexGraph()
val modes = graph.springModes(8.0)
val weight = modes.spectrum.zeroWeights()
val v = graph.interior(0.5 * graph.extent).maxBy { weight[it] }
val drum = ModalDrum(
    modes,
    fundamental = modes.fundamentalFor(220.0),
    alpha = 1.0,
    beta = 0.2,
    every = 1.5,
    autoX = graph.x[v],
    autoY = graph.y[v],
)
drum
```

### How the spectrum is computed

`vertexGraph` merges tile corners that coincide and sorts the vertices by position, so that
neighbours in the graph have nearby indices. `zeroModeCount` uses the two vertex classes: A has
rank twice that of the block B joining the classes. It reduces B's rows one at a time against an
echelon basis, modulo the primes 2³¹ − 1 and 10⁹ + 7, and keeps the larger rank. A rank computed
modulo a prime can only fall short of the true rank, and only when the prime divides one of the
matrix's minors, which is very unlikely for two large primes at once. Because the rows are in
spatial order, they stay short, and a 31,662-vertex patch takes about 0.1 s. `matchingDeficit()`
counts the vertices that a maximum matching (Hopcroft–Karp) leaves unmatched, a lower bound on the
zero count. On Penrose patches the two agree, or differ by a few rim states.

`spectrum()` is a dense symmetric eigensolve from EJML, which takes about 1.3 s for 934 vertices
and 8 s for 1,629. `confinedState(v)` grows the cluster S of vertices within *h* edges of `v`. For
each *h* it looks for amplitudes on S with (A·ψ)(u) = 0 at every vertex in S or next to it, the
null space of A restricted to those rows and S's columns. It returns the first such state with
weight on `v`.

`TilingSpectrumTest` pins the counts above: 92 zero states of 934 at radius 6, up to 3,104 of
31,662 at radius 36, a bulk fit within 0.0005 of 81 − 50τ, exactly 2R + 1 zero states on a
square-lattice disk, a third for the 3-fold tiling, the 0.16 gap, the shapes of the confined
states, and the 31.5% strike share.

## Where to go next

- [Solids that cannot fail: Manifold in Field](manifold.md) covers the `Section`, `extruded`,
  `Solid.union`, `trimByPlane`, `slice` and `saveStl` calls behind the relief and the slab.
  `Solid.slice(z)` cuts the welded slab into a `Section` outline, ready to offset into toolpaths.
- [Polytopes: topology first, coordinates later](polytope_examples.md) builds other polyhedra; the
  triacontahedron above is the zonohedron of the six 5-fold axes.
- The [eigendrum](eigendrum.md) is the modal synthesis the strikes reuse, on drums drawn as
  shapes instead of tilings.
- Open the starting points to try these boxes in your own document: the
  [flat tiling](agent_transcripts.md#debruijn_tilings_demo), the
  [3D tiling](agent_transcripts.md#debruijn_tilings_3d_demo) and the
  [spectra](agent_transcripts.md#penrose_spectra).
- Source: [`DeBruijnTiling.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/geometry/path/DeBruijnTiling.kt),
  [`DeBruijnTiling3D.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/geometry/path/DeBruijnTiling3D.kt),
  [`TilingSpectrum.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/geometry/path/TilingSpectrum.kt).

Open questions about the spectrum:

- **Periodic approximants.** The rim is the only reason the raw fraction wanders. A rational
  approximant of the Penrose tiling on a torus has no rim, and its count would be exact at every
  size. `deBruijnTiling` would need a periodic mode, with offsets and directions rounded to a
  Fibonacci ratio.
- **Count the confined state types.** Arai and coauthors reached their lower bound by counting
  confined states of several distinct types. Clustering `confinedState` supports by shape and
  comparing how often each shape occurs with their counts would check the parts, not only the
  total.
- **The Ammann–Beenker zero states.** The 4-fold tiling's 0.08 is not explained by its matching
  deficit, so those zero states have a different origin. Checking the count against the
  literature on strictly localised states in that tiling (Oktel, *Phys. Rev. B* 104, 014204,
  2021) is the next measurement.
- **A magnetic field.** Giving each hop a phase (a Peierls substitution) breaks the E = 0
  degeneracy into a Hofstadter-like butterfly. `spectrum()` would need complex Hermitian
  matrices, which EJML also solves.
- **Strike the drawn tiling.** Combine with the [eigendrum](eigendrum.md)'s drawing-layer box:
  take `notation.strokes`, keep the tiles inside the stroke, and play that patch live, so the
  shape of the cut decides how many confined states survive at its edge.
- **A relief that rings.** The [Manifold relief](#a-relief-through-manifold)
  of the same tiling, with the zero weight as each vertex's height, would make a printable object
  with the confined states raised.

??? aside "What the tests check"
    `DeBruijnTilingTest` builds tilings at 3, 4, 5, 6 (with disorder), 7, 8, 9 and 12-fold and
    checks that every edge is shared by at most two tiles, every interior edge by exactly two, no
    tile's centre lies inside another tile, and each tile's measured angle matches its `type`. For
    5-fold it checks there are exactly two rhombus types and that thick/thin is within 0.02 of φ at
    radius 14 and within 0.01 at radius 24. An earlier cut used evenly stepped default offsets and
    put three lines through one point at 7-fold, which the shared-edge check caught; the defaults
    are now a fixed hash.

    `DeBruijnTiling3DTest` builds tilings from all five stars and from moved offsets, and checks
    that every cell's outward faces enclose its volume, no face belongs to more than two cells,
    every face of an interior cell belongs to two, shared corners are bit-identical, and no
    cell's centre, or any point nine tenths of the way to one of its corners, lies inside another
    cell. For the icosahedral star it checks the two shapes, their volume ratio φ, a
    prolate/oblate count within 0.01 of φ at radius 7, and that the cells fill eight times the
    volume of the crossing ball (by Cauchy–Binet, 8 = det of the sum of e eᵀ over the six axes).
    One plane per family must give ten and ten cells of total volume 4√(5 + 2√5), the
    triacontahedron's, with thirty boundary faces, and `toSolid()` must weld them into a genus-0
    solid of 32 vertices and 60 triangles. Shape numbers must be congruence classes (equal volume
    is not enough: the cube's face diagonals give two rhombohedra of one volume). Every boundary
    edge of a `between(...)` slab must have exactly two faces, and a slice must cover a disk once at
    every sample point. An earlier cut picked slab cells by their centres; Manifold welded them,
    but cells meeting only along an edge left a non-manifold mesh, which is why `between` exists.
