# A loadable demo: one FLine, three planes, three holes

`metadata.edn` here is a complete, one-box Field document. It draws the case
`FLine.tessellate` could not fill before 2026-08-03: a single filled FLine holding six subpaths —
three outward-wound unit squares in three mutually perpendicular planes, each with a square hole
punched in it by a second subpath.

## Open it

Point Field at this folder as its workspace, or load the file in the offline viewer:

```
./field.sh dev_docs/agents/artifacts/tessellate_facing/multi_plane_fill_demo
```

or, with a Field already running,

```
open 'http://localhost:<port>/offline.html?file=dev_docs/agents/artifacts/tessellate_facing/multi_plane_fill_demo/metadata.edn'
```

Then run the single box. It opens an orbitable three.js view of the three faces and, beside it, the
same line counted twice.

## What to look for

All three faces are solid, each has its own square hole, and none of them is inside out. Then read
the numbers:

```
subpaths in this one FLine: 6 — three outer rings, three holes
per-plane grouping: 3 filled polygons, 24 triangles
one plane for everything, the old behaviour: 1 polygon, 8 triangles — two faces gone
```

The second line is the counterfactual, computed live by the same box through
`Coplanarity.SINGLE_PLANE`, which puts every ring in one plane whatever its geometry says — exactly
what the single-axis code did. The three outer rings are congruent, so the first of them won the only
axis, filled with its hole (8 triangles), and the other two projected onto a line and contributed
nothing.

`live_demo.png` is that box, screenshotted out of a real Field through the agent backdoor, so the
three.js path — `ThreeJsViewer.appendFilledMesh`, which is one of `FLine.tessellate`'s two production
call sites — is shown working and not just asserted.

The rasterised, back-face-culled proof of the same scene, and its before/after pair, are one
directory up in [`../README.md`](../README.md); the design and its tolerances are in
[`dev_docs/fline_fill_orientation.md`](../../../fline_fill_orientation.md).
