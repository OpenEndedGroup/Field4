# Driven captures: a mesh in the three.js inspector

Real Field on port 9491, real browser, real `click` events dispatched at `renderer.domElement` inside
the viewer iframe. No internal function of `threejs_viewer.html` was called directly; the probe found
the two objects by sweeping the canvas and reading the inspector's own status line. The document driven
is `public/docs/assets/three_mesh_pick_demo/metadata.edn`, loaded from disk — the same artifact a human
would open.

The full account is in [`../../../mesh_inspector_parity.md`](../../../mesh_inspector_parity.md).

| file | what it shows |
| --- | --- |
| `01_drawn.png` | the demo, opened and run: a slab (a `TriMesh`), a conduit (an `FLine`), and the dimension the figure carries from Kotlin |
| `02_picked_measurement.png` | the ruler active, and a **picked** dimension of `150 mm` between a triangle of the solid and a segment of the annotation. The status line reads `clearance 150 mm · 150 mm` — the authored dimension and the picked one, agreeing |
| `03_section_cuts_the_solid.png` | the section tool cutting the **solid** as well as the annotations, cut outline in cyan, reporting `cut 1720 mm @ 144 mm` |

## Read this before quoting the 150

The `150 mm × 25` below is true and it was **oversold** when first written. Audited afterwards, that
reading could not have failed for two whole classes of defect: the measure elements are built from
triangle-corner *indices* and never read the barycentric weights, and on this particular figure the
conduit's leg crosses both the picked edge *and* the hit triangle, so `edge` and `triangle` answer 150
alike. What it does exercise — and what nothing else does — is the raycast reaching a mesh at all, the
`faceIndex` → triangle readout, and the hand-off into `measureElement`.

The configurations where those two classes *can* fail are in `ThreeSurfaceHitDifferentialTest` under
*what the headline did NOT test*, each with its failing input named.

The sweep below is an **observation** of one browser run: nothing floors it and nothing re-runs it. The
150 itself *is* asserted, separately and exactly, by both element kinds.

## The numbers, and where they come from

Sweeping 853 pickable canvas points and measuring each against one anchor triangle on the slab gives

```
{0: 768, 60: 2, 150: 25, 154: 1, 174: 1, 666: 54, 668: 1}
```

* `0` — two picks on the same solid, on triangles that share an edge.
* `60` — the slab's own thickness.
* **`150`** — the conduit. The figure puts it at `z = 180` over a face at `z = 30`, so 150 is the
  clearance by construction, and it matches the dimension authored in Kotlin. Twenty-five of the swept
  points land on it.
* `154`, `174` — points near the conduit's bends, where the closest approach is genuinely further.
* `666`, `668` — picks on the slab's far side. Not derived here; recorded rather than explained.

With the ruler off, the same clicks reach Kotlin through the websocket:

```
slab hit: VERTEX face 1 vertex 18 bary 0.037 0.015 0.948 on (-600.0, 400.0, 30.0)
slab hit: EDGE   face 0 edge 2   bary 0.395 0.030 0.575 on (115.08658, 76.72438, 30.0)
conduit hit: EDGE node 2 u=0.966 at (300.0, 222.99457, 180.0)
```

`(-600, 400, 30)` is exactly a corner of a `1200 × 800 × 60` slab centred on the origin, and
`(300, ·, 180)` is exactly on the conduit's vertical leg. Both records check themselves.

The section's `1720 mm` is asserted rather than only observed, in
`ThreeSurfaceHitDifferentialTest.a section through a solid reports the cross-section it actually cut`.
