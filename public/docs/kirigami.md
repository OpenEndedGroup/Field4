# Kirigami: cut patterns that open into a shape

Cut a sheet of card along a grid, leaving each cut joined at one end, and the squares can turn
against each other: pull on the sheet and it opens into a lattice of squares and holes. Gary P. T.
Choi, Levi H. Dudte and L. Mahadevan asked the inverse question in
[Programming shape using kirigami tessellations](https://doi.org/10.1038/s41563-019-0452-y)
(Nature Materials 18, 999–1004, 2019): which cut pattern of a square sheet opens into a circle, or
into any other outline you choose? This page builds both halves in two Field boxes, using the
hinge layout, constraints and target shape from Choi's
[inverse-kirigami-design](https://github.com/garyptchoi/inverse-kirigami-design) code (Apache-2.0),
written again in Kotlin over Field's own solvers.

Both boxes are in the [kirigami_choi](agent_transcripts.md#kirigami_choi) transcript. Open it,
run the boxes, and drag their sliders.

## Rotating squares

The first box is the classic pattern: an 8 × 8 sheet of squares, cut along every grid line and left
joined at one corner of every cut. At grid node (a, b) the four corners that meet are joined in
pairs, left to right when a + b is even and top to bottom when it is odd, which is the unit cell of
Choi's code. Opening the sheet turns neighbouring squares by the same angle in opposite directions.

The box does not use a formula for where the squares go. It fixes the first square, turns every
other square by plus or minus the slider's angle, and slides each one until it meets an
already-placed neighbour at their shared hinge. Most hinges are never used in that walk, so the
caption reports the largest gap left at them: about 1e-15 at every angle, which shows that the
mechanism really closes.

```diffstatic_runner {data-lang=kotlin capture="ui" region="box" capture-box="kirigami_rotating_squares" screenshot="assets/kirigami/rotating_squares.png" transcript="dev_docs/agents/transcripts/kirigami_choi.yaml" padding="8" wait="1" timeout="300" embed=true hidden=true name="kirigami-rotating-squares" alt="An opening-angle slider above an 8 by 8 sheet of orange and blue squares turned 30 degrees against each other with diamond-shaped holes between them, a caption reading largest hinge gap 8.9e-16, and three panels showing the sheet closed, at 20 degrees and at 45 degrees."}
ui.pause(500)
```

## A square sheet that opens into a circle

The second box solves Choi's inverse problem. Its unknowns are the corners of every tile in the
*opened* sheet, 144 points in all, because corners joined by a hinge are one point. These
constraints make that opened sheet come from a real cut pattern that closes into a square:

- **Edge pairs.** The two tile edges either side of a cut lie on top of each other when the sheet
  is closed, so they have the same length (112 cuts).
- **Contractibility.** At each node of the closed sheet the tile angles that meet there add up to
  2π inside, π along an edge and π/2 at a corner, and the bottom edge is as long as the right one,
  so the closed sheet is a square (81 angle rings).
- **Shape.** The 32 outermost corners of the opened sheet lie on the target. The target is Choi's
  `circle.m`: four parabolic arcs, within 1% of round.
- **No overlap.** No cut closes below 0.15 rad, and no tile corner is sharper than 0.4 rad or
  flatter than π − 0.4.

The solve is `afield.geometry.conformal.KirigamiInverseDesign`, which the box calls. Choi solves this with MATLAB's interior-point `fmincon`, starting from a conformal map of the
standard pattern onto the disc (the kind of map the [Teichmüller maps](explainer_teichmuller.md) talk builds). The box skips that map: it squashes the standard pattern onto the circle and runs
Field's Levenberg–Marquardt solver, [`leastSquaresAD`](reference/math.md#leastsquares-kt--leastsquaresad),
which differentiates the residuals and drives them down to about 1e-13. The box
then glues the rigid tiles back together, cut side to cut side, to recover the closed cut pattern:

```diffstatic_runner {data-lang=kotlin capture="ui" region="box" capture-box="kirigami_inverse_circle" screenshot="assets/kirigami/inverse_circle.png" transcript="dev_docs/agents/transcripts/kirigami_choi.yaml" padding="8" wait="1" timeout="400" embed=true hidden=true name="kirigami-inverse-circle" alt="Three panels: a square cut pattern of irregular orange and blue quadrilaterals, the same sheet halfway open, and the sheet fully open inside a red circle whose rim its outermost corners touch; below, an open slider and a larger view of the opened sheet in its circle."}
ui.pause(500)
```

The solve takes about 35 seconds the first time. The box keeps the result with `cached`, so later
runs are immediate, and its `progress` panel shows which stage it is on while you wait.

## Watching it open

Launch the box and its `loop` opens and closes the sheet every nine seconds. Drag the slider to
hold it at one opening:

```diffstatic_runner {data-lang=kotlin capture="ui" region="box" capture-box="kirigami_inverse_circle" video="assets/kirigami/deploy.mp4" fps="12" transcript="dev_docs/agents/transcripts/docs_capture/kirigami_choi_movie.yaml" hide-toasts=true padding="8" wait="0" timeout="400" embed=true hidden=true name="kirigami-deploy" alt="The square cut pattern of orange and blue quadrilaterals opens, its holes widening, until its outermost corners reach the red circle, then closes back into the square."}
// The setup transcript launches the box and waits for its solve; record one open-and-close.
ui.pause(10000)
```

Every frame of that movie keeps every hinge exact, because a hinge is one shared point. The tiles
cannot stay exactly rigid on the way, though. The box checks this directly: with every tile and
every hinge held exactly, asking the closed sheet to open its cuts by 0.05 rad opens them by
about 1e-7, so the closed pattern is locked. The open and closed states fit together, but no rigid
motion connects them. A real card sheet gets from one to the other by bending a little at the
hinges and in the tiles. So each frame of the movie opens every cut a step further and holds every
tile as close to its true shape as it will go. The box prints the largest change it has to allow
on the way: about 17% in one tile's edge length or corner angle, and none at either end.

??? aside "What this port does differently from Choi's code"
    The constraint set (edge pairs, angle rings with π/2 corners, the square-ratio constraint, the
    overlap angles) and the circle target follow the MATLAB code closely. The solver and the
    starting guess do not. A regulariser that keeps neighbouring tiles similar, which Choi uses as
    the objective, is left out, and the tile-angle limits stand in for it. Choi's egg and star
    shapes did not converge with this solver from either starting guess, so the box has the circle
    only. The movie's in-between frames come from minimising tile strain, not from any method in
    the paper.

## Where to go next

- [Programming shape using kirigami tessellations](https://doi.org/10.1038/s41563-019-0452-y), the
  paper, and Choi's [inverse-kirigami-design](https://github.com/garyptchoi/inverse-kirigami-design),
  which also has triangle, hexagon and 3-D patterns.
- [FLine examples](fline_examples.md) for drawing filled shapes with `lines` and `showPlaneGrid`.
- [A Kirigami Sheet, Cut and Counted](chain_kirigami_solid.md), which gives each hinge a width
  and turns both sheets on this page into solids you could cut, with their genus counted. Its last
  section stretches the cut sheet until its tiles tilt out of the plane.
- [Teichmüller maps](explainer_teichmuller.md), a talk on the conformal and quasi-conformal maps of
  the same package, `afield.geometry.conformal`, after Choi's work on leaf shapes.
- [The kirigami_choi transcript](agent_transcripts.md#kirigami_choi), to open both boxes.
