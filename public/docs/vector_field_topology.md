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
val features = [
    [50.0, 45.0, 1.0, 0.6],
    [150.0, 40.0, -1.0, -0.8],
    [100.0, 100.0, 0.2, 1.2],
    [40.0, 115.0, -0.8, 0.0],
    [165.0, 115.0, 0.9, -0.3],
]
val flow =
    Img.generateRGB(200, 150) { x, y ->
        var u = 0.0
        var v = 0.0
        for ((cx, cy, out, swirl) in features) {
            val dx = x - cx
            val dy = y - cy
            val k = exp(-(dx * dx + dy * dy) / 900.0)
            u += k * (out * dx - swirl * dy)
            v += k * (out * dy + swirl * dx)
        }
        Triple(u.toFloat(), v.toFloat(), 0f)
    }

val topology = VectorFieldTopology.compute(flow, minStrength = 0.01)
val black = Img(200, 150, 3, FloatArray(200 * 150 * 3))
showImgGrid(
    "${topology.criticalPoints.size} critical points, ${topology.separatrices.size} separatrices" to
        black.drawFLines(
            topology.streamlines(spacing = 9.0, length = 14.0) +
                topology.toFLines() +
                topology.criticalPointsFLine(8.0),
            scale = 3.0,
        ),
    columns = 1,
    cellSide = 600,
)
```

The flow is treated as linear across each small triangle of the grid. Critical points are found
within that interpolated field, each with its own Jacobian; detail absent from the samples cannot
be recovered. The type comes from
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
    "${topology.criticalPoints.size} points" to
        print.overlay(dark, 0.6).drawFLines(topology.toFLines() + topology.criticalPointsFLine(7.0), scale = 2.0),
    columns = 2,
    cellSide = 300,
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

## Flow from old film

A flow can come from footage too. [Video particles](particle_video.md) tracks thousands of points
through a movie, and `video.flow()` spreads their velocities onto a grid, one flow per frame. This
clip is from *Golden Eagles*, a 1930s Kodak Cinegraph nature film: an eagle crossing a field to
land on a falconer's fist. Its flow is in `assets/vector_field_topology/eagle.flow.gz`, saved with
`flow.save(...)` from the tracks so the page does not need the tracker.

Taken as it is, the flow describes the camera more than the bird. The film weaves in the gate, so
the whole frame shifts and turns a little every frame, and the topology of that motion sweeps
across the sky:

```diffstatic_runner {data-lang=kotlin screenshot="assets/vector_field_topology/eagle_raw.png" alt="four darkened frames of an old film of a falconer in a field with trees behind, an eagle flying in from the right and landing on his raised fist. Over each, dense pale streamlines run across the whole frame, mostly in parallel sweeps through the sky, bending around the trees and the bird, with a few orange and blue separatrices and coloured critical points where the sweeps meet" wait="5" timeout="300" capture="box" open-outputs="true" padding="20" name="vector-field-eagle-raw" embed=true}
import afield.geometry.topology.vectorfield.*

// The eagle's flow, one grid per frame (cells of 4 pixels), and the film it came from.
val flow = loadParticleFlow("public/docs/assets/vector_field_topology/eagle.flow.gz")
val film = videoFrames("public/docs/assets/vector_field_topology/eagle.mp4", width = 480).toList()

fun panel(frame: Int, camera: CameraMotion, minSpeed: Double): Pair<String, Img> {
    val topology =
        VectorFieldTopology.compute(flow.at(frame, window = 1, camera = camera), minStrength = 0.02, maxLength = 300.0)
    val lines =
        topology.streamlines(spacing = 1.5, length = 14.0, minSpeed = minSpeed) +
            topology.toFLines() +
            topology.criticalPointsFLine(6.0)
    val picture = film[frame]
    val dark = Img(picture.width, picture.height, 3, FloatArray(picture.width * picture.height * 3))
    return "frame $frame" to
        picture.overlay(dark, 0.55).drawFLines(lines.map { it * flow.cell.toDouble() }, scale = 2.0)
}
showImgGrid(
    *[60, 120, 210, 270].map { panel(it, CameraMotion.KEEP, minSpeed = 0.0) }.toTypedArray(),
    columns = 2,
    cellSide = 480,
)
```

The whole clip in this look, rendered by the second box of the
[`eagle_flow_explorer`](agent_transcripts.md#eagle_flow_explorer) starting point:

<video controls muted loop playsinline preload="metadata" width="960" height="588" poster="../assets/vector_field_topology/eagle_raw.png" style="width:100%;height:auto">
  <source src="assets/vector_field_topology/eagle_flow_raw.mp4" type="video/mp4">
</video>

The flow's critical points depend on what is taken to be standing still. `CameraMotion.AFFINE`
fits the camera's motion to all the particles in each frame (a shift, a slight turn and zoom),
refits it without the particles that disagree, which are mostly the bird's, and subtracts it.
`CameraMotion.SHIFT` subtracts only the median velocity, which is not enough here: the weave turns
as well as shifts. With the camera taken out, and streamlines drawn only where the flow is faster
than a pixel per frame (`minSpeed = 1.0`), the flow is the eagle's:

```diffstatic_runner {data-lang=kotlin screenshot="assets/vector_field_topology/eagle_bird.png" alt="the same four darkened frames. Now the sky and trees are clear, and short pale streamlines cover only the eagle: a patch on its body as it flies in low, hatching along both spread wings as it banks towards the falconer, and around its wings and body as it lands on his fist, with orange and blue separatrices running from saddles near the bird" wait="5" timeout="300" capture="box" open-outputs="true" padding="20" name="vector-field-eagle-bird" embed=true}
import afield.geometry.topology.vectorfield.*

:::fold the same loading and drawing as above
val flow = loadParticleFlow("public/docs/assets/vector_field_topology/eagle.flow.gz")
val film = videoFrames("public/docs/assets/vector_field_topology/eagle.mp4", width = 480).toList()

fun panel(frame: Int, camera: CameraMotion, minSpeed: Double): Pair<String, Img> {
    val topology =
        VectorFieldTopology.compute(
            flow.at(frame, window = 1, camera = camera),
            minStrength = 0.02,
            maxLength = 300.0,
            minSpeed = minSpeed,
        )
    val lines =
        topology.streamlines(spacing = 1.5, length = 14.0, minSpeed = minSpeed) +
            topology.toFLines() +
            topology.criticalPointsFLine(6.0)
    val picture = film[frame]
    val dark = Img(picture.width, picture.height, 3, FloatArray(picture.width * picture.height * 3))
    return "frame $frame" to
        picture.overlay(dark, 0.55).drawFLines(lines.map { it * flow.cell.toDouble() }, scale = 2.0)
}
:::
showImgGrid(
    *[60, 150, 210, 270].map { panel(it, CameraMotion.AFFINE, minSpeed = 1.0) }.toTypedArray(),
    columns = 2,
    cellSide = 480,
)
```

Both pictures are the same footage read in two frames of reference.

??? aside "Where the footage came from"
    *Golden Eagles* (Kodak Cinegraph, 1930s), public domain, from the Internet Archive item
    `55294-golden-eagles` (Periscope Film). The clip starts 5 min 9 s in and runs 17 s; it was
    cropped of its pillarbox and burned-in timecode and tracked with `particleTracker()` at 480
    pixels wide, 16,000 particles, on Apple Metal (`device = "mps"`). The 393 MB of tracks were
    reduced to the 8.6 MB flow with `loadParticleVideo(...).flow(cell = 4, sigma = 1.5).save(...)`.

## Following a flow through time

`topology.features()` turns the critical points into the `Feature`s that
[tracking](topology_tracking.md) matches from one moment to the next: sinks as minima, sources as
maxima, saddles as saddles, with `strength` as their persistence. So the swirls of a flow can be
followed through time just like the peaks of a field:

```diffstatic {data-lang=kotlin}
import afield.geometry.topology.tracking.*
import afield.geometry.topology.vectorfield.*

val tracker = FeatureTracker(MatchingWeights(birthDistance = 10.0, maxDistance = 20.0))
for ((time, flow) in frames) tracker.add(time, VectorFieldTopology.compute(flow, minStrength = 0.01).features())
```

Over the eagle film, from its approach to the landing, the tracker follows the swirls frame by frame. Only one lasts twelve frames or more: a saddle that rides the banking wing.

```diffstatic_runner {data-lang=kotlin screenshot="assets/vector_field_topology/eagle_tracks.png" alt="the last frame of the eagle clip, darkened, with one white zigzag track over the eagle's near wing, labelled 1 track over frames 150 to 270" wait="5" timeout="300" capture="box" open-outputs="true" padding="20" name="vector-field-eagle-tracks" embed=true}
import afield.geometry.topology.tracking.*
import afield.geometry.topology.vectorfield.*
import afield.geometry.topology.morsesmale.CriticalType

val flow = loadParticleFlow("public/docs/assets/vector_field_topology/eagle.flow.gz")
val film = videoFrames("public/docs/assets/vector_field_topology/eagle.mp4", width = 480).toList()

// The bird's own motion, frame by frame from its approach to the landing, into one tracker.
val tracker = FeatureTracker(MatchingWeights(birthDistance = 10.0, maxDistance = 30.0))
for (frame in 150..270) {
    val topology = VectorFieldTopology.compute(flow.at(frame, window = 1, camera = CameraMotion.AFFINE), minStrength = 0.005, minSpeed = 0.5)
    tracker.add(frame.toDouble(), topology.features())
}

// Every track seen in at least twelve frames, over the last frame: sinks blue, sources red, saddles white.
val long = tracker.tracks.filter { it.samples.size >= 12 }
val lines = long.map { track ->
    (track.toFLine() * flow.cell.toDouble()).apply {
        thickness = 1.5
        color = when (track.type) {
            CriticalType.MINIMUM -> vec(0.4, 0.6, 1.0, 0.9)
            CriticalType.MAXIMUM -> vec(1.0, 0.35, 0.3, 0.9)
            else -> vec(1.0, 1.0, 1.0, 0.8)
        }
    }
}
val picture = film[270]
val dark = Img(picture.width, picture.height, 3, FloatArray(picture.width * picture.height * 3))
showImgGrid("${long.size} track${if (long.size == 1) "" else "s"} over frames 150 to 270" to picture.overlay(dark, 0.6).drawFLines(lines, scale = 2.0), columns = 1, cellSide = 720)
```
