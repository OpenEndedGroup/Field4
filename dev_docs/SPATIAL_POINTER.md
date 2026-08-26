# A spatial pointer: pixel → ray → named part

Field could always answer *"what does this ray hit"*. It could not produce the ray.

Before this branch, `grep -rni unproject src/jvmMain/kotlin/afield` returned exactly one hit and it
was the English word "unprojected" inside a KDoc comment. There was no way to turn a mouse position,
an XR controller pose or a headset's aim into a line in the scene. `FLineIntersection` — Field's rich
hit record — was constructed in exactly **one** place on the JVM, and that place decoded a browser
payload (`FLineClick.toIntersection()`, `outputs.kt:924`). Every picker Field owned ran in JavaScript.

This is the missing middle, in four files under `src/jvmMain/kotlin/afield/stdlib/`:

| file | what it adds |
|---|---|
| `pointing.kt` | `Ray`, `RayCamera`, and two inverted cameras |
| `pointing_pick.kt` | `PickParts`, `Pickable`, `PickHit`, `PickScene` |
| `pointing_pointer.kt` | `Pointer` (mouse / controller / pinched hand), `PointerInteraction` |
| `pointing_render.kt` | drawing the pick, and `clickableProjection` |

Open it: **`uv run --project . --group backdoor python tools/field_backdoor.py --port 9711 --with-playwright --command 'macro write spatial_pointer --file dev_docs/spatial_pointer_demo.yaml' --command 'macro run spatial_pointer'`**, then drag the **`pointer x`** slider in the first box.

---

## Two camera models, and why confusing them is the hazard

Field has two unrelated camera models. This is the single most likely way to get picking wrong, so it
is the first thing here.

**`afield.four.util.Camera`** is the live Vulkan scene's camera: a `lookAt` model-view and a
`SuperProjection`. That projection is not always a frustum — it Lie-interpolates in `GL(4)` between an
off-centre perspective matrix and an orthographic prism, `P(t) = P₀·exp(t·log(P₀⁻¹P₁))`. At
`orthoness = 0.4` there is no field of view to take a tangent of and no eye point that all the rays
pass through. So `ClipMatrixRayCamera` does the only thing that is right for all of it: inverts the
clip matrix numerically, in double precision, once per camera rather than once per ray.

A consequence worth knowing: **under a near-orthographic projection the rays are parallel and
`Ray.origin` is not a shared eye point** — it is where that pixel's ray crosses the near plane. Right
for picking, wrong to average.

**`MeshView`** is the orbit camera behind `TriMesh.renderPng`, Field's CPU rasterizer: yaw, pitch, a
distance *in multiples of the mesh's own bounding-sphere radius*, and a field of view. It makes pixels
with no GPU, no window and no AWT, which is why it is the one that can prove things headlessly.

### The refactor that makes the claim worth making

`renderPixels` used to derive its camera in a dozen local `val`s and project in two local `fun`s, so
the projection that produces Field's headless pixels was unreachable from outside the rasterizer's
body. Anyone inverting it had to transcribe it — and a transcription of a projection is exactly the
kind of thing that is 98% right and useless.

So the arithmetic moved to **`VisionGeometryPreview.ViewFrame`**, `renderPixels` calls it, and the
class also runs backwards (`rayThroughSample`, `rayThroughPixel`). `MeshView.rayCamera` holds that
object. **The unprojection is the renderer's inverse by construction, not by copy.**

Three conventions the class now states rather than buries, each a silent way to be wrong:

- **View-space `z` is positive in front of the eye**, and `project` divides by `+z`. The conventional
  negation happens in the shading normals instead.
- **The vertical extent is `contentHeight`, not `height`.** `Style.reservedBottom` reserves a caption
  band and the model is framed above it, so `sy` divides by `contentHeight` while `sx` divides by
  `width`. This is why `MeshView.rayCamera` takes the same `caption`/`title` as `renderPng`: **pass
  the same values to both.** Measured cost of getting it wrong: 28,276 interior pixels disagree (see
  below).
- **Everything is in supersampled samples.** `sampleOfPixel` does the block-centre conversion the
  box-filter downsample implies.

Pixel coordinates run from the **top-left, y downward** — GLFW's `Window.cursor`, and an image's rows.
Field's clip space is Vulkan's `0..1` depth with **y up in NDC**, because `Renderer` renders through a
negative-height viewport (`setViewportAndScissor`: `y = rect.y + rect.h`, `height = -rect.h`).

---

## A hit names a part, not a triangle

`RayHit.triangle` is an index into a `TriMesh`'s `indices`. That is the right answer for a physics
query and the wrong one for a person: a human pointing at a house wants *"the east wall"*, and a
triangle index changes on every rebuild, so it cannot be written down, logged, compared between runs
or used as a selection.

`PickParts` carries a per-triangle part id with names. Four sources, in descending order of trust:

1. **`PickParts.fromTriangulated`** — reads `TriangulatedGeometry.sourceFace`
   (`polytope/FieldAdapters.kt:287`), the polytope layer's own record of which polygonal face each
   triangle was cut from. This is the strongest kind of id because it is not derived from the
   triangles at all: re-triangulate with a different triangulator and the triangles all change while
   the face ids do not. **It had been recorded since it was written and had no consumer anywhere in
   `src`.** This is `SCRATCHPAD` backlog item 34's "stable picking ids through triangles", finally
   consumed.
2. **`TriMeshBuilder.group(name)` + `groups()`** — the author says so. Each `group` call starts a new
   part even if the name repeats, because merging two distinguishable pieces into one would defeat the
   purpose of an id.
3. **`PickParts.byComponent`** — connected components, ranked biggest-first. Honest about its limit:
   components join through *shared vertices*, so a building welded into one surface is one part. Right
   for a bag of separate solids, wrong for a watertight house.
4. **`PickParts.single`** — the default. A pick still names the object; the part adds nothing. Much
   better than pretending each triangle is a part, which reads as information and is not.

### What happens when the geometry is rebuilt

`PickParts` is **keyed by triangle index and valid only for the mesh it was built from.** `Pickable`
checks the triangle count on construction, which catches the gross case; it cannot catch a mesh of the
same size with different triangles and does not pretend to. Rebuild the `PickParts` with the mesh, and
carry the **part id** — never the triangle — across the rebuild.

### Transforms

Picking happens in model space (the ray goes through the inverse) because that is where the BVH lives.
Two consequences, both handled:

- `PickHit.distance` is measured **in world space**, from the ray origin to the transformed hit point,
  so a non-uniformly scaled pickable cannot win "nearest hit" with a compressed model-space parameter.
- `PickHit.normal` goes through the **inverse transpose** of the upper 3×3.

---

## One pointer, three devices

`PointerState` is a ray, a select value in `0..1`, and whether it exists. That is enough for all three,
which is why they are three small functions rather than three implementations.

`pinchPointer` is **the first consumer of `OpenXRHmdTarget.handState` anywhere in the tree** — before
this, every mention of it was inside the file that writes it. Its ray is derived from **joint positions
only**: from the wrist out through the pinch point (thumb-tip / index-tip midpoint), starting at the
pinch point. Both alternatives are worse:

- *Rotate a local forward by a joint quaternion*, as `controllerPointer` does, needs to know which axis
  of an `XR_EXT_hand_tracking` joint runs down the finger. Get it wrong and the ray is confidently
  perpendicular to where the person is pointing. A difference of two positions has no such convention.
- *Point the index finger* (`INDEX_TIP - INDEX_PROXIMAL`) reads beautifully and is unusable: the finger
  curls toward the thumb *in order to* pinch, so the aim swings away exactly as the person commits.
  Wrist-through-pinch swings **under 0.5°** between an open hand and a full pinch, which is what makes
  "aim, then click without re-aiming" possible.

The version a headset's own UI uses — a ray from the *eye* through the pinch point — is better still
and needs a head pose. Compose it yourself: `Ray.through(headPosition, pinchPoint)`.

`controllerPointer` reuses `ImGuiPanel.controllerRay`'s +Z convention rather than forming a second
opinion. Two conventions for "which way is a controller pointing" is the bug that takes a day to find.

---

## Two kinds of hysteresis, because there are two flickers

`PointerInteraction` is hover / press / drag / release. Writing its tests changed its design twice, so
both rules are stated in the code rather than implied.

**A Schmitt trigger on select** (press `0.6`, release `0.35`). A geometric `pinchStrength` derived from
a 40 mm range of finger separation sits on any single threshold and chatters — measured at 7
press/release edges in 10 samples with the hysteresis removed, and 0 with it.

**A dwell on the hovered part**, under one rule: *the dwell guards every transition **away** from a
held hover, and never guards acquiring one.* The asymmetry is the whole design, and both halves were
found by a failing test:

- **Acquiring is immediate.** Guarding acquisition too left the hover permanently `null` on a ray
  sitting exactly on a shared edge — technically flicker-free, actually useless. There is also nothing
  to arbitrate: the dwell settles a *competition*, and an empty hover is not a claim.
- **Switching waits**, so alternation across a shared edge never accumulates a run and the hover
  settles on whichever part it saw first.
- **Releasing waits too**, which is the case it would be easy to miss: a ray grazing a silhouette
  alternates between hitting and missing, and an instant release flickers there just as badly.

The acknowledged cost: a single stray hit lights a label for `switchSamples` samples — 33 ms at 90 Hz
— before releasing. That is the price of zero acquisition lag, and it is the right way round.

Once pressed, **the drag belongs to what it grabbed**: hover freezes, and `dragOffset` is measured on
the plane through the original contact point perpendicular to the grab-time ray, so letting the pointer
wander off the object does not drop the drag.

---

## The evidence

### The z-buffer differential

`Raster` now hands out the z-buffer it always computed and threw away, in view-space z at sample
resolution. That makes two *unrelated* implementations answer "how far away is the surface at this
pixel": a scanline rasterizer writing a depth buffer, and a BVH Möller-Trumbore cast down the
unprojected ray. They share the `ViewFrame` — deliberately, that is the thing under test as an inverse
— and share nothing about how a surface is found.

```
depth differential over 172800 pixels:
  both-surface=53788  both-empty=119012  raster-only=0  ray-only=0
  agreement=1.000000  worst depth error=6.418e-07
```

Zero disagreements. Asserted as `assertEquals(0, …)` rather than behind a lenient floor: Java floating
point is deterministic, so this is a reproducible equality and if it ever becomes 1 somebody should
have to look at it. The worst depth error is a relative 1e-7 — exactly the noise floor for `Float`
positions widened to `Double` by both sides — floored at 2e-6.

**And the differential is shown to have teeth.** Building the camera with a caption band the render did
not have — the exact mistake that "simplifying" `rayCamera` to drop its caption argument would make —
gives **28,276 interior disagreements against the correct camera's 0**. A test that only checks the
good case cannot tell you the check is measuring anything.

### The browser's picker and the JVM's, on the same click

`PickScene.clickableProjection` projects the scene as one filled, clickable FLine per visible triangle,
painter-sorted far to near so the nearest is the topmost SVG element. A click is then answered twice:
the browser decides which `<g>` was hit (SVG hit testing plus `sliders.js`'s own point-in-polygon pass)
and reports the label; the JVM takes `FLineIntersection.position`, unprojects it, and casts a ray.

Verified **live**, by dispatching a real click in the real browser against a real Field document on
port 9711:

```
click at (383, 158)  browser: house / chimney  jvm: house / chimney  AGREE   [1 agree / 0 disagree]
```

A JVM-side sweep over the same polygon set measures the agreement rate, and **the disagreement is the
interesting part**:

```
clickable-projection agreement: 4143 of 4154 (0.99735)
distinct disagreeing part pairs:
  [(house / wall north, house / roof), (house / floor, house / wall north)]
```

The 11 that disagree are **not** edge noise — I had guessed they would be. Every one lies along a strip
where two surfaces nearly touch. That is the painter's sort: `clickableProjection` orders whole
triangles by centroid depth, so where two surfaces meet the centroid of a large triangle can sort in
front of the near edge of a small one. **An SVG has no per-pixel depth, so this cannot be fixed there;
the JVM half is exact, which is why the disagreement is informative rather than embarrassing.**

The test therefore asserts the *structural* claim as well as the rate: every disagreement must
concentrate on part pairs whose surfaces touch (`pairs.size <= 2`). A flipped y or a caption-band offset
scatters disagreements across unrelated pairs even when the count stays small; a depth-sort tie
concentrates them. The rate alone cannot tell those apart.

Honest scope note: that sweep replays `sliders.js`'s even-odd rule on the JVM rather than running
`sliders.js`. It pins the polygon set and the topmost-wins ordering — where a convention mismatch would
live — and not the browser's own arithmetic. The *live* click above is what covers the real browser,
and it is one click, not a corpus. Running a corpus through the real thing would want the node probe
harness `FLineHitRecordProbeTest` uses.

### Pictures, looked at

`SpatialPointerPixelTest` renders the figure a person will see, decodes the PNG back out and samples
it: the right part lit (11.8% and 12.4% of the frame for the fixture's two largest parts, floored at
4%), the pointer's own pixel on the lit part, and the counterfactual as a number — moving the pointer
to another part gives lit regions overlapping by **under 2%**. Pointing at nothing lights nothing while
still drawing the model, because a picker that falls back to part 0 is worse than one that admits it
found nothing.

Captures go to `AFIELD_POINTER_CAPTURE_DIR` when it is set, and to a deleted temp directory otherwise,
so a test never writes a committed asset.

---

## The demo

`dev_docs/spatial_pointer_demo.yaml`, replayable into a fresh scratch document:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --port 9711 --with-playwright \
  --command 'macro write spatial_pointer --file dev_docs/spatial_pointer_demo.yaml' \
  --command 'macro run spatial_pointer'
```

Two boxes over one little house whose five pieces are named — floor, wall north, wall west, roof,
chimney.

**Box 1, "point at it with a slider".** Move **`pointer x`**. The crosshair sweeps left to right, the
part under it lights up, and the caption changes: `house / wall west`, then `house / roof`, then
`house / chimney`, with the distance changing with it. `pointer y` is the quicker way to get from the
roof down to the floor through the open front.

**Box 2, "… or with the mouse".** The same house as a clickable SVG in five hues. Hover highlights;
click prints both pickers' answers and whether they agree, and relights the model from the JVM's
answer.

Not registered in `dev_docs/agents/transcripts/` on purpose: `AgentTranscriptIndexGuard` requires a
hand-written entry in `public/docs/agent_transcripts.md` and `checkAgentTranscripts` requires the
regenerated `public/docs/assets/agent_transcripts/` bundle, and `public/docs/` was owned by another
agent the night this landed. Registering it is a two-step follow-up: add a `**spatial_pointer** — …`
paragraph under the *replayed end to end* section, bump the totals paragraph from 73/54 to 74/55, and
run `./gradlew materializeAgentTranscripts`.

---

## Deliberately not done

- **Picking FLines in 3D.** Nothing in `afield.four` draws an FLine into a 3D scene: `grep -rln FLine
  src/jvmMain/kotlin/afield/four` returns 11 files and all of them *produce* FLines for the 2D/browser
  output. There is no 3D FLine path to pick *in*, so the JVM does not fill `FLineIntersection`-shaped
  records for 3D geometry. `clickableProjection` is the 2D bridge instead.
- **GPU picking.** An id-buffer readback would be exact and per-pixel, and would need a live renderer,
  which is the opposite of what made this branch verifiable.
- **A public `TriMesh.transformedBy`.** `PickScene.mergedMesh` needs one and has a private
  `placedBy`. A public geometry operation belongs in `mesh_process.kt` beside its neighbours, and that
  is a separate decision from this layer's.
