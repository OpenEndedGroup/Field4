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

```diffstatic_runner {data-lang=kotlin screenshot="assets/debruijn_tilings/penrose.png" wait="2.0" capture="box" open-outputs="true" w="760" padding="14"}
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

The parameters are the ones Pattern Collider exposes:

| Parameter | Meaning |
| --- | --- |
| `symmetry` | the number of line families; 5 is Penrose, 8 is Ammann–Beenker |
| `offsets` | how far each family is shifted (one value per family). `defaultDeBruijnOffsets(n)` gives a set that sums to zero, which is what makes 5-fold a true Penrose tiling |
| `disorder` | moves every line by a random amount up to `disorder / 2` of the spacing (0 to 0.99), seeded by `seed`. The result still tiles, but loses its long-range order |
| `radius` | how far from the centre crossings are kept, in line spacings |
| `scale` | the rhombus edge length in drawing units |

Each `DeBruijnTile` carries its four `corners`, its `type` (the acute angle is
`type * 180 / symmetry` degrees), its `center`, and the two grid lines `first` and `second` whose
crossing made it, as `DeBruijnLine(family, index)`.

## Other symmetries, and disorder

The same call at 8, 7 and 12-fold, and at 6-fold with its lines jittered. An *n*-fold tiling has
*n* / 2 kinds of rhombus (rounded down); 8-fold has squares among its four. `lines()` colours each
kind from a palette, which you can replace.

```diffstatic_runner {data-lang=kotlin screenshot="assets/debruijn_tilings/gallery.png" wait="2.0" capture="box" open-outputs="true" w="760" padding="14"}
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

```diffstatic_runner {data-lang=kotlin screenshot="assets/debruijn_tilings/ribbon.png" wait="2.0" capture="box" open-outputs="true" w="760" padding="14"}
// Every tile on one grid line's ribbon shares an edge with the next: the line threads a chain of
// rhombi whose shared edges are all parallel. Line 0 of family 0 is drawn in red.
val penrose = deBruijnTiling(symmetry = 5, radius = 7.0, scale = 14.0)
val line = DeBruijnLine(family = 0, index = 0)
val ribbon = penrose.ribbon(line)

val grey = listOf(vec(0.32, 0.34, 0.40, 1.0), vec(0.22, 0.24, 0.30, 1.0))
lines(penrose.lines(palette = grey, highlight = line), w = 560.0, h = 560.0, fitToSize = true)

"${ribbon.size} tiles on family ${line.family}, line ${line.index}"
```

## Outlines for a plotter, and the dual graph

`lines(fill = false)` gives one stroked, closed outline per rhombus. Shared edges are drawn
twice, once by each neighbour, so dedupe them if your plotter minds. `adjacency()` lists, for
every tile, the indices of the tiles across its edges: this is the tiling's dual graph, handy for
colouring regions or walking from tile to tile.

```diffstatic_runner {data-lang=kotlin screenshot="assets/debruijn_tilings/plotter.png" wait="2.0" capture="box" open-outputs="true" w="760" padding="14"}
// Outlines only, one closed FLine per rhombus: what a pen plotter wants. Each tile's two parallel
// pairs of edges are perpendicular to the two grid lines that made it.
val tiling = deBruijnTiling(symmetry = 5, radius = 5.0, scale = 16.0)
val outlines = tiling.lines(fill = false, outlineThickness = 1.2, palette = listOf(vec(0.92, 0.92, 0.88, 1.0)))
lines(outlines, w = 480.0, h = 480.0, fitToSize = true)
"${outlines.size} closed outlines, ${tiling.adjacency().sumOf { it.size } / 2} shared edges"
```

## Sliding the grid

Moving one family's offset slides its lines. Most tiles stay where they are; small groups flip
into a new arrangement as lines pass through crossings. Run this box and drag the sliders.

```diffstatic_runner {data-lang=kotlin screenshot="assets/debruijn_tilings/slider.png" wait="2.0" capture="box" open-outputs="true" w="760" padding="14"}
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
