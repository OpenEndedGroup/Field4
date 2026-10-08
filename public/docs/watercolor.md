# Watercolour and ink

A [PaintCanvas](paint_canvas.md) made with `PaintCanvas.paper(...)` is a sheet of watercolour
paper, and water laid on it moves by itself. It flows downhill and into the hollows of the paper,
sinks in, wicks along the fibres and evaporates. The pigment it carries settles, stains or dries
in place. A wash dries with a darker edge and the paper's grain in it, clean water dropped into a
damp wash pushes its colour out into a backrun, and a nearly dry brush skips over rough
paper, because the water and the pigment do those things, not because a rule paints them in.

The oil paint, brushes and canvas underneath are alice's
[claude-paint](https://github.com/aliceisjustplaying/claude-paint) (see
[Paint and pencil from FLines](paint_canvas.md)). The water model is Field's own addition to it,
written on the same pixel grid, so pencil and oil paint work over a watercolour and feel the
paper's tooth. It needs the same native library (`./gradlew buildPaintNative`).

Here is a sheet of cold-pressed paper, a round watercolour brush and a sky, with hills painted once
the sky had dried:

```diffstatic_runner {data-lang=kotlin screenshot="assets/watercolor/first.png" alt="a small watercolour study on off-white cold-pressed paper. The top is a sky of four overlapping horizontal brushstrokes of blue that have run together into one band, deeper above and paler below, with a fine grain where a little more pigment gathered in the hollows of the paper's texture. Below, two broad wavy strokes of warm grey-brown for the hills, with the same fine grain and crisp edges and a little white paper left between them" wait="20" timeout="600" capture="box" open-outputs="true" padding="20" requires="paint" name="first" embed=true}
:::static
import afield.paint.*

val sheet = PaintCanvas.paper(700, aspect = 1.5, paper = Paper.ColdPress, sizeMm = 200.0, seed = 3)
val brush = sheet.waterBrush(width = 70.0)

// the sky, in overlapping strokes while it is wet
brush.load(Watercolors.ultramarine * 3.0 + Watercolors.burntSienna * 0.5)
sheet.wash(FLine().moveTo(60, 90).lineTo(940, 90).moveTo(940, 135).lineTo(60, 135), brush, speed = 50.0)
brush.load(Watercolors.ultramarine * 1.2, fill = 0.8)
sheet.wash(FLine().moveTo(60, 180).lineTo(940, 180).moveTo(940, 225).lineTo(60, 225), brush, speed = 50.0)

// let it dry, then the hills over it
sheet.dry()
brush.load(Watercolors.burntSienna * 3.0 + Watercolors.ultramarine * 2.0)
val hills = FLine().moveTo(40, 420).cubicTo(250, 300, 450, 420, 960, 340)
sheet.wash(hills, brush, pressure = listOf(1.0), speed = 30.0)
sheet.wash(FLine().moveTo(40, 470).lineTo(960, 450), brush, pressure = listOf(1.0), speed = 30.0)
sheet.dry()

display("main", sheet.png())
```

What is new compared with oil:

- **Paint is pigment in water.** `Watercolors.ultramarine * 3.0` is ultramarine at 3 µg of pigment
  per mm³ of water (1 is pale, 6 strong), and `+` mixes pigments. The brush holds water, not paint:
  `load(paint, fill)` fills its belly to `fill` of what it holds.
- **Time passes.** Every stroke takes as long as it would at its `speed` (mm/s), and the water moves
  for that long. `waitSeconds(s)` lets more time pass and `dry()` waits until the sheet is dry. A
  stroke laid into a wet one runs into it; a stroke over a dry one keeps its edge.
- **The sheet has a real size.** `sizeMm` is how wide the paper is, so a brush 70 units wide on a
  200 mm sheet is 14 mm across. Water and pigment are counted in mm³ and µg, and the paper's
  texture is in mm, so the same strokes on a smaller sheet look coarser.

## Papers

`Paper` has five sheets. Each is a physical make-up, how much water it holds, how fast it drinks
and wicks, its grain, tooth and sizing, so the same drop behaves differently on each because the
water does. Below, one 15 µl drop of sumi ink on each, left to dry: cold press, hot press, rough,
xuan (rice paper) and blotting paper. On the sized cotton sheets the drop stands as a small dome
and dries where it stood, a crisp black dot; xuan drinks it at once and feathers it out along its
long fibres; blotting paper takes it in quickly and softens its edge.

```diffstatic_runner {data-lang=kotlin screenshot="assets/watercolor/papers.png" alt="five squares of paper side by side, each with one dried drop of black ink in the middle. On cold press, hot press and rough paper the drop is a small, crisp, round black dot, a little ragged on the rough sheet. On xuan paper the ink has spread into a much larger, soft grey disc that feathers out into the paper in fine radial streaks. On blotting paper it is a round black dot a little larger than the first three, with a soft edge" wait="20" timeout="600" capture="box" open-outputs="true" padding="20" requires="paint" name="papers" embed=true}
:::static
import afield.paint.*

val sheets = Paper.entries.map { paper ->
    PaintCanvas.paper(220, aspect = 1.0, paper = paper, sizeMm = 50.0, seed = 2).also { sheet ->
        sheet.drop(500.0, 500.0, Watercolors.sumi * 3.0, volumeMm3 = 15.0)
        sheet.dry()
    }
}
display("main", pngRow(sheets))
```

`sheet.paper` reports the make-up: how much water the sheet holds saturated (`capacityMicrons`),
how fast dry paper takes up standing water (`absorbMicronsPerSecond`), how fast water wicks through
it (`wickMm2PerSecond`), its tooth (`roughnessMicrons`) and how deep water must be to advance onto
dry paper (`pinMicrons`). The numbers are estimates in the ranges reported for each kind of paper,
not measurements of one maker's sheet.

## Wet into wet, blooms and backruns

`wet(region, microns)` lays water over a region at once, as a wide mop would: clean water to paint
into, or, with `paint = ...`, a flat wash. Paint touched into wet paper spreads, because paint
lowers the water's surface tension and the cleaner water around it pulls the surface outward. How
far it goes depends on how wet the paper still is.

The left half below is paper dampened with 40 µm of clean water, with small drops of strong
ultramarine and quinacridone rose let fall into it straight away: each spreads into a soft blob,
densest where it fell, with a feathered edge. On wetter paper the paint flies further and leaves
more of its colour at that edge. The right half is a wash of burnt sienna left for
four minutes until it was damp, with one drop of clean water into it. The clean water floods
through the settled pigment and pushes it out ahead of itself, and the colour dries where the flood
stopped: a backrun, with a pale middle and a dark, frilled edge.

```diffstatic_runner {data-lang=kotlin screenshot="assets/watercolor/blooms.png" alt="two passages side by side on cold-pressed paper. Left: on dampened paper, three blue and two rose drops, each a dense, rounded blot of strong colour surrounded by a pale, soft halo where its colour spread into the damp paper, the rose halos larger and feathered at their edges. Right: a flat light-brown wash with a fine grain and a small pale patch in its middle, outlined by a thin, darker, frilled line where the clean water pushed the pigment out" wait="20" timeout="600" capture="box" open-outputs="true" padding="20" requires="paint" name="blooms" embed=true}
:::static
import afield.paint.*

fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
    FLine().moveTo(x0, y0).lineTo(x1, y0).lineTo(x1, y1).lineTo(x0, y1).lineTo(x0, y0)

val sheet = PaintCanvas.paper(700, aspect = 1.6, paper = Paper.ColdPress, sizeMm = 160.0, seed = 3)

// wet in wet: clean water, then drops of colour into it at once
sheet.wet(rect(60.0, 80.0, 460.0, 540.0), microns = 40.0)
for ((x, y) in listOf(150.0 to 180.0, 330.0 to 260.0, 200.0 to 420.0))
    sheet.drop(x, y, Watercolors.ultramarine * 15.0, volumeMm3 = 4.0)
for ((x, y) in listOf(260.0 to 160.0, 380.0 to 440.0))
    sheet.drop(x, y, Watercolors.quinacridoneRose * 12.0, volumeMm3 = 4.0)

// a backrun: a wash left until damp, then clean water into it
sheet.wet(rect(540.0, 80.0, 940.0, 540.0), microns = 110.0, paint = Watercolors.burntSienna * 2.5)
sheet.waitSeconds(240.0)
sheet.drop(740.0, 310.0, WaterPaint.Clear, volumeMm3 = 25.0)
sheet.dry()

display("main", sheet.png())
```

## Tilting the board

`tilt(degrees, direction)` tilts the sheet, and wet washes run downhill and gather in a bead at
their lower edge. Dry paper holds a bead back until it is heavier than the paper can pin, about a
millimetre deep on sized paper; then it breaks out in drips that run down the sheet, and off it if
they reach the edge. Below, a wash laid all at once on hot-pressed paper tilted 15° toward the
bottom of the sheet: its colour drains with the water into the bead, and the bead has dripped.
`tilt(0.0)` lays the board flat again.

```diffstatic_runner {data-lang=kotlin screenshot="assets/watercolor/tilt.png" alt="on smooth white paper, a rectangle of lilac wash in the upper part of the sheet, palest at its top and deepening downwards to its lower edge. From that edge many thin, straight violet drips run down the rest of the sheet to the bottom, where a dark violet line has dried along the edge of the paper" wait="20" timeout="600" capture="box" open-outputs="true" padding="20" requires="paint" name="tilt" embed=true}
:::static
import afield.paint.*

val wash = FLine().moveTo(200, 150).lineTo(800, 150).lineTo(800, 600).lineTo(200, 600).lineTo(200, 150)
val sheet = PaintCanvas.paper(400, aspect = 0.75, paper = Paper.HotPress, sizeMm = 120.0, seed = 4)
sheet.tilt(15.0, 90.0)
sheet.wet(wash, microns = 60.0, paint = Watercolors.ultramarine * 2.0 + Watercolors.quinacridoneRose * 1.2)
sheet.dry()
display("main", sheet.png())
```

## The brush

A watercolour brush lays water in proportion to how long it touches the paper and how full it is,
so a slow stroke lays more than a fast one, and the belly runs down as it goes. Pressing harder
widens the stroke and pushes the hairs into the paper's hollows. How full the brush is decides
how far the water on it reaches down into the tooth: a full brush floods across everything; a
nearly dry one, light and fast, touches only the top of the tooth, which is drybrush.

Drybrush wants paint nearly as strong as it comes off the pan, 20 to 80 µg/mm³, since so little
water reaches the paper. Below, the same light, fast stroke of that paint from a brush loaded full,
a third full and nearly dry, on rough paper (left) and hot-pressed paper (right). On rough paper even the full brush lays its paint in
the hollows, the third-full one leaves scattered marks, and the nearly dry one only specks on the
highest points of the tooth. On the smooth sheet the full and third-full strokes stay whole, and
only the nearly dry one breaks up.

```diffstatic_runner {data-lang=kotlin screenshot="assets/watercolor/drybrush.png" alt="two sheets side by side, each with three horizontal brown strokes. On the left, rough paper with a coarse texture: the top stroke is a band of dark brown blots gathered in the paper's hollows over a paler tint; the middle one is a broken row of small pale marks; the bottom one is only a few tiny specks. On the right, smooth hot-pressed paper: the top stroke is a solid dark band with a fine texture, the middle one an even pale band, and the bottom one a pale band broken up into specks" wait="20" timeout="600" capture="box" open-outputs="true" padding="20" requires="paint" name="drybrush" embed=true}
:::static
import afield.paint.*

val sheets = listOf(Paper.Rough, Paper.HotPress).map { paper ->
    val sheet = PaintCanvas.paper(420, aspect = 1.3, paper = paper, sizeMm = 120.0, seed = 5)
    for ((i, fill) in listOf(1.0, 0.3, 0.1).withIndex()) {
        val brush = sheet.waterBrush(90.0).load(Watercolors.burntSienna * 40.0 + Watercolors.ultramarine * 20.0, fill = fill)
        sheet.wash(FLine().moveTo(60, 130.0 + i * 230).lineTo(940, 130.0 + i * 230), brush, pressure = listOf(0.3), speed = 200.0)
    }
    sheet.dry()
}
display("main", pngRow(sheets))
```

A brush also takes water back up. A clean, damp brush stroked through a wet wash drinks colour
out of it; squeezed out (`load(WaterPaint.Clear, fill = 0.05)`) it is thirsty and lifts more.
On dried paint the water from a damp brush redissolves the gum the pigment dried in, and scrubbing
lifts it, slowly. How much comes up depends on the pigment: ultramarine sits loose on the surface
and lifts back nearly to white, while phthalo blue has stained the fibres and barely moves.

```diffstatic_runner {data-lang=kotlin screenshot="assets/watercolor/lift.png" alt="two dried rectangles of blue wash side by side on cold-pressed paper, a mid ultramarine on the left and an intense phthalo blue on the right. A horizontal band has been scrubbed across both with a damp brush: through the ultramarine it is a clearly paler stripe, lifted most of the way to the paper; through the phthalo it is barely visible" wait="20" timeout="600" capture="box" open-outputs="true" padding="20" requires="paint" name="lift" embed=true}
:::static
import afield.paint.*

fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
    FLine().moveTo(x0, y0).lineTo(x1, y0).lineTo(x1, y1).lineTo(x0, y1).lineTo(x0, y0)

val sheet = PaintCanvas.paper(600, aspect = 1.5, paper = Paper.ColdPress, sizeMm = 150.0, seed = 6)
sheet.wet(rect(100.0, 100.0, 460.0, 560.0), microns = 120.0, paint = Watercolors.ultramarine * 3.0)
sheet.wet(rect(540.0, 100.0, 900.0, 560.0), microns = 120.0, paint = Watercolors.phthaloBlue * 1.0)
sheet.dry()

// scrub a band across both: wet the brush, scrub, squeeze it out, scrub again, six times
val brush = sheet.waterBrush(50.0)
val band = FLine().moveTo(150, 330).lineTo(850, 330)
repeat(6) {
    brush.load(WaterPaint.Clear, fill = 0.6)
    sheet.wash(band, brush, pressure = listOf(0.9), speed = 120.0)
    brush.load(WaterPaint.Clear, fill = 0.05)
    sheet.wash(band, brush, pressure = listOf(0.9), speed = 120.0)
}
sheet.dry()
display("main", sheet.png())
```

To come back to a brush later, with the water and pigment still in it, keep it in a
`PaintToolbox` as you would an oil brush: `tools["wash"] = sheet.waterBrush(50.0)`, then
`tools.waterBrush("wash")`.

## Pigments

`Watercolors` has seven pigments by their common names: `ultramarine`, `phthaloBlue`,
`burntSienna`, `quinacridoneRose`, `cadmiumYellow`, `viridian` and `sumi` (carbon ink in animal
glue). Each one is its colour and the physics that decide how it behaves:

- **density** (g/cm³) and **diameterUm**, the particle size. Dense, coarse pigment (ultramarine,
  viridian, the earths) settles out onto the paper within a minute, where the water laid it. Fine
  pigment (phthalo, quinacridone, carbon) stays suspended and goes where the water goes: into the
  paper as it soaks in, and onto the fibres it diffuses to.
- **stain**: how fast it soaks into the fibres from water in the sheet, after which it no longer
  lifts.
- **lift**: how fast a dried deposit goes back into water standing on it.

`WaterPigment.of(name, masstone, ...)` makes another one from the colour it has laid thick, with
the same physical parameters, and its optics follow from that colour (Kubelka–Munk absorption and
scattering, the same optics the oil paint uses). Below, ultramarine, phthalo blue and the two
mixed, each laid as the same wash and left to dry. Each shows the paper's grain a little, because a
wash stands deeper over the hollows; the ultramarine has settled out onto the paper, while the
phthalo has soaked into its fibres and stained them, which is why it would not lift.

```diffstatic_runner {data-lang=kotlin screenshot="assets/watercolor/pigments.png" alt="three dried rectangles of blue wash side by side on cold-pressed paper, each with a soft, fine grain from the paper's texture. Left, ultramarine alone: a mid violet-blue. Middle, phthalo blue alone: an intense greenish blue. Right, the two mixed: a deeper, stronger blue" wait="20" timeout="600" capture="box" open-outputs="true" padding="20" requires="paint" name="pigments" embed=true}
:::static
import afield.paint.*

fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
    FLine().moveTo(x0, y0).lineTo(x1, y0).lineTo(x1, y1).lineTo(x0, y1).lineTo(x0, y0)

val sheet = PaintCanvas.paper(700, aspect = 2.0, paper = Paper.ColdPress, sizeMm = 160.0, seed = 7)
val u = Watercolors.ultramarine * 3.0
val p = Watercolors.phthaloBlue * 0.8
for ((i, paint) in listOf(u, p, u + p).withIndex())
    sheet.wet(rect(60.0 + i * 310, 70.0, 320.0 + i * 310, 430.0), microns = 120.0, paint = paint)
sheet.dry()
display("main", sheet.png(relief = 0.4))
```

## Oil, pencil and the knife on paper

A sheet of paper is a canvas like any other, so `stroke`, `draw`, `knife` and the rest work on it,
and the paper's tooth is the surface they feel. Oil paint laid over a watercolour seals the
watercolour under it into the picture: it no longer lifts, and water laid on the oil beads on it
instead of soaking in. Below, a dried ultramarine wash crossed by two strokes of pale oil paint,
then a wash of burnt sienna laid across everything: it soaks into the paper and stands off the
oil.

```diffstatic_runner {data-lang=kotlin screenshot="assets/watercolor/oil.png" alt="on cold-pressed paper, a horizontal band of dried blue watercolour across the top, crossed by two vertical strokes of thick cream oil paint that run down the whole sheet. Across the lower half a band of brown watercolour soaks into the paper between the oil strokes and stops at them, leaving the cream paint clean" wait="20" timeout="600" capture="box" open-outputs="true" padding="20" requires="paint" name="oil" embed=true}
:::static
import afield.paint.*

fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
    FLine().moveTo(x0, y0).lineTo(x1, y0).lineTo(x1, y1).lineTo(x0, y1).lineTo(x0, y0)

val sheet = PaintCanvas.paper(600, aspect = 1.5, paper = Paper.ColdPress, sizeMm = 150.0, seed = 7)
sheet.wet(rect(100.0, 100.0, 900.0, 300.0), microns = 120.0, paint = Watercolors.ultramarine * 2.5)
sheet.dry()

val oil = sheet.brush(BrushKind.HogFlat, 60.0).load(rgb(0.9, 0.85, 0.7), hiding = 0.6)
sheet.stroke(FLine().moveTo(300, 80).lineTo(300, 620).moveTo(650, 80).lineTo(650, 620), oil)

sheet.wet(rect(100.0, 400.0, 900.0, 560.0), microns = 120.0, paint = Watercolors.burntSienna * 2.5)
sheet.dry()
display("main", sheet.png())
```

## Watching the water

`waterMap(map)` reads one value per pixel of what the water is doing, and `waterMapPng(map)` draws
it, black at nothing and white at the most. `WaterMap.SurfaceWater` is the water standing on the
paper (µm), `AbsorbedWater` the water in it, `Saturation` how full its pores are, `Pigment` all the
pigment (µg/mm²), and `Suspended`, `Carried`, `Deposited`, `Glued` and `Bound` the pigment in each
of its states (below). `Relief` is the paper's surface and `Sealed` where oil paint has sealed it.
`render(wet = true)` shows the sheet as it looks at that moment, wet paper darker.

Below, a wash with a stroke laid into it, 20 seconds later: as it looks, the water standing on the
paper (the stroke still puddled, the wash already sinking in), and the water in the paper.

```diffstatic_runner {data-lang=kotlin screenshot="assets/watercolor/maps.png" alt="three panels of the same sheet. Left, the picture: a pale blue wash with a darker blue stroke curving through it, the paper still looking wet. Middle, a black and white map of the water standing on the paper: the stroke is bright, the wash around it a dim grey, the dry paper black. Right, a map of the water in the paper: the whole wash is a fairly even grey with brighter spots, sharp-edged against the dry black paper" wait="20" timeout="600" capture="box" open-outputs="true" padding="20" requires="paint" name="maps" embed=true}
:::static
import afield.paint.*
import java.util.Base64

fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
    FLine().moveTo(x0, y0).lineTo(x1, y0).lineTo(x1, y1).lineTo(x0, y1).lineTo(x0, y0)
fun img(png: ByteArray) = "<img width='32%' src='data:image/png;base64,${Base64.getEncoder().encodeToString(png)}'>"

val sheet = PaintCanvas.paper(300, aspect = 1.0, paper = Paper.ColdPress, sizeMm = 80.0, seed = 8)
sheet.wet(rect(100.0, 100.0, 900.0, 900.0), microns = 80.0, paint = Watercolors.ultramarine * 1.0)
val brush = sheet.waterBrush(80.0).load(Watercolors.ultramarine * 4.0)
sheet.wash(FLine().moveTo(200, 250).cubicTo(500, 100, 500, 900, 800, 750), brush, speed = 40.0)
sheet.waitSeconds(20.0)

display("main", img(sheet.png(wet = true)) + " " + img(sheet.waterMapPng(WaterMap.SurfaceWater)) + " " +
    img(sheet.waterMapPng(WaterMap.AbsorbedWater)))
```

## The books

Every volume of water and mass of pigment on the sheet is accounted for. `sheet.water` is a
`WaterLedger`: the water standing on the paper and in it now, and everything added by tools, taken
up by brushes, evaporated and run off the edge (mm³). Its `pigments` are one `PigmentLedger` each,
in µg, with the pigment in each state:

- `suspended` in the water on the paper, moving with it;
- `carried` in the water in the paper's pores, moving with it;
- `deposited`, settled out but loose: water flowing through carries it on;
- `glued`, dried in place in its gum: rewetting lifts it slowly, a brush faster;
- `bound`, stained into the fibres: it no longer lifts;
- and `added`, `taken`, `runoff` and `sealed` (under oil paint), what came and went.

What is on the sheet always equals what was added less what left, to rounding, and
`imbalanceMm3` and `imbalance` say by how much. Here are the books of the tilted wash above:

```diffstatic_runner {data-lang=kotlin screenshot="assets/watercolor/books.png" alt="a box output showing two small tables. The first gives the water in cubic millimetres: how much was added, how much evaporated and how much ran off the bottom edge, none left on or in the sheet, and an imbalance of a small fraction of a cubic millimetre. The second lists the two pigments, ultramarine and quinacridone rose, in micrograms: how much was added, how much ran off, and how much is glued, bound or deposited on the sheet" wait="20" timeout="600" capture="box" open-outputs="true" padding="20" requires="paint" name="books" embed=true}
:::static
import afield.paint.*

val wash = FLine().moveTo(200, 150).lineTo(800, 150).lineTo(800, 600).lineTo(200, 600).lineTo(200, 150)
val sheet = PaintCanvas.paper(400, aspect = 0.75, paper = Paper.HotPress, sizeMm = 120.0, seed = 4)
sheet.tilt(15.0, 90.0)
sheet.wet(wash, microns = 60.0, paint = Watercolors.ultramarine * 2.0 + Watercolors.quinacridoneRose * 1.2)
sheet.dry()

val w = sheet.water!!
fun f(x: Double) = "%.2f".format(x)
markdown("""
| water, mm³ | added | evaporated | run off | on the sheet | imbalance |
| --- | --- | --- | --- | --- | --- |
| | ${f(w.addedMm3)} | ${f(w.evaporatedMm3)} | ${f(w.runoffMm3)} | ${f(w.surfaceMm3 + w.absorbedMm3)} | ${"%.1e".format(w.imbalanceMm3)} |

| pigment, µg | added | run off | glued | bound | deposited | imbalance |
| --- | --- | --- | --- | --- | --- | --- |
""".trimIndent() + "\n" + w.pigments.joinToString("\n") { p ->
    "| ${p.name} | ${f(p.added)} | ${f(p.runoff)} | ${f(p.glued)} | ${f(p.bound)} | ${f(p.deposited)} | ${"%.1e".format(p.imbalance)} |"
})
```

## The sheet, the air and time

- `PaintCanvas.paper(widthPx, aspect, paper, sizeMm, seed, view, engine)` makes a sheet. `sizeMm`
  is 560 mm (a half imperial sheet) unless you say otherwise. Coordinates are the same as any canvas:
  1000 units wide, y down.
- `evaporation(micronsPerSecond)` sets how fast open water evaporates: about 0.15 µm/s in still air
  at 20 °C and 50 % humidity, several times that in a warm draught.
- `marangoni(newtonsPerMetre)` sets how far paint lowers the surface tension of the water it is in
  (0.008 by default): it drives the spreading of wet-in-wet. 0 turns it off.
- `dispersion(mm2PerSecond)`, 0 by default, adds an extra spreading of pigment through standing
  water. It is not simulated (see below), a dial for when you want softer mixing.
- `saveState(path)`, `PaintCanvas.load(path)` and `copy()` keep the water and every pigment in it
  exactly, wet or dry, and the copy carries on as the original would have.

## How it works, and what is approximated

The water model keeps, for every pixel, the water standing on the sheet and the water in its pores,
and for each pigment its mass in each of the five states of the books above. On every step:

| | how | simulated or approximated |
| --- | --- | --- |
| Flow on the surface | a thin film driven by gravity and the paper's relief (lubrication theory), the sheet's tilt included | simulated; surface tension is not, so shapes finer than about 3 mm (water's capillary length) are not resolved |
| Wetting dry paper | water advances only from a film deeper than the paper's `pinMicrons` | an approximation of contact-angle hysteresis by one number |
| Absorption and wicking | surface water drawn into the pores at the paper's sizing rate; capillary diffusion through the sheet, faster along the fibres | simulated, with a linear stand-in for the slowing of uptake as the sheet fills |
| Evaporation | from the surface water, faster near the edge of a wet patch (Deegan et al., *Nature* 389, 1997); then from the pores | simulated; the dark edge of a dried wash is the flow toward the edge carrying pigment, not a rule |
| Settling | each pigment falls through the water at its Stokes velocity, from its density and particle size | simulated |
| Diffusion to the fibres | fine particles diffuse through the water to the paper (Stokes–Einstein) and stick there, the same amount per area wherever the water stands deep enough | simulated |
| Texture in a wash | a wash's surface lies level, so it stands deeper over the paper's hollows and leaves more there; how much shows depends on the paper's relief | simulated; the reliefs are estimates |
| Granulation | coarse pigment gathering in the hollows beyond what the water's depth leaves there, so that ultramarine speckles more than phthalo | not simulated: here every pigment follows the depth about equally |
| Filtering and staining | particles larger than the pores stay on the surface; staining pigments adsorb onto the fibres | simulated with estimated rates |
| Backruns | settled pigment is carried on by water flowing through it; drying glues it down | simulated |
| Blooms | paint lowers surface tension, and the cleaner water pulls the surface outward (Marangoni flow) | simulated with an estimated tension drop; the wetting agents are carried and adsorbed |
| Colour | Kubelka–Munk absorption and scattering per pigment, laid over the sheet | simulated optics; wet paper's darkening is a fixed factor |
| The paper | flocs of fibre and the felt's texture | generated, not made by a simulated papermaking |
| Every number for a paper or pigment | | an estimate in the reported range, not a measurement of a product |

Time steps are adaptive: short while water is moving fast, long while the sheet is only drying.
The wetter and larger the sheet, the longer a wait takes to compute; a 700-pixel sheet drying a
full wash takes some seconds.

??? aside "Where the code is"
    The model is in `native/field-paint/vendor/paint/src/field/aqueous.rs` (the sheet, its water
    and pigments) and `watercolor.rs` (brushes, drops and washes). Its module documentation gives
    every equation with its units and source. It lives inside the vendored claude-paint crate so
    that it can share the canvas's grid, height and optics, but it is Field's code, not alice's;
    `VENDORED.md` there lists exactly what Field changed in alice's files. `WaterMediaTest` checks
    the books, saving and restoring, the brushes and gestures.

See also: [Paint and pencil from FLines](paint_canvas.md) for the canvas, oil paint and the
other tools, [Brushes with moving hairs](paint_gestures.md) for a brush whose hairs bend and splay
in watercolour as in oil, and [AI4 paints](ai4_painting.md), where the AI chat paints in
watercolour too.
