# A loadable demo: one FLine, three planes, three holes

`metadata.edn` here is a complete, one-box Field document. It draws the case
`FLine.tessellate` could not fill before 2026-08-03: a single filled FLine holding six subpaths —
three outward-wound unit squares in three mutually perpendicular planes, each with a square hole
punched in it by a second subpath.

## Open it

Hand this folder to the launcher — it opens the workspace above it, with this document
listed — or load the file in the offline viewer:

```
./field.sh dev_docs/agents/artifacts/tessellate_facing/multi_plane_fill_demo --read-only
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
three.js path — `ThreeJsViewer.appendFilledMesh`, one of `FLine.tessellate`'s two production call
sites — is shown working rather than asserted.

## The editable-mode pair

The other three images are the same geometry drawn with `editable = false` and `editable = true`,
which used to be two different answers to the same question. Editable mode re-triangulates
client-side — the user drags points, and the fill has to follow between server round-trips — and it
did that with a naive per-subpath fan.

| | |
|---|---|
| `editable_true_before.png` | Every hole is **filled in**: each hole ring was fanned as a solid quad and drawn on top of its own face. The HUD reports `12 tri`. |
| `editable_true.png` | Three faces, three holes, `24 tri`. |
| `editable_false.png` | The server's triangulation of the same line, for comparison: the same picture and the same `24 tri`. |

`threejs_viewer.html`'s `tessellateCoplanarGroups` is now the same rule as
`afield.graphics.PlanarTessellation`. Written twice, deliberately — a dragged point must not wait for
a round-trip to re-fill.

Guarded, since 2026-08-04, by a test that **runs the viewer's JavaScript**:
`src/jvmTest/kotlin/afield/graphics/ThreeJsViewerTessellationParityTest.kt` lifts the marked region of
`threejs_viewer.html` and evaluates it on GraalJS (`ViewerJavaScript.kt`), then compares it against
`PlanarTessellation` triangle for triangle over roughly sixty ring sets. One of its counterfactuals
injects the fan that produced `editable_true_before.png` and asserts the differential goes red, so the
"before" row of that table is now an assertion as well as a picture.
`ThreeJsEditableFillParityTest.kt` still pins `meta.triangles`, but both sides of *that* comparison are
Kotlin, so it never executed the client at all.

Regenerate the pair with:

```
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch \
  --with-playwright --port <your port> \
  --command 'macro write parity --file <a transcript that draws the box twice>' \
  --command 'macro run parity' \
  --command 'screenshot-box fill_static  editable_false.png --padding 12' \
  --command 'screenshot-box fill_editable editable_true.png  --padding 12'
```

The rasterised, back-face-culled proof of the same scene is one directory up in
[`../README.md`](../README.md); the design and its tolerances are in
[`dev_docs/fline_fill_orientation.md`](../../../../fline_fill_orientation.md).
