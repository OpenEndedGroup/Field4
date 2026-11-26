# A Drawn Outline, Repaired and Offset

Smooth-min two rounded rectangles together and you get a shape with a soft waist where they meet.
The outline is exactly where you want it. The numbers either side of it, the ones that are meant
to say how far each point is from that outline, are not: inside the overlap and outside the waist
they come out closer to zero than the true distance. This page follows one such shape through four steps, from drawn
[squircles](fline_examples.md#squircles-squirclefrom-and-squirclerectfrom) to a signed distance
field, through the [repair](mesh_examples.md#repairing-a-field-that-is-only-a-bound) that makes the
distances honest again, and on to the offsets and toolpaths that read them.

## Two squircles as distance fields

The shape starts as two `squircleRectFrom` rectangles with clothoid corners, the corner style whose
curvature never jumps. `signedDistance2D()` turns a closed `FLine` into its exact signed distance,
negative inside, and a smooth min joins the two fields with a fillet of radius `k`. `sdfGrid2D`
samples the result on a 97 × 65 grid, and `repaired()` rebuilds every sample from the outline the
grid itself implies.

The box below does all of that, then measures the blended grid. Each dot is one sample (every other
one is drawn), sized and reddened by how far its value is from the true distance to the orange
outline. The thin white lines are the two squircles before blending.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_squircle_offsets/error.png" wait="2.0" capture="box" open-outputs="true" padding="20" alt="An orange outline of a large rounded rectangle joined by a soft waist to a smaller one on its right, over a grid of dots. Dots inside the overlap of the two shapes are large and red, and two faint red columns run up and down from the waist; elsewhere the dots are small and grey"}
:::static
// Two squircle rectangles with clothoid corners, overlapping.
val big = squircleRectFrom(-1.0, -0.6, 1.4, 1.2, radius = 0.5, gauge = 0.79, style = SquircleStyle.Clothoid)
val small = squircleRectFrom(0.1, -0.35, 1.0, 0.7, radius = 0.3, gauge = 0.79, style = SquircleStyle.Clothoid)

// Each outline as an exact signed distance, then the two joined with a smooth min.
val da = big.signedDistance2D()
val db = small.signedDistance2D()
val k = 0.25
val blend = { x: Double, y: Double ->
    val p = da(x, y)
    val q = db(x, y)
    val t = (0.5 + 0.5 * (q - p) / k).coerceIn(0.0, 1.0)
    q + (p - q) * t - k * t * (1 - t)
}

val pseudo = sdfGrid2D(xMin = -1.5, yMin = -1.0, xMax = 1.5, yMax = 1.0, n = 97, field = blend)
val fixed = pseudo.repaired()
:::fold trace the blended outline finely, to have a true distance to compare with
// The answer to check against: the exact distance to the blend's own outline, traced finely.
val traced = FLine()
for (contour in generateIsoContours(blend, -1.5, 1.5, -1.0, 1.0, 600, 400, 0.0)) {
    traced.moveTo(contour[0])
    for (p in contour.drop(1)) traced.lineTo(p)
    traced.close()
}
val truth = traced.signedDistance2D()
:::endfold
// what consistency() finds in each grid, and each grid's error against the traced outline
val table = buildString {
    appendLine("| grid | consistency() | error against the outline |")
    appendLine("|---|---|---|")
    appendLine("| blend | ${pseudo.consistency()} | ${pseudo.errorAgainst(truth)} |")
    appendLine("| repaired | ${fixed.consistency()} | ${fixed.errorAgainst(truth)} |")
}
:::fold draw every other sample, sized by its error
// one dot per sample, sized and reddened by how far the blend's value is from the true distance
val scale = 150.0
val drawn = ArrayList<FLine>()
for (j in 0 until pseudo.ny step 2) for (i in 0 until pseudo.nx step 2) {
    val x = pseudo.x(i)
    val y = pseudo.y(j)
    val e = (abs(pseudo[i, j] - truth(x, y)) / 0.1).coerceIn(0.0, 1.0)
    drawn +=
        fline { circle(x * scale, -y * scale, 1.0 + 4.0 * e) }
            .apply {
                filled = true
                color = vec(0.35 + 0.65 * e, 0.4 - 0.2 * e, 0.5 - 0.4 * e, 0.4 + 0.6 * e)
            }
}
for (shape in [big, small]) drawn +=
    shape.transform { vec(it.x * scale, -it.y * scale, 0.0) }.apply { color = vec(1.0, 1.0, 1.0, 0.35) }
drawn +=
    traced
        .transform { vec(it.x * scale, -it.y * scale, 0.0) }
        .apply {
            thickness = 2.5
            color = vec(1.0, 0.67, 0.0, 1.0)
        }
lines(drawn, channel = "error", w = 640.0, h = 430.0, fitToSize = true)
:::endfold
markdown("readout", table)
```

![The blend's error, sample by sample](assets/chain_squircle_offsets/error.png)

Two places are wrong, and the table under the figure says by how much. Inside the overlap each sample only
knows how far it is from the nearer squircle's own edge, which is now buried inside the shape, so
the value is closer to zero than it should be. Outside the waist the smooth min pulls values down to make the fillet, and
that reaches outwards in the two columns above and below it. `consistency()` finds both without
being told the truth: it checks each sample's circle against the zero crossings of the grid, and
the blend's circles fall short. After `repaired()` none do, and the error against the traced
outline drops to the size of a grid cell's interpolation error.

## Offsetting the outline

An offset contour is the set of points at a given distance from the outline: draw the field's level
set at `d` instead of at zero. That is the first thing that goes wrong with a field whose values are
only bounds, because a value closer to zero than the true distance puts the contour further from the outline
than it should be. The box draws the
offsets at −0.2, −0.1, 0.1, 0.2 and 0.3 from both grids: red from the blend, blue from the repaired
grid, over the outline in orange. The table measures each contour against the true distance.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_squircle_offsets/offsets.png" wait="2.0" capture="box" open-outputs="true" padding="20" alt="Five pairs of nested offset contours around the orange blended outline. Red and blue agree along the straight sides and corners; around the waist the red contours part from the blue ones, most visibly on the innermost inset, where the red line reaches further into the larger shape"}
:::static
:::fold the shapes, the blend, its grids and the traced outline, as above
// Two squircle rectangles with clothoid corners, overlapping.
val big = squircleRectFrom(-1.0, -0.6, 1.4, 1.2, radius = 0.5, gauge = 0.79, style = SquircleStyle.Clothoid)
val small = squircleRectFrom(0.1, -0.35, 1.0, 0.7, radius = 0.3, gauge = 0.79, style = SquircleStyle.Clothoid)

// Each outline as an exact signed distance, then the two joined with a smooth min.
val da = big.signedDistance2D()
val db = small.signedDistance2D()
val k = 0.25
val blend = { x: Double, y: Double ->
    val p = da(x, y)
    val q = db(x, y)
    val t = (0.5 + 0.5 * (q - p) / k).coerceIn(0.0, 1.0)
    q + (p - q) * t - k * t * (1 - t)
}

val pseudo = sdfGrid2D(xMin = -1.5, yMin = -1.0, xMax = 1.5, yMax = 1.0, n = 97, field = blend)
val fixed = pseudo.repaired()
// The answer to check against: the exact distance to the blend's own outline, traced finely.
val traced = FLine()
for (contour in generateIsoContours(blend, -1.5, 1.5, -1.0, 1.0, 600, 400, 0.0)) {
    traced.moveTo(contour[0])
    for (p in contour.drop(1)) traced.lineTo(p)
    traced.close()
}
val truth = traced.signedDistance2D()
:::endfold
// Offset contours read off each grid, and how far each lands from the distance it was asked for.
val offsets = [-0.2, -0.1, 0.1, 0.2, 0.3]
fun offset(grid: SdfGrid2D, d: Double) =
    generateIsoContours({ x, y -> grid.bilinear(x, y) }, -1.5, 1.5, -1.0, 1.0, 300, 200, d)
fun worst(contours: List<List<Vec2>>, d: Double) =
    contours.flatten().maxOf { abs(truth(it.x.toDouble(), it.y.toDouble()) - d) }
val table = buildString {
    appendLine("| offset | worst miss, blend | worst miss, repaired |")
    appendLine("|---|---|---|")
    for (d in offsets) appendLine(
        "| %.2f | %.4f | %.4f |".format(d, worst(offset(pseudo, d), d), worst(offset(fixed, d), d)),
    )
}
:::fold draw the offsets from both grids over the outline
val scale = 150.0
val drawn = ArrayList<FLine>()
fun draw(contours: List<List<Vec2>>, tint: Vec4, width: Double) {
    for (c in contours) drawn +=
        polylineFrom(c.map { vec(it.x * scale, -it.y * scale) }, closed = true).apply {
            thickness = width
            color = tint
        }
}
draw(offset(fixed, 0.0), vec(1.0, 0.67, 0.0, 1.0), 2.5)
for (d in offsets) {
    draw(offset(fixed, d), vec(0.5, 0.8, 1.0, 0.9), 2.0)
    draw(offset(pseudo, d), vec(1.0, 0.3, 0.25, 0.9), 1.5)
}
lines(drawn, channel = "offsets", w = 640.0, h = 430.0, fitToSize = true)
:::endfold
markdown("readout", table)
```

![Offsets from both grids](assets/chain_squircle_offsets/offsets.png)

Away from the waist the two sets of contours lie on top of each other, since there the blend is
exactly one squircle's distance. At the waist they part. The inset at −0.2 is the worst: it runs
through the overlap, where the blend's values are furthest off, and the table shows it missing by
more than a third of the distance it was asked for. The repaired grid's contours stay within a fifth of
a grid cell (the cell here is 0.03125) of where they should be. The sharp corners on
the outer blue offsets are correct: the true offset of an inward curve tighter than the offset
distance comes to a point, and the blend's red contours round them off.

## Stepping inwards: a pocketing toolpath

Clearing the inside of a shape with a cutter, or filling it with a pen plotter, means following a
stack of insets one tool width apart. Here the step is 0.04, and the box draws fourteen insets from
each grid side by side: the blend on the left, the repaired grid on the right. The table counts the
rings, counts the depths at which the path broke into two separate loops, and measures the worst
miss against the true distance, also as a number of steps.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_squircle_offsets/insets.png" wait="2.0" capture="box" open-outputs="true" padding="20" alt="Two copies of the orange outline filled with nested insets. On the left the red insets split at the waist into two separate pockets, one in each squircle, with a wide empty band between them. On the right the blue insets stay one connected family, evenly spaced, and run through the waist into the smaller squircle, meeting in a point along the middle"}
:::static
:::fold the shapes, the blend, its grids and the traced outline, as above
// Two squircle rectangles with clothoid corners, overlapping.
val big = squircleRectFrom(-1.0, -0.6, 1.4, 1.2, radius = 0.5, gauge = 0.79, style = SquircleStyle.Clothoid)
val small = squircleRectFrom(0.1, -0.35, 1.0, 0.7, radius = 0.3, gauge = 0.79, style = SquircleStyle.Clothoid)

// Each outline as an exact signed distance, then the two joined with a smooth min.
val da = big.signedDistance2D()
val db = small.signedDistance2D()
val k = 0.25
val blend = { x: Double, y: Double ->
    val p = da(x, y)
    val q = db(x, y)
    val t = (0.5 + 0.5 * (q - p) / k).coerceIn(0.0, 1.0)
    q + (p - q) * t - k * t * (1 - t)
}

val pseudo = sdfGrid2D(xMin = -1.5, yMin = -1.0, xMax = 1.5, yMax = 1.0, n = 97, field = blend)
val fixed = pseudo.repaired()
// The answer to check against: the exact distance to the blend's own outline, traced finely.
val traced = FLine()
for (contour in generateIsoContours(blend, -1.5, 1.5, -1.0, 1.0, 600, 400, 0.0)) {
    traced.moveTo(contour[0])
    for (p in contour.drop(1)) traced.lineTo(p)
    traced.close()
}
val truth = traced.signedDistance2D()
:::endfold
// Inset rings every 0.04, as a pocketing toolpath would step in, read off each grid.
val step = 0.04
val depths = (1..14).map { -step * it }
fun rings(grid: SdfGrid2D, d: Double) =
    generateIsoContours({ x, y -> grid.bilinear(x, y) }, -1.5, 1.5, -1.0, 1.0, 300, 200, d)
val table = buildString {
    appendLine("| grid | depths | rings | depths split in two | worst miss | worst miss, in steps |")
    appendLine("|---|---|---|---|---|---|")
    for ((name, grid) in ["blend" to pseudo, "repaired" to fixed]) {
        var worst = 0.0
        var count = 0
        var split = 0
        for (d in depths) {
            val cs = rings(grid, d)
            count += cs.size
            if (cs.size > 1) split++
            for (p in cs.flatten()) worst = max(worst, abs(truth(p.x.toDouble(), p.y.toDouble()) - d))
        }
        appendLine("| $name | %d | %d | %d | %.4f | %.2f |".format(depths.size, count, split, worst, worst / step))
    }
}
:::fold draw the rings from both grids, side by side
val scale = 110.0
val drawn = ArrayList<FLine>()
for ((panel, grid) in [pseudo, fixed].withIndex()) {
    val dx = panel * 3.2
    val tint = if (panel == 0) vec(1.0, 0.3, 0.25, 1.0) else vec(0.5, 0.8, 1.0, 1.0)
    for (c in rings(grid, 0.0)) drawn +=
        polylineFrom(c.map { vec((it.x + dx) * scale, -it.y * scale) }, closed = true).apply {
            thickness = 2.5
            color = vec(1.0, 0.67, 0.0, 1.0)
        }
    for (d in depths) for (c in rings(grid, d)) drawn +=
        polylineFrom(c.map { vec((it.x + dx) * scale, -it.y * scale) }, closed = true).apply {
            thickness = 1.5
            color = tint
        }
}
lines(drawn, channel = "insets", w = 660.0, h = 240.0, fitToSize = true)
:::endfold
markdown("readout", table)
```

![Inset toolpaths from each grid](assets/chain_squircle_offsets/insets.png)

The blend's toolpath is wrong where it matters. Inside the overlap its values say the points are
close to the edge when they are far from it, so the deeper insets cut off at the waist and leave
two pockets, one per squircle, with an uncut band between them. A cutter following it misses
material by several steps. The repaired insets stay one tool width apart and run through the waist
in one connected family, which is what the shape's distance says they should do.

A sphere tracer, which steps along a ray by the field's value, barely notices the difference: a
smooth min only ever underestimates, so tracing through the blend stays safe and costs a few more
steps near the waist. The repair matters to whatever reads the value as a distance, and offsets
read nothing else.

## What passes along the chain, and what is lost

- **Outline to field.** `signedDistance2D()` flattens the `FLine`, 24 points per cubic, and
  resamples its edges finely; the distance is exact to that flattening. Inside and outside follow
  the even-odd rule, so a counter wound either way is a hole.
- **Field to blend.** The smooth min keeps the zero set exactly and loses the distances: the error
  figure shows where.
- **Blend to grid.** Sampling keeps the sign of every sample and nothing finer than a cell. The
  repair trusts those signs, so a feature thinner than a cell is lost before it starts.
- **Grid to offsets and toolpaths.** Both read values only, through `bilinear`. Whatever the values
  say, the contours follow.

## Where to go next

- [Squircles](fline_examples.md#squircles-squirclefrom-and-squirclerectfrom) compares the five
  corner styles and their curvature.
- [Repairing a field that is only a bound](mesh_examples.md#repairing-a-field-that-is-only-a-bound)
  shows the circles the repair works with, and `upsampled` for refining a coarse grid.
- [Examples that cross several parts of Field](chains.md) lists the other chains.
