# Tracking features through time and scale

A [Morse–Smale complex](morse_smale.md) describes one field at one moment. When the field changes,
because it is a frame of animation, a simulation step, or the same image blurred a little more,
you usually want to know which peak *now* is which peak *then*. `FeatureTracker` answers that. It
matches each moment's features to the last moment's, so every feature gets a **track**: a stable id,
a path, the moment it was born and the moment it died.

This example moves six hills along circular paths for 90 frames, computes the complex of every
frame, and follows its maxima. Each track is drawn in its own colour, ending in a dot where the hill
is now:

```diffstatic_runner {data-lang=kotlin screenshot="assets/topology_tracking/hills.png" alt="a viridis field on dark purple with six soft yellow-green hills, each crossed by a coloured line ending in a dot on the hill: smooth arcs and ellipses tracing where each hill has travelled over 90 frames, with a caption reading 7 tracks over 90 frames" wait="5" timeout="240" capture="box" open-outputs="true" padding="20" requires="morse-smale" name="tracking-hills" embed=true}
import afield.geometry.topology.morsesmale.*
import afield.geometry.topology.tracking.*

// Six hills circling at different speeds, sampled for 90 frames. Each frame's maxima are matched to
// the last frame's, so each hill keeps one track for as long as it stays distinct.
val hills = (0 until 6).map { i -> Triple(0.4 + 0.25 * i, 30.0 + 9.0 * i, 1.0 - 0.1 * i) }
fun field(t: Double) =
    Img.generate(200, 150) { x, y ->
        hills
            .withIndex()
            .sumOf { (i, h) ->
                val (speed, radius, amplitude) = h
                val cx = 100 + radius * cos(speed * t + i)
                val cy = 75 + radius * 0.7 * sin(speed * t * 1.3 + i)
                amplitude * exp(-((x - cx).pow(2) + (y - cy).pow(2)) / 120.0)
            }
            .toFloat()
    }

val tracker = FeatureTracker(MatchingWeights(birthDistance = 10.0, maxDistance = 20.0))
for (frame in 0 until 90) {
    val ms = MorseSmale.compute(field(frame / 40.0), persistence = 0.05)
    tracker.add(frame.toDouble(), ms.features(minPersistence = 0.05, types = setOf(CriticalType.MAXIMUM)))
}

val last = field(89 / 40.0).normalized().colormap("viridis")
showImgGrid(
    "${tracker.tracks.size} tracks over 90 frames" to
        last.drawFLines(tracker.toFLines(thickness = 2.0) + tracker.aliveFLine(9.0), scale = 3.0),
    columns = 1,
    cellSide = 600,
)
```

Six hills give seven tracks: one track ended when its hill passed close to another, and a new one
started when they parted. In this example the overlap produces a single peak, so the tracker records
the merge and the later split.

!!! note "Needs the Morse–Smale library"
    The examples compute Morse–Smale complexes, which need the optional library: see the welcome
    page's **Geometry extensions** panel. The tracker itself is plain Kotlin and works on features
    from anywhere.

## How features are matched

`FeatureTracker.add(time, features)` matches the new features to the previous ones at the least
total cost, by the Hungarian method (Field's `HungarianAssignment`, the same solver behind
`assignment` and flow matching). The costs come from `MatchingWeights`:

- matching two features of the same type costs their squared distance, plus `valueWeight` times the
  squared change in value and persistence;
- leaving a feature unmatched costs `birthDistance` squared plus `valueWeight` times half its squared
  persistence. That is the price of letting it die or be born, so a faint feature dies easily and a
  strong one does not;
- features further apart than `maxDistance` are never matched, and minima never match maxima.

The persistence terms are the lifted Wasserstein distance between persistence diagrams that Soler,
Plainchault, Conche and Tierny introduced for tracking topological features (2018).

When things move further between moments than these distances allow, pass a motion estimate:
`tracker.add(time, features) { x, y -> dx to dy }` moves each previous feature by your prediction
before matching. Optical flow, the particles of a [particle video](particle_video.md) or a
simulation's velocity field can all supply it.

The tracks are ordinary data: `tracker.tracks`, each with its `samples` over time; `born` and `died`
after each `add`; `toFLines()` and `aliveFLine()` for drawing; and `network()` for the tracks as a
`GeometryNetwork`, optionally in three dimensions with time as z.

## Through scale

The same matching works across scale instead of time. `ScaleSpace.track` blurs an image at a list of
increasing σ, computes the complex at each, and tracks the extrema from each σ to the next. A feature
that survives a large blur is large. One that dies at the first blur is fine detail. This example
tracks the dark places in the leaf print and draws each one that survives to σ 3 or more as a circle
twice the largest σ it reached:

```diffstatic_runner {data-lang=kotlin screenshot="assets/topology_tracking/scale.png" alt="two tall panels of the leaf print. On the right, over the darkened photograph, coloured circles mark the dark features that survive blurring: two large overlapping circles in the middle of the leaf, where the whole leaf is one dark blob at large scale, smaller circles on its lobes and at the frame's corners, each with a short line showing how its position drifted as the blur grew" wait="6" timeout="240" capture="box" open-outputs="true" padding="20" requires="morse-smale" name="tracking-scale" embed=true}
import afield.geometry.topology.morsesmale.*
import afield.geometry.topology.tracking.*

// The leaf print's dark spots (minima of its brightness), followed as the image is blurred more and
// more. A feature that survives a large blur is large; one that dies at the first is fine detail.
val print = imgFromFile("public/docs/assets/vision/fline-viewer.jpg", 3).crop(78, 262, 128, 236).scaleBy(2.0)
val sigmas = [1.0, 2.0, 3.0, 4.0, 6.0, 8.0, 11.0, 16.0]
val space =
    ScaleSpace.track(print.luma(), sigmas, persistence = 0.02, maxDistance = 14.0, types = setOf(CriticalType.MINIMUM))

// Each feature that survives to σ 3 or more, as a circle where it starts with radius twice the largest
// σ it reached (the displayed diameter is four times σ), and its path through scale.
val lasting = space.tracker.tracks.filter { space.scaleOf(it) >= 3.0 }
val circles = lasting.map { t ->
    val (x, y) = t.samples.first().second.let { it.x to it.y }
    FLine().circle(x, y, 2 * space.scaleOf(t)).also {
        it.color = FeatureTracker.colourOf(t.id)
        it.thickness = 1.5
    }
}
val tracks = lasting.map { track ->
    track.toFLine().apply {
        color = FeatureTracker.colourOf(track.id)
        thickness = 1.5
    }
}
val dark = Img(print.width, print.height, 3, FloatArray(print.width * print.height * 3))
showImgGrid(
    "photograph" to print,
    "by scale" to print.overlay(dark, 0.55).drawFLines(circles + tracks),
    columns = 2,
    cellSide = 320,
)
```

## Comparing fields by their topology

`persistenceDiagramDistance(a, b)` measures how different two persistence diagrams are: the least
total cost of moving one diagram's (birth, death) pairs onto the other's, or onto the diagonal where
pairs vanish (the p-Wasserstein distance, `p = 2` by default). Two fields with the same peaks and
pits in the same places at the same heights score 0; small changes in shape score little. It is
solved exactly by default; with `epsilon` it uses the entropic Sinkhorn solver, which handles the
tens of thousands of pairs a noisy image has.

```diffstatic_runner {data-lang=kotlin screenshot="assets/topology_tracking/distance.png" alt="a table of persistence diagram distances from field A: A against itself 0 exact, B with its three hills moved a few pixels about 0.1, C with five hills about 0.8, the entropic column agreeing to within a few thousandths; below it the three viridis fields side by side" wait="4" timeout="240" capture="box" open-outputs="true" padding="20" requires="morse-smale" name="tracking-distance" embed=true}
import afield.geometry.topology.morsesmale.*
import afield.geometry.topology.tracking.*

// Three hills, the same hills nudged a few pixels, and a different set of five.
fun hills(centres: List<Pair<Double, Double>>) =
    Img.generate(160, 120) { x, y ->
        centres.sumOf { (cx, cy) -> exp(-((x - cx).pow(2) + (y - cy).pow(2)) / 150.0) }.toFloat()
    }
val frameA = hills([40.0 to 40.0, 100.0 to 60.0, 70.0 to 95.0])
val frameB = hills([43.0 to 41.0, 98.0 to 63.0, 72.0 to 92.0])
val frameC = hills([30.0 to 30.0, 60.0 to 30.0, 120.0 to 40.0, 50.0 to 90.0, 110.0 to 95.0])

fun diagram(f: Img) = MorseSmale.compute(f, persistence = 0.02).persistenceDiagram(dimension = 1)
val a = diagram(frameA)
val rows = ["A, the same field" to a, "B, hills moved" to diagram(frameB), "C, a different field" to diagram(frameC)]
markdown(
    "distances",
    buildString {
        appendLine("| A against | pairs | exact | entropic (ε = 1e-4) |")
        appendLine("| --- | --- | --- | --- |")
        for ((label, d) in rows) {
            appendLine(
                "| $label | ${d.size} | ${persistenceDiagramDistance(a, d).f4} | ${persistenceDiagramDistance(a, d, epsilon = 1e-4).f4} |",
            )
        }
    },
)
showImgGrid(
    "A" to frameA.normalized().colormap("viridis"),
    "B" to frameB.normalized().colormap("viridis"),
    "C" to frameC.normalized().colormap("viridis"),
    columns = 3,
    cellSide = 200,
)
```

The moved hills score about a tenth of the different field. The entropic solver lands within a few thousandths of the exact distance, which is also why it gives A against itself a small positive number rather than 0.

## Cerf diagrams: when the topology changes

A tracker says where each feature went. A **Cerf diagram** says when the field's topology changed.
It plots the value of every critical point (every minimum, saddle and maximum) against time. A
changing field alters its critical points in only two ways. A pair of neighbouring index can appear or
cancel together: a maximum with a saddle, or a minimum with a saddle. And two critical values can swap
order. `CerfDiagram` follows every critical point with a `FeatureTracker` and marks both kinds of event.

This example puts a small hill on the flank of a broad one and raises it from nothing and back down.
At first the flank only has a shoulder. Once the small hill is tall enough, a maximum (red) and a saddle
(green) appear together at an orange dot. When it shrinks again they meet and cancel at a violet one.
The field beside the diagram shows frame 20, where the cursor is:

```diffstatic_runner {data-lang=kotlin screenshot="assets/topology_tracking/cerf_flank.png" alt="left, a Cerf diagram on dark grey: a flat red line at the top for the broad hill's peak, and below it a red arc and a green arc that start together at an orange dot, rise, and meet again at a violet dot, crossed by a white vertical cursor in the middle; flat blue and green lines along the bottom for the minima and saddles on the frame's edge. Right, the field at the cursor in viridis: a big yellow hill and a smaller one to its right, each with a red dot, and a green saddle dot between them" wait="4" timeout="240" capture="box" open-outputs="true" padding="16" trim="true" requires="morse-smale" name="tracking-cerf-flank" embed=true}
import afield.geometry.topology.morsesmale.*
import afield.geometry.topology.tracking.*

// A broad hill with a smaller one on its flank whose height rises from nothing and falls back.
fun field(t: Double) =
    Img.generate(90, 70) { x, y ->
        fun hill(cx: Double, s: Double) = exp(-((x - cx).pow(2) + (y - 35.0).pow(2)) / (2 * s * s))
        (hill(35.0, 10.0) + 0.8 * sin(PI * t) * hill(55.0, 5.0)).toFloat()
    }

val cerf = CerfDiagram()
for (frame in 0..40) cerf.add(frame.toDouble(), MorseSmale.compute(field(frame / 40.0)), minPersistence = 1e-3)

val diagram =
    Img.filled(300, 200, 1, 0.08f).drawFLines(cerf.toFLines(300.0, 200.0, cursor = 20.0, thickness = 2.0), scale = 2.0)
val view = field(0.5).normalized().colormap("viridis").drawFLines(cerf.fieldMarkers(20.0, 7.0), scale = 4.0)
showImgGrid("Cerf diagram, cursor at frame 20" to diagram, "frame 20" to view, columns = 2, cellSide = 420)
```

`cerf.add(time, result, minPersistence)` takes one Morse–Smale result per frame. A birth is a new
saddle whose persistence partner in that frame is a new extremum, so the two dots of a pair are
always one event. `cerf.events(CerfEventType.BIRTH)` lists the births, and `DEATH` and `SWAP` work
the same way. Each event has its `time`, `value`, the `tracks` involved and its position `x`, `y` in
the field. `pairIndex` is 0 for a minimum–saddle pair and 1 for a saddle–maximum pair. The flat
lines along the bottom are critical points on the edge of the grid, where the field is cut off.

### A simulation whose topology churns

The diagram is most useful beside a field you did not design. This box runs a
[Gray–Scott reaction](alife_examples.md) from three seed patches in its spot regime, where every
spot grows, splits in two and pushes its daughters apart until spots fill the field. It records
120 frames, 40 steps apart, and draws the diagram with its cursor at frame 70 beside the field at that frame.
`fieldMarkers(t)` marks the critical points alive at `t`, and puts a large orange or violet dot
where a pair is born or cancels within `window` frames of it:

```diffstatic_runner {data-lang=kotlin screenshot="assets/topology_tracking/cerf_grayscott.png" alt="two panels. Left, a dense Cerf diagram: a band of thin red maximum curves near the top and a band of green saddle curves below it, both rising out of a few curves at the left and fanning into dozens that weave through each other, blue minimum curves along the bottom, orange birth dots and violet cancellation dots scattered between the bands, and a white cursor at frame 70. Right, the Gray–Scott field at frame 70 in magma: a front of magenta spots spreading from both sides of the frame across a black middle, a green saddle dot between neighbouring spots, and a few large orange and violet dots where spots are splitting" wait="8" timeout="300" capture="box" open-outputs="true" padding="20" trim="true" requires="morse-smale" name="tracking-cerf-grayscott" embed=true}
import afield.geometry.topology.morsesmale.*
import afield.geometry.topology.tracking.*

// Three seed patches in Gray–Scott's spot regime, where each spot grows, splits in two and pushes its
// daughters apart until the spots fill the field. 120 frames, 40 steps apart: 4,800 steps in all.
var state = grayScottSeed(96, 96, patches = 3, patchSize = 10, seed = 7)
val frames = ArrayList<Img>()
val cerf = CerfDiagram()
for (frame in 0 until 120) {
    state = state.stepTimes(GRAY_SCOTT_SPOTS, 40)
    val v = state.v.gaussianBlur(1.5)
    frames.add(v)
    cerf.add(frame.toDouble(), MorseSmale.compute(v, persistence = 0.05), minPersistence = 0.05)
}

val births = cerf.events(CerfEventType.BIRTH).size
val deaths = cerf.events(CerfEventType.DEATH).size
val swaps = cerf.events(CerfEventType.SWAP).size

fun show(t: Double) {
    val diagram =
        Img.filled(480, 320, 1, 0.08f)
            .drawFLines(cerf.toFLines(480.0, 320.0, cursor = t, showSwaps = false, thickness = 1.0), scale = 2.0)
    val view =
        frames[t.toInt()].colormap("magma", 0f, 0.4f).drawFLines(cerf.fieldMarkers(t, 4.0, window = 1.0), scale = 5.0)
    showImgGrid(
        "$births births, $deaths cancellations, $swaps swaps" to diagram,
        "frame ${t.toInt()}" to view,
        columns = 2,
        cellSide = 480,
    )
}

show(70.0)
```

The few curves at the left are the seed patches. Each time a spot divides, a new maximum is born with
the saddle that separates it from its sister, so the red and green bands thicken together. Spots
squeezed out by their neighbours cancel with a saddle at a violet dot. Inside each band the curves
cross all the time: the run has thousands of swaps, which is why this box passes `showSwaps = false`
to `toFLines`. Call `show` with another frame, or wrap it in a `slider` as the
[`cerf_explorer`](agent_transcripts.md#cerf_explorer) demo does, to watch each event's dots light up on the field in the frame where it happens. Raise `minPersistence` until the diagram shows only the changes you care about.

!!! note "Sampled in time"
    `CerfDiagram` looks at the field at the frames you give it, so an event is placed at the first
    frame that shows it, not at the instant it happens. With a persistence threshold, a pair counts
    as born when its persistence rises above the threshold, not when it is zero. A pair born and
    cancelled between two frames is missed. Dhar, Chakraborty and Natarajan,
    [*Analyzing Time-Varying Scalar Fields using Piecewise-Linear Morse–Cerf Theory*](https://arxiv.org/abs/2507.00725)
    (2025, free full text on arXiv), compute the diagram exactly by treating time as one more
    dimension of the mesh. That paper is also the source of the Cerf diagram as a tool for this kind
    of analysis.

## Real footage

Tracking the extrema of brightness or colour in filmed footage is harder than in a field you
compute. On cloth, wings or skin, the brightest point moves between features from frame to frame,
and features split and merge as the material deforms. With footage, use the `motion` argument with
a dense motion estimate, and expect tracks that last for parts of a shot rather than all of it.

??? aside "How this is checked"
    `FeatureTrackingTest` checks the matching (a moved feature is matched, a new one is born,
    minima never match maxima, the distance gate holds, a faint feature dies rather than jumps), the
    tracker's identities and events, motion prediction bridging jumps longer than the gate, a
    drifting Gaussian followed for all 30 frames, two merging hills leaving the stronger one as the
    survivor, a large blob outlasting a small one in scale space, and the diagram distance on a hand
    computed case, with the entropic solver within 1% of the exact distance at small epsilon.
    `CerfDiagramTest` raises and lowers a hill on another's flank and checks for exactly one
    saddle–maximum birth and one cancellation, at the frames where a 1-D profile through the two
    hills first and last has a second peak. The same field negated gives one minimum–saddle pair, and
    two crossing values give one swap at the interpolated time and value. Three
    real clips were tried for the footage section: the 1896 serpentine dance from the particle-video
    page, a veil dance and a high-speed butterfly. None gave tracks worth showing without dense
    motion guidance, which is why that section is advice rather than a figure.
