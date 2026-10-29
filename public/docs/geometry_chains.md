# Chaining the geometry extensions

Field's three geometry extensions each answer a different question about an image or a point cloud:

- [Morse–Smale complexes](morse_smale.md) find the ridges and valleys of a scalar field.
- [Polyvector vectorization](polyvector.md) finds the strokes of a drawing.
- [Principal curves](principal_curves.md) find the smooth curves through a cloud of points.

None of them depends on the others. What they share is their output: ordinary `FLine`s and
`GeometryNetwork`s, which any of them, or anything else in Field, can take as input. This page
chains them three ways.

!!! note "Needs the libraries"
    These examples need the Morse–Smale, principal-curve and polyvector libraries. The welcome page's
    **Geometry extensions** panel says which are built and how to build the rest.

## Ridges to a forest

A Morse–Smale complex keeps every ridge that persists, exactly as the field draws it, wiggles and
all. A principal forest can summarise those ridges as a few smooth, branching curves. This example
takes the ridges of the leaf print at persistence 0.03, samples a point at every vertex along them,
and fits a forest of four components with 90 nodes in all:

```diffstatic_runner {data-lang=kotlin screenshot="assets/geometry_chains/ridges_forest.png" alt="two tall panels of the darkened leaf print. On the left, the Morse–Smale ridges as red polylines: long lines down the frame's edges, and a wandering network around the leaf, its lobes and the pale spots. On the right, the same ridges summarised as a principal forest: four smooth orange curves with white dots at their branch points and tips, one down each edge of the frame, one branching from the spots down the leaf's stem, and one looping around the leaf's lower lobes" wait="5" timeout="240" capture="box" open-outputs="true" padding="20" requires="morse-smale,principal-curves" name="chain-ridges-forest" embed=true}
import afield.geometry.curves.principal.*
import afield.geometry.topology.morsesmale.*

// 1. Morse–Smale: the ridges of the leaf print's brightness, keeping only those that persist.
val print = imgFromFile("public/docs/assets/vision/fline-viewer.jpg", 3).crop(78, 262, 128, 236).scaleBy(2.0)
val ms = MorseSmale.compute(print.luma().gaussianBlur(3.0), persistence = 0.03)
val ridges = ms.ascending.curves

// 2. One point per surviving ridge vertex. Persistence already chose the ridges; fit them with
//    the default equal weights.
val ridgePoints =
    ridges
        .flatMap { ridge ->
            (0 until ridge.vertexCount).flatMap { index ->
                val point = ridge.vertex(index)
                [point.x.toDouble(), point.y.toDouble()]
            }
        }
        .toDoubleArray()

// 3. A principal forest: four separate branching curves summarising the weighted ridge points.
val tree = PrincipalCurves.fit(ridgePoints, curves = 4, nodes = 90, allowBranching = true)
val junctions = tree.network.nodesFLine().apply {
    pointed = true
    pointSize = 6.0
    color = Vec4(1f, 1f, 1f, 1f)
}

val dark = Img(print.width, print.height, 3, FloatArray(print.width * print.height * 3))
val dim = print.overlay(dark, 0.6)
showImgGrid(
    "ridges" to dim.drawFLines(ms.toFLines(traceDescending = false, thickness = 1.5)),
    "forest" to dim.drawFLines(tree.toFLines(thickness = 2.5) + junctions),
    columns = 2,
    cellSide = 320,
)
```

The forest is an approximation, not a copy. It keeps the large-scale structure and lets go of
detail and of the complex's exact connectivity. Where two ridges meet at a saddle, the forest may
join them, smooth past the join, or split there, whichever fits the points with least energy. For
the complex's exact topology, use the complex.

## Strokes to smooth curves

Polyvector vectorization traces a drawing into curves, junctions and crossings, retaining the
wobble of the hand. Principal curves can smooth the result while preserving the starting graph's
connections. This example vectorizes the pencil sketch from the
[polyvector page](polyvector.md), uses the resulting curves as the starting graph of a forest, and
refits that graph to the inked pixels with `Growth.REFINE`, which only adds nodes along curves that
already exist:

```diffstatic_runner {data-lang=kotlin screenshot="assets/geometry_chains/strokes_refit.png" alt="two panels on black of the pencil sketch, an A, a post meeting a beam with a stroke crossing it, and a wave. On the left the polyvector curves in orange, following the wave's every wobble. On the right the same curves refitted as principal curves: the wave is now a smooth arc, and the A, the T and the crossing are unchanged" wait="6" timeout="240" capture="box" open-outputs="true" padding="20" requires="polyvector,principal-curves" name="chain-strokes-refit" embed=true}
import afield.geometry.curves.principal.*
import afield.geometry.vectorize.polyvector.*

:::fold the pencil sketch from the polyvector page
// A sketch, drawn the way a hand would: each stroke laid down three times with a little wobble, in a
// soft pencil line about three pixels wide.
val random = java.util.Random(3)
val strokes = [
    [Vec2(40f, 200f), Vec2(110f, 60f), Vec2(180f, 200f)], // an A
    [Vec2(70f, 140f), Vec2(150f, 140f)], // its crossbar
    [Vec2(220f, 60f), Vec2(220f, 200f)], // a post
    [Vec2(220f, 130f), Vec2(300f, 130f)], // meeting a beam: a T
    [Vec2(250f, 60f), Vec2(340f, 200f)], // and a stroke crossing it: an X
    (0..30).map { Vec2(40f + 300f * it / 30, (245 + 12 * sin(it / 30.0 * 2 * PI)).toFloat()) }, // a wave
]
val w = 380
val h = 280
val ink = FloatArray(w * h)
for (stroke in strokes) repeat(3) {
    val jitter = { random.nextGaussian().toFloat() * 1.5f }
    val pts = stroke.map { Vec2(it.x + jitter(), it.y + jitter()) }
    for (k in 0 until pts.size - 1) {
        val a = pts[k]
        val b = pts[k + 1]
        val n = (a.distance(b) * 2).toInt()
        for (s in 0..n) {
            val t = s.toFloat() / n
            val cx = a.x + (b.x - a.x) * t
            val cy = a.y + (b.y - a.y) * t
            for (y in (cy - 4).toInt()..(cy + 4).toInt()) for (x in (cx - 4).toInt()..(cx + 4).toInt()) {
                if (x !in 0 until w || y !in 0 until h) continue
                val d = hypot(x + 0.5 - cx, y + 0.5 - cy) / 1.6
                ink[y * w + x] = maxOf(ink[y * w + x], (0.8 * exp(-d * d)).toFloat())
            }
        }
    }
}
val drawing = Img(w, h, 3, FloatArray(w * h * 3) { 1f - ink[it / 3] })
:::

// 1. Polyvector: the vectorized as curves, with the right junctions and crossings but the hand's jitter.
val vectorized = Polyvector.vectorize(drawing).toFLines().filter { it.length() > 12f }

// 2. Points: every inked pixel, weighted by how dark it is.
val pts = ArrayList<Double>()
val weights = ArrayList<Double>()
for (i in ink.indices) if (ink[i] > 0.2f) {
    pts.add(i % w + 0.5)
    pts.add(i / w + 0.5)
    weights.add(ink[i].toDouble())
}

// 3. Principal curves, started from the vectorized and refitted to the ink with their topology fixed:
//    REFINE only adds nodes along existing curves, so crossings stay crossings.
val forest = PrincipalCurves.create(
    pts.toDoubleArray(),
    weights = weights.toDoubleArray(),
    parameters = ElasticParameters(mu = 0.5),
)
forest.initFromFLines(vectorized, nodesPerCurve = 6)
forest.grow(forest.nodeCount * 2, Growth.REFINE)

val pencil = Img(w, h, 3, FloatArray(w * h * 3) { 0.04f + 0.7f * ink[it / 3] })
val orange = Vec4(1f, 0.4f, 0.2f, 1f)
showImgGrid(
    "polyvector curves" to
        pencil.drawFLines(
            vectorized.onEach {
                it.color = orange
                it.thickness = 2.0
            },
            scale = 2.0,
        ),
    "refitted as principal curves" to pencil.drawFLines(forest.toFLines(color = orange, thickness = 2.0), scale = 2.0),
    columns = 2,
    cellSide = 440,
)
```

The two curves that cross in the X stay separate, because separate curves in a forest never share a
node. That is the reason to start from the strokes rather than grow a forest from nothing, which
would turn the crossing into a junction (see [crossing or branching](principal_curves.md#crossing-or-branching-who-decides)).

## Ridges as a required graph for strokes

One module can also constrain another. Passing a `GeometryNetwork` as `required` to
`Polyvector.vectorize` makes tracing keep its connectivity: the required curves are added to the
ink so the field follows them, traced curves are cut and joined at the required nodes, and the
result says which required curves no chain of traced curves reproduces. Here the required graph is
the ridges of the sketch's ink, keeping only the ridges that stay on ink along their whole length:

```diffstatic_runner {data-lang=kotlin screenshot="assets/geometry_chains/ridges_required.png" alt="two panels on black of the pencil sketch: an A, a post meeting a beam with a stroke crossing it, and a wave. On the left, in blue, the two Morse–Smale ridges that stay on ink: the upper halves of the A's legs, meeting at its apex, and its crossbar, with white dots at their ends. On the right, the polyvector curves in orange, traced under those ridges and titled 0 of 2 missing, with white nodes where curves meet: at the A's apex, at the left end of its crossbar, at the T, and at a join in the wave" wait="6" timeout="240" capture="box" open-outputs="true" padding="20" requires="morse-smale,polyvector" name="chain-ridges-required" embed=true}
import afield.geometry.topology.morsesmale.*
import afield.geometry.vectorize.polyvector.*

:::fold the pencil sketch from the polyvector page
// A sketch, drawn the way a hand would: each stroke laid down three times with a little wobble, in a
// soft pencil line about three pixels wide.
val random = java.util.Random(3)
val strokes = [
    [Vec2(40f, 200f), Vec2(110f, 60f), Vec2(180f, 200f)], // an A
    [Vec2(70f, 140f), Vec2(150f, 140f)], // its crossbar
    [Vec2(220f, 60f), Vec2(220f, 200f)], // a post
    [Vec2(220f, 130f), Vec2(300f, 130f)], // meeting a beam: a T
    [Vec2(250f, 60f), Vec2(340f, 200f)], // and a stroke crossing it: an X
    (0..30).map { Vec2(40f + 300f * it / 30, (245 + 12 * sin(it / 30.0 * 2 * PI)).toFloat()) }, // a wave
]
val w = 380
val h = 280
val ink = FloatArray(w * h)
for (stroke in strokes) repeat(3) {
    val jitter = { random.nextGaussian().toFloat() * 1.5f }
    val pts = stroke.map { Vec2(it.x + jitter(), it.y + jitter()) }
    for (k in 0 until pts.size - 1) {
        val a = pts[k]
        val b = pts[k + 1]
        val n = (a.distance(b) * 2).toInt()
        for (s in 0..n) {
            val t = s.toFloat() / n
            val cx = a.x + (b.x - a.x) * t
            val cy = a.y + (b.y - a.y) * t
            for (y in (cy - 4).toInt()..(cy + 4).toInt()) for (x in (cx - 4).toInt()..(cx + 4).toInt()) {
                if (x !in 0 until w || y !in 0 until h) continue
                val d = hypot(x + 0.5 - cx, y + 0.5 - cy) / 1.6
                ink[y * w + x] = maxOf(ink[y * w + x], (0.8 * exp(-d * d)).toFloat())
            }
        }
    }
}
val drawing = Img(w, h, 3, FloatArray(w * h * 3) { 1f - ink[it / 3] })
:::

// 1. Morse–Smale: the ridges of the ink, keeping those whose every vertex is on ink.
val ms = MorseSmale.compute(Img(w, h, 1, ink).gaussianBlur(1.5), persistence = 0.2)
val ridges = ms.ascending.filterCurves { ridge -> ridge.samples.getValue("value").data.min() > 0.3 }

// 2. Polyvector, traced so that the ridges' connectivity is kept.
val traced = Polyvector.vectorize(drawing, required = ridges)

val pencil = Img(w, h, 3, FloatArray(w * h * 3) { 0.04f + 0.7f * ink[it / 3] })
fun dots(nodes: FLine) = nodes.apply {
    pointed = true
    pointSize = 7.0
    color = Vec4(1f, 1f, 1f, 1f)
}
showImgGrid(
    "required ridges" to
        pencil.drawFLines(
            ridges.toFLines().onEach {
                it.color = Vec4(0.3f, 0.6f, 1f, 1f)
                it.thickness = 2.0
            } + dots(ridges.nodesFLine()),
            scale = 2.0,
        ),
    "traced, ${traced.missingCurves.size} of ${ridges.curves.size} missing" to
        pencil.drawFLines(
            traced.network.toFLines().onEach {
                it.color = Vec4(1f, 0.4f, 0.2f, 1f)
                it.thickness = 2.0
            } + dots(traced.network.nodesFLine()),
            scale = 2.0,
        ),
    columns = 2,
    cellSide = 440,
)
```

The constraint is soft. A required curve that crosses bare paper, as many ridges of a line drawing
do between one stroke and the next, cannot be traced, and it is listed in `traced.missingCurves`
rather than drawn in; `traced.satisfied` is true only when nothing is missing. A required node
that tracing stopped short of is joined to the nearest traced junction on its curve within `reach`
pixels (12 by default).
