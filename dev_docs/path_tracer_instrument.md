# The path tracer's instrument, and its integrator reload

*2026-09-11.* What was already there, what was not, and the three things that went wrong on the way
— because two of them are traps the next person will hit and one of them is a number I had written
in three places and never counted.

The published half is under **Reading a running tracer** and **Replacing the integrator on a running
tracer** in [`public/docs/path_tracing.md`](../public/docs/path_tracing.md). This page is the part a
doc page should not carry.

## Where this came from

A live session on 2026-09-10 left five boxes beside a 1920 × 1080 copy of the shipped
hidden-environment demo. Four were printf loops — `frames { repeat(180) { frame() } }` with a
`println` inside, **two of them byte-identical copy-pastes** of the other two (271 chars each and
471 chars each) — and all four asked the same two questions: how fast is this converging, and what
is the camera doing. The fifth hand-rolled an integrator hot reload.

That is the whole specification. The four loops are now
[`PathTraceConvergence`](../src/jvmMain/kotlin/afield/four/fabricated/PathTraceInstrument.kt) and a
value printer; the fifth is `PathTracingRenderer.reloadIntegrator`.

## What the status quo already did

Established by running `.h` and `.h2` on a live tracer **before** designing anything — a 64 × 48
`COMPUTE_BVH` tracer over one emissive quad, driven to 8 samples on an M1 Max. Not by reading the
code and reasoning about it.

`.h2` **already worked**, and this is the finding that changed the design. `H2Reflect` lifted `.h2`
to "everything", and on a live tracer it rendered `PathTracingRenderer · 33 properties` with the
`/h2-explorer` iframe, the `/tree` outliner link and the `/browse` columns link — 1 442 bytes of
working drill-down. **33 is exactly the count of the class's public properties**, so the reflective
tree was already answering "the twenty-odd tuning properties with their current values". A
hand-written adapter would have *replaced* something that worked.

`.h` showed nothing. 376 bytes, of which ~330 is the `value-viz` wrapper `<div>`. The payload:

```
PathTracingRenderer afield.four.fabricated.PathTracingRenderer@19266ca3
```

`PathTracingRenderer` is not a data class, so `ValueVisualizer.visualizeBuiltIn` fell to
`formatFallbackObject` (`ValueVisualizer.kt:398`) — class name, `toString()`, nothing.

And the *reason* usually given for that is wrong. `.h` is **not** "six hand-coded types plus
reflection". The extension point is `ValuePrinterRegistry` / `registerValuePrinter<T>`
(`ValueVisualizer.kt:2655`), dispatched at `ValueVisualizer.kt:265` **ahead of** the built-in
`when`. Population, derived by `ProseNumeralContractTest`'s own `valuePrinterClassKeys` rather than
grepped: **50 class-keyed registrations before this change, 26 of them in
`afield/four/FourVisualizers.kt`** — `Renderer`, `Camera`, `Window`, `Device`, `SwapChain`,
`Module`, `ComputeModule`, `ComputeModulePointer`, `Mesh`, `Introspection`, `RenderToTexture`,
`VulkanBuffer`, `FrameBufferGroup`, `Structure`, `Parameters`, `LoadedTexture`, `StorageImage`, … .

So every type the tracer is *built out of* had a printer, and the tracer did not. The gap was a
missing printer in a family of 26, not a missing core case. (This change makes it 51, which moved
two guarded prose numerals; see below.)

## The fact that is not a property

Reflection cannot reach this one
(`src/jvmMain/kotlin/afield/four/fabricated/PathTracingRenderer.kt:437#private fun updateFrame`:
the vector is `val up`, a local inside a private method, so there is no `KProperty1` for
`H2Reflect.properties` to enumerate — and `PathTraceInstrumentTest.kt` has to reconstruct it from
`camera.right` and `camera.forward` to test it at all).

Two of the four scratch boxes printed `camera.up` and then its three components as `toRawBits()`,
which is what you do when a number is not what you expect and you have run out of ideas.

**They were watching a vector the tracer never reads.** `updateFrame` builds its own up as
`Vector3f(right).cross(forward).normalize()` — the component of the declared up that is
perpendicular to the view direction. The shipped material study's camera declares `(0, 1, 0)` and
the tracer uses `(-0.087, 0.978, -0.187)`: **11.94° apart**, on a demo that has shipped four
captured figures.

No property tree can surface that, reflective or hand-written-from-properties, because the value is
a local inside a private method. That is the argument for `PathTraceCameraBasis` computing both, and
for the `.h` row appearing *only* when they disagree — on a camera where they agree it is a row to
read past.

## Where the reload line actually was

Narrower than "no reload exists". `pathTracingHere` (`PathTraceShaderBoxes.kt:18`) slices the
packaged `trace.comp` at `// MATERIAL_BEGIN` / `// MATERIAL_END` into the `pathTracing.preamble` and
`pathTracing.integrator` shader templates and puts **only the material function** in the box's
editable `compute` property; Reload Compute Shader then re-expands it. So on a `pathTracingHere`
tracer the material slot hot-reloads and the integrator is whatever the template froze.

What was missing:

- All five shipped demos and `PathTraceDemo.install` use the **plain constructor**, which had no
  reload path at all. `grep -rn 'reload' src/jvmMain/kotlin/afield/four/fabricated/` returned 7
  hits: two comments and five about `GaussianSplatRenderer` / `FabricateShaderBoxes`.
- The backend `#define` was written twice and reachable from neither a box nor a doc.
  `grep -rn 'HARDWARE_QUERY' src/ public/docs/ dev_docs/`, excluding `trace.comp` itself, returned
  exactly **two** sites, both effectively private: `internal fun shader(hardware: Boolean)` and
  `_pathTracingHere`. Zero in `public/docs/`, zero in `dev_docs/`, zero in tests. `internal` is why
  the scratch box retyped the one-liner instead of calling it — and it retyped it without the strip
  step `_pathTracingHere` has, so applying it twice would have produced two defines.
- The superseded `ComputeModule` had to be retired by hand. `init` registers
  `disposals += { materialModule.current.cleanup() }` — a lambda that reads `current` **at close
  time** — so `close()` retires whatever is current *then* and the superseded module leaks unless
  the caller chains `renderer.cleanup += Runnable { old.cleanup() }`. Exactly what the scratch box
  does, and nothing in the tree said to.
- `ComputeModulePointer.reload` returns `false` for two unrelated things (`ComputeModule.kt:34` and
  `:70`). The scratch box wraps it in `check(...)`, so it **throws on an unchanged source**.

## Three things that went wrong

### The reset reason was overwritten, and the generation double-counted

A shader swap is *observed* a frame after it is *requested*. `reloadIntegrator` replaces the module;
`updateFrame` is what compares `pipeline` against `previousPipeline` and discards accumulation. So
the requester's own `reset("integrator reloaded")` was overwritten by `updateFrame`'s bare `reset()`
a frame later. First live run: `reason='property edit'`, `generation=4` for three resets.

Fixed by handing the reason forward in `pendingPipelineReason`, which `updateFrame` consumes — so
the generation advances once per reset and a `pathTracingHere` material reload, which arrives with
no reason set, still gets an honest `"shader reloaded"`. Second run: `resetReason=integrator
reloaded`, `generation=3`, and the plottable mark reads `(integrator reloaded, 1.297 s)`.

Then the demo showed the same branch firing on the **first** frame, where `previousPipeline` is
still its initial zero and the pipeline is being *seen*, not swapped — a tracer that had never
reloaded anything reporting `last reset: shader reloaded`. Now `"first frame"`.

### The image compared bit-identical to itself

The same frame of lag, from the other side. The first verification did
`reloadIntegrator(patched)` and then `awaitSamples(tracer, 96)` — which returned **instantly**,
because `samples` was still 96 from before the swap. It paused, read back, and compared the old
image with itself: `before == after` to the last bit, and the convergence log had logged 64 frames
for a run that should have had 200.

This is a trap for exactly the code that wants to check a reload, so it is now documented on the
method and in `public/docs/path_tracing.md`. Wait for `samples` to *fall*, or for `lastResetReason`
to change. With that fix: 155 099 of 230 400 pixels changed, mean linear radiance 0.27579 → 0.26030,
`camera.at`/`target`/`up` bitwise identical.

### A sustained reset is not a falling sample count

`PathTraceConvergence` detected a reset as `samples < previousSamples`. A camera being dragged
resets accumulation on *every* frame it moves, which holds `samples` at `samplesPerFrame` rather
than lowering it — so a 200-frame drag was recorded as **one** reset. Found by this feature's own
test, not by a live run.

It is `<=` now, and that is exact rather than a heuristic: `render` appends to the log *after*
`samples += samplesPerFrame`, so on an undisturbed frame the count has strictly risen. Failing to
rise therefore *is* "accumulation was discarded this frame". The collapse of a drag into one plot
mark then happens in `resetMarks`, where it belongs, instead of being an accident of the detector.

Also, and in the same run: `{good:…}` is not a markdown role. The six are `accent`, `muted`, `dim`,
`ok`, `warn`, `bad` (`MarkdownOutput.kt:90`), so the tracer's name rendered as the literal text
`{good:'path-tracer'}` in the panel. Caught by looking at the screenshot rather than at its byte
count.

## Plotly, and the plotting code that was not written

The rendering order on this project is Plotly → FLine → raster, and the honest answer here is that
there is nothing Plotly cannot do. `showSignals` (`plotly.kt:170`) already takes named series, a
shared `x` (wall seconds), and `marks: List<Pair<String, Number>>`, which it draws as full-height
dashed rules carrying their label into the legend. That is precisely "samples against wall time, and
the reason the last reset happened".

So `Box.showConvergence` is **one call to `showSignals`** and the feature contains no new plotting
code, no FLine geometry and no raster path. Quoting "geometry is FLines" at an agent once produced a
1 689-line second plotting engine; the check is worth making explicitly every time.

## Why the demo's boxes are 800 px apart

Because the panels were measured, not guessed. `screenshot-box` reports a clip of **290 px** for the
`.h` card box and **518 px** for the `showSignals` box, each including 2 × 20 px of padding. An
800 px pitch clears the worse of those by at least **190 px** at every gap, in either reading order.

The previous demo in that folder put boxes 116 px apart over a 267 px panel, so running the box its
README told you to run first covered the two it told you to run next — and every gate was green over
it, because no gate measures a panel against the next box.

## Rig notes, which cost four failed runs

- `tools/field_backdoor.py --workspace` is **not** forwarded under `--attach-existing`;
  `--document` is. Splitting the path — `--workspace <dir> --document <name>` — is what makes
  `boxes` return the document's boxes instead of `[]` or `[root]`.
- `--graphics-context` puts you in a **scratch** document with an `agent_graphics_context` box, not
  in the document you asked for. Do not pass it when the document has its own renderer box.
- A `create: box: <id>` alias is scoped to **one macro run**. A later macro's
  `connect: { parent: <id> }` silently *skips* — `applied=3 skipped=1 errors=0` — and the child then
  cannot see the parent's `prop`. A control (`ctl_parent` → `ctl_child` over a plain `String`)
  proved `prop` inheritance itself is fine. Everything stateful belongs in one macro.
- `display(...)` from inside a coroutine started by `start(...)` reaches the panel under the
  transcript's `launch`, but produced nothing under the backdoor's `run`. The demo does not depend
  on it: the instrument is its own one-line synchronous box.
- `screenshot-box` clips to a 40 × 40 glyph for a box with no open **display** panel — `println`
  output alone is not enough. A box that ends in `display(...)` screenshots properly.

## What is guarded

`PathTraceInstrumentTest` — 13 tests, everything except "does the image change", which needs the
device. That is why the two renderings take a `PathTraceStatus` rather than a live tracer: a
hand-built status is a complete input.

The load-bearing assertions are the negative and the exact ones. `.h` is only useful if it stays
short, so the row count is pinned as an **equality** at both ends — five for a default tracer, ten
with every optional group — and each group is asserted to cost exactly one row. The `.h2` map is
asserted to be a **superset** of the tuning keys by derivation from the status, not by a spot check,
because registering an adapter replaces a reflective tree that worked.

And `everyMarkedNumeralMatchesItsDerivation` caught the drift this change causes, which is the
system working: `valuePrinterClassKeys` 50 → 51 in `DATAFRAME_PLUGIN.md`, and
`valuePrinterRegistrationsInSource` 54 → 55 in `citation_contracts.md`. Both recounted by deriving.

## Loadable demo

[`dev_docs/agents/demos/path_tracer_instrument/`](./agents/demos/path_tracer_instrument/) — six
boxes: the renderer, the tracer with a log, the instrument in one line, the convergence curve, a
live integrator swap, and putting it back.

```bash
./field.sh dev_docs/agents/demos/path_tracer_instrument --read-only
```

`--read-only` because the folder is in the repository and opening a document normally writes a
backup, a screenshot, history metrics and an operation transcript into it.
