# A Drawn Morph, Heard

Two strokes on the drawing layer, a circle and a heart. `notation` reads them back as lists of
points, `resampleByLength` gives each the same number of points, evenly spaced, and `matchPoints`
pairs every point of the circle with a point of the heart. Moving each point toward its partner is
a morph. The size of the cloud as it moves is a number per step, and played as pitch it is a tune
you can hear the shape change in.

The two strokes here are written by code, so the page can be rebuilt without anyone holding a pen.
The reading does not know that: two strokes you draw by hand and name "from" and "to" are read the
same way.

## Two strokes, paired

`morphShapes()` draws the circle in blue, starting on its right and going round anticlockwise, and
the heart in red, starting at its bottom point and going clockwise. The circle is drawn the way a
hand draws one, slowly off the start and into the end, so its points are bunched there. The box
reads both strokes back by name, resamples them, and matches them:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_drawn_morph/paired.png" wait="3" capture="box" open-outputs="true" w="1180" h="460" padding="18" timeout="90" name="chain-drawn-morph-paired" embed=true alt="Two copies of a circle and a heart joined by coloured chords. On the left each circle point is joined to the heart point matchPoints gave it, short chords that fan outward evenly; on the right each is joined to the heart point with the same list index, long chords that cross the whole figure. A table beside them gives the point counts and the total squared travel of each pairing."}
notation { morphShapes() }
val drawnFrom = notation.named("from")!!.points
val drawnTo = notation.named("to")!!.points
val from = drawnFrom.resampleByLength(240, closed = true)
val to = drawnTo.resampleByLength(240, closed = true)
val match = matchPoints(from, to)
val partner = match.reorder(to)

:::fold draw the pairs both ways, and the table
fun chord(a: Vec2, b: Vec2, dx: Double) = FLine().moveTo(a.x + dx, a.y).lineTo(b.x + dx, b.y).also {
    it.thickness = 0.8
    it.color = okhsl(atan2(a.y - 260.0, a.x - 520.0) * 180 / PI + 360, 0.85, 0.6, 0.9)
}
lines(from.indices.map { chord(from[it], partner[it], -220.0) } + from.indices.map { chord(from[it], to[it], 220.0) },
    w = 760.0, h = 380.0, fitToSize = true)
val byIndex = from.indices.sumOf { (from[it].x - to[it].x).toDouble().pow(2) + (from[it].y - to[it].y).toDouble().pow(2) }
markdown("travel", """
| | |
|---|---|
| points drawn, circle and heart | ${drawnFrom.size} and ${drawnTo.size} |
| points after resampling | ${from.size} and ${to.size} |
| squared travel, matched (left) | ${"%.0f".format(match.squaredCost)} |
| squared travel, by list index (right) | ${"%.0f".format(byIndex)} |
""", width = 420)
:::endfold
```

Matched, each chord is short and they fan out together. Paired by index, the circle's first point
goes to the heart's first point, which is on the other side of the figure, and every other pair is
dragged round with it. The readout is the total squared distance each pairing asks the points to
travel.

Resampling is what makes the two strokes comparable. Excalidraw keeps a stroke as the points the
pen produced, so the two strokes have different numbers of points, and the circle's are crowded at
its start. `resampleByLength(n)` walks the stroke by arc length and places exactly `n` points along
it at equal distances; `closed = true` treats the stroke as a loop, so the last point is not a
repeat of the first.

## The morph

`morphBetween(from, partner, t)` puts each point a fraction `t` of the way to its partner. Five
steps of each pairing, top matched and bottom by index:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_drawn_morph/morph.png" wait="3" capture="box" open-outputs="true" w="1180" h="460" padding="18" timeout="90" name="chain-drawn-morph-morph" embed=true alt="Two rows of five dot clouds going from a circle to a heart. In the top row, the matched one, every in-between step is a shape between the two, with a few dozen points leaving the outline for the heart's top cleft. In the bottom row, paired by list index, the middle steps fold in on themselves and shrink toward the centre before opening out into the heart."}
notation { morphShapes() }
val from = notation.named("from")!!.points.resampleByLength(240, closed = true)
val to = notation.named("to")!!.points.resampleByLength(240, closed = true)
val partner = matchPoints(from, to).reorder(to)

:::fold draw five steps of each
fun dot(p: Vec2, dx: Double, dy: Double, colour: Vec4) = FLine().also { f ->
    val x = (p.x - 520.0) * 0.5 + dx; val y = (p.y - 260.0) * 0.5 + dy
    f.moveTo(x - 2, y - 2).lineTo(x + 2, y - 2).lineTo(x + 2, y + 2).lineTo(x - 2, y + 2).lineTo(x - 2, y - 2)
    f.color = colour; f.filled = true; f.stroked = false
}
val steps = listOf(0.0, 0.25, 0.5, 0.75, 1.0)
lines(steps.indices.flatMap { s ->
    morphBetween(from, partner, steps[s]).map { dot(it, s * 200.0, 0.0, vec(0.1, 0.45, 0.85, 1.0)) } +
        morphBetween(from, to, steps[s]).map { dot(it, s * 200.0, 200.0, vec(0.88, 0.2, 0.2, 1.0)) }
}, w = 1000.0, h = 400.0, fitToSize = true)
:::endfold
```

Paired by index, the points cross the middle of the figure to reach their partners, so halfway
through the cloud is folded in on itself and smaller than either shape. Matched, the outline stays
an outline, except for the few points that the first figure shows as long chords on its left: the
matching sends them from the top of the circle down into the cleft between the heart's two lobes,
and they cross the inside of the shape to get there.

## The morph, as a number and as sound

`spreadOf(points)` is how big a cloud is: the root-mean-square distance of its points from their
centre. Follow it from `t = 0` to `t = 1` for both pairings:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_drawn_morph/spread.png" wait="3" capture="box" open-outputs="true" w="1180" h="300" padding="18" timeout="90" name="chain-drawn-morph-spread" embed=true alt="Two curves of cloud size against morph fraction. The matched curve falls in a straight line from the circle's size to the heart's; the index-order curve falls far below both before rising to the heart's size at the end."}
notation { morphShapes() }
val from = notation.named("from")!!.points.resampleByLength(240, closed = true)
val to = notation.named("to")!!.points.resampleByLength(240, closed = true)
val partner = matchPoints(from, to).reorder(to)
notation.clear()   // read: take the strokes down so they do not lie over the plot
val ts = (0..50).map { it / 50.0 }
showSignals(
    "matched" to ts.map { spreadOf(morphBetween(from, partner, it)) },
    "by list index" to ts.map { spreadOf(morphBetween(from, to, it)) },
    x = ts, title = "how big the cloud is, through the morph", xTitle = "t", yTitle = "spread", channel = "spread",
)
```

To hear it, the morph goes out to the heart and back in 32 steps, a quarter of a beat each. Each
step is a note: MIDI 72 when the cloud is the circle's size, an octave lower for every halving of
it. `patTimed` makes the notes a pattern of eight beats, and `patRenderStereo` plays it through
`patVoice`, the stand-in synth the pattern pages use:

```diffstatic {data-lang=kotlin}
val notes = mutableListOf<TimedNote>()
val spread0 = spreadOf(from)
for (k in 0 until 32) {
    val t = if (k < 16) k / 15.0 else (31 - k) / 15.0   // out to the heart, then back
    val midi = 72 + 12 * log2(spreadOf(morphBetween(from, partner, t)) / spread0)
    notes += TimedNote(k * 0.25, k * 0.25 + 0.25, midi)
}
val audio = patRenderStereo(patTimed(notes, beats = 8.0), cycles = 2, cps = 0.25,
    voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.004, sustain = 0.6))
```

The matched morph, twice through:

```diffstatic_runner {data-lang=kotlin audio="assets/chain_drawn_morph/drawn-morph-matched.wav" audio-spec="drawn-morph-matched" audio-renderer="afield.four.synth.DrawnMorphDocs" embed=true hidden=true name="chain-drawn-morph-matched-audio"}
// Offline-rendered by DrawnMorphDocs.renderWav("drawn-morph-matched").
```

The same loop with `to` in place of `partner`, the points paired by index:

```diffstatic_runner {data-lang=kotlin audio="assets/chain_drawn_morph/drawn-morph-index.wav" audio-spec="drawn-morph-index" audio-renderer="afield.four.synth.DrawnMorphDocs" embed=true hidden=true name="chain-drawn-morph-index-audio"}
// Offline-rendered by DrawnMorphDocs.renderWav("drawn-morph-index").
```

The heart is smaller than the circle, so the first falls steadily to the heart's pitch and climbs
back. The second falls about twice as far and climbs back before it reaches the heart, which is the
fold in the bottom row of the morph above. The renderer opens a document of its own, draws the same two
strokes, and runs the loop above; `DrawnMorphPageTest` fails if the loop on this page and the one
the renderer runs drift apart, and the `synth.drawn.morph` smoke test fails if matching stops at
least halving the travel or the index-order morph stops dipping at least twice as far.

The pitch the box computes, step by step, for both:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_drawn_morph/pitch.png" wait="3" capture="box" open-outputs="true" w="1180" h="300" padding="18" timeout="90" name="chain-drawn-morph-pitch" embed=true alt="Two staircases of MIDI pitch over 32 steps, out and back. The matched one descends in a straight line to the heart's pitch and back; the index-order one drops about twice as far at the middle of each half. Below, the lowest note of each."}
notation { morphShapes() }
val from = notation.named("from")!!.points.resampleByLength(240, closed = true)
val to = notation.named("to")!!.points.resampleByLength(240, closed = true)
val matched = DrawnMorphDocs.morphNotes(from, matchPoints(from, to).reorder(to))   // the loop above
val byIndex = DrawnMorphDocs.morphNotes(from, to)
notation.clear()

showSignals("matched" to matched.map { it.note }, "by list index" to byIndex.map { it.note },
    title = "pitch per step", xTitle = "step", yTitle = "MIDI", channel = "pitch")
markdown("dips", "lowest note, matched: %.2f  \nlowest note, by list index: %.2f".format(matched.minOf { it.note }, byIndex.minOf { it.note }), width = 400)
```

## Drawing your own

Open **drawn_morph** from the welcome screen's starting points and run `shapes`, then `paired`,
`morph` and `play`. To morph your own shapes, press `alt-d` and draw two closed strokes with the
pen (`p`), then replace `morphShapes()` in `shapes` with a line that names the two strokes you drew
last:

```diffstatic {data-lang=kotlin}
val mine = notation.strokes.filter { it.handDrawn }.takeLast(2)
notation.edit(mine[0]) { name = "from" }
notation.edit(mine[1]) { name = "to" }
```

Run the boxes after `shapes` again and they morph what you drew. A shape with corners, or two
shapes of very different sizes, makes the index-order fold worse.

## Where each piece is documented

* [Drawing and Notation](drawing_and_notation.md) for the drawing layer and `notation`.
* [A Drawn Score, Heard](chain_drawn_score.md) is the nearest chain: the same drawing layer read as
  a score instead of as shapes.
* [Patterns & Rhythm](pattern_language.md) for `patTimed` and the voice.
