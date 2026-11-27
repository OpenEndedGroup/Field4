# A Kirigami Sheet, Cut and Counted

The [kirigami page](kirigami.md) opens a sheet of rotating squares by turning rigid tiles about
hinges, and each hinge there is a single shared point. That is exact for the mechanism, but you
cannot cut it: a point of material holds nothing together. This page gives every hinge a width, hands the
sheet to `afield.geometry.solid.manifold` as a 2D region, extrudes it into a solid, and measures
the result. The [kirigami_solid_chain](agent_transcripts.md#kirigami_solid_chain) starting point has
all three boxes.

The construction is in `afield.examples.chain.KirigamiSolidChain`:

- every unit tile is shrunk about its centre by `gap` on each side, so each cut becomes a slit
  `2 * gap` wide;
- every hinge becomes a short bar across its slit, `bridge` long, running from one tile's corner
  to the other's along the cut they share. The bar joins those two tiles and no others;
- `Section.union` merges tiles and bars into one region, and `Section.extruded` makes it a solid.

The hinge layout is the one in the kirigami page's first box: at grid node `(a, b)` the four corners
that meet pair left to right when `a + b` is even and top to bottom when it is odd.

## The cut plate

At an opening angle of zero the region is the pattern you would send to a laser cutter: a square
plate with its slits, and one bridge at every hinge.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_kirigami_solid/cut_plate.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="x thickness 0.1" embed=true force=true timeout="300" padding="18" name="chain-kirigami-cut-plate" alt="Four lines reading 64 tiles and 112 hinges, genus 49 against hinges minus tiles plus one equals 49, one watertight part, and volume 5.524480 equal to area 55.244800 times thickness 0.1, above a grey render of the square cut plate seen from above at three-quarters, its slits and hinge bridges visible" macro-file="../../dev_docs/agents/transcripts/kirigami_solid_chain.yaml" capture-box="kirigami_cut_plate"}
// Built and launched from dev_docs/agents/transcripts/kirigami_solid_chain.yaml.
```

You can predict the genus before Manifold reports it. Think of the plate as a graph: each tile is a
vertex and each bridge is an edge. A connected graph has `E − V + 1` independent cycles, and each
cycle of tiles and bridges surrounds one hole through the plate. An `n × n` sheet has `2n(n − 1)`
hinges, so

```diffstatic {data-lang=text}
genus = 2n(n − 1) − n² + 1 = (n − 1)²
```

which is 49 for this 8 × 8 sheet, one hole for each interior grid node. If Manifold reports a different genus, then
a bridge is missing or a bar touches a tile it should not. A missing bridge lowers the count and leaves
more than one part. A stray bar raises it.

## Opened

Build the same region with the tiles at their opened positions and print that instead. The bridges stretch
across wider slits, and nothing else changes.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_kirigami_solid/deployed.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="45 deg" embed=true force=true timeout="300" padding="18" name="chain-kirigami-deployed" alt="Four grey renders of the bridged sheet at 0, 15, 30 and 45 degrees, each captioned genus 49: closed slits, then squares turning against each other with diamond holes opening between them" macro-file="../../dev_docs/agents/transcripts/kirigami_solid_chain.yaml" capture-box="kirigami_deployed"}
// Built and launched from dev_docs/agents/transcripts/kirigami_solid_chain.yaml.
```

The genus stays at 49 through every opening angle, and the solid is always one part, because opening
the sheet moves the tiles without changing which tiles are joined. The neighbouring tile centres sit
`cos θ + sin θ` apart, so the sheet's area grows by `(cos θ + sin θ)²`, which is 2 at 45°. The
measured hull falls a little short of that (1.987 at 45°) because the outer edge of the solid
is shrunk by `gap` and has teeth where the tiles turn.

The volume does change: it rises from 5.524 closed to 5.621 at 30°, because each bridge bar spans a
wider slit as the sheet opens. A plate printed already open is not the same object as a plate cut
closed and pulled open. In the printed one the bridges are longer and straight, and in the cut one
they bend.

## The sheet that opens into a circle

The [kirigami page](kirigami.md)'s second box solves Choi's inverse problem: which cut pattern of a
square sheet opens into a circle. That solve is `afield.geometry.conformal.KirigamiInverseDesign`,
and the page's box calls it. Its tiles are irregular quadrilaterals, but its hinges are the same
112 cuts, so `KirigamiSolidChain.bridgedSection` takes them unchanged, closed or opened:

```diffstatic {data-lang=kotlin}
val design = KirigamiInverseDesign(n = 8).solve()
val (closed, opened) = KirigamiSolidChain.measureDesign(design, gap = 0.01)
```

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_kirigami_solid/circle.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="gap 0.04" embed=true force=true timeout="400" padding="18" name="chain-kirigami-circle" alt="Lines of numbers giving, for a slit half-width of 0.01, genus 49 closed and opened against a predicted 49, one watertight part, the two volumes and an opened-to-closed hull ratio of 2.019, then for 0.04 a genus of 47; below, grey renders of the square cut pattern of irregular tiles and of the same solid opened onto a circle, both seen from above at three-quarters" macro-file="../../dev_docs/agents/transcripts/kirigami_solid_chain.yaml" capture-box="kirigami_circle_solid"}
// Built and launched from dev_docs/agents/transcripts/kirigami_solid_chain.yaml.
```

With slits 0.02 wide (`gap = 0.01` in the readout), both states have genus 49, as the tile graph
predicts. Widen them to 0.08 (`gap = 0.04`) and Manifold reports 47. The design has tiles whose edges are as short as a third of a unit and whose corners
are as sharp as 0.4 rad, so pulling every edge in by 0.04 moves a sharp corner far enough that its
bridge no longer meets the tile. Two hinges stop holding, and the genus check catches it even though
the solid is still one part. The rotating squares, whose tiles are all unit squares, take the wider
slit without trouble.

The opened circle sheet covers 2.019 times the hull area of the closed square. That is slightly
more than the rotating squares' 2 at 45°, and the volume grows by about 1.5% as the bridges stretch.

## Stretched until it pops up

Everything above stays in the plane. A real cut sheet does not have to. Pull a thin one by two
opposite edges and, past a small strain, its tiles tilt out of the plane and the hinges twist, so
the flat sheet turns into a textured 3D surface. Ahmad Rafsanjani and Katia Bertoldi measured and
simulated this in
[Buckling-Induced Kirigami](https://doi.org/10.1103/PhysRevLett.118.084301) (Phys. Rev. Lett. 118,
084301, 2017), on the same pattern of mutually orthogonal cuts used here. (See also Shyu et al.,
[A kirigami approach to engineering elasticity in nanocomposites through patterned defects](https://doi.org/10.1038/nmat4327),
Nature Materials 14, 785–789, 2015.)

`afield.examples.chain.KirigamiPopUp` simulates the cut plate from the first section. Each tile is a
rigid plate. Each hinge is that section's bridge: a ligament `2 * gap` long from tile to tile,
`bridge` wide along its cut and `thickness` thick. Beam theory gives it four stiffnesses:

- `stretch`, holding the two tiles together at the hinge;
- `inPlane`, resisting a turn about the sheet normal, which goes as `t · bridge³`;
- `fold`, resisting a turn about the cut, out of the plane, which goes as `bridge · t³`;
- `twist`, resisting a turn about the ligament's own axis, also as `bridge · t³`.

The left column of tiles is clamped and the right column is pulled along the sheet. Each strain
step starts from the last pose and relaxes the sheet to the nearest energy minimum. Every hinge is
cut with a random rest kink of 0.001 rad, the small imperfection that decides which way each tile
tips.

The solver is `afield.simulation.physics.HingedPlates`, which relaxes rigid plates joined by those
four-stiffness hinges by Levenberg–Marquardt. Field's particle solver, `SimdVerlet`, is the wrong
tool here: its particles have no orientation, so a ligament cannot twist, and its constraint
stiffness is multiplied by the iteration count, so there is no force to read a stress from.

The sweep below stretches three sheets, 0.06, 0.08 and 0.12 thick, to 4% in steps of 0.1%:

```diffstatic {data-lang=kotlin}
import afield.examples.chain.KirigamiPopUp

val strains = (0..40).map { it * 0.001 }
val runs = [0.06, 0.08, 0.12].map { t -> t to KirigamiPopUp.sheet(thickness = t).sweep(strains) }
for ((t, states) in runs) println("t = $t: flat until strain ${100 * KirigamiPopUp.threshold(states)}%")
```

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_kirigami_solid/popup_sweep.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="t = 0.12" embed=true force=true timeout="420" padding="18" name="chain-kirigami-popup-sweep" alt="Two Field charts against strain from 0 to 4%. Above, the mean tile tilt of three sheets, 0.06, 0.08 and 0.12 thick: each stays at zero, then rises at its own threshold, marked by a vertical rule, near 0.3%, 0.5% and 1%, and climbs to between 6.9 and 9.2 degrees at 4%. Below, the stress of all three rises along one line until each sheet's threshold, where its curve bends to a shallower slope." macro-file="../../dev_docs/agents/transcripts/kirigami_popup.yaml" capture-box="kirigami_popup_sweep"}
// Built and launched from dev_docs/agents/transcripts/kirigami_popup.yaml.
```

Each sheet stays flat, with tilts below 0.04° where the imperfection alone puts them, until a
critical strain. Then the tiles tilt, and the tilt keeps growing with the stretch. The thresholds
the box prints are 0.31%, 0.51% and 1.04% for the three thicknesses. At the threshold the stress
curve bends to a shallower slope: once the tiles can tilt, the sheet extends more easily. Before
the threshold the three stress curves lie on one line. The sheet is then opening in its plane,
and the in-plane stiffness per unit cross-section does not depend on the thickness.

You can see why the threshold depends on the thickness by comparing the two ways the sheet can
extend. Opening in the plane, by the rotating-squares mechanism, turns each tile by an angle
proportional to the strain `ε`, and so costs about `inPlane · ε²`. Tilting extends the sheet only
at second order in the tilt, so the tilt goes as `√ε` and the cost is about `fold · ε`. The first
is cheaper while `ε` is below about `fold / inPlane = (t / bridge)²`, and the second is cheaper
above it. Dividing each measured threshold by `(t / bridge)²` gives 0.0122, 0.0115 and 0.0104,
which are nearly constant while the thickness doubles. Halving the sheet's thickness lowers its
threshold from 1.04% to 0.31%, about three and a half times.

The 0.06 sheet at 6% strain, path traced from three-quarters above. The tiles stand at up to 23°
to the floor, every ligament is twisted between its two tilted tiles, and the random kinks decide
which way each tile tips, so the tilts do not form a regular pattern:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_kirigami_solid/popup_traced.png" capture="native-window" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="popped sheet traced" embed=true hidden=true timeout="600" name="chain-kirigami-popup-traced" alt="A path-traced 8 by 8 kirigami sheet of orange and blue tiles, seen from three-quarters above over a grey floor. The tiles have tipped out of the plane at different angles, each joined to its neighbours at the corners by small pale ligaments, and their tilts cast an uneven pattern of shadows on the floor." macro-file="../../dev_docs/agents/transcripts/kirigami_popup.yaml" capture-box="kirigami_popup_trace"}
// Built and launched from dev_docs/agents/transcripts/kirigami_popup.yaml.
```

The same box, launched, stretches the sheet from flat to 6% in steps of 0.25% and traces every
step:

```diffstatic_runner {data-lang=kotlin capture="native-window" capture-box="kirigami_popup_trace" transcript="dev_docs/agents/transcripts/kirigami_popup.yaml" video="assets/chain_kirigami_solid/popup.mp4" duration="24" wait="0" timeout="600" embed=true hidden=true name="chain-kirigami-popup-video" alt="A flat 8 by 8 sheet of orange and blue tiles is stretched step by step. For the first moments it stays flat, then its tiles begin to tip out of the plane, and the tilts grow until the sheet is a field of tilted tiles casting shadows on the floor."}
// The transcript launches the box, which ramps the strain from 0 to 6%; record the ramp.
```

The [kirigami_popup](agent_transcripts.md#kirigami_popup) starting point has the sweep and the
traced sheet as boxes. Drag the trace box's `stretch (%)` slider and the sheet relaxes to that
strain, stepping 0.25% at a time from where it is, so it stays on the branch it is on, and the
tracer starts again on the new pose.

??? aside "What this model leaves out"
    Tiles are rigid, and each ligament is one beam element, which is rough for a ligament shorter
    than it is wide (0.08 by 0.12 here). Tiles do not touch each other, so nothing stops two
    neighbours passing through each other at large tilts. A rigid plate with point hinges is the
    smallest model that can show the threshold and its `(t / bridge)²` scaling. It does not
    reproduce the paper's finite-element numbers.

## What crosses each hop

| hop | the call | what crosses | what stops here |
| --- | --- | --- | --- |
| mechanism to tiles | `KirigamiSolidChain.deploy` | four corners per tile, keyed by grid node | the turn angle. Only positions go on |
| tiles to a region | `KirigamiSolidChain.section` | polygons, one per tile and one per bridge | which hinge joined which tiles. `Section.union` keeps only the area |
| region to a solid | [`Section.extruded`](reference/graphics.md) | the region's contours and a thickness | nothing, since a straight extrusion is exact |
| solid to numbers | `Solid.genus`, `Solid.decompose`, `Solid.volume` | a closed surface | the tile graph, which the genus has to match to show that no bridge was lost |
| solid to a picture | [`TriMesh.renderPng`](reference/graphics.md) | positions and indices | everything but the shape |
| tiles and bridges to a stretched sheet | `KirigamiPopUp.sheet`, `Sheet.stretch` | tile centres, hinge points and the ligament stiffnesses | the region. The plates are rigid, so only their poses move |
| sheet to a picture in 3D | `Sheet.mesh`, `importTriMesh` | each tile and ligament as a prism, posed | the hinge forces |

The pop-up sweep runs headless in CI as the `chain.kirigami-popup` test. It checks that each sheet relaxes, stays flat below half its threshold and buckles above it, and that the thresholds divided by `(t / bridge)²` agree to within 35%.

Every check on the solid also runs headless in CI as the `chain.kirigami-solid` test, at 0°, 15°, 30° and
45° and for both states of the circle sheet: the gap at each hinge (below 4e-15), the genus against `hinges − tiles + 1`, one part, a
watertight mesh, and Manifold's volume against the region's area times the thickness.

## The limits of this route

The bridges are straight bars of constant width. A real hinge needs a width and length chosen for
its material, so treat `gap` and `bridge` as settings for your material, not as recommended
values.

## Where the code is

* [`KirigamiSolidChain.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/examples/chain/KirigamiSolidChain.kt)
  holds the hinges, the opened tiles, the bridged region and the measurements.
* [`KirigamiInverseDesign.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/geometry/conformal/KirigamiInverseDesign.kt)
  is Choi's inverse design: the constraints, the solve, the closed pattern and the deployment.
* [`KirigamiPopUp.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/examples/chain/KirigamiPopUp.kt)
  stretches the sheet and measures its tilt and stress;
  [`HingedPlates.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/simulation/physics/HingedPlates.kt)
  is the solver.
* The [kirigami_solid_chain](agent_transcripts.md#kirigami_solid_chain) starting point has the same steps
  as a document.
* The [kirigami_popup](agent_transcripts.md#kirigami_popup) starting point has the pop-up sweep and
  the path-traced sheet with its stretch slider.

## Where to go next

- [Kirigami: cut patterns that open into a shape](kirigami.md), for the mechanism and Choi's
  inverse problem.
- [A Word Cut as a Stencil](chain_stencil_bridges.md), for bridges that hold the islands of a stencil in place.
- [Manifold](manifold.md), for `Section`, offsets and the rest of the 2D-to-3D toolkit.
- [Buckling-Induced Kirigami](https://doi.org/10.1103/PhysRevLett.118.084301), the paper behind the pop-up section.
- [Progressive Path Tracing](path_tracing.md), for the tracer that draws the popped sheet.
- [Examples that cross several parts of Field](chains.md), for the other chains.
