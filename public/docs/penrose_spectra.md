# Hearing a Penrose Tiling

A [Penrose tiling](debruijn_tilings.md)'s corners and edges make a graph. Let a particle hop
along the edges and about one state in ten has energy exactly zero, trapped on a few dozen
vertices. Here we'll count those states exactly and draw them on the tiles. Then we'll treat the
tiling as a network of masses and springs, strike it like the [eigendrum](eigendrum.md), and
listen to them.

The quickest way in is the starting point. Open **Starting points** on the welcome screen and search
for **penrose_spectra**.
[Browse the starting point ↗](offline.html?file=assets/agent_transcripts/penrose_spectra/metadata.edn)

Put one site on every vertex of a rhombus tiling and let a particle hop to any vertex one edge away,
all hops equally likely. The energies of this tight-binding model are the eigenvalues of the
graph's adjacency matrix A. Kohmoto and Sutherland (*Phys. Rev. Lett.* 56, 2740, 1986) studied this
model on the Penrose tiling and found a macroscopic number of states at exactly E = 0. Arai, Tokihiro, Fujiwara and Kohmoto
(*Phys. Rev. B* 38, 1621, 1988) showed that those states are *strictly localised*: each one is zero
everywhere outside a finite cluster of vertices. They also proved that the zero states make up at
least 81 − 50τ ≈ 0.0983 of all states, with τ the golden ratio, and conjectured that this is the
exact fraction. Flicker, Simon and Parameswaran (*Phys. Rev. X* 10, 011005, 2020) later found the
same number as the share of vertices that any maximum dimer covering of the Penrose tiling leaves
uncovered.

Four calls do the work. All of them are in `afield.geometry.path` beside `deBruijnTiling`:

- `tiling.vertexGraph()` turns a tiling into a `TilingGraph`, with one vertex per corner and one
  edge per tile edge.
- `graph.zeroModeCount()` counts the E = 0 states exactly. It works out the rank of A by integer
  elimination modulo a large prime, so no rounding is involved, and it handles thirty thousand
  vertices in a fraction of a second.
- `graph.spectrum()` returns every energy and state, solved densely, for a patch of up to a couple
  of thousand vertices.
- `graph.confinedState(v)` finds a strictly localised state through vertex `v`, if there is one.

## Counting the zero states

The box below builds Penrose patches of growing radius and counts their zero states. It sets them
beside two controls with the same number of vertices. One is a square lattice, which is periodic.
The other is the same 5-fold construction with every grid line jittered by up to a tenth of a
spacing. A patch has a ragged rim that can hold zero states of its own, so the last line fits
`zeros = bulk·N + rim·√N` over the three largest patches to separate the two contributions.

```diffstatic_runner {data-lang=kotlin screenshot="assets/penrose_spectra/count.png" embed=true alt="A table of five Penrose patches from 934 to 31,662 vertices whose E = 0 fraction stays between 0.0977 and 0.0985, beside a square lattice falling from 0.037 to 0.006 and a jittered tiling near 0.055; below it the bulk fit 0.0983 against 81 − 50τ = 0.0983"}
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

## Where the confined states live

`spectrum.zeroWeights()` gives, for every vertex, how much of the E = 0 states lies on it. The
weight does not depend on which basis the solver picked for the degenerate level, and it sums to
the number of zero states. On the left each vertex gets a disc sized by its weight. On the right,
enlarged, is the smallest strictly localised state `confinedState` finds near the middle of the
patch, orange where the amplitude is positive and blue where it is negative. The box checks that
A·ψ is zero at every vertex.

```diffstatic_runner {data-lang=kotlin screenshot="assets/penrose_spectra/states.png" embed=true alt="On the left a grey Penrose tiling with orange discs of different sizes on many of its vertices; on the right an enlarged patch of the same tiling with one small ring of alternating orange and blue discs and nothing outside it"}
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

## A gap around zero

The integrated density of states N(E) counts the share of states at or below each energy. Zero
states show up as a vertical jump, and a flat stretch is a gap with no states in it. The grey
curve counts every state of a radius-8 patch. The orange curve weights each state by how much of
it lies in the inner 60% of the patch, which removes states that live on the rim. The right panel
enlarges the energies from −0.5 to 0.5.

```diffstatic_runner {data-lang=kotlin screenshot="assets/penrose_spectra/idos.png" embed=true alt="Two panels of rising curves, grey and orange. On the left the whole spectrum, with a vertical jump in the middle. On the right the energies near zero enlarged: a tall vertical jump at a white tick, flat on either side of it"}
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

## Other symmetries

The same counts at every symmetry `deBruijnTiling` makes, with a fit over four patch sizes each
(about 1,600 to 14,500 vertices), and then two ways of breaking the Penrose tiling: jittering its
grid lines, and moving the sum of its offsets away from zero. A 5-fold grid makes a true Penrose
tiling only when its offsets sum to an integer. Other sums give generalized Penrose tilings,
built from the same two rhombi but with different vertex neighbourhoods. The box takes a few
seconds.

```diffstatic_runner {data-lang=kotlin screenshot="assets/penrose_spectra/sweep.png" embed=true alt="Three tables: zero-state fraction by symmetry, 0.33 at 3-fold falling through 0.08 at 4-fold and 0.10 at 5-fold to about 0.02 at 12-fold; by disorder, from 0.099 down to about 0.03; and by offset sum, 0.099 at zero dropping to 0.045 and climbing back to about 0.1 at one half"}
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

## Drag it yourself

Two sliders rebuild the tiling at any symmetry and disorder, count its E = 0 states exactly, and
draw their weight on the vertices. The patch radius shrinks as the symmetry grows, so the vertex
count stays near 650 and each update takes under a second. Run this box and drag the
sliders.

```diffstatic_runner {data-lang=kotlin screenshot="assets/penrose_spectra/slider.png" trim=true embed=true alt="Two sliders, symmetry at 5 and disorder at 0, above a grey Penrose tiling with orange discs on many of its vertices; below it the line 5-fold, disorder 0.00: 62 E = 0 states of 646 vertices, fraction 0.0960"}
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

## Striking the tiling

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
white tick marks 220 Hz. The rows are in the order of the players.

```diffstatic_runner {data-lang=kotlin screenshot="assets/penrose_spectra/strike.png" trim=true embed=true alt="Three audio players above three rows of orange bars along a pitch axis, a white tick under each row at 220 Hz. In the first row one bar at the tick stands several times taller than the rest; in the second the bar at the tick is missing; in the third the bars spread fairly evenly, none much taller than the rest. Below, the shares at 220 Hz: 31.5%, 0.0% and 7.2%"}
import afield.audio.engine.WavFloatArrayWriter
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
strikes.forEachIndexed { row, (name, modes, v) ->
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
    display(
        name,
        WavFloatArrayWriter.encodeMono16(
            modes.render(a, zeroHz = 220.0, seconds = 3.0, alpha = 1.0, beta = 0.2),
            44100,
        ),
    )
}
lines(out, w = 600.0, h = 300.0, fitToSize = true)
markdown(
    "measure",
    strikes.joinToString(" · ") { (name, modes, v) -> "$name: %.1f%% at 220 Hz".format(100 * modes.zeroShare(v)) },
)
```

The confined vertex puts almost a third of its strike energy into a single pitch, a steady
220 Hz note over the spread of the other modes. The seven-edged vertex has nothing at 220 Hz. The
square lattice spreads its energy over a broad band around 220 Hz. Its 7% at exactly E = 0 comes
from the 2R + 1 zero states counted above, and their average weight per vertex, (2R + 1)/N, shrinks
as the lattice grows.

```diffstatic_runner {data-lang=kotlin audio="assets/penrose_spectra/penrose-confined.wav" audio-spec="penrose-confined" audio-renderer="afield.audio.modal.PenroseSpectraDocs" embed=true hidden=true name="penrose-spectra-confined"}
// offline-rendered by PenroseSpectraDocs.renderWav("penrose-confined", ...): the vertex with the most confined weight
```

```diffstatic_runner {data-lang=kotlin audio="assets/penrose_spectra/penrose-open.wav" audio-spec="penrose-open" audio-renderer="afield.audio.modal.PenroseSpectraDocs" embed=true hidden=true name="penrose-spectra-open"}
// offline-rendered by PenroseSpectraDocs.renderWav("penrose-open", ...): a seven-edged vertex with no confined weight
```

```diffstatic_runner {data-lang=kotlin audio="assets/penrose_spectra/square.wav" audio-spec="square" audio-renderer="afield.audio.modal.PenroseSpectraDocs" embed=true hidden=true name="penrose-spectra-square"}
// offline-rendered by PenroseSpectraDocs.renderWav("square", ...): the square lattice struck at its centre
```

## A live tiling

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

## How it is computed

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
- **A relief that rings.** The [Manifold relief](debruijn_tilings.md#a-relief-through-manifold)
  of the same tiling, with the zero weight as each vertex's height, would make a printable object
  with the confined states raised.
