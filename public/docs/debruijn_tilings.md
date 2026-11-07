# Penrose and Other Quasiperiodic Tilings

`deBruijnTiling(...)` covers the plane with rhombi that never repeat: Penrose's tiling of thin and
thick rhombs, the 8-fold Ammann–Beenker tiling, and the same thing at any symmetry from 3 to 12 or
beyond. Every tile is a closed `FLine`, so a tiling draws with `lines(...)`, exports as SVG, and
can go straight to a pen plotter.

The method is N. G. de Bruijn's *multigrid* construction ("Algebraic theory of Penrose's
non-periodic tilings of the plane", 1981). This implementation follows Aatish Bhatia's
[Pattern Collider](https://aatishb.com/patterncollider/)
([source](https://github.com/aatishb/patterncollider), MIT licence), an interactive explorer of
these tilings that is worth playing with.

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
val tiles = t.lines(palette = [vec(0.30, 0.30, 0.36, 1.0)]) + picked.outline().apply {
    color = orange
    filled = true
}
// Grid on the left, tiling to its right.
val shift = 60.0 * 3.0 + 100.0 + 20.0 * 5.0
lines(grid + tiles.map { it.copy().apply { transform { v -> v.add(shift.toFloat(), 0f, 0f) } } }, w = 680.0, h = 340.0, fitToSize = true)
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
*n* / 2 kinds of rhombus (rounded down); 8-fold has squares among its four. `lines()` colours each
kind from a palette, which you can replace.

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings/gallery.png" wait="2.0" capture="box" open-outputs="true" w="760" padding="14"}
// The same construction at four symmetries, one with its grid lines jittered.
val panel = { title: String, t: DeBruijnTiling ->
    val counts = t.typeCounts().entries.joinToString("  ") { (type, n) -> "%.1f°: %d".format(type * 180.0 / t.symmetry, n) }
    "$title\n$counts" to t.lines()
}

showPlaneGrid(
    panel("8-fold, Ammann–Beenker", deBruijnTiling(symmetry = 8, radius = 5.0, scale = 10.0)),
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
// Slide every line of family 0 through one full spacing over six seconds. Most tiles hold still;
// small groups flip as each line passes a crossing. At offset +1 the lines are back where they
// started, relabelled, so the loop has no seam. The red ribbon is line 0 of family 0.
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

## A relief, through Manifold

Each tile's outline is a closed, counter-clockwise `FLine`, so it goes straight into a Manifold
`Section`. Here every rhombus is inset, extruded to a height set by its type, and unioned with a
base into one closed solid, ready for `saveStl`. See [Solids & Booleans](manifold.md).

```diffstatic_runner {data-lang=kotlin embed=true screenshot="assets/debruijn_tilings/relief.png" wait="4" capture="box" open-outputs="true" padding="20" requires="manifold" w="760" name="debruijn-relief"}
import afield.geometry.solid.manifold.*

// A Penrose relief: every rhombus inset by 0.6 and extruded, thin ones taller than thick ones,
// then all unioned into one closed solid. Each tile's outline is a closed FLine, so it goes
// straight into a Manifold Section.
val tiling = deBruijnTiling(symmetry = 5, radius = 3.5, scale = 10.0)
val relief = Solid.scope {
    val prisms = tiling.tiles.map { tile ->
        Section.ofFLines([tile.outline()], sampleDistance = 10.0)
            .offset(-0.6, JoinType.MITER)
            .extruded(if (tile.type == 1) 6.0 else 3.0)
    }
    val base = Section.ofFLines(tiling.tiles.map { it.outline() }, sampleDistance = 10.0).offset(0.5, JoinType.MITER).extruded(1.5)
    Solid.union(prisms + base)
}
val mesh = relief.toTriMesh()
showMesh(
    mesh,
    title = "Penrose relief, ${tiling.tiles.size} rhombi",
    caption = "thin 36° tiles 6 high, thick 72° tiles 3 high · genus ${relief.genus} · volume %.0f".format(relief.volume),
    view = MeshView(yawDegrees = 20.0, pitchDegrees = 50.0, width = 760, height = 520),
)
```

The demo document `dev_docs/agents/transcripts/debruijn_tilings_demo.yaml` holds all of these
boxes.

??? aside "What the tests check"
    `DeBruijnTilingTest` builds tilings at 3, 4, 5, 6 (with disorder), 7, 8, 9 and 12-fold and
    checks that every edge is shared by at most two tiles, every interior edge by exactly two, no
    tile's centre lies inside another tile, and each tile's measured angle matches its `type`. For
    5-fold it checks there are exactly two rhombus types and that thick/thin is within 0.02 of φ at
    radius 14 and within 0.01 at radius 24. An earlier cut used evenly stepped default offsets and
    put three lines through one point at 7-fold, which the shared-edge check caught; the defaults
    are now a fixed hash.
