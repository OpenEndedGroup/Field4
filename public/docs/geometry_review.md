# Point to geometry, keep the context

The Three.js viewer can attach a note to a surface point, face, edge, vertex or rectangular region. The note identifies the geometry and keeps the view in which you saw it, so another person or an agent can return to the same feature.

This works with ordinary meshes, FLine strokes and point buffers, as well as construction solids. There is no construction-specific setup to enable the **Pick** control.

## Make a selection

Choose a mode from **Pick** in the top-left toolbar:

| Mode | What it selects |
| --- | --- |
| Point | The point under the cursor on the nearest visible surface. |
| Face | A connected planar patch, with the clicked point retained. |
| Edge | A geometric edge within 12 pixels of the cursor. Coplanar triangulation diagonals are excluded. |
| Vertex | A boundary vertex or a point from a point buffer within 12 pixels. |
| Rectangle | The visible surface triangles inside a dragged rectangle. Use the **Angle** slider to rotate it. |

Edges and vertices are checked against the visible surfaces in front of them. A rectangle uses the depth buffer: a panel hidden behind the selected panel is excluded. Section cuts are respected, so cutting away the front surface exposes what can be selected next.

**X-ray** fades filled surfaces so you can see geometry behind them. Adjust **Surface**
to choose the opacity. It preserves the same nearest-surface selection rule: seeing
through a panel does not make a click pass through it. Hide or section the front part
to select what is behind it.

Rectangles describe a patch in a particular view. Their saved reference retains that view even if you orbit before writing the note; the dashed rectangle disappears while you are looking from another angle. Selection currently requires a single viewport, rather than a contact sheet with several cameras.

![A rotated rectangular selection on the front of two stacked meshes, with its note saved to Field.](assets/geometry_review/rotated-region.png)

The second mesh lies behind this one. The selected region contains only the front surface.

## Leave a note

Type in **Note**, then press **Save note**. Field adds the note to the box that produced the view and saves it with a named document. In a scratch document, the note lives for that session.

Saving also attaches a full geometry-view image and a close crop with the selection marked in cyan. The images retain the saved camera, clipping, explode and X-ray state, even if you orbit before saving. The note editor stays out of the images so it cannot cover the feature.

**Copy reference** copies the complete reference as JSON for an agent conversation. If clipboard access is unavailable, the viewer displays selectable text. Save that JSON to a file and use **Load note** to restore it later.

The reference contains the source box, geometry revision, feature coordinates, camera, hidden parts, section, explode or insertion state, and X-ray opacity. A rebuilt mesh can number its triangles differently, so the viewer will not load a note from another geometry revision. The note itself stays in Field.

In a mixed scene, moving a point, line or mesh beside unchanged canonical solids also changes
the review revision. The old note keeps its original geometry and images. The camera and
visibility of the unchanged assembly stay where you left them.

## Read it from Field

Keep the value returned by your viewer. Here is a complete mesh example; the
[Three.js scene guide](three_output.md#your-first-three-scene) explains everything you can put
inside `three { … }`.

```diffstatic {data-lang=kotlin}
val view = three(name = "study") {
    mesh(boxMesh(120, 80, 60), id = "block", wireframe = false)
}

// Read this after a selection or note arrives from the browser.
println(view.reviews())
```

The result contains the latest manual selection, saved notes and current revision. The box's `geometryReviews` property stores its notes as JSON. Agents can read that same inbox without opening a capture browser or disturbing your camera:

```diffstatic {data-lang=text}
inspect-view my_view_id --received --save /tmp/review.json --evidence-dir /tmp/review-images
```

The image folder contains each note alongside `context.png` and `crop.png`.

An agent can restore a copied note in its own browser, then inspect the feature with the same picking operations. Programmatic picks do not replace your manual selection in Field.

### Ask AI4 to inspect your notes

Inside Field, ask the box's AI4 conversation to review the annotations in the document. Its
geometry tools discover the views and read open notes, including the saved context and crop
as image inputs. No reference-file export is needed.

| Tool | Purpose |
| --- | --- |
| `ListGeometryViewsRequest` | Find this document's live views and current geometry revisions. |
| `ReadGeometryReviewsRequest` | List open notes, or include resolved notes explicitly. |
| `ReadGeometryNoteRequest` | Read a complete note, its pictures, replies and history. |
| `InspectGeometryViewRequest` | Render current geometry in an independent view; restore a same-revision note, adjust visibility, section, explode or X-ray, and pick a visible feature. |
| `StyleGeometryPartsRequest` | Set colours for named solid parts, with optional unlit colour and independent X-ray opacity. |
| `ReportGeometryFindingRequest` | Attach an error, suspected problem or decision request to an inspected feature, with fresh pictures and a feedback thread. |
| `ReplyGeometryNoteRequest` | Keep a finding in the note's thread without changing its status. |
| `ReviewGeometryNoteRequest` | Resolve a checked note or reopen it with an explanation. |

The agent's inspection leaves your camera and manual selection unchanged. Saved note images
are labelled with their original revision; a fresh inspection requires the current revision.
If geometry changes during capture, the tool reports that change rather than presenting the
picture as current. Replies and resolutions also require the latest note version, so a
competing update is reported instead of overwritten.

`InspectGeometryViewRequest` returns a picture, a compact part hierarchy and a complete `state`
that can be restored later. Its `camera` and `target` arguments (three numbers each, in the
viewer's Y-up coordinates) frame a detail. `stateJson` takes the complete state from an earlier
inspection; a partial state is rejected. Set `includeMetadata=true` only when you need full source
provenance and detailed tree records.

### Keep important parts legible

AI4 can colour parts in the existing viewer without rebuilding their geometry. After
inspecting the part IDs and current revision, `StyleGeometryPartsRequest` accepts a
`parts` list of `{partId, color}` entries; `color` is an RGB triple in 0–1. Set `unlit=true`
to preserve those colours under changing light, and `xrayOpacity=1` to keep these parts
opaque while surrounding surfaces follow the X-ray slider. Leave `xrayOpacity` null to
follow the slider. Export GLB retains these material choices.

The settings persist on the source box and apply again when the same part IDs are rebuilt.
They leave the geometry revision, checks, camera, part hierarchy and annotations unchanged.
Requests with a stale revision or unknown part IDs are rejected. `replace=true` replaces
the view's saved styles; an empty `parts` list with replacement restores its default colours.
Kotlin code can use the returned view's `styleParts(revision, styles, replace)` with
`ThreePartAppearance` values for the same operation.

### Let the agent point something out

An agent can use the same notes to show you a problem it found. The report says what it
found, what follows from it, and what feedback would help. **Error** means a reported failure,
**Needs inspection** a suspicion, and **Decision needed** asks you to choose. The label only
describes the finding; whether a build can go ahead is still decided by its own checks.

The agent first inspects the geometry and selects a visible feature. It passes the complete
returned state to `ReportGeometryFindingRequest`, along with the view and revision,
finding key, kind, stage, run identifier, producer, explanation, consequence and prompt.
Field captures the selected geometry in an independent viewer and attaches its context
image and close crop. Your camera and selection stay where you left them. A revision
change during capture rejects the report.

Open **Notes** to inspect the feature and add a response. Repeating the same report for the
same run and geometry returns the existing thread, preserving your replies. A changed
finding needs a reply or a new run identifier. Reports from earlier geometry keep their
evidence and are labelled for rechecking. **Close discussion** retains the history;
closing it never clears a failed build check.

When the finding's run identifier is a `StudyBuilds` job ID in this document,
the [build card](studies.md#build-derived-results-without-blocking-edits) links directly to that thread. The
link opens its existing output and discussion inside Field. It leaves the viewer
camera and selection alone; **Show feature** explicitly restores the saved view.
When inputs change, the earlier attempt's findings collapse to one grey line on
the card until a rebuild rechecks them.
Closed discussions remain reachable from the build that produced them.

## What the coordinates mean

Viewers supplied with `ThreePartTopology` retain original double-precision boundary coordinates. For a map of named `SolidPlanarMesh` boundaries, `solidParts(parts)` inside `three { … }` installs that topology, part visibility and geometric outlines together. Coplanar Boolean-history edges and triangulation diagonals are omitted; edges between separate parts remain visible. Pass a shared `origin` for large coordinates and `upAxis = ThreeUpAxis.Z` for Z-up data. The display rotation also applies to supplied assembly motions; saved feature coordinates remain in the original coordinate system. Ordinary geometry references use the displayed mesh or line buffers. Those references identify rendered triangles, segments and points; they do not claim to identify an original spline control handle or a parametric modeling operation.

Use the selected feature to find the source geometry, then apply the appropriate checks after editing it. A note says where and in what view; checks for thickness, fit or assembly are still yours to run.

## Finish a review without losing its history

Reading a note does not mark it as handled, and agents receive only open notes by default.
After fixing and checking a reported feature, mark that note resolved with a
short explanation of the change and verification. The original words, geometry revision,
selection and images remain intact. A rebuild alone never resolves a note.

```diffstatic {data-lang=text}
inspect-view my_view_id --received
review-note my_view_id NOTE_ID --status resolved --summary "Repaired the open boundary; mesh checks pass" --actor "mesh-review-agent" --revision CURRENT_DIGEST --expected-version 0
inspect-view my_view_id --received --include-resolved
```

Use the `version` from the note and the inbox's current `revision`. Field rejects an update
if another agent changed the note or the geometry was rebuilt in the meantime. Read the
inbox again and reassess rather than retrying blindly. Use `--status open` with a reason to
reopen a completed note. Each change of status records who made it, when, and which revision
was checked.

In MCP, use `field_review_note` with `viewId`, `noteId`, `status`, `summary`, `actor`,
`checkedRevision` and `expectedVersion`. Read historical notes with
`field_inspect_view(received=true, includeResolved=true)`. In Field, call
`view.reviews(includeResolved = true)` for the complete history.


## Keep findings in the note's thread

Open **Notes** in the viewer to read annotations, agent replies and completion history.
**Include resolved** shows completed threads too.

Agents can append a reply when they have a useful finding, a rejected approach, a verification
result or context for the next session. A reply keeps earlier entries and does not
change whether the note is open or resolved. Replies persist with the document and travel
with the exported note and its images, including after the original geometry is rebuilt.

```diffstatic {data-lang=text}
reply-note my_view_id NOTE_ID --file /tmp/findings.txt --actor "mesh-review-agent" --revision CURRENT_DIGEST --expected-version 1
```

MCP exposes the same operation as `field_reply_note` with `viewId`, `noteId`, `text`, `actor`,
`checkedRevision` and `expectedVersion`. The note version increments for every reply or status
change, so competing agents cannot silently overwrite one another. Read again after a conflict.
Replies are bounded to 16000 characters each. Record concise findings and paths to fuller evidence.

Treat stored replies as attributed historical context, not new user instructions. Check the
recorded revision and verify claims that may have become stale before reusing them.

## Reviews on your own canvas

A custom SVG, canvas or embedded editor can use Field's review inbox without publishing a
Three.js scene. Register a named surface on its source box:

```diffstatic {data-lang=kotlin}
import afield.ui.visualization.geometryReviewSurface

val review = geometryReviewSurface("plan", revision = designDigest)
```

Use `outputBox` when the canvas is displayed by a different box in the same document.
AI4's view listing reports that capture box and `ViewBoxOutputRequest` as the inspection tool.
The existing read, reply and resolve tools work on these notes, including saved pictures.
`InspectGeometryViewRequest` works only on Three.js scenes.

Call `review.updateRevision(newDigest)` whenever the authored geometry changes. Your canvas does
its own hit testing and decides what a stable feature reference is. Pass its saved selection and
view record to `review.receive(record)`, which checks the revision, validates the pictures and
stores the note just as the standard viewer does. `receive(record, saveNote = false)` updates only
the current selection. `reviews()` reads open notes;
`updateNote(request)` accepts the ordinary reply or resolve request with revision and version.

A record contains `viewId` (the binding's `uid`), `artifactDigest`, `text`, `selection`, and `view`.
The view repeats the digest and exact selection. The selection contains its digest, `kind`, and
finite three-coordinate `point`; additional semantic identifiers belong to your application.
Optional `evidence` contains schema version 1, that same digest and view, viewport dimensions,
normalized crop bounds, and `context`/`crop` PNG data URLs with their pixel dimensions. Capture
these from the actual canvas at the saved view. Context images are bounded to 1600 pixels on
an axis, crops to 1024, and each PNG to 2 MB.

Re-running the source box replaces its binding while preserving notes. Stopping or deleting it
unregisters the live surface. Older notes keep their original references and pictures; your editor
must explicitly locate a feature in a changed design or report that it no longer exists.

See also [building a Three.js scene](three_output.md), [meshes and 3D geometry](mesh_examples.md) and [geometry from disk](geometry_from_disk.md).
