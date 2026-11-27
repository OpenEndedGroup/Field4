# A Kirigami Sheet, Cut and Counted

The [kirigami page](kirigami.md) opens a sheet of rotating squares by turning rigid tiles about
hinges, and each hinge there is a single shared point. That is exact for the mechanism, but you
cannot cut it: a point of material holds nothing together. This page gives every hinge a width, hands the
sheet to `afield.geometry.solid.manifold` as a 2D region, extrudes it into a solid, and measures
the result. The [kirigami_solid_chain](agent_transcripts.md#kirigami_solid_chain) starting point has
both boxes.

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

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_kirigami_solid/cut_plate.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="x thickness 0.1" embed=true force=true timeout="300" padding="18" name="chain-kirigami-cut-plate" alt="Four lines reading 64 tiles and 112 hinges, genus 49 against hinges minus tiles plus one equals 49, one watertight part, and volume 5.524480 equal to area 55.244800 times thickness 0.1, above a grey render of the square cut plate seen at an angle, its slits and hinge bridges visible" macro-file="../../dev_docs/agents/transcripts/kirigami_solid_chain.yaml" capture-box="kirigami_cut_plate"}
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

## What crosses each hop

| hop | the call | what crosses | what stops here |
| --- | --- | --- | --- |
| mechanism to tiles | `KirigamiSolidChain.deploy` | four corners per tile, keyed by grid node | the turn angle. Only positions go on |
| tiles to a region | `KirigamiSolidChain.section` | polygons, one per tile and one per bridge | which hinge joined which tiles. `Section.union` keeps only the area |
| region to a solid | [`Section.extruded`](reference/graphics.md) | the region's contours and a thickness | nothing, since a straight extrusion is exact |
| solid to numbers | `Solid.genus`, `Solid.decompose`, `Solid.volume` | a closed surface | the tile graph, which the genus has to match to show that no bridge was lost |
| solid to a picture | [`TriMesh.renderPng`](reference/graphics.md) | positions and indices | everything but the shape |

Every check on this page also runs headless in CI as the `chain.kirigami-solid` test, at 0°, 15°, 30° and
45°: the gap at each hinge (below 4e-15), the genus against `hinges − tiles + 1`, one part, a
watertight mesh, and Manifold's volume against the region's area times the thickness.

## The limits of this route

The chain uses the rotating-squares sheet and not the kirigami page's inverse-designed circle. The
circle's 35-second solve lives in its box's code and has no library entry point yet. Its tiles are
quadrilaterals with the same hinge layout, so `section` would take them unchanged once that solve
returns corner positions. The bridges are straight bars of constant width. A real hinge needs a
width and length chosen for its material, so treat `gap` and `bridge` as settings for your
material, not as recommended values.

## Where the code is

* [`KirigamiSolidChain.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/examples/chain/KirigamiSolidChain.kt)
  holds the hinges, the opened tiles, the bridged region and the measurements.
* The [kirigami_solid_chain](agent_transcripts.md#kirigami_solid_chain) starting point has the same steps
  as a document.

## Where to go next

- [Kirigami: cut patterns that open into a shape](kirigami.md), for the mechanism and Choi's
  inverse problem.
- [A Word Cut as a Stencil](chain_stencil_bridges.md), for bridges that hold the islands of a stencil in place.
- [Manifold](manifold.md), for `Section`, offsets and the rest of the 2D-to-3D toolkit.
- [Examples that cross several parts of Field](chains.md), for the other chains.
