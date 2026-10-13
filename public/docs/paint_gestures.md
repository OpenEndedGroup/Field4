# Brushes with moving hairs

An ordinary [PaintCanvas](paint_canvas.md) brush places its bristles by rule: they follow the
path, and the tips trail a little behind. `canvas.dynamicBrush(...)` makes a brush whose hairs are
simulated instead. Each hair is a chain of short segments that bends, drags on the surface, sticks
and slides, splays when it is pressed and springs back when it is lifted. Where the hairs touch,
they lay and pick up paint, oil or watercolour. A brush pressed hard and dragged sideways for long
enough keeps a set, as a real one does.

Because the hairs have mass and stiffness, it matters how the hand moves, not only where. A
`TimedGesture` gives the brush's position over time, how hard it presses, and how the handle is
tilted and turned. The same path drawn slowly, fast, with a pause, tilted or rolled gives different
marks.

Here is a round brush drawn along the same curve at 20, 80 and 300 mm/s, and then right to left.
The slow stroke lays the most paint and runs out first; the fast one lays thinner paint and carries
it further.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_gestures/speed.png" alt="four wavy horizontal brushstrokes of dark brown on cream paper. The top three go left to right at increasing speed: the first is dark at its start and runs out into separate streaks of bristle well before its end; the second is solid for longer and fades more gradually; the third, the fastest, is the most even and stays dark nearly to its end, with a slightly beaded edge. The fourth goes right to left, dark and blunt at its right-hand start and fading to the left" wait="20" timeout="600" capture="box" open-outputs="true" padding="20" requires="paint" name="speed" embed=true}
:::static
import afield.paint.*

val canvas = PaintCanvas(900, aspect = 1.5, groundColor = rgb(0.97, 0.96, 0.92), sizeMm = 200.0, seed = 2)
val umber = rgb(0.25, 0.15, 0.1)

for ((i, speed) in listOf(20.0, 80.0, 300.0).withIndex()) {
    val brush = canvas.dynamicBrush(BrushKind.Round, 60.0, hairs = 32).load(umber, stiff = 0.5)
    val y = 90.0 + i * 120
    canvas.stroke(FLine().moveTo(80, y).cubicTo(350, y - 60, 600, y + 70, 920, y), brush, speed = speed, tilt = 35.0)
}
val brush = canvas.dynamicBrush(BrushKind.Round, 60.0, hairs = 32).load(umber, stiff = 0.5)
canvas.stroke(FLine().moveTo(920, 450).cubicTo(600, 520, 350, 390, 80, 450), brush, speed = 80.0, tilt = 35.0)

display("main", canvas.png(relief = 0.6))
```

`dynamicBrush(kind, width, hairs, segments, config)` takes the same `BrushKind` and
[`BrushConfig`](paint_canvas.md#brushes-made-to-order) as an ordinary brush, with the number of
hairs to simulate (32 by default) and the segments in each (5). It loads like one, `load(color,
...)` or `load(tubes)`, and `canvas.stroke(line, brush, pressure, speed, tilt)` drags it along an
FLine. Every stroke is simulated in 1 ms steps, so a long slow stroke takes longer to paint than
with an ordinary brush.

## Gestures

`canvas.gesture(line, speed, pressure, tilt, lean, roll)` turns an FLine into a `TimedGesture`:

- `speed` is in mm/s along the path, so the time a stroke takes depends on the canvas's `sizeMm`.
- `pressure` is one value, a start and an end, or a profile at evenly spaced points along each
  subpath. At 0 the tips just touch, at 1 the ferrule is pressed down by 60% of the hairs' length,
  and more presses harder still. Below 0 the brush is lifted clear of the surface, so a stroke that
  ends below 0 leaves the paper.
- `tilt` is the handle's lean from upright in degrees, and with `lean` (on by default) it leans
  toward where it is going, so the hairs trail behind the way a pulled brush's do.
- `roll` turns the handle about its own axis, which for a flat is the direction of its wide edge.
  By default a flat is held across its path.
- `facets` moves the hand in straight chords of that many canvas units instead of along the curve:
  at each corner the handle turns at once and the hairs whip round, beading a curve with a mark at
  every corner. A path drawn as a polyline does this at its own corners
  ([the sweep](paint_sweeps.md#the-hand) shows how it looks).

A gesture is a list of `TimedGesture.Sample(x, y, t, pressure, tilt, azimuth, roll)`, so you can
also build one yourself, from a pen's samples or a calculation. `faster(k)`, `pause(seconds)`,
`then(other)` and `reversed()` make new gestures from old ones.

Below, top left, a flick: pressed at the start and lifted off over a fast 400 mm/s stroke. Top
right, the same over a slow one. Second, a stroke that stops for 0.6 s halfway and carries on: the
brush held still on the canvas keeps laying paint, so the paint pools under it, and it lays thinner
paint afterwards. Third, a flat rolled through half a turn along its stroke, a ribbon that narrows
to the brush's edge and opens out again. Last, a filbert held upright and laid well over: upright,
only its tips touch, and it runs out sooner.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_gestures/gestures.png" alt="seven brown brushstrokes on cream paper in four rows. Top left, a short fast stroke that starts as a full, blunt mark and tapers off to a thin point where the brush left the paper. Top right, a slower stroke over the same distance that thins gradually and fades into streaks. Second row, a long horizontal stroke, dark in its left half, with a dark ring halfway along where it paused, and much paler after it. Third row, a long ribbon that is full width at its left end, narrows to a thin twisted line in the middle where the flat brush was edge-on, and opens out to full width again with visible bristle lines at its right end. Bottom row, two short strokes: the left, from an upright filbert, fades quickly into a light, ridged texture; the right, laid over, is solid and even to its end" wait="20" timeout="600" capture="box" open-outputs="true" padding="20" requires="paint" name="gestures" embed=true}
:::static
import afield.paint.*

fun line(x0: Double, y0: Double, x1: Double, y1: Double) = FLine().moveTo(x0, y0).lineTo(x1, y1)
val canvas = PaintCanvas(900, aspect = 1.5, groundColor = rgb(0.97, 0.96, 0.92), sizeMm = 200.0, seed = 3)
val umber = rgb(0.25, 0.15, 0.1)
fun round() = canvas.dynamicBrush(BrushKind.Round, 50.0).load(umber, stiff = 0.4)

// a flick, then a slow lift over the same distance
canvas.stroke(canvas.gesture(line(80.0, 100.0, 420.0, 70.0), speed = 400.0, pressure = listOf(0.9, -0.6), tilt = 30.0), round())
canvas.stroke(canvas.gesture(line(500.0, 100.0, 900.0, 70.0), speed = 30.0, pressure = listOf(0.9, -0.6), tilt = 30.0), round())

// a pause halfway
val first = canvas.gesture(line(80.0, 250.0, 450.0, 250.0), speed = 80.0, pressure = listOf(0.6), tilt = 30.0)
val second = canvas.gesture(line(450.0, 250.0, 900.0, 250.0), speed = 80.0, pressure = listOf(0.6), tilt = 30.0)
canvas.stroke(first.pause(0.6).then(second), round())

// a flat rolled through half a turn
val straight = canvas.gesture(line(80.0, 400.0, 900.0, 400.0), speed = 60.0, pressure = listOf(0.6), tilt = 20.0)
val n = straight.samples.size - 1
val rolled = TimedGesture(straight.samples.mapIndexed { i, s -> s.copy(roll = 90.0 + 180.0 * i / n) })
canvas.stroke(rolled, canvas.dynamicBrush(BrushKind.HogFlat, 60.0).load(umber, stiff = 0.7))

// upright, then laid well over
canvas.stroke(canvas.gesture(line(80.0, 540.0, 450.0, 540.0), speed = 80.0, tilt = 0.0), canvas.dynamicBrush(BrushKind.Filbert, 50.0).load(umber))
canvas.stroke(canvas.gesture(line(500.0, 540.0, 900.0, 540.0), speed = 80.0, tilt = 65.0), canvas.dynamicBrush(BrushKind.Filbert, 50.0).load(umber))

display("main", canvas.png(relief = 0.6))
```

## Seeing the hairs

`canvas.hairs(brush)` gives each hair's nodes as they are now: x and y in canvas coordinates and
the height above the canvas in mm. Below, a round brush pressed down and pulled to the right,
stopped partway, seen from the side: the handle's end of each hair is held in the ferrule at the
top, and the rest bends back and lies along the paper behind it. `canvas.contactMap()` is how many
seconds of hair touched each pixel during the last stroke, and `contactMapPng()` draws it.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_gestures/hairs.png" alt="a side view of a brush's hairs drawn as thin pale lines above a horizontal blue-grey line for the canvas. The hairs start together at the top left, where the ferrule holds them, and curve down and back to the left, their lower parts lying flat along the canvas line behind the brush, fanning out a little at their tips" wait="10" timeout="120" capture="box" open-outputs="true" padding="20" requires="paint" name="hairs" embed=true}
:::static
import afield.paint.*

val canvas = PaintCanvas(600, aspect = 2.0, groundColor = rgb(0.97, 0.96, 0.92), sizeMm = 100.0, seed = 3)
val brush = canvas.dynamicBrush(BrushKind.Round, 80.0, hairs = 24).load(rgb(0.25, 0.15, 0.1))
canvas.stroke(canvas.gesture(FLine().moveTo(200, 250).lineTo(500, 250), speed = 60.0, pressure = listOf(0.8), tilt = 20.0), brush)

// x along the stroke against height: on a 100 mm canvas 10 units are a mm, so this is to scale
val side = canvas.hairs(brush).map { hair ->
    val l = FLine().moveTo(hair[0].first, -hair[0].third * 10)
    for ((x, _, z) in hair.drop(1)) l.lineTo(x, -z * 10)
    l.apply { thickness = 1.5; color = vec(0.95, 0.8, 0.55, 1.0) }
}
val paper = FLine().moveTo(300, 0).lineTo(560, 0).apply { thickness = 2.0; color = vec(0.5, 0.6, 0.7, 1.0) }
lines(side + paper, w = 520.0, h = 240.0, fitToSize = true)
```

## A brush that keeps a set

Hair and hog bristle are keratin, which bends back elastically up to a strain of about 2% and
yields beyond it. A thick, stiff hog bristle reaches that strain at a smaller bend than a fine
sable hair, so ordinary painting leaves a brush nearly straight, but a hog pressed hard and
scrubbed sideways comes away splayed, and stays that way for its next strokes.
`brush.retainedBend` is the bend the hairs keep at rest, in radians averaged over every joint, 0
for a straight brush; a set comes slowly, over seconds of hard scrubbing, and goes again more
slowly still. `brush.set(...)` changes how the hairs behave:

- `friction`, static and kinetic, against the surface (0.35 and 0.25 by default);
- `yieldDegrees`, the bend past which a joint yields (from the fibre's thickness and the 2% strain
  by default);
- `plasticity`, how fast a yielded joint takes on its bend, per second, and `recovery`, how fast a
  set straightens again;
- `cohesion`, how strongly the liquid in a wet brush draws its hairs together: 1 draws a full
  brush's tips to a point, more pulls harder and closer, and below 0 pushes nearby hairs apart;
- `clumping`, which way it does it: 0 (the default) draws the tips of neighbouring hairs together,
  1 joins any wet hairs that come near each other by liquid bridges that snap when stretched, so the
  brush gathers into clumps; between, a blend; past 1 or below 0, more or the reverse;
- `splay`, `splayFan` (0 every pressed hair pushed out alike, 1 the outer ones furthest, so the
  brush fans open) and `splayScatter` (each bundle of hairs buckling its own way).

`stroke(gesture, brush, ...)` can vary any of the last five along a stroke: give it a function of
the gesture's sample, such as `{ s -> 0.2 + 0.2 * PaintNoise.along(s) }`.

The defaults are estimates for hair and hog bristle. Any value that makes physical sense is
accepted: friction can't be negative.

Below, two hog flats loaded the same way and drawn along the same line at the same light pressure.
The lower one was first scrubbed hard back and forth for a few seconds. Its bristles have taken a
bend, so at that pressure most of them no longer reach the paper, and it paints a thin line where
the fresh brush lays a full band.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_gestures/set.png" alt="two horizontal dark brown strokes from flat brushes on cream paper. The upper one, from a fresh brush, is an even band with fine bristle lines along it. The lower one, from the brush that was scrubbed, is only a thin line, dark at its start and fading towards its end" wait="20" timeout="600" capture="box" open-outputs="true" padding="20" requires="paint" name="set" embed=true}
:::static
import afield.paint.*

fun line(x0: Double, y0: Double, x1: Double, y1: Double) = FLine().moveTo(x0, y0).lineTo(x1, y1)
val umber = rgb(0.25, 0.15, 0.1)

// a scrap canvas of the same size to abuse the brush on
val scrap = PaintCanvas(400, aspect = 1.5, sizeMm = 200.0, seed = 4)
val abused = scrap.dynamicBrush(BrushKind.HogFlat, 60.0, hairs = 40).load(umber, stiff = 0.6)
val scrub = scrap.gesture(FLine().moveTo(100, 300).lineTo(900, 300).lineTo(100, 310).lineTo(900, 320).lineTo(100, 330),
    speed = 120.0, pressure = listOf(1.6), tilt = 10.0, roll = 0.0)
scrap.stroke(scrub, abused)

val canvas = PaintCanvas(900, aspect = 3.0, groundColor = rgb(0.97, 0.96, 0.92), sizeMm = 200.0, seed = 4)
val fresh = canvas.dynamicBrush(BrushKind.HogFlat, 60.0, hairs = 40)
for ((y, brush) in listOf(100.0 to fresh, 230.0 to abused)) {
    brush.load(umber, stiff = 0.6, wipe = 1.0)
    canvas.stroke(canvas.gesture(line(100.0, y, 900.0, y), speed = 80.0, pressure = listOf(0.5), tilt = 25.0), brush)
}
display("main", canvas.png(relief = 0.6))
markdown("retained bend: fresh %.3f rad, scrubbed %.3f rad".format(fresh.retainedBend, abused.retainedBend))
```

## Watercolour

`brush.loadWater(canvas, paint, fill)` loads a moving brush with
[watercolour](watercolor.md) instead, and from then on its hairs trade water and pigment with the
sheet wherever they touch it. How far the water on them reaches into the paper's tooth depends on
how hard the brush is pressed and how full it is, so a nearly dry brush, light and fast, catches
only the top of the tooth. Below, three strokes on rough paper with the brush full, a quarter full
and nearly dry:

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_gestures/water.png" alt="three horizontal brown-grey watercolour strokes on rough white paper. The top, from a full brush, is a dark, flooded wash with irregular blooms and pale gaps at its edges where the water pooled and dried. The middle, a quarter full, is a paler band mottled with darker patches in the hollows and small white flecks. The bottom, nearly dry, is broken into scattered small specks and short streaks along the stroke's direction, with white paper between them" wait="20" timeout="600" capture="box" open-outputs="true" padding="20" requires="paint" name="water" embed=true}
:::static
import afield.paint.*

val sheet = PaintCanvas.paper(700, aspect = 2.5, paper = Paper.Rough, sizeMm = 150.0, seed = 5)
for ((i, fill) in listOf(1.0, 0.25, 0.08).withIndex()) {
    val brush = sheet.dynamicBrush(BrushKind.Round, 80.0, hairs = 32)
        .loadWater(sheet, Watercolors.burntSienna * 80.0 + Watercolors.ultramarine * 40.0, fill = fill)
    val y = 70.0 + i * 120
    sheet.stroke(FLine().moveTo(60, y).lineTo(940, y), brush, pressure = listOf(0.3), speed = 200.0, tilt = 50.0)
}
sheet.dry()
display("main", sheet.png(relief = 0.5))
```

## A finished piece

Broad black gestures in the manner of Robert Motherwell's *Elegies* and the Tachistes need a big
brush and a big canvas, real ones: here two metres of canvas and a house painter's brush about
15 cm wide, with soft, uneven hairs (`stiffness = 0.5`, `ragged = 0.5`), held together
(`splay = 0.0`) so each slab stays one band.
Fat black paint, stiff enough to hold its edge (`stiff = 0.5`, `oil = 1.5`), laid as a thin film
(`lay = 0.8`) from a half-loaded brush (`amount = 0.5`), so the brush tracks show through it. Each
movement is fast, 260 to 320 mm/s, and ends lifted off the canvas (pressure `-0.2`), which is where
the edges come from: the hairs drag dry and part as they leave. The ovals spiral out from their
middles, so their outer edge is painted last, as the brush runs out.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_gestures/elegy.png" alt="a wide abstract painting in black on an off-white canvas: three tall vertical slabs of black alternating with two large black ovals squeezed between them, like an elegy. The black is dense but shows its brush tracks, curving round inside the ovals. Each slab ends at the bottom in a fringe of dry, streaked bristle marks where the brush lifted off, and each oval's last turn trails away to the right in loose grey streaks of hair" wait="20" timeout="600" capture="box" open-outputs="true" padding="20" requires="paint" name="elegy" embed=true}
:::static
import afield.paint.*

// two metres of canvas, primed off-white
val canvas = PaintCanvas(1200, aspect = 2.1, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 2000.0, seed = 11)
val h = canvas.heightUnits

// a house painter's brush: soft, uneven hairs held together, laying a thin film of fat black
val housePainter = BrushConfig(stiffness = 0.5, ragged = 0.5, splay = 0.0, lay = 0.8, run = 1100.0)
fun brush(width: Double) = canvas.dynamicBrush(BrushKind.HogFlat, width, hairs = 48, segments = 6, config = housePainter)
    .load(rgb(0.05, 0.045, 0.04), hiding = 0.65, stiff = 0.5, oil = 1.5, amount = 0.5)

// an oval spiralling out from its middle, so its edge comes last, as the brush runs dry
fun oval(cx: Double, cy: Double, rx: Double, ry: Double): FLine {
    val line = FLine()
    for (i in 0..120) {
        val u = i / 120.0
        val a = PI / 2 + 2 * PI * 1.8 * u
        val r = 0.2 + 0.8 * u
        if (i == 0) line.moveTo(cx + rx * r * cos(a), cy + ry * r * sin(a)) else line.lineTo(cx + rx * r * cos(a), cy + ry * r * sin(a))
    }
    return line
}

// three slabs, each one movement, pressed down and lifted off the canvas at its end
for ((x, width) in listOf(120.0 to 70.0, 470.0 to 80.0, 905.0 to 65.0))
    canvas.stroke(canvas.gesture(FLine().moveTo(x, 25).lineTo(x + 8, h - 25), speed = 260.0,
        pressure = listOf(0.7, 1.0, 0.9, 0.4, -0.2), tilt = 25.0), brush(width))
// and two ovals between them
for ((cx, cy, rx) in listOf(Triple(300.0, 250.0, 120.0), Triple(690.0, 230.0, 140.0)))
    canvas.stroke(canvas.gesture(oval(cx, cy, rx, 170.0), speed = 320.0,
        pressure = listOf(1.1, 1.0, 0.8, 0.4, -0.2), tilt = 35.0), brush(80.0))

display("main", canvas.png(relief = 0.3))
```

The same painting, with the black let down by a little painting medium (`thinner = 0.15`): the film
no longer covers, and breaks into a cellular veil over the ground while the edges stay dark.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_gestures/elegy_thinned.png" alt="the same composition of three black slabs and two ovals on off-white, but the black has broken into a pale, cellular film: the slabs are mottled grey and cream with darker bands, and the ovals are rings of dark curving streaks with cream showing between them. Edges stay dark and crisp, and the slab ends still finish in dark streaked fringes" wait="20" timeout="600" capture="box" open-outputs="true" padding="20" requires="paint" name="elegy_thinned" embed=true}
:::static
import afield.paint.*

// two metres of canvas, primed off-white
val canvas = PaintCanvas(1200, aspect = 2.1, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 2000.0, seed = 11)
val h = canvas.heightUnits

// the same brush, its black let down with a little painting medium
val housePainter = BrushConfig(stiffness = 0.5, ragged = 0.5, splay = 0.0, lay = 0.8, run = 1100.0)
fun brush(width: Double) = canvas.dynamicBrush(BrushKind.HogFlat, width, hairs = 48, segments = 6, config = housePainter)
    .load(rgb(0.05, 0.045, 0.04), hiding = 0.55, stiff = 0.5, thinner = 0.15, oil = 1.5, amount = 0.5)

// an oval spiralling out from its middle, so its edge comes last, as the brush runs dry
fun oval(cx: Double, cy: Double, rx: Double, ry: Double): FLine {
    val line = FLine()
    for (i in 0..120) {
        val u = i / 120.0
        val a = PI / 2 + 2 * PI * 1.8 * u
        val r = 0.2 + 0.8 * u
        if (i == 0) line.moveTo(cx + rx * r * cos(a), cy + ry * r * sin(a)) else line.lineTo(cx + rx * r * cos(a), cy + ry * r * sin(a))
    }
    return line
}

// three slabs, each one movement, pressed down and lifted off the canvas at its end
for ((x, width) in listOf(120.0 to 70.0, 470.0 to 80.0, 905.0 to 65.0))
    canvas.stroke(canvas.gesture(FLine().moveTo(x, 25).lineTo(x + 8, h - 25), speed = 260.0,
        pressure = listOf(0.7, 1.0, 0.9, 0.4, -0.2), tilt = 25.0), brush(width))
// and two ovals between them
for ((cx, cy, rx) in listOf(Triple(300.0, 250.0, 120.0), Triple(690.0, 230.0, 140.0)))
    canvas.stroke(canvas.gesture(oval(cx, cy, rx, 170.0), speed = 320.0,
        pressure = listOf(1.1, 1.0, 0.8, 0.4, -0.2), tilt = 35.0), brush(80.0))

display("main", canvas.png(relief = 0.3))
```

!!! note
    Medium changes the black all at once, not by degrees: none gives a covering film, and even
    `thinner = 0.03` gives nearly the veil above. `hiding` does little to black at any value, since
    black absorbs almost everything in any film. For a black that is only a little less dense, lay
    less of it (`lay`, `amount`) instead.

## Keeping a brush

A moving brush keeps its paint or water, and the set of its hairs, from one stroke to the next. A
`PaintToolbox` keeps it by name with the other tools (`tools.dynamicBrush("hog")`), and
`brush.state()` and `DynamicBrush.restore(state, kind)` snapshot it exactly: every hair's
position and rest bend, and every bristle's paint. [AI4 paints](ai4_painting.md) keeps its
painter's moving brushes this way, so rewinding a conversation brings back a brush as splayed as
it was then.

## What is simulated

| | how | |
| --- | --- | --- |
| The hairs | chains of segments under position-based dynamics (Verlet integration, constraint projection, 1 ms steps): each segment keeps its length, each joint is pulled toward its rest angle, more strongly the stiffer the hair | simulated |
| Contact | a node below the surface (paper, dry paint, wet paint) is put back on it | simulated |
| Friction | Coulomb's: a node sticks until its slip exceeds static friction times how hard it presses, then slides against kinetic friction | simulated |
| Splay | a hair pressed into the surface is pushed out from the brush's middle by `splay` (in `BrushConfig`) times how far it is pressed in, against the same grip: hard enough, a flat parts in two | an approximation of buckling by one number |
| A set | a joint bent past its yield (2% strain at the surface of a fibre whose thickness is estimated from the brush's stiffness) moves its rest angle toward the bend, and recovers slowly | simulated, with estimated rates |
| Clumping | by default the liquid in a full brush draws neighbouring tips together; with `clumping` 1, a liquid bridge forms where two wet hairs come near each other and pulls them together, harder toward the tips and the wetter and thinner the paint, and snaps when they are pulled a few spacings apart, further the more liquid it holds, so splay tears a wet brush into clumps | the tip pull is one strength; the bridges are simulated, their strength and reach estimates |
| Oil paint | each of the brush's dense bristles follows the hair nearest it and trades paint where it touches, through claude-paint's own paint model | the paint is claude-paint's; the contact is Field's |
| Watercolour | every touching hair trades with the sheet over a disc a clump of hairs wide, through the [watercolour](watercolor.md) model | simulated |
| Hair count | 16–64 hairs stand for the thousands in a real brush; the dense bristles copy their hair's contact; hairs do not collide with each other except through their bridges | approximated |

??? aside "Where the code is"
    The hairs are `native/field-paint/vendor/paint/src/field/bristles.rs`, Field's addition to
    alice's [claude-paint](https://github.com/aliceisjustplaying/claude-paint) crate; oil paint
    goes through claude-paint's own bristle `exchange`, which `VENDORED.md` lists among the places
    Field opened up in alice's code. `DynamicBrushTest` checks contact, tilt, the set,
    keeping and restoring, watercolour's books, and gesture headings.

See also: [Paint settings, swept](paint_sweeps.md), where one gesture is painted with each setting in
turn, [Paint and pencil from FLines](paint_canvas.md), [Watercolour and ink](watercolor.md),
[AI4 paints](ai4_painting.md).
