# What a capture can and cannot show

For anything visual, *it compiles* is not verification. The way to know a change worked is to drive
it to pixels and look at them, and [Automating Field](automating_field.md) is how that is
done here without a human in the chair.

This page is about the pictures that cannot be taken, or that come out wrong without saying so.
They mark the edge of what a person or an agent can claim about a running document from a capture.

The limits sort into three kinds:

| kind | what you get | what it costs you |
|---|---|---|
| **Loud** | the capture fails and names what blocked it | a minute |
| **Quiet** | the capture times out | an hour, guessing why |
| **Silent** | the capture succeeds and shows the wrong thing | a published figure that is wrong |

The loud ones tell you what to fix. This page is mostly about the other two.

---

## Silent: a capture that reported success and showed nothing

Captures that request open outputs reject missing content and unsettled layout. Without that
check, `screenshot-box` returns a picture of nothing with `ok: true`. The two captures below were
taken without it, to show what that looks like.

Here are two runs of the **same box** with the **same command**. One variable changes: whether a
browser client was attached at the moment the box ran.

```diffstatic {data-lang=kotlin}
display("<div style='width:360px;height:200px;background:#2f5fd0'>capture limits probe</div>")
displayOpen("output")
println("PROBE-DONE")
```

Run it with no client attached, attach one afterwards, then photograph it:

![a 48 by 48 pixel image: a sliver of empty canvas, which is the whole of what the capture returned](assets/capture_limits/no_client.png)

That is the figure, reproduced at its real size. The capture reported:

```diffstatic {data-lang=json}
{ "ok": true, "kind": "box",
  "clip":     { "x": 23.0, "y": 286.0, "w": 24.0,  "h": 24.0  },
  "viewport": { "x": 0.0,  "y": 0.0,   "w": 2200.0, "h": 2800.0 } }
```

Run the same box with the client already attached and everything else identical:

![the probe's blue output panel, 360 by 200 logical pixels, captioned "capture limits probe", with its output disclosure tab visible above it](assets/capture_limits/client_up.png)

```diffstatic {data-lang=json}
{ "ok": true, "kind": "box",
  "clip":     { "x": 23.0, "y": 290.0, "w": 396.0, "h": 258.0 },
  "viewport": { "x": 0.0,  "y": 0.0,   "w": 2200.0, "h": 2800.0 } }
```

Both replies say `ok: true`. The **clip** tells them apart. Check it even with the readiness
checks on, because automation cannot know which picture you intended.

### Why it happens

`displayOpen(channel)` does not open a panel. It sends an `output-control` message *to a client*
(`afield/outputs.kt`). With no client connected there is nobody to receive it, so the panel is never
created, and the box collapses to its handle.

The capture then clips to whatever the box's element measures on the page
(`getBoundingClientRect`, in the prep script inside `afield/docs/screenshot.kt`). A collapsed box
measures 24 × 24 logical pixels, and at this display's 2× scale that is a 48 × 48 PNG.

So do not look for "48 × 48". **The clip is the box's own rectangle**, so its size is whatever that
particular box collapsed to, times the device pixel ratio; `dev_docs/path_tracer_instrument.md`
records a 40 × 40 one. What to watch for is a clip much smaller than the output you were expecting.

### How the capture rejects it

`screenshot-box` opens outputs by default (`--open-outputs` is the default in
`tools/field_backdoor.py`; `--no-open-outputs` turns it off), and the prep script waits for
anything that declares itself busy, such as a "loading waveform" badge, before shooting.

An output area that was never populated does not declare itself busy, so box and targeted window
captures also open the disclosures first, then require visible, populated content, loaded images
and iframes, and stable geometry within the preparation deadline. If the layout wait expires, the
capture fails rather than photographing a box handle. Animation inside a fixed-size output is
still allowed, since the check is on geometry, not on whether the pixels stop moving.

There is no minimum image size; tiny valid outputs are allowed. For an intentional box-handle
capture use `--no-open-outputs`. For an editor-only window capture, omit `--open-outputs`.

### What to do instead

Attach the client *before* the box runs, so the panel exists when `displayOpen` fires:

```diffstatic {data-lang=bash}
python3 tools/field_backdoor.py --port <port> --with-playwright \
  --command "run <uid>" \
  --command "wait-output <uid> --contains DONE --timeout 240" \
  --command "screenshot-box <uid> out.png"
```

Or open the document in a browser first. And whichever you do, **read the `clip` in the reply**. It
is the one field that separates a good capture from a plausible one.

---

## Silent: the right size, the wrong frame

A capture can be correctly sized, correctly timed, and still of the wrong thing.

- **The previous selection can still be in focus.** `screenshot-box` selects the box, but the
  canvas's depth-of-field can lag, and the result is a plausible-looking blurred figure.
  The reliable recipe is `editor-open <uid> --open-outputs`, settle, then
  `screenshot-box --preserve-selection`. Two consecutive `screenshot-box` calls on the same box
  are not reliable (`dev_docs/chart_primitive.md`).
- **Neighbouring panels bleed in.** With several output panels open on one canvas, the clip around
  the target can contain its neighbours' panels too (`dev_docs/chart_bad_separation.md`).
- **The box capture path pans, and panning drops the selection.** A figure whose subject *is* the
  selection, such as a navigation highlight, cannot use that path at all, which is why
  [Moving around the canvas](moving_around_the_canvas.md#what-the-overlay-draws) drives its own capture.

---

## Quiet: the native window times out

The Field 4 graphics window is photographed by a different mechanism: the renderer copies its
own swapchain image straight to a file. It needs no screen recording permission, does not raise
the window, and works headless.

One budget, 15 seconds by default, covers renderer readiness, settling *and* the copy; it is not
a fresh allowance per stage (`afield/docs/doc.kt`). When it expires you get "Timed out waiting for
native renderer" and no information about which stage ate it.

Here is how per-frame work on one scene affects this path:

| the box does this every frame | native capture |
|---|---|
| nothing | works |
| skins the mesh on the CPU, 118 fps | works |
| rewrites a 128-matrix buffer, ~120 fps | works |
| reads a box property with `getPropertyRaw` | times out |
| re-uploads vertices with `applyToMesh` | times out |

Deforming the mesh and rewriting buffers every frame are both fine. What times the capture out is
a per-frame *vertex re-upload*, or a per-frame box-property read, not per-frame GPU work in
general.

??? aside "Where those five rows come from"

    They are carried over from the skinning work, in `dev_docs/SKINNED_CHARACTERS.md` and
    `dev_docs/GPU_SKINNING.md`, and were **not** re-run for this page.

A related limit is not a timeout at all and is easy to mistake for one: **`MeshInstance.applyToMesh`
cannot run from a `main` in the first place.** Its buffers come from `defaultBuffer { … }.buffer`,
which allocates through a renderer parameter defaulting to `fromContext()`, and `fromContext` reads
`Box.context`, which is a box. From a `main` there is no box, and it throws
`Cannot invoke "afield.document.graph.Box.getProperties()"` before any capture is attempted. A `jvm-main` smoke
test runs as a `main`, so it meets this.

---

## Pictures no capture can take

**There is no moving picture of the native window that an agent can take.** The backdoor's
`record-window`, `record-editor` and `record-box` all record the *browser*, through the page. A
docs fence that asks for `capture="native-window"` together with `video=` is accepted, falls through
to the browser recorder, and silently records the wrong window
(`dev_docs/DOCS_RUNNER_GPU_CAPTURES.md`). No published page does this today.

A native-window recorder **does** exist: `Window.raiseAndRecord`, in `afield/docs/doc.kt`, raises
the GLFW window and records its rectangle with macOS `screencapture`. Its only caller is the
`render_clip` comment command in the same file, which neither the backdoor nor the docs fence
harness reaches.

It also records the screen rather than copying the swapchain, so it needs Screen-Recording
permission and fails headless, the same contrast `dev_docs/imgui_vr_panel.md` draws between its
`capture` and `capture-gpu` modes. So a moving GPU figure is a still plus a plot of the signal it follows.

Two more pictures cannot be taken, for reasons that have nothing to do with the capture code:

- **A static capture cannot click.** Anything whose subject is an interaction, such as picking two
  elements to measure between them (`dev_docs/three_inspector.md`), has to be driven by the `ui`
  capture mode or left to a demo.
- **Some figures need hardware or a build the clone does not ship.** The Ceres figure on
  `polytope_examples.md` needs a native wrapper with no committed binary, and the GPU capture path
  cannot run in the macOS working loop or on a runner with no GPU. `dev_docs/DOCS_RUNNER_GPU_CAPTURES.md`
  gives explicit licence to skip these and say why. A faked capture is worse than none.

---

## Telling a timed-out capture from a broken subject

The two look identical: "Timed out waiting for native renderer", with no hint whether the scene
failed to draw or the capture path failed to fetch it. Guessing at the cause rarely helps, since
there is always something slow to blame.

To tell them apart, capture a **trusted control** alongside the thing you are trying to
photograph: a document whose committed figures show it captured before. Then:

| your subject | the control | verdict |
|---|---|---|
| times out | works | the subject is broken; debug the scene |
| times out | times out too | the **capture path** is broken in this environment; debug nothing else |
| works | — | you have your picture |

For example, if a scene will not photograph and `fieldml_frames_demo`, whose committed captures
show it worked, fails the same way, the fault is the environment and not the scene
(`dev_docs/FIELDML_HOST_BRIDGE.md`).

Some practical points:

- **Any page with a committed native-window capture is a control.**
- **For box captures, the `clip` is the control.** You do not need a second run: compare the clip
  in the reply against the size you expected, as the two figures at the top of this page do.
- **Run the control in the same invocation.** A separate run is a different session, a different
   browser context and a different scratch document, so a control run later has already changed the
   variable you were holding still.

---

## Where each limit is written down

| limit | recorded in |
|---|---|
| outputs closed with no client → a box-handle-sized capture, `ok: true` | `dev_docs/MARF_DEMO.md`, `dev_docs/OUTPUT_PANEL_SIZING.md`, `dev_docs/path_tracer_instrument.md` |
| stale selection blurs a box capture; repeats are flaky | `dev_docs/chart_primitive.md` |
| neighbouring panels inside the clip | `dev_docs/chart_bad_separation.md` |
| box capture pans and drops the selection | [Moving around the canvas](moving_around_the_canvas.md#what-the-overlay-draws) |
| per-frame vertex re-upload and property read time out the native capture | `dev_docs/SKINNED_CHARACTERS.md`, `dev_docs/GPU_SKINNING.md` |
| `applyToMesh` throws from a `main` | `dev_docs/GPU_SKINNING.md` |
| no native-window video on any agent-facing path | `dev_docs/DOCS_RUNNER_GPU_CAPTURES.md`, [Pattern → particles](chain_pattern_particles.md) |
| `screencapture` needs permission; the swapchain copy does not | `dev_docs/imgui_vr_panel.md` |
| a static capture cannot click | `dev_docs/three_inspector.md` |
| captures with a build prerequisite, and host-gated captures | `dev_docs/DOCS_RUNNER_GPU_CAPTURES.md`, `dev_docs/NVFLOW_BINDINGS.md` |
| a capture that can no longer be recreated, and must not be faked | `dev_docs/docs_capture_staleness.md` |
