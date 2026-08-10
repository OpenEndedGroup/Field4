# What a click on a figure returns

*2026-08-10.* An interactive `FLine` figure could be clicked long before this document existed. What
it could not do was *tell you what you clicked*. This is the chain, end to end, what each link can
promise, and what is tested by executing which code.

## The claim, adjudicated

The prompt for this work said Field's figures are "write-only" and the interactive path was probably
dead. Half of that was wrong and half of it was the point.

**Wired, and in use.** `FLine.onClick` and `FLine.hoverColor` (`StandardDrawing.kt`) make
`Box.lines(...)` switch to `buildInteractiveSVG`; the emitted `<g>` carries an `onclick` calling
`window.sendFLineClick`; `sliders.js` posts an `FLineClick`; `Server.kt`'s `fline-click` handler looks
the callback up in the registry in `outputs.kt` and calls it. `PetriVisual` has shipped on top of it
for months — click a place to inject a token, click a transition to fire it.

**But nothing tested it, and the record could not name anything.** No test and no docs capture drove a
click through that chain: the only test that mentioned `onClick` set it in order to assert it does
*not* serialise. And `FLineIntersection` was two fields — the nearest node's index, and a point — so a
caller could learn *that* a line was clicked (from which closure fired) and nothing else. Hence
`PetriVisual` allocating one closure per place and per transition, and hence the 2026-08-09 `panel-*`
documents reading measurements about specific panels out of `println` lines: there was no way to ask
the figure.

**And the position was wrong anyway.** Measured on a live canvas while building the demo below: a
click in the middle of a panel reconstructed three times too far up the figure. Field's output area
sizes the emitted `<svg>` at `width: 100%` and the canvas scales the box with CSS, and
`getScreenCTM()` — which the reconstruction used on its own — does not include that scale. Every
position the interactive SVG output has ever reported was wrong wherever the canvas was not at 1:1.
Nobody could notice, because nothing read it. So: not write-only. **Write-mostly, untested, and
quietly mis-calibrated.**

## The chain

| step | code | carries |
| --- | --- | --- |
| a line asks for interaction | `FLine.onClick`, `FLine.hoverColor`, `FLine.label` (`StandardDrawing.kt`) | — |
| the renderer emits it | `buildInteractiveSVG` (`someSkiko.kt`) | `onclick`, `data-fline-callback-id`, `data-fline-index`, `data-fline-label`, `data-fline-vertices`, `data-fline-elements`, `data-fline-pick`, `data-fit-*`, one `data-fline-calibration` rect per figure |
| the browser reconstructs the hit | `__flineIntersectionFromSvg` (`sliders.js`) | kind, line, node, subpath, element, `u`, `v`, the point on the element |
| the browser posts it | `sendFLineClick` → `afield.send('fline-click', …)` | `FLineClick` (`rpc.kt`) |
| the server dispatches it | `fline-click` handler (`Server.kt`), `FLineClick.toIntersection()` (`outputs.kt`) | `FLineIntersection` |
| three.js does the same | `installThreeJsHandler`, `buildThreeGeometryPayload` (`ThreeJsViewer.kt`), `intersectionForHit` + the `fline-hit-picking` region (`threejs_viewer.html`) | the same record: kind, line, label, node, subpath, element, `u`/`v`, the point on the element |

### What the renderer sends, and in whose coordinates

`data-fline-elements` is the line's topology in **the line's own, unfitted coordinates** — the space
the user's numbers are in — so everything the record reports comes back in those units, not in pixels.
Two lists:

- `segments`, one per drawn segment in draw order: `k` is `l` / `c` / `z` (straight, cubic, or the
  segment a `close` draws), `n` the index in `nodes` of the node that draws it, `na`/`nb` its ends' own
  node indices, `p` the subpath, `e` the segment's ordinal, `a`/`b`/`c1`/`c2` the geometry.
- `faces`, one per closed subpath, as the polygon through that subpath's anchors. Cubics contribute
  their endpoint, not their curve — which is what makes "exactly four corners" mean *a quad panel*.

`data-fline-pick` is the picking policy, in line units: how close counts as a node, and as a segment.
Chosen in screen pixels and divided by the fit scale, because that is where the finger is.

The three.js viewer receives the **same** `flineTopologyJson` string — as `ThreeInteractiveObject.topology`,
and on `ThreeEditableLine.topology` in editable mode — so the two clickable outputs cannot describe the
same line differently. Only the picking differs; see below.

### How the browser decides

Node → segment → face, first match wins, and then:

- **node** when the click is within the point tolerance of an anchor. A node's reach is additionally
  capped at a third of the segment it ends, so a 28-gon disc drawn at a third of its size still reports
  its rim as an *edge* rather than as whichever tessellation vertex was nearest.
- **edge** when within the edge tolerance of a segment. `u` is the projection for a straight segment
  and a sampled-then-bisected search for a cubic (accurate to ~1e-4 in the test's cases).
- **face** when the click is inside a closed subpath (even-odd). `(u, v)` are inverse-bilinear for a
  four-corner face — `u` from corner 0 to corner 1, `v` from corner 0 to corner 3 — and
  bounding-box-relative for anything else. `z` comes from the plane through the face's first three
  non-collinear corners, so a planar 3D face reports an exact depth.
- otherwise the nearest segment, because the click did hit this line's `<g>`.

`onElement` is the point *on* the element. Its `z` is interpolated along the element, which is the one
way a 3D line drawn into a 2D SVG can report a depth at all.

### How the three.js viewer decides

It has it easier in exactly one respect, and that respect is the kind. three.js has already told the
click handler *which geometry* the ray hit — the `LineSegments`, the fill `Mesh`, or the `Points` — so
`flineRoleForObject` reads the kind off `isLineSegments`/`isMesh`/`isPoints` (which also works in
editable mode, where one entry owns several meshes) and no tolerance is involved. What is left is
*which* element of that kind, and where on it, and that is a nearest-search in 3D over the same
topology:

- **edge** — nearest segment to `hit.pointOnLine` (three.js's own nearest point on the picked segment,
  which is closer to the geometry than the point on the ray), `u` by projection or by the same
  sampled-then-bisected search.
- **face** — the face whose *own plane* the hit is closest to and whose polygon contains its projection.
  The frame is Newell's normal plus the first usable edge, so a slightly non-planar or concave polygon
  still gets its area-weighted plane; `(u, v)` are then computed in that frame, which is what makes them
  comparable with the 2D path's.
- **point** — nearest anchor in 3D.

The maths is a marked region, `// [viewer-region fline-hit-picking]`, which is how a test can execute it:
`ViewerJavaScript` lifts the text and `ThreeJsHitRecordDifferentialTest` runs it on GraalJS against
topology from the real Kotlin producer. Registering it in `ViewerJavaScript.KNOWN_REGIONS` also enrols it
in `ViewerJavaScriptRegionTest`'s health rules — found, unique, declares its exports, evaluable.

### What `u` means, and what `(u, v)` means

**`u` on a cubic is the Bézier parameter, not arc length.** A reader will assume arc length, so the
difference is measured rather than described: on the lopsided cubic in `FLineHitRecordProbeTest` — both
controls crowded against the start — parameter `0.5` sits at **16.8%** of the curve's length, and the
test asserts that gap exceeds 0.3. The parameter is the right choice because it is the number that
indexes back into the curve the author wrote; if arc length is ever wanted it should be a *second*
field, and the test above will fail rather than let `u` change meaning silently.

**`(u, v)` on a face says which convention it followed**, via `FLineIntersection.faceCoordinates`:

| convention | when | what it is |
| --- | --- | --- |
| `BILINEAR` | a four-corner face whose bilinear map inverts *and* passes a check | `u` corner 0 → 1, `v` corner 0 → 3: a position on a panel |
| `BOUNDING_BOX` | any other closed subpath, or a quad whose solve fails the check | the point in the face's own bounding box (in its plane, for three.js) — monotone, and not a parameterisation |
| `NONE` | a face with no plane at all, or a non-face hit | `u` and `v` are `NaN`: a refusal |

The check is what makes `BILINEAR` a measurement: the Newton solve has to converge, the answer has to
land inside the patch, and the forward map has to reproduce the click to within `1e-6` of the face's
diagonal. Without it a non-convex quad returns a coordinate off the fold that looks perfectly ordinary.

That said, the adversarial case corrected the assumption behind it, and the correction is worth stating:
the checked solve is **robust**, not fragile. Over the 941 interior points of a 2-unit grid on a dart
(one corner pulled back past the diagonal) **939 verify** and two fall back — both in the extreme corners
either side of the notch, and in the 2D path those two are within the edge tolerance and are reported as
edges anyway. So on that figure the fallback is unreachable by clicking, and the place it gets exercised
is the function-level differential.

Both implementations of that rule — `flineFaceCoords` in `sliders.js` (a click in the plane) and
`flinePickFaceCoords` in the viewer region (a hit projected into a face's plane) — are executed against
each other on six faces in `FLineHitRecordProbeTest`, in the `z = 0` plane so they receive identical
numbers, and must agree to `1e-9` including on `NaN`.

### Calibration, and why it is a rectangle

Every figure carries one `<rect data-fline-calibration="100" .../>`: 100×100 user units at the origin,
painting nothing, `pointer-events="none"`. Dividing its `getBoundingClientRect()` by its known size
measures the entire chain from user units to client pixels — viewBox, CSS transform, CSS `zoom`,
devicePixelRatio — in two divisions, without knowing which of them is in play. `getScreenCTM()` is
kept as the fallback for a page holding an older cached SVG.

### Compatibility

Every field added to `FLineIntersection` and to `FLineClick` is defaulted, on purpose in both cases.
The server decodes `FLineClick` **strictly** (an unknown key throws), so the JavaScript and the data
class must name the same things — but a browser on a *stale cached* `sliders.js`, posting only the
original five keys, still arrives and still fires the callback with `kind = UNKNOWN`. `u`, `v` and the
`ex`/`ey`/`ez` triple travel as `null` rather than `NaN`, because JSON cannot carry a NaN and the whole
message would be rejected.

## Using it

```kotlin
val panels = wall.panels().mapIndexed { i, p -> p.toFLine().also { it.label = "panel $i" } }
inspect(panels, w = 620.0, h = 420.0, onHit = { hit -> println(hit.describe()) })
```

`Box.inspect` (`FLineInspect.kt`) renders exactly what `lines(...)` renders and adds the interaction:
hover, and a card naming whatever is clicked — `panel 1,2 · face 0 · u 0.497 v 0.531 · at (266,
180.466, 109.885)`. `FLineIntersection.describe()` is that one line; pass `describe =` to say something
domain-specific instead. Any `onClick` a line already had still runs.

**The demo.** `public/docs/assets/fline_hit_records_demo/metadata.edn` — one box, loads into a fresh
document. Run it, then click a panel: the *inspect — what you clicked* output names that panel, whether
you hit its face, one of its edges or a corner, and where on it, in the wall's own coordinates with `z`
the distance along the wall. Hovering highlights the panel under the pointer. Clicking the amber ridge
line names that instead, with `u` along its length.

## What is tested, by running what

| cover | runs | does not run |
| --- | --- | --- |
| `FLineHitRecordProbeTest` (`jvmTest`) | the **verbatim** `sliders.js` under node, against SVG the real `buildInteractiveSVG` emitted; then the payloads it posted, decoded as `FLineClick` and dispatched through `getFLineClickCallback`; plus the six-face differential against the lifted viewer region | a browser — no layout, no hit-testing, no z-order |
| `ThreeJsHitRecordDifferentialTest` | the viewer's `fline-hit-picking` region on GraalJS, against topology from the real Kotlin producer: a 3D segment at `u = 0.3`, a tilted quad's bilinear `(0.25, 0.75)`, a point sprite, a 28-gon's `bbox`, a planeless face's refusal, and 57 interior points of a dart | three.js itself — no raycast, no camera |
| `ThreeInteractivePayloadTest` | that `buildThreeGeometryPayload` sends the topology, the label and the line index, byte-identical to the SVG's string, and that a hover-only line carries none | the transport |
| `graphics.fline-hit-records.smoke` | `FLineHitInspectorFigure`: the emitted SVG has a callback, a label and a face per panel, and still carries the wall's own `z` | anything client-side |
| `tools/fline_hit_proof.py` | Chromium, real mouse clicks on the real emitted SVG next to the real `sliders.js`, six cases checked against the geometry | the socket, the server, the callback |
| the live check recorded below | all of it, in a running Field | nothing — but it is a manual run, not a gate |

`sliders.js` is staged **off the classpath** in the test, which makes it a declared input of `jvmTest`:
editing it re-runs the test instead of leaving a stale UP-TO-DATE pass. A parity test with both sides
written in Kotlin would agree with itself forever and guard nothing.

## The pixels

`dev_docs/agents/artifacts/fline_hit_records/`:

- `panel_wall_hits.png` — markers drawn at the points the *browser* reported for six real clicks (face
  purple, edge cyan, node amber). They land on the elements they name.
- `panel_wall_hover.png` — the same figure mid-hover: exactly one panel lights.
- `field_ui_inspect.png` — the demo box inside a running Field, showing the hit card after a click.
- `reported_hits.json` — the payloads behind `panel_wall_hits.png`.

Reproduce:

```
python3 tools/run_field_tests.py --port 9492 --id graphics.fline-hit-records.smoke
python3 tools/field_backdoor.py --port 9492 --command \
  "run-jvm-main afield.examples.graphics.FLineHitInspectorFigure --arg /tmp/panel_wall.svg"
python3 tools/fline_hit_proof.py --svg /tmp/panel_wall.svg --out /tmp/fline-proof
```

## Left undone

- **The three.js raycast itself is not executed by any test.** Its picking maths is (GraalJS, against
  the real producer) and its payload is (`ThreeInteractivePayloadTest`), but the step in between — a
  real camera, a real ray, `hit.pointOnLine`, and which of an entry's meshes the ray chose — needs a
  browser with WebGL and the viewer's CDN `THREE`. The 2D path has that cover
  (`tools/fline_hit_proof.py`); the 3D path does not, and a click in a live three.js viewer has not been
  observed end to end. That is the next thing to do, and the honest state of it today.
- **Hit-testing across overlapping lines** is the browser's, so the topmost `<g>` wins. A click on a
  shared corner of four panels is reported by whichever of them the browser picked; the hit says
  *which* line answered, which is at least honest about it.
- **`(u, v)` for a face with more than four corners** is bounding-box-relative, not a parameterisation.
  A general one would need the face's own frame, which for a planar polygon is a small amount of work
  and for a non-planar one is a research question.
