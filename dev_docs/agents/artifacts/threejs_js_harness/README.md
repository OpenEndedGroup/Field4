# The three.js viewer's client-side fill, in pixels

*2026-08-04.*

`ThreeJsViewerTessellationParityTest` executes `threejs_viewer.html`'s `tessellateCoplanarGroups` on
GraalJS and compares it to `afield.graphics.PlanarTessellation` triangle for triangle. That is a strong
statement about the numbers and **no statement at all about the pixels**, so these are the pixels.

Replayable, and replayed to make these three images:

```
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch \
  --with-playwright --port <your port> \
  --command 'macro write tjs --file dev_docs/agents/transcripts/docs_capture/threejs_editable_fill_pixels.yaml' \
  --command 'macro run tjs --timeout 240' \
  --command 'click-box fill_editable_planes --wait-ms 2500' \
  --command 'screenshot-box fill_editable_planes dev_docs/agents/artifacts/threejs_js_harness/editable_planes.png --timeout 120' \
  --command 'click-box fill_editable_overlap --wait-ms 2500' \
  --command 'screenshot-box fill_editable_overlap dev_docs/agents/artifacts/threejs_js_harness/editable_overlap.png --timeout 120' \
  --command 'click-box fill_static_planes --wait-ms 2500' \
  --command 'screenshot-box fill_static_planes dev_docs/agents/artifacts/threejs_js_harness/static_planes.png --timeout 120'
```

Reported `applied=7 skipped=0 errors=0`.

| | |
|---|---|
| `editable_planes.png` | `editable = true`, so the **client** triangulated: three perpendicular faces, three square holes, `24 ln \| 24 tri \| 24 p`. Nothing inside out, no hole filled in, no face missing. |
| `static_planes.png` | the same FLine with `editable = false`, so the **server** triangulated: the same picture and the same `24 tri`. Agreement, in pixels. |
| `editable_overlap.png` | two congruent overlapping squares, each listed from a vertex inside the other, `editable = true`, `8 ln \| 4 tri \| 8 p`. **Until 2026-08-04 this froze the tab** — the nesting pass could name each ring the other's parent and the depth walk never returned. Both squares fill; neither is a hole in the other, because neither is larger. |

## Two things worth knowing before you regenerate these

**`click-box` before `screenshot-box`.** Field dims and blurs the output of a box that is not the
focused one, so a capture of an unfocused three.js pane is legible but soft. Clicking the box first is
what makes it crisp.

**A `threejs` pane reports before its geometry arrives.** The transcript ends on a 45 s wait for that
reason; screenshot earlier and you bank the axis tripod and `waiting for data…`.

## And a bug these captures found

Two of the first three panes were permanently blank while the third was fine, and the only difference
between them was an **apostrophe in a display name**.

`Box.threeLines(name = …)` builds the view uid as `"${box.id}_$name"` and puts it into a
*single-quoted* `src` attribute as well as into a query string. `name = "the client's own
triangulation"` closed the attribute at the apostrophe, so the iframe loaded with no `uid`, subscribed
as an anonymous client, and sat on `waiting for data…` for as long as you cared to look — with nothing
in the browser console, the server log or the box output to say why. Percent-encoded now
(`threeViewerIframeSource`), held to that contract by `ThreeJsViewerIframeSourceTest`, and the
transcript keeps the apostrophe **deliberately** so this pair of images is a regression test as well as
an illustration.

It is the second defect on this branch that only a test which actually renders something could have
found; the first was the hang above. Both were in code that had been reviewed, documented and declared
guarded.

The design and its tolerances are in
[`dev_docs/fline_fill_orientation.md`](../../../fline_fill_orientation.md); the server-side pixel proof
of the same geometry, back-face culled, is in
[`../tessellate_facing/README.md`](../tessellate_facing/README.md).
