# Eigendrum: hear the shape of a drum

*Draw a closed shape and Field tells you how it sounds as a drum: it finds the shape's vibration
modes, the pitches they ring at, where each one is still, and plays a strike as the sum of them.
The idea comes from [eigendrum.com](https://eigendrum.com), which does this in the browser; this
page builds the same thing in Field from the textbook method, in `afield.audio.modal`.*

The quickest way in is the starting point. Open **Starting points** on the welcome screen and search
for **eigendrum**. Its fifth box makes a drum from whatever you last drew on the drawing layer.
[Browse the starting point ↗](offline.html?file=assets/agent_transcripts/eigendrum/metadata.edn)

A drum head clamped at its rim vibrates in *modes*: shapes u that satisfy −∇²u = λu inside the
shape and u = 0 on the rim. Each mode rings at a frequency proportional to √λ, so the list of λ is
the drum's spectrum. Three calls do the work:

- `drumMesh(polygon, h)` triangulates the region inside a polygon (alternating x, y coordinates)
  with triangles about `h` across; `drumMeshFitted(fline)` does the same for an FLine of any size,
  and `polarDrumMesh(h) { t -> r(t) }` for a polar curve.
- `mesh.modes(16)` returns the sixteen lowest modes: `lambda`, `ratios()`, `frequencies(f0)`, and a
  shape for each.
- `modes.strike(x, y)` says how strongly a mallet at (x, y) excites every mode, and
  `modes.render(amplitudes)` turns that into a sound, one decaying sine per mode.

## Sixteen modes of a square

A unit square is the drum whose answer we know: λ = π²(m² + n²), so 19.74, then 49.35 twice, then
78.96. The table lists the sixteen lowest, each with a player that sounds that mode alone; below it
are the first eight, orange where the head moves up and blue where it moves down.

```diffstatic_runner {data-lang=kotlin screenshot="assets/eigendrum/modes.png" embed=true alt="A table of sixteen modes of a square drum with λ, frequency ratio, frequency and an audio player for each, starting at λ 19.740; below it eight squares coloured orange and blue in checkerboard patterns of one, two, three and four lobes"}
import afield.audio.modal.*

// A unit-square drum. Its lowest sixteen modes, each a row you can play on its own;
// the analytic spectrum is π²(m² + n²), so λ₁ ≈ 19.74.
val square = drumMesh(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0), 0.04)
val modes = square.modes(16)
display("modes", modes.modeTable(fundamental = 110.0))
lines(
    (0 until 8).flatMap { k ->
        modes.modeMesh(k).map { it.copy().apply { transform { v -> v.add((k % 4) * 1.1f, (k / 4) * 1.1f, 0f) } } }
    },
    w = 520.0,
    h = 270.0,
    fitToSize = true,
)
```

Modes come in pairs where the square's symmetry allows (2 and 3, 5 and 6): two shapes at one
pitch. Within such a pair the solver may return any mixture of the two, so a pair's pictures can
look rotated against the textbook ones.

## A drum from a polar equation

Any curve can be a drum. Here r(θ) = 1 + 0.3 cos 5θ, a five-lobed flower: on the left the mesh,
on the right the sixth mode, coloured by displacement, with its nodal line (where the head stays
still) in white.

```diffstatic_runner {data-lang=kotlin screenshot="assets/eigendrum/polar.png" embed=true alt="On the left a five-lobed flower shape filled with a fine triangle mesh; on the right the same flower coloured by its sixth mode, an orange disc in the middle ringed by a white nodal line, with a blue patch in each of the five lobes"}
import afield.audio.modal.*

// r(θ) = 1 + 0.3 cos 5θ: a five-lobed drum. Left, the triangle mesh;
// right, its sixth mode coloured by displacement, nodal lines in white.
val mesh = polarDrumMesh(0.06) { t -> 1 + 0.3 * cos(5 * t) }
val modes = mesh.modes(8)
val wire = mesh.wireframe().apply {
    color = vec(0.8, 0.8, 0.8, 0.6)
    thickness = 0.5
}
val shape = modes.modeMesh(5).map { it.copy().apply { transform { v -> v.add(3f, 0f, 0f) } } }
val nodes = modes.nodalLines(5).copy().apply {
    transform { v -> v.add(3f, 0f, 0f) }
    color = vec(1.0, 1.0, 1.0, 1.0)
    thickness = 1.5
}
lines(listOf(wire) + shape + listOf(nodes), w = 520.0, h = 260.0, fitToSize = true)
println("${mesh.nodeCount} nodes, ${mesh.triangleCount} triangles, ratios ${modes.ratios().map { it.f3 }}")
```

```diffstatic_runner {data-lang=kotlin audio="assets/eigendrum/polar.wav" audio-spec="polar" audio-renderer="afield.audio.modal.EigendrumDocs" embed=true hidden=true name="eigendrum-polar"}
// offline-rendered by EigendrumDocs.renderWav("polar", ...): the flower struck off-centre
```

## Two shapes, one spectrum

In 1966 Mark Kac asked whether you can hear the shape of a drum, that is, whether the spectrum
fixes the shape. In 1992 Gordon, Webb and Wolpert answered no with this pair: two different
polygons, each made of seven half-squares, with exactly the same eigenvalues. The table compares
the first ten computed for each; they agree to better than 0.05%, which is the size of the
discretisation error itself.

```diffstatic_runner {data-lang=kotlin screenshot="assets/eigendrum/kac.png" embed=true alt="A table of ten eigenvalues for drums A and B that agree to within 0.05 percent, beside the two different seven-piece polygons outlined in yellow, each coloured by its third mode; below, two audio players"}
import afield.audio.engine.WavFloatArrayWriter
import afield.audio.modal.*

// Gordon, Webb and Wolpert's isospectral pair: you cannot hear the shape of a drum.
val a = drumMesh(KacPair.drumA, 0.06).modes(10)
val b = drumMesh(KacPair.drumB, 0.06).modes(10)
// λ A and λ B share one colour range, so matching rows come out the same colour.
val spectra = table(
    "mode" to (1..10).toList(),
    "λ A" to a.lambda.take(10),
    "λ B" to b.lambda.take(10),
    "diff %" to (0 until 10).map { 100 * abs(a.lambda[it] - b.lambda[it]) / a.lambda[it] },
)
    .format("λ A") { (it as Double).f3 }
    .format("λ B") { (it as Double).f3 }
    .format("diff %") { (it as Double).f3 }
    .heat("λ A", "λ B", by = HeatScope.TABLE)
    .heat("diff %", colormap = "magma")
display("spectra", spectra)
val outlineA = polygonFLine(KacPair.drumA).apply {
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 2.0
}
val outlineB = polygonFLine(KacPair.drumB).apply {
    transform { v -> v.add(4f, 0f, 0f) }
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 2.0
}
val shapeA = a.modeMesh(2)
val shapeB = b.modeMesh(2).map { it.copy().apply { transform { v -> v.add(4f, 0f, 0f) } } }
lines(shapeA + shapeB + listOf(outlineA, outlineB), w = 520.0, h = 220.0, fitToSize = true)
val strikeA = a.render(a.strike(1.5, 1.5, 0.05), fundamental = 140.0, seconds = 2.0)
val strikeB = b.render(b.strike(1.5, 1.5, 0.05), fundamental = 140.0, seconds = 2.0)
display("drum A", WavFloatArrayWriter.encodeMono16(strikeA, 44100))
display("drum B", WavFloatArrayWriter.encodeMono16(strikeB, 44100))
```

The same notes do not make the same sound. Struck at the same point the two drums ring at the same
pitches, but in different proportions, because a point means different things on different shapes:

```diffstatic_runner {data-lang=kotlin audio="assets/eigendrum/kac-a.wav" audio-spec="kac-a" audio-renderer="afield.audio.modal.EigendrumDocs" embed=true hidden=true name="eigendrum-kac-a"}
// offline-rendered by EigendrumDocs.renderWav("kac-a", ...): drum A struck at (1.5, 1.5)
```

```diffstatic_runner {data-lang=kotlin audio="assets/eigendrum/kac-b.wav" audio-spec="kac-b" audio-renderer="afield.audio.modal.EigendrumDocs" embed=true hidden=true name="eigendrum-kac-b"}
// offline-rendered by EigendrumDocs.renderWav("kac-b", ...): drum B struck at (1.5, 1.5)
```

## Where you strike

A mallet excites each mode in proportion to how much that mode moves under it. Strike on a nodal
line and that mode stays silent. The centre of a square lies on a nodal line of every mode with an
even m or n, so a centre strike leaves out more than half the spectrum (the bars, one per mode)
and sounds hollower than a strike off-centre:

```diffstatic_runner {data-lang=kotlin screenshot="assets/eigendrum/strike.png" embed=true alt="Two copies of a square drum coloured by its second mode with a white nodal line across the middle; on the left a yellow dot on the nodal line and above it a sparse row of bars, several of them zero; on the right a dot off the line and a full row of bars"}
import afield.audio.engine.WavFloatArrayWriter
import afield.audio.modal.*

// A square drum struck on the centre, which lies on a nodal line of every mode with m or n even,
// and off-centre. The bars are how strongly each of the 16 modes rings.
val modes = drumMesh(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0), 0.05).modes(16)
val spots = [0.5 to 0.5, 0.31 to 0.22]
val out = ArrayList<FLine>()
spots.forEachIndexed { s, (px, py) ->
    val dx = s * 2.4f
    out += modes.modeMesh(1).map { it.copy().apply { transform { v -> v.add(dx, 0f, 0f) } } }
    out +=
        modes.nodalLines(1).copy().apply {
            transform { v -> v.add(dx, 0f, 0f) }
            color = vec(1.0, 1.0, 1.0, 1.0)
            thickness = 1.5
        }
    out +=
        fline { circle(px + dx, py, 0.04) }
            .apply {
                color = vec(1.0, 0.85, 0.3, 1.0)
                filled = true
            }
    val amps = modes.strike(px, py)
    val peak = amps.maxOf { abs(it) }
    amps.forEachIndexed { k, a ->
        out +=
            fline {
                moveTo(dx + k * 0.06, -0.15)
                lineTo(dx + k * 0.06, -0.15 - 0.6 * abs(a) / peak)
            }
                .apply {
                    color = vec(1.0, 0.85, 0.3, 1.0)
                    thickness = 3.0
                }
    }
    display("strike ${s + 1}", WavFloatArrayWriter.encodeMono16(modes.render(amps, fundamental = 120.0), 44100))
}
lines(out, w = 520.0, h = 300.0, fitToSize = true)
```

```diffstatic_runner {data-lang=kotlin audio="assets/eigendrum/square-centre.wav" audio-spec="square-centre" audio-renderer="afield.audio.modal.EigendrumDocs" embed=true hidden=true name="eigendrum-square-centre"}
// offline-rendered by EigendrumDocs.renderWav("square-centre", ...): the square struck at its centre
```

```diffstatic_runner {data-lang=kotlin audio="assets/eigendrum/square-off.wav" audio-spec="square-off" audio-renderer="afield.audio.modal.EigendrumDocs" embed=true hidden=true name="eigendrum-square-off"}
// offline-rendered by EigendrumDocs.renderWav("square-off", ...): the square struck at (0.31, 0.22)
```

## Your own drum

Press Alt+D, draw a closed shape with the freedraw tool, and run the fifth box of the starting
point: `notation.strokes.lastOrNull()?.toFLine()` is your stroke, and `drumMeshFitted` scales it to
fit before meshing. With nothing drawn it uses a polar curve, which is what was captured here: the
first four modes and their nodal lines.

```diffstatic_runner {data-lang=kotlin screenshot="assets/eigendrum/draw.png" embed=true alt="Four copies of a rounded three-lobed shape: the first all orange, then split in two by one white nodal line, then by a curved line, then by two lines into three bands"}
import afield.audio.engine.WavFloatArrayWriter
import afield.audio.modal.*

// Draw a closed shape on the drawing layer (Alt+D, freedraw), then run this box.
// With nothing drawn yet it uses a heart-ish polar curve.
val stroke = notation.strokes.lastOrNull()?.toFLine()
val outline = stroke ?: polygonFLine(polarPolygon(200) { t -> 1 + 0.35 * sin(t) * abs(cos(t)) + 0.15 * cos(3 * t) })
val mesh = drumMeshFitted(outline, 32)
val modes = mesh.modes(12)
val pictures =
    (0 until 4).flatMap { k ->
        modes.modeMesh(k).map { it.copy().apply { transform { v -> v.add(k * 1.15f, 0f, 0f) } } } +
            modes.nodalLines(k).copy().apply {
                transform { v -> v.add(k * 1.15f, 0f, 0f) }
                color = vec(1.0, 1.0, 1.0, 1.0)
            }
    }
lines(pictures, w = 520.0, h = 150.0, fitToSize = true)
val c = mesh.x.indices.filter { !mesh.boundary[it] }.minBy { abs(mesh.x[it] - 0.4) + abs(mesh.y[it] - 0.35) }
display(
    "strike",
    WavFloatArrayWriter.encodeMono16(modes.render(modes.strike(mesh.x[c], mesh.y[c]), fundamental = 130.0), 44100),
)
println(
    if (stroke == null) "no stroke yet: using the polar curve" else "drum from your stroke: ${mesh.nodeCount} nodes",
)
```

## A live drum

`render` makes a finished sound. For a drum you play, `ModalDrum(modes)` is a voice in Field's
audio engine: return it from a box and launch the box (it is a `Signal`, like `SineOsc`). Each
mode is a decaying oscillator running on the audio thread. `drum.strike(x, y)` can be called from
any box or callback; it computes the mallet's projection where it is called and hands it to the
audio thread, which adds it at the start of the next block. `every = 1.5` makes the drum strike
itself on a sample-accurate clock.

```diffstatic {data-lang=kotlin}
import afield.audio.modal.*

// The flower drum as a live voice in the audio engine: it strikes itself every 1.5 s.
// Call drum.strike(x, y) from any box or callback to hit it somewhere else.
val modes = polarDrumMesh(0.06) { t -> 1 + 0.3 * cos(5 * t) }.modes(16)
val drum = ModalDrum(modes, fundamental = 110.0, every = 1.5, autoX = 0.35, autoY = 0.1)
drum
```

## A drum that changes shape

`retune(newModes)` swaps the drum's shape while it rings: the oscillators keep their energy and
change pitch. With `morph = shapes`, the voice glides through a list of shapes and back, every
`morphPeriod` seconds, interpolating each mode's frequency on the audio thread. Here nine drums run
from a circle to the five-lobed flower. The table shows the overtones closing in on the
fundamental as the lobes deepen, so the strikes sound lower and more clustered at the flower end.

```diffstatic_runner {data-lang=kotlin screenshot="assets/eigendrum/morph.png" embed=true alt="Nine drum shapes in a row, from a circle to a deep five-lobed flower, each glowing orange with its first mode; below, a table of frequency ratios for modes one to six, the second falling from 1.593 to 1.499 and the fourth from 2.135 to 1.716"}
import afield.audio.modal.*

// Nine drums from a circle to a five-lobed flower. The voice glides through them and back
// every 4 s, striking itself as it goes, so every mode's pitch bends with the outline.
val shapes = (0..8).map { i -> polarDrumMesh(0.07) { t -> 1 + 0.05 * i * cos(5 * t) }.modes(12) }
val outlines = shapes.flatMapIndexed { i, m ->
    m.modeMesh(0).map { it.copy().apply { transform { v -> v.add(i * 2.8f, 0f, 0f) } } }
}
lines(outlines, w = 620.0, h = 100.0, fitToSize = true)
// One row per shape; each mode's column is heated on its own, so you can watch every
// overtone fall as the lobes deepen. Mode 1 is the reference and stays plain.
val modeNames = listOf("f₁", "f₂", "f₃", "f₄", "f₅", "f₆")
val ratios = table(
    "a" to shapes.indices.map { 0.05 * it },
    *modeNames.mapIndexed { k, name -> name to shapes.map { it.ratios()[k] } }.toTypedArray(),
).apply { title = "f / f₁ of modes 1–6" }
    .format("a") { (it as Double).f2 }
modeNames.forEach { name -> ratios.format(name) { (it as Double).f3 } }
ratios.heat(*modeNames.drop(1).toTypedArray())
display("ratios", ratios)
ModalDrum(shapes[0], fundamental = 110.0, every = 0.6, autoX = 0.3, autoY = 0.1, morph = shapes, morphPeriod = 4.0)
```

The interpolation follows modes by their rank, so where two modes cross in pitch partway through
the morph, they swap roles rather than crossing.

## How it is computed

`drumMesh` puts points along the rim every `h`, fills the inside with a triangular lattice of
spacing `h`, and joins them with a Delaunay triangulation. On those triangles, linear finite
elements turn the equation into a matrix problem Kφ = λMφ: K (stiffness) measures how much a shape
bends, M (mass) how much it moves. `modes(n)` finds the n smallest λ by subspace iteration, solving
with a banded Cholesky factor of K. The mass matrix is an average of the consistent and lumped
forms, which cancels most of the error of linear elements: on a unit square and a unit disk with
`h` = 0.04 and 0.05 the lowest eigenvalues land within 0.13% of π²(m² + n²) and of the Bessel
zeros (j₀,₁² ≈ 5.783), which `EigendrumTest` checks. A thousand-node drum solves in well under a
second.

`render` gives mode i the frequency f₀√(λᵢ/λ₁) and decay rate α + β(fᵢ/f₀)², so the high modes die
first, as on a real membrane. Change `alpha` and `beta` for a drier or more ringing head.

For a membrane under steady load rather than vibrating, see
[Pressure membranes](pressure_membranes.md). For playing sound in boxes and the pattern-to-audio
path, see [Sound](sound.md).

The method and the Gordon–Webb–Wolpert coordinates follow Toby Driscoll, "Eigenmodes of isospectral
drums", *SIAM Review* 39 (1997), and Cleve Moler's
[blog post](https://blogs.mathworks.com/cleve/2012/08/06/can-one-hear-the-shape-of-a-drum-part-1-eigenvalues/);
the idea of a drum you draw is [eigendrum.com](https://eigendrum.com)'s.
