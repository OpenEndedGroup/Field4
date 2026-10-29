# What a capture can and cannot show

A visual example can compile and still produce the wrong picture. Field's capture tools let you
run a document and inspect what it actually draws; [Automating Field](automating_field.md) shows
how to drive them.

Some captures fail clearly, while others time out or produce a plausible but wrong image. Here's
how to recognize those cases and what you can check before trusting the result.

The cases below range from an explicit failure to a capture that succeeds but shows the wrong thing:

| kind | what you get | what it costs you |
|---|---|---|
| **Loud** | the capture fails and names what blocked it | a minute |
| **Quiet** | the capture times out | an hour, guessing why |
| **Silent** | the capture succeeds and shows the wrong thing | a published figure that is wrong |

The loud ones tell you what to fix. The quiet and silent ones need the checks below.

---

## Loud: a box with no output to show

`screenshot-box` opens a box's outputs before it shoots, and requires visible, populated content
and settled geometry. A box with nothing to show fails with `ok: false` and says so, rather than
returning a picture of its collapsed handle:

```diffstatic {data-lang=json}
{ "ok": false, "kind": "box",
  "message": "output has no visible content or its layout did not settle; attach the browser before running the box, or use --no-open-outputs for an intentional handle-only capture" }
```

A box that was run before the browser attached is still captured: its retained output panels are
replayed to the page, and the reply's `captureWarnings` says that anything that was not a retained
panel never reached it.

`displayOpen(channel)` does not open a panel itself: it sends an `output-control` message to a
client (`afield/ui/output/outputs.kt`), so with no client connected the panel is only created when
one attaches and the retained output is replayed.

### Handle-only captures

There is no minimum image size; tiny valid outputs are allowed. For an intentional box-handle
capture use `--no-open-outputs`. For an editor-only window capture, omit `--open-outputs`.

### Attach before running

For DOM that is not a retained panel, attach the client *before* the box runs:

```diffstatic {data-lang=bash}
python3 tools/field_backdoor.py --port <port> --with-playwright \
  --command "run <uid>" \
  --command "wait-output <uid> --contains DONE --timeout 240" \
  --command "screenshot-box <uid> out.png"
```

Or open the document in a browser first. Either way, read `captureWarnings` and the `clip` in
the reply.

---

## Silent: the right size, the wrong frame

A capture can be correctly sized, correctly timed, and still of the wrong thing.

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
a fresh allowance per stage (`afield/tools/docs/doc.kt`). When it expires, the error names the stage that
was running (renderer readiness, settle frames or the swapchain copy) and how many settle steps
had finished.

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

---

## Pictures no capture can take

**There is no moving picture of the native window that an agent can take.** The backdoor's
`record-window`, `record-editor` and `record-box` all record the *browser*, through the page, so a
docs fence that asks for `capture="native-window"` together with `video=` is refused.

A native-window recorder **does** exist: `Window.raiseAndRecord`, in `afield/tools/docs/doc.kt`, raises
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
  in the reply against the size you expected.
- **Run the control in the same invocation.** A separate run is a different session, a different
   browser context and a different scratch document, so a control run later has already changed the
   variable you were holding still.

---

??? aside "Agents: where each limit is written down"

    | limit | recorded in |
    |---|---|
    | outputs closed with no client → refused with `ok: false` (it used to return a handle-sized picture with `ok: true`) | `dev_docs/MARF_DEMO.md`, `dev_docs/OUTPUT_PANEL_SIZING.md`, `dev_docs/path_tracer_instrument.md` |
    | stale selection blurs a box capture | `dev_docs/chart_primitive.md` |
    | neighbouring panels inside the clip | `dev_docs/chart_bad_separation.md` |
    | box capture pans and drops the selection | [Moving around the canvas](moving_around_the_canvas.md#what-the-overlay-draws) |
    | per-frame vertex re-upload and property read time out the native capture | `dev_docs/SKINNED_CHARACTERS.md`, `dev_docs/GPU_SKINNING.md` |
    | no native-window video on any agent-facing path | `dev_docs/DOCS_RUNNER_GPU_CAPTURES.md`, [Pattern → particles](chain_pattern_particles.md) |
    | `screencapture` needs permission; the swapchain copy does not | `dev_docs/imgui_vr_panel.md` |
    | a static capture cannot click | `dev_docs/three_inspector.md` |
    | captures with a build prerequisite, and host-gated captures | `dev_docs/DOCS_RUNNER_GPU_CAPTURES.md`, `dev_docs/NVFLOW_BINDINGS.md` |
    | a capture that can no longer be recreated, and must not be faked | `dev_docs/docs_capture_staleness.md` |
