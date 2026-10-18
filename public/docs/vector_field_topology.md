# Vector field topology

A flow, whether wind, a fluid simulation, optical flow between frames, or the gradient of an image,
has a skeleton. It has points where it stops: **sources** it pours out of, **sinks** it drains into,
**foci** it spirals around, **centres** it circles, and **saddles** where it arrives from two sides
and leaves to the other two. From each saddle four streamlines, the **separatrices**, run to the
other points or off the frame, and they divide the frame into regions where every streamline starts
in the same place and ends in the same place. `VectorFieldTopology` finds all of these and gives
them back as points and FLines.

This example makes a flow from a few swirls, sources and sinks, and draws its topology over a
light scatter of streamlines: sources and outward spirals in red, sinks and inward spirals in blue,
saddles in white, the separatrices leaving saddles in orange and those arriving in blue:

```diffstatic_runner {data-lang=kotlin screenshot="assets/vector_field_topology/swirls.png" alt="on black, a field of short grey streamlines curling around several centres. Red and blue dots mark where the flow pours out and drains in, white dots mark saddles, and from each white dot four smooth curves run out, orange ones leaving and blue ones arriving, ending on the coloured dots or at the frame's edge, dividing the frame into cells" wait="5" timeout="240" capture="box" open-outputs="true" padding="20" name="vector-field-swirls" embed=true}
import afield.geometry.topology.vectorfield.*

// Each entry is (x, y, outflow, swirl): positive outflow is a source, negative a sink, swirl turns it.
val features = [listOf(50.0, 45.0, 1.0, 0.6), listOf(150.0, 40.0, -1.0, -0.8), listOf(100.0, 100.0, 0.2, 1.2),
    listOf(40.0, 115.0, -0.8, 0.0), listOf(165.0, 115.0, 0.9, -0.3)]
val flow = Img.generateRGB(200, 150) { x, y ->
    var u = 0.0; var v = 0.0
    for ((cx, cy, out, swirl) in features) {
        val dx = x - cx; val dy = y - cy; val k = exp(-(dx * dx + dy * dy) / 900.0)
        u += k * (out * dx - swirl * dy); v += k * (out * dy + swirl * dx)
    }
    Triple(u.toFloat(), v.toFloat(), 0f)
}

val topology = VectorFieldTopology.compute(flow, minStrength = 0.01)
val black = Img(200, 150, 3, FloatArray(200 * 150 * 3))
showImgGrid("${topology.criticalPoints.size} critical points, ${topology.separatrices.size} separatrices" to
    black.drawFLines(topology.streamlines(spacing = 9.0, length = 14.0) + topology.toFLines() + topology.criticalPointsFLine(8.0), scale = 3.0),
    columns = 1, cellSide = 600)
```

The flow is treated as linear across each small triangle of the grid, so every critical point is
found exactly, with its own Jacobian, and nothing is missed between samples. The type comes from
the Jacobian: a negative determinant is a saddle; otherwise the sign of the divergence says out or
in, and complex eigenvalues add the spiral.

## The gradient of an image

Any scalar field gives a flow: its gradient, which runs uphill. `VectorFieldTopology.gradientOf`
computes it. The gradient's sinks are the field's peaks, its sources the pits, and its separatrices
are the ridges and valleys between them: the same skeleton a [Morse–Smale complex](morse_smale.md)
finds, here from plain Kotlin. With `rotate = true` the gradient is turned a quarter turn so the flow
runs *along* the contours, and every peak and pit becomes a centre.

This example takes the brightness of the leaf print, blurred enough that only its larger shapes
remain, and draws the topology of its gradient over it:

```diffstatic_runner {data-lang=kotlin screenshot="assets/vector_field_topology/leaf.png" alt="two tall panels of the leaf print. Left, the photograph. Right, the photograph darkened, with blue dots on its bright places, red dots in its dark places, white saddles between them, and orange and blue curves running from the saddles along the ridges and valleys of the leaf's brightness" wait="5" timeout="240" capture="box" open-outputs="true" padding="20" name="vector-field-leaf" embed=true}
import afield.geometry.topology.vectorfield.*

val print = imgFromFile("public/docs/assets/vision/fline-viewer.jpg", 3).crop(78, 262, 128, 236)
val brightness = print.luma().gaussianBlur(7.0)
val topology = VectorFieldTopology.compute(VectorFieldTopology.gradientOf(brightness))

val dark = Img(print.width, print.height, 3, FloatArray(print.width * print.height * 3))
showImgGrid(
    "photograph" to print.scaleBy(2.0),
    "${topology.criticalPoints.size} points" to print.overlay(dark, 0.6).drawFLines(topology.toFLines() + topology.criticalPointsFLine(7.0), scale = 2.0),
    columns = 2, cellSide = 300,
)
```

## What you get

`topology.criticalPoints` are `FlowCriticalPoint`s: position, `type` (a `FlowCriticalType`:
`SOURCE`, `SINK`, `SADDLE`, `REPELLING_FOCUS`, `ATTRACTING_FOCUS`, `CENTER`), the `jacobian`, and
from it `divergence`, `curl` and `strength`. `of(types)` filters them. `topology.separatrices` are
`Separatrix`es: the `saddle` they start from, whether they are `unstable` (leaving it), their
`points`, and the critical point they `end` at, or null if they left the frame.

For drawing: `toFLines()` for the separatrices, `criticalPointsFLine()` for the points coloured by
type, `streamline(x, y)` for one streamline and `streamlines(spacing)` for a grid of them. The
whole skeleton is also a [GeometryNetwork](geometry_chains.md) through `network()`, with the points
as nodes and the separatrices as curves between them.

`compute` takes the flow as one image, u in channel 0 and v in channel 1 of an RGB image, or as two
one-channel images `compute(u, v)`. `minStrength` drops weak points (noise makes many), and
`centerTolerance` sets how much spiral a centre or how little a focus may have.

## Following a flow through time

`topology.features()` turns the critical points into the `Feature`s that
[tracking](topology_tracking.md) matches from one moment to the next: sinks as minima, sources as
maxima, saddles as saddles, with `strength` as their persistence. So the swirls of a flow can be
followed through time just like the peaks of a field:

```diffstatic {data-lang=kotlin}
import afield.geometry.topology.tracking.*
import afield.geometry.topology.vectorfield.*

val tracker = FeatureTracker(MatchingWeights(birthDistance = 10.0, maxDistance = 20.0))
for ((time, flow) in frames)
    tracker.add(time, VectorFieldTopology.compute(flow, minStrength = 0.01).features())
```
