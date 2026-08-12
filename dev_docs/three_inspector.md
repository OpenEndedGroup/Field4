# The three.js output area as an instrument

| | |
| --- | --- |
| the viewer | `src/commonMain/resources/threejs_viewer.html` |
| the Kotlin surface | `src/jvmMain/kotlin/afield/graphics/ThreeJsInspector.kt` |
| the payload | `src/jvmMain/kotlin/afield/graphics/ThreeJsViewer.kt` |
| the harness | `src/jvmTest/kotlin/afield/graphics/ViewerJavaScript.kt` |
| the demo | `public/docs/assets/three_inspector_demo/metadata.edn` |
| the user-facing docs | `public/docs/fline_examples.md`, "The 3D output area as an instrument" |

## Why

Every one of the human's 2026-08-09 `panel-*` workspace documents renders through the three.js viewer.
One of them, `panel-cross-host-3d`, ends:

```kotlin
showConduitRelaxationThree(relaxed, channelPrefix = "cross-host-assembled", explosionMm = 0.0, height = 720)
```

`explosionMm = 0.0`. An exploded view existed — as a number you retype and re-run a box for.

`06352df08` taught the viewer to name *which element* a click hit, which answered "what is this". The
next three questions anyone asks of a 3D preview of a thing that gets built are:

1. **how far apart are these two things**
2. **what does this look like in section**
3. **how does it come apart**

Before this change, `grep -n "clippingPlane\|explode\|measure" threejs_viewer.html` returned nothing.

## The shape of it

`Box.threeInspect(...)` is a **sibling** of `Box.threeLines(...)`, not more parameters on it. Two
reasons, and the second is the real one: `fline_examples.md` pins `threeLines`' signature in a static
block, and the return type has to differ. `threeLines` returns something you push new *geometry* to;
`threeInspect` returns a `ThreeInspectorView` you push new *questions* to —

```kotlin
val view = threeInspect(panels, units = "mm")
view.explode(900.0)                             // no re-run, no retyped literal
view.explodeAlong(0.0, 0.0, 1.0, 500.0)
view.section(0.0, 0.0, 1.0, fraction = 0.42)
view.measureFaces(panels[0], panels[3], "clear ")
```

A payload with no `inspector` block behaves exactly as it did before any of this existed: no HUD, no
clipping planes, no kept copy of the base positions. That is asserted, not assumed
(`a payload with no group attribute passes through untouched`).

## Where the work happens, and why that matters

**In the viewer, on the client.** A slider that costs a websocket round-trip and a box re-run is the
thing being replaced. So the displacement, the cut and the closest approach are all computed in
`threejs_viewer.html` — which means they are computed **twice**, there and in Kotlin.

That is exactly the situation `ViewerJavaScript` was built for, and its KDoc invited it:

> the *next* subsystem that duplicates logic into the viewer marks a region and gets a differential
> test for free.

So each of the three is a `// [viewer-region …] BEGIN/END` region, lifted out of the shipped HTML and
executed on GraalJS against expectations derived from the geometry the figures were built from.

| region | what it computes | what the differential asserts |
| --- | --- | --- |
| `inspector-measure` | closest approach between a point / segment / polygon, and where to draw the dimension | two panels built 2.5 apart come back as 2.5 (1e-12); 240 seeded pairs never lose to a 21×21 Kotlin lattice, and every reported point is on its element to 1.8e-12 |
| `inspector-explode` | group centroids, per-group offsets, the displaced buffer | four skins at x = 0/12/24/36 fan to exactly −150/−50/50/150; `amount = 0` returns the assembled buffer unchanged |
| `inspector-section` | the plane from bounds + normal + fraction, and the cut through triangles and lines | a horizontal section through four 200-wide skins is exactly 800.0 of cut line, worst off-plane residual 0.0 |
| `interactive-entry` | what a clickable object remembers about itself | a real `ThreeInteractiveObject` becomes an entry that still knows its topology, index and label |

Every one of them has at least one **counterfactual** — the region's text rewritten so a branch stops
working, with the specific wrong number asserted. Those are the tests that say the healthy assertions
are load-bearing rather than accidentally true. Two worth knowing:

* delete `measureNearestOnElement`'s polygon-interior branch and the two panels measure `hypot(10, 2.5)`
  instead of 2.5, because a corner can then only reach the other panel's *boundary*;
* change `inspector-section`'s straddle test from `(da > 0) !== (db > 0)` to `da * db < 0` and a cut
  through a triangle with one vertex on the plane is not slightly wrong, it is **empty**. They differ
  exactly when a vertex lies on the plane — the common case, not the rare one, because real geometry is
  built on round numbers and sections get put on round numbers.

## Three details that are not obvious

### Rank spacing, not distance spacing

`ThreeExplode(mode = "axis")` spaces groups by their **order** along the axis, not by their distance
along it. A wall's four skins are 90, 50 and 12 mm apart; a distance-proportional explode leaves three
of them in a clump and sends the fourth off-screen. Rank spacing fans them evenly, which is what a
build-up drawing wants.

The ranks are **centred** — the middle group stays put — so the figure does not walk out of frame as
the slider is dragged. That is a usability property with an arithmetic test: the mean offset is exactly
0, and the counterfactual that removes the centring drifts it to exactly 150.

### The per-vertex `group` attribute

The batched geometry merges every FLine into one buffer, so each vertex carries a `group` index. It is
synthesised in `buildThreeGeometryPayload` by **recording the buffer length before and after each
line** rather than by threading a `group` out-parameter through `appendFLineGeometry` and its five
callees. The result is identical and five signatures stay the length they are.

`withGroups` then *checks* the count against the vertex count, and `buildThreeGeometryPayload` requires
one key per line. Both are `check`/`require` rather than tests, because the failure is invisible: a
group array one entry short means every vertex quietly inherits its neighbour's group, and three.js
renders a plausible wrong explosion.

Interactive objects are each one FLine, so their group is uniform and they move by `object.position`
instead — same offsets, same region function, much less work per slider frame.

### A fraction of the bounds, not a world coordinate

`ThreeSection.fraction` runs 0..1 across the figure's own bounds *measured along the normal*, with all
eight corners projected so an oblique normal gets the true extent. `0.42` therefore means the same
thing on a 3 m wall and a 30 mm bracket, and a scrubber over it is meaningful without knowing the
model's scale.

`flip` negates the plane without moving the cut. That is why it is a separate control from negating the
normal: negating would also reverse the fraction and send the slider backwards under the user's hand.

## The bug this turned up

While wiring pick-to-measure — which needs `entry.topology` — it became clear that last night's hit
record was **dead on the path every `panel-*` document takes**.

There were three places that built an interactive entry:

* `makeInteractiveEntry`, for editable scenes — updated by `06352df08`, correct;
* an object literal inside `upsertInteractiveObject`, for ordinary ones — not updated;
* `addInteractiveObject`, which had all three fields and **no callers at all**.

`renderPayloadGeometry` calls the second for every non-editable payload. So `entry.topology` was
`undefined`, `flinePickClassify` was handed `null`, and every click came back with a kind but
`nodeIndex: -1`, `elementIndex: -1` and `label: null`.

Nothing failed and nothing would have: `ThreeJsHitRecordDifferentialTest` tests the classifier, and the
classifier was fine. The dead third copy is what made the drift invisible — reading the file, the
correct constructor is right there.

The fix is one constructor, marked as the `interactive-entry` region, with the dead copy deleted.
`ThreeInteractiveEntryDifferentialTest` covers it, and adds a *structural* guard — exactly one
`topology: parseFLineTopology(` in the whole viewer — because a differential can prove the surviving
constructor right but not that nobody has added a fourth beside it, which is how this happened.

## What is not covered, said plainly

* **The three.js plumbing is not under differential test.** The regions are the geometry. Buffers,
  materials, sprites and sliders are not liftable and are not lifted. If the dimension's canvas sprite
  rendered empty, every test here would still be green. That is why `public/docs/fline_examples.md`
  carries four real captures rather than four claims, and why the demo's code *is* one of those
  captures.
* **Clipping covers the three custom `ShaderMaterial`s as well as the standard one**, but by hand:
  `makeLineMaterial`, `makePointMaterial` and their two interactive siblings each carry
  `clipping: true` plus three.js's `clipping_planes_*` chunks, with `<clipping_planes_vertex>` after a
  `vec4 mvPosition` it reads *by name*. That is an undocumented-ish contract with `WebGLPrograms`, and
  a fifth custom material added later will silently not clip. The editable-mode overlay materials
  (handles, marquee highlights) deliberately do not clip — edit mode and section mode are not meant to
  be combined, and that is untested rather than prevented.
* **A measurement is model-space.** It reads the real distance between two things and is *not* moved by
  the explode slider, so on an exploded figure the dimension detaches from the geometry. That is the
  right trade — a drawing wants the real number — but it means dimensions and explosions are best read
  one at a time. Making them travel with their groups would require a measurement to know which group
  each end belongs to, and would then have to choose between drawing a dimension line whose length
  disagrees with its number and reporting the exploded distance, which is not a distance.
* **Pick-to-measure is implemented but only verified by reading.** Clicking two elements with the ruler
  tool active resolves each hit through `flinePickClassify` and measures between the resolved elements.
  The *resolution* and the *measurement* are both under test; the click plumbing between them is not,
  because a static docs capture cannot click. The interactive path is the demo's job.
* **`public/zensical.yml` carries an explicit nav** and was untouchable on the branch this landed on
  (the human had uncommitted work in it), so the user-facing documentation is a section of
  `fline_examples.md` rather than a page of its own. Beside `threeLines` is arguably the right home
  anyway, but if it grows, promoting it is a one-line nav change.
