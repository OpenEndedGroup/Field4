# OperationTranscript — the canonical automation format

`OperationTranscript` is how an agent **builds, edits, runs, and saves Field documents**
without touching the browser UI. It is a small YAML language of *operations* that
Field applies to a live document. The agent backdoor (`tools/field_backdoor.py`) is the
transport and capture harness around it; the transcript is the durable, semantic layer.

Two formats, two jobs:

- **`metadata.edn`** is the *document* format — what a Field document **is** on disk
  (and in the pasteboard, and in the history repo). Boxes, properties, sidecar files.
- **An OperationTranscript YAML** is the *puppeting* format — a script for **making,
  editing, running, and interacting with** a document. Running a transcript can produce
  a `metadata.edn` document (via the `save` op) that you can reopen later.

If you only remember one thing: **build the document with transcript ops, prove it ran
by reading the captured `output`, capture a screenshot if you need a picture, then
`save` it to a `metadata.edn` you can reopen.**

For the low-level harness (server lifecycle, Playwright, screenshots, video, JVM
smoke), see [`FIELD_BACKDOOR.md`](./FIELD_BACKDOOR.md). For runnable, curated examples,
see [`transcripts/README.md`](./transcripts/README.md). This doc is the language and
lifecycle reference.

---

## Running a transcript

A transcript is written into the current document as a named macro, then run:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --port 9477 \
  --command 'macro write my_scene --file /path/to/my_scene.yaml' \
  --command 'macro run my_scene'
```

- `--scratch` opens a disposable transient document — the right default for building
  something from nothing. Use `--document <name>` to operate on a saved workspace
  document instead (see the gotcha below about how that name resolves).
- `macro write <name> --file <path>` stores the YAML as a macro on the document.
  `--content '<yaml>'` works too, but `--file` avoids all shell quoting.
- `macro run <name>` applies the operations in order and returns a machine-readable
  result (see [Observability](#observability)).

The preferred YAML shape is a top-level `operations:` list. The loader also accepts
`ops:` and a bare top-level list. Each entry is a single-key map whose key is the
**verb** and whose value is the **payload**:

```yaml
name: my_scene            # optional; macro write --name overrides
description: A short note  # optional
operations:
  - create:
      box: hello
      name: Hello
      frame: { x: 40.0, y: 40.0, w: 200.0, h: 120.0 }
      properties:
        code: |
          println("hello from a transcript")
  - launch:
      box: hello
      property: code
  - await:
      wait: pending
```

---

## The operation vocabulary

Every verb, its aliases, and the payload keys it reads. Box references (`box`) accept
any of `box` / `uid` / `id` / `target`.

| Verb (aliases) | What it does | Key payload fields |
|---|---|---|
| `create` (`new-box`, `box`) | Create or revive a box, set name/frame/properties, connect under root | `box`, `name`, `frame`, `properties` |
| `edit` (`set`, `set-string`) | Change a text-like property | `box`, `property`, `from`, `to` |
| `move` (`frame`, `set-rect`) | Move/resize a box | `box`, `to` (or `frame`) |
| `connect` / `disconnect` | Change parent→child graph topology | `parent`, `child` |
| `execute` (`execute-fragment`) | Run a code fragment **once**, awaited | `box`, `property`, `code` |
| `launch` (`launch-all`, `launch-fragment`) | Launch code as a running job; Kotlin-only groups use the awaited batch path | `box`, `property`, `code`, `strand` |
| `stop` (`stop-all`, `stop-box`) | Stop a running job | `box` |
| `wait` | Sleep, or wait N real runloop frames | `millis`/`ms`/`seconds`, `frames`, `pending` |
| `await` (`sync`, `settle`, `wait-pending`) | **Barrier**: drain all pending fragments/launches | `wait: pending` |
| `save` (`save-document`, `save-as`) | Persist the live boxes to a `metadata.edn` document | `path` (or `to`/`fn`/`document`) |
| `delete` (`tombstone`) | Tombstone a box (undo revives) | `box` |
| `drive` (`drive-ui`, `playwright`) | Run a Kotlin script that drives the live UI via Playwright (keypresses/clicks), optionally recording an `.mp4` and/or a still | `script`, `video`, `screenshot`, `capture`, `captureUid`, `clipSelector`, `fps`, `padding`, `warmCompletion` |

Undo (`macro undo <name>`) reverses supported ops: `create`↔tombstone, `edit` swaps
`from`/`to`, `connect`↔`disconnect`, `launch`→`stop`, etc. `execute`, `wait`, `await`,
`save`, and `drive` are not reversible and are skipped on undo.

### drive

Runs an arbitrary Kotlin fragment with a `ui: DocsUiDriver` in scope (injected via
`fromContext()`), so a doc page can reproduce a live UI state and capture it. Used to
regenerate screenshots/videos of UI that pure-code captures can't produce — autocomplete,
the command palette, AI panels. When `video` is set the interaction is recorded to an
`.mp4` (the recorder grabs frames on the Playwright thread *while* the script drives, so
keypresses show up in the clip); `screenshot` captures a final still.

Capture the command palette (an overlay — clip to the panel selector, not a box):

```yaml
- drive:
    capture: window
    clipSelector: ".afield-palette-shell"   # the visible palette panel
    screenshot: assets/command-palette.png
    padding: 6
    script: |
      ui.openEditor(id)          # `id` is the hidden host box's own id
      ui.pause(700)
      ui.clickSelector(".monaco-editor")
      ui.pause(300)
      ui.press("F1")             # open the palette
      ui.pause(1600)
```

`DocsUiDriver` methods: `type(text)`, `press/key/down/up(key)`, `click(uid)`,
`clickSelector(css)`, `hover(uid)`/`hoverSelector(css)`, `drag(uid, dx, dy, steps, hold)`,
`mouseUp()`, `eval(js)`, `openEditor(uid)`, `shot(path)`, `shotClip(path, css, padding)`,
`waitForSelector(css, timeoutMs)`, `pause(ms)`. It is deliberately not named `wait` (collides with
`Object.wait`). The resolved output paths are bound in scope as `out` and `vid`.

The drive fragment runs *on the main runloop thread*, so `pause`/`waitForSelector` must not simply
`Thread.sleep` — that would freeze the loop and starve everything queued on it, most importantly
the `complete` RPC behind a live `Control+Space` (which runs on the main runloop). Both instead
**pump the runloop** while waiting (draining queued main work), reproducing an idle editor — the
state a real user leaves after triggering autocomplete, which is when the popup populates. Off the
main thread (a recording-only drive) they fall back to a plain sleep so the off-thread recorder
keeps grabbing frames.

When `clipSelector` is set the op auto-captures the clip *inside* the script (overlays close on
blur before the op's own capture would run). `.afield-palette` is the full-screen backdrop;
`.afield-palette-shell` is the panel you want.

**Autocomplete captures.** `Control+Space` opens the popup; the enriched type-based items and their
KDoc come from the `complete` RPC on the main runloop. Set **`warmCompletion: true`** on the op for
any capture that triggers a completion: it builds the K1 completion engine up front, on the
non-reentrant runloop, *before* the fragment starts pumping. This is required for correctness, not
just speed — the completion engine's first bootstrap is a heavy compile, and if it fired from inside
a runloop-pumping wait it would re-enter the K1 engine mid-compile and corrupt its analysis state
(`Rewrite at slice PACKAGE_TO_FILES`), hanging the completion. With it warm, wait for the real
content — e.g. `ui.waitForSelector("text=Creates a mutable", 15000.0)` for `vec`'s doc, not just a
`.afield-suggest-row` — so the capture shows the documented result rather than the instant
quick-stubs. (Base and AI completions both render without the other blocking the popup.)

### create

```yaml
- create:
    box: particles              # stable id — reuse it in later ops
    name: Particle Sim          # display name (also `title`)
    frame: { x: 240.0, y: 40.0, w: 160.0, h: 90.0 }
    properties:                 # any box property, incl. sidecar-backed text
      code: |
        println("built")
      compute: |
        #version 450
        ...
```

`create` sets each entry of `properties` with `setPropertyRaw` and connects the box
under the document root. Prefer **stable ids** (`particles`, `renderer`) over generated
ones — later `connect`/`launch`/`edit` ops reference the box by that id, and `box(id)`
is memoised per session so every op sees the same instance.

### connect / disconnect

`connect` is a **direct back-end graph mutation** (`parent.connect(child)`), not a
front-end RPC. `parent` accepts `parent`/`from`/`source`/`box`; `child` accepts
`child`/`to`/`target`. This is the transcript equivalent of selecting the parent and
pressing `g` in the UI. For `afield.four`, a graphics example box must be connected
**beneath** the renderer box before it launches, so it inherits the `Renderer` context.

### execute vs launch

Both run code and fall back to the box's **stored** code when the
op carries no inline `code:` — so a `create` that sets `code`, followed by a bare
`execute`/`launch`, runs that stored code:

```yaml
- create:  { box: b, properties: { code: 'println("stored")' } }
- launch:  { box: b, property: code }   # runs the stored code — no need to repeat it
```

- **`execute`** runs a fragment once and returns (`executeFragmentAndWait` — it joins,
  so it does not stall a headless server). Use it for one-shot setup or a computed result.
- **`launch`** starts a running job. A group whose targets all use
  `KotlinExecution` goes through `launchBatchAndWait`, which captures replies and
  waits for the batch's launch barrier. If any target uses another execution
  runtime, the current implementation dispatches each `launchAll` without that
  shared awaited-batch guarantee. **This is the primary execution verb** for
  renderers, compute loops, and draw loops. A renderer box's launched code should
  end in `start(renderer::run)`.

`property` selects which property holds the code (default `code`; use `compute`,
`fragment`, etc. for shader properties). `strand` (default `default`) names the
execution strand.

### launch fails loud — by design

`launch` never silently does nothing. If it cannot run real code, the run reports
`success=false` and the reason names the box:

- **Missing box** — `` `launch` target box `X` is not present in the document (present: a, b, c) ``
  (it lists the boxes that *are* loaded — usually the fastest way to spot a typo or a
  document that didn't load).
- **Empty code** — `` `launch` target box `X` has no code to run: neither the op nor the
  box's `code` property carries any code ``.

Each loud failure is logged (`error,operation-transcript`), notified, and folded into
the run's `errors` list. A bare `create`+`launch` that resolves and runs real code
reports `applied=N skipped=0 errors=0` with the code's output in `output`.

### wait and await

- `wait` sleeps a fixed time (`millis`/`ms`/`seconds`, capped) or advances a fixed
  number of real runloop frames (`frames`). Reserve it for genuinely time-based waits.
- **`await`** (aka `sync`/`settle`/`wait-pending`, or `wait: { pending: true }`) is the
  Field-state barrier implemented by `awaitPending`: it advances runloop frames
  until tracked fragments and launches satisfy that function's readiness checks,
  then settles extra frames. It cannot manufacture an acknowledgement that an
  optional execution runtime never reports. Put it after fire-and-forget work, and
  use a feature-specific readiness assertion when downstream state matters.

### save

```yaml
- save:
    path: /abs/or/workspace-relative/path/to/newDoc
```

`save` snapshots the live boxes (excluding the synthetic root and tombstoned boxes) and
calls `SavingContext.save(path, boxes)`. That path writes `metadata.edn`, configured
sidecar properties, and `document.md`; directory initialization seeds `README.md` and
`AGENTS.md` only when their templates exist and the destinations do not. On a changed
save, the history/metrics machinery runs as part of `SavingContext`. The transcript op
waits up to 30 seconds on the save callback and fails if it times out. Callers that
require durable evidence may additionally inspect the target files. This is how an
agent turns an assembled scene into a loadable document.

---

## Observability

`macro run` returns one JSON object. Read it, don't guess:

```json
{
  "success": true,
  "message": "Ran macro 'my_scene': applied=2 skipped=0 errors=0.",
  "applied": 2,
  "skipped": 0,
  "errors": [],
  "output": [
    { "uid": "hello", "line": 1, "html": "hello from a transcript" }
  ]
}
```

- **`success`** is `false` if any op errored (including a loud `launch` failure).
- **`applied` / `skipped` / `errors`** count what happened. `skipped` with no `errors`
  and the note *"No operations changed anything"* usually means a box id didn't resolve.
- **`output`** collects `print` / `_print` / `println` and inline display output from
  every `execute`/`launch` in the run, tagged by `uid` and source `line`. This is your
  proof the code actually ran. Compile errors and runtime exceptions surface here too.

`print`/`_print`/`println` inside launched or executed code all reach `output`. There is
no need to reach for the browser to see whether code ran — read `output`.

`transcript` is an exact CLI alias for `macro`; both verbs use the same parser and the same
server command. In scratch mode, write and run in one `field_backdoor.py` invocation because the
scratch document and its document-scoped macro directory belong to that invocation.

---

## The document lifecycle (build → run → capture → save → reopen)

The whole point of the format, end to end:

1. **Open a transient document** — `--scratch` (or a named `--document`).
2. **Build** — `create` boxes (with code/shaders), `connect` the graph.
3. **Run** — `launch` (or `execute`); read `output` to confirm it ran clean.
4. **Barrier** — `await` so all work has settled.
5. **Capture** (optional) — a backdoor `screenshot`/`native-screenshot`/`record-*`
   command for a picture or video (see [`FIELD_BACKDOOR.md`](./FIELD_BACKDOOR.md)).
6. **Save** (optional) — `save` to a `metadata.edn` document.
7. **Reopen** — `--document <name>` loads it back; a bare `launch`/`execute` reruns the
   stored code and reproduces the original output.

This round-trip is verified: a saved document reopened in a fresh session loads its
boxes (correct id + name), and a bare `launch` of a loaded box reruns its stored code
(`applied=… skipped=0 errors=0`) and reproduces its output — code sidecar **and**
regenerated preamble both survive.

---

## Gotchas

- **`--document` is resolved relative to the workspace root.** The backdoor computes the
  session key as `{workspace}/{document}`. Passing an *absolute* path yields a bogus
  `.../AFieldWorkspace//abs/path` and loads an **empty** document (`boxes: []`) — after
  which ops that reference your boxes are skipped, or CLI `launch`/`run` silently create
  an empty box. To reopen a saved document, either `save` it **into** a workspace dir and
  open it by its relative name, or point `--workspace` at the save location's parent.
- **`--value`/`--file` on CLI `run`/`launch <uid>` are optional and only override when
  supplied.** Omitting them leaves the argparse default `None`, so `run_box`/`launch_box`
  skip the `set_string` step and execute the box's currently *stored* code unchanged — the
  same stored-code fallback the transcript `execute`/`launch` ops use. (A `codeLength: 0`
  run means the box's cached `code` property is genuinely empty — e.g. a box created but
  never snapshotted — not that an empty `--value` was sent.)
- **Inline `code:` in YAML mangles Kotlin string templates.** `"${expr}"` inside a
  transcript code block can be eaten by interpolation; build strings with `+`
  concatenation (`"n=" + n`) in transcript-authored code.
- **EDN vs YAML quoting.** `metadata.edn` is EDN (Clojure data); transcripts are YAML.
  The `\"` you sometimes see in logs is normal JSON log escaping, not corruption —
  `--file` round-trips code cleanly.
- **A freshly `create`d box only has the *document root* as its ancestor — that is not
  enough for `fromContext()` or a bare `prop<T>` name to resolve.** `create` connects
  under root (see above), but a renderer's `prop<Window>(window)`/`prop<Renderer>(renderer)`
  only propagates to boxes connected **beneath the renderer box specifically** (parent,
  grandparent via a chain, etc. — sibling-under-root does not count). Two ways this
  bites you, both fixed by adding the `connect` op *before* you `launch`:
  - A helper that takes its context implicitly (`computeModuleHere {}`, `meshModuleHere {}`,
    `rayTracingModuleHere {}` — these call `fromContext<Renderer>()` under the hood) fails
    with `` must pass in a value for context parameter afield.four.Renderer `` if the box
    isn't connected under a renderer yet.
  - Code that reads a bare name a parent declared with `prop<T>(name)` (e.g. `renderer`
    itself, or `outputPositions`) fails with a plain `` Unresolved reference 'renderer' ``
    (or whatever the name is) for the same reason — the preamble that would inject that
    accessor is only regenerated/propagated once the connection exists.
  Fix: `connect: { parent: <renderer-box>, child: <this-box> }` before the `launch`, per
  [Worked example B](#worked-example-b--afieldfour-vulkan-renderer--compute--draw). Don't
  assume a `fromContext()`-based helper "does the wiring for you" just because it looks
  automatic from the call site — it still walks the box graph, so the graph has to be
  built first.

---

## Worked example A — a canvas document

Build one box, run it (reading its output), and save it to a reopenable document.
`savetest.yaml`:

```yaml
name: savetest
description: Create a box, run it (observable), then persist as metadata.edn.
operations:
  - create:
      box: sv_box
      name: save probe
      frame: { x: 40.0, y: 40.0, w: 160.0, h: 90.0 }
      properties:
        code: |
          prop<Int>(savemarker) = 12321
          println("save test alive, marker=" + savemarker)
  - launch:
      box: sv_box
      property: code
  - await:
      wait: pending
  - save:
      path: /Users/you/Documents/AFieldWorkspace/savetest
```

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --port 9477 \
  --command 'macro write savetest --file savetest.yaml' \
  --command 'macro run savetest'
```

Expect `applied=4 skipped=0 errors=0` and
`output: [{ "uid": "sv_box", "html": "save test alive, marker=12321" }]`, and a
`metadata.edn` document written under the workspace. Reopen it with
`--document savetest` and a bare `launch: { box: sv_box }` reruns the stored code.

`prop<Int>(savemarker) = 12321` in a box lets that box read `savemarker` bare later —
that is how Field properties work: a `prop<T>(name)` in a parent injects the accessor,
and the value propagates to children via the regenerated preamble. Deleting a preamble
is never fatal; it is regenerated the next time the box runs.

## Worked example B — afield.four Vulkan (renderer + compute + draw)

The capstone: one transcript builds a three-box native graphics system on a blank
canvas — a **renderer** box that owns the `Window`/`Renderer` and pumps the loop, a
**compute** box that simulates particles into a `buffer_reference` buffer (and probes
per-particle speed with `{% include "fieldProbe" %}`), publishing the buffer as
`prop<FrameBufferGroup>(outputPositions)`, and a **draw** box that reads
`outputPositions` and renders. The full transcript is
[`transcripts/threebox.yaml`](./transcripts/threebox.yaml). Its shape:

```yaml
operations:
  - create: { box: tb_renderer, name: renderer, properties: { code: |
        prop<Window>(window); prop<Renderer>(renderer)
        once.renderer { window = Window("Field three-box"); Renderer(window) }
        println("renderer ready")
        start(renderer::run) } }
  - create: { box: tb_compute, name: compute, properties: { compute: |
        #version 450
        ... buffer_reference push_constant + {% include "fieldProbe" %} ...
      code: |
        prop<FrameBufferGroup>(outputPositions) = outData
        println("compute launched, N=" + N) } }
  - create: { box: tb_draw,    name: draw,    properties: { code: |
        // reads outputPositions, draws the particles
        println("draw launched") } }
  - connect: { parent: tb_renderer, child: tb_compute }  # draw is chained under compute, not
  - connect: { parent: tb_compute,  child: tb_draw }     # a second direct child of the renderer
  - launch:  { box: tb_renderer }      # renderer first — it owns the loop
  - await:   { wait: pending }
  - launch:  { box: tb_compute }       # publishes outputPositions
  - await:   { wait: pending }
  - launch:  { box: tb_draw }          # reads outputPositions
  - await:   { wait: pending }
```

Run it with `--with-playwright` (for capture) and a `native-screenshot` after the final
`await`. The renderer/compute/draw `output` lines confirm all three boxes ran; the
native screenshot shows the rendered particle cloud. The topology rule is load-bearing:
graphics children must be connected beneath the launched renderer, and the renderer must
be launched first. See [`FIELD_BACKDOOR.md`](./FIELD_BACKDOOR.md#graphics-context-mode)
and [`transcripts/graphics_renderer_launch.yaml`](./transcripts/graphics_renderer_launch.yaml)
for the reusable renderer-parent pattern the docs runner uses.

---

## Reading and rewriting a user's saved macros

Macros are documents' saved automations — a user can record or write them, and an agent
can inspect and adapt them. The backdoor `macro`/`transcript` command surface:

| Command | Purpose |
|---|---|
| `macro list` | list the macros saved on the current document/project |
| `macro state` | the transcript recorder's current state |
| `macro read <name>` | print a macro's YAML so you can understand what it does |
| `macro page --offset 0 --limit 80` | page through the live operation transcript |
| `macro write <name> --file <path>` | write/replace a macro from YAML |
| `macro run <name> [--selection <uid>]` | apply a macro |
| `macro undo <name> [--selection <uid>]` | reverse a macro's supported ops |
| `macro save-selection <name> --indices 3,4,5` | save chosen transcript entries as a macro |

Typical adapt loop: `macro read <theirs>` to understand it, edit the YAML for your ends
(rename target boxes, change properties, add a `save`), `macro write <yours> --file …`,
then `macro run <yours>`. For blank-canvas / multi-box construction, use stable ids and
run **without** `--selection` — selection remapping is meant for single-box edit macros.

---

## See also

- [`FIELD_BACKDOOR.md`](./FIELD_BACKDOOR.md) — the run/capture/screenshot/video harness and CLI.
- [`transcripts/README.md`](./transcripts/README.md) — curated runnable transcript corpus.
- [`README.md`](./README.md) — why transcripts are the canonical semantic layer and the backdoor is the transport/capture harness.
- [`../DOCS_RUNNER_GPU_CAPTURES.md`](../DOCS_RUNNER_GPU_CAPTURES.md) — driving GPU/compute/probe captures from `diffstatic_runner` fences.
