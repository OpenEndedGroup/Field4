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

The second half of the page turns the question around. [Raman's harmonic drum](#ramans-harmonic-drum)
loads the head the way a tabla is loaded, which makes its overtones harmonic, and
[Designing a drum](#designing-a-drum) fits the load or the outline to the pitches you want. Its
starting point is **harmonic drum**.
[Browse it ↗](offline.html?file=assets/agent_transcripts/harmonic_drum/metadata.edn)

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
).format("λ A") { (it as Double).f3 }
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

```diffstatic_runner {data-lang=kotlin trim=true screenshot="assets/eigendrum/strike.png" embed=true alt="Two copies of a square drum coloured by its second mode with a white nodal line across the middle; on the left a yellow dot on the nodal line and above it a sparse row of bars, several of them zero; on the right a dot off the line and a full row of bars"}
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

Press ++alt+d++, draw a closed shape with the freedraw tool, and run the fifth box of the starting
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
val modeNames = ["f₁", "f₂", "f₃", "f₄", "f₅", "f₆"]
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

## Raman's harmonic drum

A plain circular drum has no clear pitch: its overtones ring at 1.59, 2.14 and 2.30 times the
fundamental, not at 2, 3 and 4. The tabla and the mridangam fix this by loading the middle of the
head with a heavy black paste, the syahi. C. V. Raman worked out how in 1919 (reported in *Nature*
in 1920 and in full in 1935): the load pulls the nine lowest modes into five tones a harmonic series
apart, and the third, fourth and fifth tones are each two or three different modes tuned to one
pitch.

| tone | modes (nodal diameters, nodal circles counting the rim) |
| --- | --- |
| 1 | (0,1) |
| 2 | (1,1) |
| 3 | (2,1) and (0,2) |
| 4 | (3,1) and (1,2) |
| 5 | (4,1), (2,2) and (0,3) |

A load is a density on the drum head. `mesh.withDensity { x, y -> ... }` gives every triangle the
density there (the bare skin is 1), and `syahi(radius, massRatio)` is a disc of paste centred on the
origin. Ramakrishna and Sondhi modelled the tabla in 1954 as exactly this: a disc of radius 0.4 at
3.125² ≈ 9.77 times the skin's density. Below, that drum's nine mode families, one column per tone.
The table puts each family's frequency, with (1,1) set to 2, beside the bare drum's, the exact value
for this model and the frequencies measured on a real drum. `ramanHarmonicity()` measures how far the
fifteen lowest eigenvalues are from 1 : 2 2 : 3 3 3 : 4 4 4 4 : 5 5 5 5 5, in cents (hundredths of a
semitone) after the best overall tuning.

```diffstatic_runner {data-lang=kotlin screenshot="assets/eigendrum/raman.png" embed=true alt="A table of nine mode families with their frequencies on a bare drum, on the loaded drum, in the exact composite-membrane model and as measured, the loaded column close to 1, 2, 3, 3, 4, 4, 5, 5, 5; below it nine discs in five columns, each coloured orange and blue by one mode, with white nodal lines and a dark ring marking the edge of the paste"}
import afield.audio.modal.*

// A unit drum loaded like Ramakrishna & Sondhi's tabla: density ×9.77 inside radius 0.4.
// Its nine lowest mode families, drawn in Raman's five columns, one per harmonic.
val bare = polarDrumMesh(0.05) { 1.0 }
val bareModes = bare.modes(15)
val modes = bare.withDensity(syahi(0.4, 3.125 * 3.125)).modes(15)
val labels = modes.circularLabels()
val pictures = ArrayList<FLine>()
val perTone = IntArray(6)
Raman.families.forEach { (n, m, tone) ->
    val k = labels.indexOfFirst { it.diameters == n && it.circles == m }
    val dx = (tone - 1) * 2.3f
    val dy = perTone[tone]++ * 2.3f
    pictures += modes.modeMesh(k).map { it.copy().apply { transform { v -> v.add(dx, dy, 0f) } } }
    pictures +=
        modes.nodalLines(k).copy().apply {
            transform { v -> v.add(dx, dy, 0f) }
            color = vec(1.0, 1.0, 1.0, 1.0)
            thickness = 1.5
        }
    pictures +=
        fline { circle(dx.toDouble(), dy.toDouble(), 0.4) }
            .apply {
                color = vec(0.1, 0.1, 0.1, 1.0)
                thickness = 1.0
            }
}
val families = table(
    "mode" to Raman.families.map { (n, m, _) -> "($n,$m)" },
    "tone" to Raman.families.map { it.third },
    "bare" to bareModes.ramanFamilies().toList(),
    "loaded" to modes.ramanFamilies().toList(),
    "exact" to Raman.compositeExact.toList(),
    "measured" to Raman.measured.toList(),
)
listOf("bare", "loaded", "exact", "measured").forEach { c -> families.format(c) { (it as Double).f3 } }
display("families", families)
lines(pictures, w = 520.0, h = 330.0, fitToSize = true)
println("bare drum: ${bareModes.ramanHarmonicity()}")
println("loaded drum: ${modes.ramanHarmonicity()}")
```

The loaded drum is about 36 cents from a harmonic series; the bare one is 190 cents away. Most of what
is left is the fundamental, a few percent sharp, which the measured drum shares. The finite-element
values agree with the exact ones to within 1%, which `HarmonicDrumTest` checks, along with the
claim that the fifteen lowest modes fall into Raman's five groups exactly as the table above lists
them.

Listen to the difference. The bare drum, struck halfway between the centre and the rim:

```diffstatic_runner {data-lang=kotlin audio="assets/eigendrum/disk-bare.wav" audio-spec="disk-bare" audio-renderer="afield.audio.modal.EigendrumDocs" embed=true hidden=true name="eigendrum-disk-bare"}
// offline-rendered by EigendrumDocs.renderWav("disk-bare", ...): a bare disk struck at (0.62, 0.15)
```

and the same drum with the syahi:

```diffstatic_runner {data-lang=kotlin audio="assets/eigendrum/disk-raman.wav" audio-spec="disk-raman" audio-renderer="afield.audio.modal.EigendrumDocs" embed=true hidden=true name="eigendrum-disk-raman"}
// offline-rendered by EigendrumDocs.renderWav("disk-raman", ...): Ramakrishna & Sondhi's loaded disk struck at (0.62,
// 0.15)
```

### Two modes at one pitch

Because (2,1) and (0,2) ring at the same pitch, any mix of them is also a way the drum can vibrate.
Raman damped the head with his fingers to pick a mix and scattered sand on it to see the nodal lines:
a circle, then ellipses, then two parallel lines across the drum, so the head divides into three like
a string sounding its third harmonic, then hyperbolas, and at the far end two diameters. The same
mixes of the loaded drum's two modes, from all (0,2) on the left to all (2,1) on the right:

```diffstatic_runner {data-lang=kotlin screenshot="assets/eigendrum/sand.png" embed=true alt="Six discs with white nodal lines: a small circle, an ellipse, a longer ellipse, a long narrow loop nearly spanning the disc as two parallel lines, two hyperbolas, and two crossing diameters"}
import afield.audio.modal.*

// Mixes of the loaded drum's (0,2) and (2,1) modes, which share one pitch: the nodal lines
// Raman saw in sand, from a circle to two parallel lines to two diameters.
val modes = polarDrumMesh(0.04) { 1.0 }.withDensity(syahi(0.4, 3.125 * 3.125)).modes(10)
val labels = modes.circularLabels()
val ring = labels.indexOf(CircularLabel(0, 2))
val cross = labels.indexOf(CircularLabel(2, 1))
val mixes = [0.0, 0.6, 1.0, 1.25, 2.5, 1000.0]
val out = ArrayList<FLine>()
mixes.forEachIndexed { i, w ->
    val dx = i * 2.3f
    out +=
        fline { circle(dx.toDouble(), 0.0, 1.0) }
            .apply {
                color = vec(0.5, 0.5, 0.5, 1.0)
                thickness = 1.0
            }
    out +=
        modes.mesh.zeroLines(modes.superpose(ring, cross, w)).apply {
            transform { v -> v.add(dx, 0f, 0f) }
            color = vec(1.0, 1.0, 1.0, 1.0)
            thickness = 2.0
        }
}
lines(out, w = 520.0, h = 110.0, fitToSize = true)
```

### The whole range of loadings

How heavy, and how wide? `harmonicityMap(radii, massRatios)` solves a loaded drum for every pair
and scores each one. Radius runs across from 0.15 to 0.7 of the rim and density ratio up from 1 (no
paste) to 30, spaced evenly in log; bright cells are close to a harmonic series. The white ring is
Ramakrishna and Sondhi's tabla.

```diffstatic_runner {data-lang=kotlin screenshot="assets/eigendrum/map.png" embed=true alt="A twelve by twelve grid of coloured cells, dark along the bottom row and at the left and right edges, with a bright band running from small heavy loads at the top down to wider, lighter ones in the middle; a white ring sits in the brightest part of the band"}
import afield.audio.modal.*

// Loading radius across (0.15–0.7 of the rim), density ratio up (1–30, log spaced); each
// cell is one loaded drum's rms distance from 1:2:3:4:5, bright where it is close.
val map = harmonicityMap(linSpace(0.15, 0.7, 12), logSpace(1.0, 30.0, 12), h = 0.07)
val tabla = [Raman.compositeRadius to Raman.compositeMassRatio]
lines(map.cells(w = 12.0, h = 12.0, marks = tabla), w = 400.0, h = 400.0, fitToSize = true)
val (i, j) = map.best
println("most harmonic cell: radius ${map.radii[j].f2}, density × ${map.massRatios[i].f2}, ${map.cents[i][j].f1} cents")
println("bare skin: ${map.cents[0][0].f1} cents")
```

The most harmonic cell is radius 0.4 at about ten times the skin's density, Ramakrishna and Sondhi's
own choice. Around it runs a valley: a smaller patch has to be heavier, a wider one lighter. Makers'
drums sit along it; Sathej and Adhikari give densities of 6 to 16 times the skin over 45–55% of the
radius. Above about twenty times the skin the scores hardly change: the paste then does nearly all
the moving, and the bare skin acts only as a spring.

## Designing a drum

The map answers one question: given a load, how harmonic is it? Turned around, the question
becomes: given the pitches you want, which drum plays them? Field's least-squares solver,
`leastSquares`, can answer it with either of two knobs: the paste, or the outline.

### Shape alone cannot reach the octave

For a uniform drum of any shape, the second mode is at most 1.593 times the first. Ashbaugh and
Benguria proved this in 1992, and the circle is the shape that reaches it. So no outline can put
the first overtone an octave up, and every uniform drum stays far from Raman's series. Forty random
outlines show it. Across is each drum's f₂/f₁, with the white line at 1.593; up is its distance from
1:2:3:4:5. The uniform drums are blue, and Ramakrishna and Sondhi's loaded drum is the yellow dot.

```diffstatic_runner {data-lang=kotlin screenshot="assets/eigendrum/shapes.png" embed=true alt="A scatter of forty blue dots, all left of a vertical white line and high up, beside a single yellow dot far to the right and low down"}
import afield.audio.modal.*

// Forty random uniform drums: f₂/f₁ across (1.0–2.1), distance from 1:2:3:4:5 up (0–400 cents).
// The white line is f₂/f₁ = 1.593, the most any uniform drum can reach; yellow is the syahi.
val disk = polarDrumMesh(0.08) { 1.0 }
val rnd = java.util.Random(3)
val shapes = (0 until 40).map {
    PolarShape.circle(5).apply {
        for (k in 2..5) {
            cosine[k] = 0.5 * (rnd.nextDouble() - 0.5) / k
            sine[k] = 0.5 * (rnd.nextDouble() - 0.5) / k
        }
    }
}
val scores =
    shapes
        .parallelStream()
        .map { s ->
            val m = disk.polarMorphed(s).modes(15)
            m.ratios()[1] to m.ramanHarmonicity().rms
        }
        .toList()
val syahi = loadedDisk(Raman.compositeRadius, Raman.compositeMassRatio, h = 0.07).modes(15)
fun dot(f: Double, cents: Double) = fline { circle((f - 1.0) * 400, 400 - cents, 6.0) }.apply { filled = true }
val out = ArrayList<FLine>()
out +=
    fline {
        moveTo(0.0, 0.0)
        lineTo(440.0, 0.0)
        lineTo(440.0, 400.0)
        lineTo(0.0, 400.0)
        lineTo(0.0, 0.0)
    }
        .apply { color = vec(0.4, 0.4, 0.4, 1.0) }
out +=
    fline {
        moveTo(0.593 * 400, 0.0)
        lineTo(0.593 * 400, 400.0)
    }
        .apply {
            color = vec(1.0, 1.0, 1.0, 1.0)
            thickness = 1.5
        }
scores.forEach { (f, c) -> out += dot(f, c).apply { color = vec(0.35, 0.6, 1.0, 1.0) } }
out += dot(syahi.ratios()[1], syahi.ramanHarmonicity().rms).apply { color = vec(1.0, 0.85, 0.3, 1.0) }
lines(out, w = 440.0, h = 400.0, fitToSize = true)
println(
    "highest f₂/f₁ of 40 shapes: ${scores.maxOf { it.first }.f3}; closest to 1:2:3:4:5: ${scores.minOf { it.second }.f1} cents",
)
println("loaded disk: f₂/f₁ ${syahi.ratios()[1].f3}, ${syahi.ramanHarmonicity().rms.f1} cents")
```

### Fitting the paste

`designSyahi(layers)` keeps the circle and fits the paste instead. The paste is a stack of discs,
each with its own radius and added density, like the layers a maker presses on one at a time. Raman
noted that the paste "is stepped down by three, five or seven stages towards the margin". The fit
starts from Ramakrishna and Sondhi's single disc and keeps the centre at no more than 16 times the
skin, the top of the range makers use. Each step needs only one solve, because the gradient is
exact: making a patch 1% heavier lowers each mode's λ by that mode's share of energy on the patch,
times 1%.

```diffstatic_runner {data-lang=kotlin screenshot="assets/eigendrum/syahi.png" embed=true timeout="300" alt="A table of syahi designs with one, two and three layers, their radii, centre densities and distances from a harmonic series falling from about 27 to 19 cents; below it four density profiles, high at the centre on the left and stepping down to the bare skin towards the rim on the right"}
import afield.audio.modal.*

// Fit a syahi of one, two and three stacked layers to 1:2:3:4:5, each starting from
// Ramakrishna & Sondhi's disc. Below, each design's density from the centre (left) to the rim.
val designs = (1..3).toList().parallelStream().map { designSyahi(it, h = 0.08) }.toList()
display(
    "designs",
    table(
        "layers" to designs.map { it.layers },
        "radii" to designs.map { d -> d.radii.sorted().joinToString(" ") { it.f2 } },
        "centre density" to designs.map { it.peak.f1 },
        "start (cents)" to designs.map { it.startFit.rms.f1 },
        "fitted (cents)" to designs.map { it.fit.rms.f1 },
        "worst (cents)" to designs.map { it.fit.worst.f1 },
    ),
)
val colours = [vec(0.35, 0.6, 1.0, 1.0), vec(1.0, 0.85, 0.3, 1.0), vec(1.0, 0.45, 0.3, 1.0)]
val profiles = designs.mapIndexed { i, d -> d.profile(w = 3.0, h = 1.0, top = 16.0).apply { color = colours[i] } }
val composite = fline {
    moveTo(0.0, 1 - 9.77 / 16)
    lineTo(1.2, 1 - 9.77 / 16)
    lineTo(1.2, 1 - 1.0 / 16)
    lineTo(3.0, 1 - 1.0 / 16)
}
    .apply { color = vec(0.6, 0.6, 0.6, 1.0) }
lines(profiles + composite, w = 520.0, h = 180.0, fitToSize = true)
```

The grey line is the single disc the fits start from; blue, yellow and red are one, two and three
layers. Two layers take the drum from about 27 cents to about 21, and a third adds little. The fits
keep Raman's grouping, (0,2) with (2,1), (1,2) with (3,1), and so on, without being told it: the
target is just the fifteen numbers. The two-layer drum:

```diffstatic_runner {data-lang=kotlin audio="assets/eigendrum/disk-syahi.wav" audio-spec="disk-syahi" audio-renderer="afield.audio.modal.EigendrumDocs" embed=true hidden=true name="eigendrum-disk-syahi"}
// offline-rendered by EigendrumDocs.renderWav("disk-syahi", ...): the fitted two-layer syahi struck at (0.62, 0.15)
```

### A drum that plays a chord

Without paste, an outline can still be fitted to any target it can reach. `designShape(target)`
bends r(θ) as a short Fourier series (`PolarShape`) until the lowest modes sound the target. Here
the target is a major triad, 4:5:6. The starting guess is your last drawn stroke, as in
[Your own drum](#your-own-drum), fitted with three harmonics by `PolarShape.fromLine`; with nothing
drawn it is the heart from that section. Grey is the start, yellow the fitted drum, followed by its
three lowest modes.

```diffstatic_runner {data-lang=kotlin screenshot="assets/eigendrum/triad.png" embed=true alt="A grey rounded three-lobed outline with a yellow, more lopsided outline over it, then three copies of the yellow shape coloured by its first three modes: all orange, then split in two by one curved nodal line, then into three by two"}
import afield.audio.engine.WavFloatArrayWriter
import afield.audio.modal.*

// Bend your last drawn stroke (or the heart) into a uniform drum whose three lowest
// modes sound a major triad, 4:5:6.
val stroke = notation.strokes.lastOrNull()?.toFLine()
val start = if (stroke != null) PolarShape.fromLine(stroke, 3) else PolarShape.fromPolygon(EigendrumDocs.heart, 3)
val design = designShape([4.0, 5.0, 6.0], start = start, h = 0.08)
val modes = polarDrumMesh(0.05) { 1.0 }.polarMorphed(design.shape).modes(12)
val out = ArrayList<FLine>()
out +=
    design.start.outline().apply {
        color = vec(0.6, 0.6, 0.6, 1.0)
        thickness = 2.0
    }
out +=
    design.shape.outline().apply {
        color = vec(1.0, 0.85, 0.3, 1.0)
        thickness = 2.0
    }
for (k in 0 until 3) {
    val dx = (k + 1) * 2.8f
    out += modes.modeMesh(k).map { it.copy().apply { transform { v -> v.add(dx, 0f, 0f) } } }
    out +=
        modes.nodalLines(k).copy().apply {
            transform { v -> v.add(dx, 0f, 0f) }
            color = vec(1.0, 1.0, 1.0, 1.0)
        }
}
lines(out, w = 520.0, h = 140.0, fitToSize = true)
println("start ${design.startFit}, fitted ${design.fit}; ratios ${modes.ratios().take(3).map { (4 * it).f3 }}")
display(
    "strike",
    WavFloatArrayWriter.encodeMono16(modes.render(modes.strike(0.3, 0.25, 0.04), fundamental = 220.0), 44100),
)
```

```diffstatic_runner {data-lang=kotlin audio="assets/eigendrum/triad.wav" audio-spec="triad" audio-renderer="afield.audio.modal.EigendrumDocs" embed=true hidden=true name="eigendrum-triad"}
// offline-rendered by EigendrumDocs.renderWav("triad", ...): the heart bent into a 4:5:6 drum, struck at (0.3, 0.25)
```

### Morphing between them

The live voice glides between any drums with the same number of modes. This one runs from the bare
drum through Ramakrishna and Sondhi's syahi to the fitted one-layer syahi and back, every four
seconds, striking itself between the paste and the rim. The table lists the overtones it passes
through.

```diffstatic_runner {data-lang=kotlin screenshot="assets/eigendrum/raman-morph.png" embed=true alt="A table with three rows, bare, composite and one layer, of frequency ratios for modes two to eight, the bare row starting 1.593 and the loaded rows close to 2, 3 and 4"}
import afield.audio.modal.*

// From the bare drum to Raman's harmonic drum and back, every 4 s, as a live voice.
val bare = polarDrumMesh(0.07) { 1.0 }.modes(15)
val composite = loadedDisk(Raman.compositeRadius, Raman.compositeMassRatio, h = 0.07).modes(15)
val fitted = designSyahi(1, h = 0.08).modes
val drums = [bare, composite, fitted]
val names = ["f₂", "f₄", "f₆", "f₇", "f₉", "f₁₁", "f₁₅"]
val picks = [1, 3, 5, 6, 8, 10, 14]
val spectra = table(
    "drum" to ["bare", "composite", "one layer"],
    *names.mapIndexed { c, name -> name to drums.map { it.ratios()[picks[c]] } }.toTypedArray(),
)
names.forEach { n -> spectra.format(n) { (it as Double).f3 } }
display("spectra", spectra)
ModalDrum(bare, fundamental = 220.0, every = 0.7, autoX = 0.62, autoY = 0.15, morph = drums, morphPeriod = 4.0)
```

The loaded drum follows C. V. Raman, "The Indian musical drums", *Proc. Indian Acad. Sci.* A 1,
179–188 (1935), and C. V. Raman and S. Kumar, "Musical drums with harmonic overtones", *Nature* 104,
500 (1920); the composite membrane is B. S. Ramakrishna and M. M. Sondhi, *J. Acoust. Soc. Am.* 26,
523 (1954). The measured and exact frequencies in the table are from G. Sathej and R. Adhikari, "The
eigenspectra of Indian musical drums", *J. Acoust. Soc. Am.* 125, 831 (2009),
[arXiv:0809.1320](https://arxiv.org/abs/0809.1320), Table I. The bound on f₂/f₁ is M. S. Ashbaugh and
R. D. Benguria, "A sharp bound for the ratio of the first two eigenvalues of Dirichlet Laplacians and
extensions", *Annals of Mathematics* 135, 601–628 (1992).

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
