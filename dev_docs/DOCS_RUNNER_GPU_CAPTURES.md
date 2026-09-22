# Docs runner: GPU / compute capture fences

Practical notes for authoring `diffstatic_runner` fences that drive GPU work
(compute shaders, buffers, `fieldProbe`/`fieldStats`) during the docs build, and
a map of the sharp edges we hit so the next person doesn't re-derive them.

Companion reading: `public/docs/building_and_testing.md` (fence attributes),
`public/docs/diff-player.md` (runner reference), `agents/FIELD_BACKDOOR.md`
(the backdoor the hook shells out to), `public/docs_hooks.py` (the hook itself).

Build one page with, e.g.:

```bash
FIELD_DOCS_RUNNER_MODE=strict FIELD_DOCS_RUNNER_PORT=9488 \
  /opt/homebrew/Caskroom/miniconda/base/bin/uv run --project . --group docs \
  python tools/build_zensical_docs.py --page <page-stem>
```

(Note the absolute `uv` path — bare `uv` is not on the non-interactive PATH.)

---

## Principle — a capture exercises the real code, or it does not exist

The docs runner's entire value is that it drives the **real** Field code path and embeds the
**real** result. Every capture is a test: green means the feature actually works; a capture that
can't be produced is a real defect to surface, not to hide.

So **never fake, synthesize, hardcode, mock, or stand in for a capture** to make it "look right."
Do not substitute a CPU render for a GPU one, a hand-written string for a value the code should
compute, or a scripted placeholder for a live panel. If the real capture won't come out:

1. **fix the code** — the feature, the completion, the render path — so it genuinely produces the
   result; or
2. **fix the capture system** — the hook, the `drive` script, the framing/timing/selectors; or
3. **omit the capture** and say why. An honest gap is fine; a convincing fake is not.

A faked capture is worse than none: it stops testing anything, silently rots as the UI moves on,
and misrepresents the product to the reader. (If you just want a picture for yourself, take a
screenshot by hand — that never enters the docs pipeline.)

## Captures with a build prerequisite — you may skip these

One qualification on "omit and say why", because there is now a capture the docs build **cannot**
produce on a fresh clone however correct the code is — the figure is drawn by `relaxWithCeres`, and
everything on that path needs a native wrapper that a clone does not ship
(`CeresRealization.kt:25#wrapper to be built and loaded`).

| page | asset | prerequisite |
|---|---|---|
| `public/docs/polytope_examples.md` | `assets/polytope_examples/kkc_canonical.png` | `./gradlew buildCeresRedistributable` |

That figure calls `relaxWithCeres`, and the Ceres wrapper is an optional extra with **no committed
binary** — see `dev_docs/CERES.md`. Without it, `FfmCeresBackend`'s constructor refuses (by design,
naming the command), the box errors, and the capture fails.

**You have license to skip it.** This is not the "fix the code" case: the code is fine and the
picture on disk is a true capture of it. Concretely:

- The **default incremental build already does the right thing.** `continue_on_error` defaults to
  true in `incremental` mode, so a failed capture is logged as a failure, the committed asset on
  disk is kept, and the build finishes. That is the intended outcome, not a workaround.
- **`FIELD_DOCS_RUNNER_MODE=always` flips `continue_on_error` to false**, so a forced re-capture
  without the wrapper *will* fail the build. If you are force-recapturing a page for some unrelated
  reason and do not want to build Ceres, set
  `FIELD_DOCS_RUNNER_CONTINUE_ON_ERROR=true` alongside it.
- If you skip, **leave the committed PNG and its prose alone.** The one thing that is still not
  allowed is editing the numbers in the surrounding text to match a picture you did not regenerate,
  or replacing the figure with a hand-made one. A stale-but-real capture with prose that matches it
  is honest; anything else is the fake this page exists to forbid.

Prefer building the wrapper when the figure is what you are changing. Skipping is for when it is not.

## Captures with a *platform* prerequisite — macOS cannot produce these at all

The Ceres case above is a prerequisite you can satisfy: run a Gradle task and the figure comes
out. Ray tracing is the other kind. `Device.kt#supportsRayTracing = enableRayTracing` takes its
value from a conjunction carrying an unconditional `!Options.isMac`, so no command, flag, property
or environment variable makes these four captures possible on a Mac; every render method instead
reaches `RayTracingDocsHarness.kt#private fun rayTracingUnavailable`, which throws rather than
writing a stand-in. An agent on this machine cannot pay this debt however long it tries.

| page | assets | prerequisite |
|---|---|---|
| `public/docs/ray_tracing_examples.md` | `assets/rt-uv-gradient.png`, `assets/rt-triangle-scene.png`, `assets/rt-shader-tiles.png`, `assets/rt-mirror-balls.png` | a **Linux** device exposing `VK_KHR_ray_tracing_pipeline` |

The gate is in Field, not in the driver:

```kotlin
// Device.kt
enableRayTracing = Options.enableRayTracing && !Options.isMac && rayFeatureProbe.pipeline
```

`!Options.isMac` is unconditional, and `Options.enableRayTracing` itself defaults to `!isMac`.
The device never reaches `rayFeatureProbe`, so this is not a MoltenVK capability question that a
driver update could change — though MoltenVK does not expose the extension either. The gate
arrived in `03ad66360`, the commit that added ray tracing, so ray tracing has never once been
enabled on macOS in this project.

Every `RayTracingDocsHarness` render method therefore reaches `rayTracingUnavailable`, which
**throws and writes nothing**. That refusal is deliberate and recent. The harness used to write a
"Ray Tracing / Not Available" card instead, and on 2026-07-09 four of those cards were committed
as this page's figures under prose claiming they were renders — a placeholder is a perfectly valid
PNG, so nothing in the build could tell.

**What holds the line now.** `KNOWN_PLACEHOLDERS` in `tools/check_doc_assets.py` carries the four
paths. They are reported on every run and do not gate, so docs stay buildable; but
`tools/test_check_doc_assets.py` fails if an entry outlives the placeholder it names, so the list
cannot quietly become permanent. Any placeholder *not* on that list fails immediately. Run the
checker to see the current position — it prints `KNOWN GAP — 4 published figure(s) are still
placeholder cards` and still exits 0.

**Two traps specific to this page.**

- **`citationGates` is vacuous here.** `ray_tracing_examples.md` is not in
  `CitationContractTest.GUARDED_PAGES` — that map is `dev_docs/*` plus
  `public/docs/autodiff_examples.md`. A green `citationGates` says nothing about this page, so do
  not quote it as cover for an edit here.
- **The page's only test coverage is the four fences, and all four `skipOn: [darwin]`.** They are
  the entire ray tracing test population: no file under `src/jvmTest` mentions ray tracing at all,
  and these four are the *only* `skipOn: [darwin]` entries in the whole `discover_field_tests.py`
  corpus. On a Mac, coverage of everything this page teaches is zero, and nothing local
  compensates.

**What a macOS agent should do instead.** The pictures are unavailable; the code is not. The page
prints eight GLSL and Kotlin excerpts and asserts the harness runs them, and that is checkable by
reading `RayTracingDocsHarness.kt` — the shader sources are `val` constants near the bottom of the
file, so the excerpts can be compared directly against what compiles. Drift found that way is a
real defect on a page whose figures cannot back it up. Correcting prose to match the *code* is
always allowed; what the section above forbids is the opposite move, editing prose to match a
*picture* you did not regenerate.

## Decision guide (what works today)

| You want… | Do this | Status |
|---|---|---|
| A GPU **compute** result | **one compute-only box** (`computeModuleHere` + `ComputeMesh`, no draw), `graphics-context=true` | ✅ works |
| A real `fieldProbe` output card | GPU compute box plus an output-area capture | Runtime works; docs framing remains open, so no capture is embedded |
| A **graphics** still (vertex/fragment) | one graphics box (`moduleHere` + `Mesh`), `capture="native-window"` | ✅ works (see `afield_four_graphics.md`) |
| An **editor/output video** (a live `display(...)` card) | `capture="box"` + `video=` (auto-rewritten to editor recording), `execution="launch"`, a `frames { while(true){…; frame() } }` loop | ✅ works |
| A **compute + draw** particle system **in one box** | — | ❌ see "Known limitation" |
| A **video of the GPU window** | — | ❌ not supported; native surface is stills-only. `ensure_video` *accepts* `capture="native-window"` (`docs_hooks.py:1617#video_capture = options.capture if options.capture in`), but the `is_video` branch has no native case and falls through to `record-window`, and the backdoor has `native-screenshot` with no recording twin (`field_backdoor.py:2446#native_screenshot = subparsers.add_parser`). So a native-window `video=` silently records the **browser** window instead |
| A **live-UI** capture (autocomplete, command palette, AI panels, typing) | `capture="ui"` + `video=`/`screenshot=`; the fence body is a Kotlin `drive` script using the `ui` driver | ✅ works |

## Live-UI captures (`capture="ui"`)

For editor/chrome UI that no pure-code capture can produce — the command palette, popups, AI
panels, keypress sequences — use `capture="ui"`. The fence body is a Kotlin script that drives
the real editor through Playwright via a `ui` object (`DocsUiDriver`, in scope automatically).
It runs the `drive` transcript op (see `agents/OPERATION_TRANSCRIPT.md`), producing `video=`
and/or `screenshot=`. The script runs in a hidden host box whose own id is `id`; type into its
editor with `ui.openEditor(id)` — no separate `create` is needed.

**Working example — the command palette** (verified; ships in `survivalGuide.md`):

````markdown
```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector=".afield-palette-shell" screenshot="assets/command-palette.png" padding="6" embed=true hidden=true name="command-palette"}
ui.openEditor(id)
ui.pause(700)
ui.clickSelector(".monaco-editor")
ui.pause(300)
ui.press("F1")
ui.pause(1600)
```
````

Attributes:

- `region=` (`window` | `editor` | `window-editor` | `box`, default `window-editor`) clips the
  recording/still. For `editor`/`box`, `capture-box`/`uid` sets the box (defaults to the host box).
- `clip-selector=` clips the still to a CSS selector's bounding box — **use this for overlays
  that are not a box** (command palette, popups). The palette's visible panel is
  `.afield-palette-shell` (NOT `.afield-palette`, which is the full-screen backdrop). The op
  auto-captures the clip *inside* the script (overlays close on blur before the op finishes).
- `video=` records an `.mp4`; frames are grabbed while the script runs, so keypresses appear.
- `padding=`, `fps=`, `wait=`, `timeout=`, `embed`, `hidden` as for other runner fences.

`ui` methods: `type(text)`, `press`/`key`/`down`/`up(key)`, `click(uid)`, `clickSelector(css)`,
`hover`/`hoverSelector`, `drag`, `eval(js)`, `openEditor(uid)`, `shot(path)`,
`shotClip(path, css, padding)`, `waitForSelector(css, timeoutMs)`, `pause(ms)`. Use `pause` (not
`wait`). The resolved output paths are bound in scope as `out` (screenshot) and `vid` (video).

**Autocomplete** works — `ui.press("Control+Space")` opens the FieldSuggest popup. The first
completion on a cold box triggers a K1 compile that can take a few seconds, so **poll for the
result instead of guessing a pause**: `ui.waitForSelector(".afield-suggest-row", 15000.0)` blocks
until a row renders (or times out). Base (type-based) completions render as soon as they load;
AI completions stream in a few seconds later and merge (they no longer block the popup — that was
fixed in `fieldSuggest.js`). For an AI-completion shot, wait a beat longer after the first row so
the `AI` section is populated.

`field_probes.md` deliberately omits its card capture until the hook can frame the output area of
the real GPU-driven probe. The former CPU-created `FieldProbeSnapshot` stand-in was removed because
it exercised only card rendering, not the documented probe path.

---

## How a runner fence is driven (recap)

`public/docs_hooks.py` parses the fence and shells out to
`tools/field_backdoor.py` (or the persistent `tools/field_session.py`). The
important mechanics (all verified by reading the hook + logs):

- **`source-box="assets/canonical_graphics/<uid>"`** loads shader properties from
  files by literal name: `<uid>_<property>_.{glsl,kt}` under `public/docs/…`. No
  `metadata.edn` involvement — drop the files and go. `source-box-properties`
  selects which (`compute,vertex,fragment,code,preamble,…`).
- **The fence body is the box `code`.** For a run it is persisted to the `code`
  property (`run_box` → `set_string`) *then* executed via `execute-all`.
- **`graphics-context=true`** spins up a native renderer and parents the box under
  `agent_graphics_context`, so `fromContext<Renderer>()` resolves. Required for any
  GPU work (compute or draw). Uses the per-process, server-restarting path.
- **Capture modes**: `native-window` (GPU window, **stills only**), `box`/`editor`
  (browser panes). Any `video=` is force-rewritten to an **editor** recording with
  `--open-outputs` — there is no box-video or native-window-video path.
- **Animation**: `execution="launch"` + a `frames { while(true){ …; frame() } }`
  loop keeps the strand alive for `duration`; the hook stops it afterward. A plain
  `run` body that returns gives a single frame.

---

## Shader + Kotlin gotchas (each cost us a build)

1. **push_constant block name ≠ instance name.** `layout(push_constant) uniform
   pc { … } pc;` makes glslang emit *"block instance name redefinition"*. Name the
   block something else: `uniform Push { … } pc;`.
2. **`#extension` must precede all declarations.** The `fieldStats` *producer*
   include emits `#extension GL_KHR_shader_subgroup_*`. If you place
   `{% include "fieldStats" %}` after `buffer_reference` block declarations it is
   illegal and cascades into confusing errors. Put the include right after
   `#version` / `layout(local_size…) in;` and *before* any `buffer_reference`
   blocks. (`fieldProbe` emits no extensions, so it is position-tolerant.)
3. **`ssboSizer` returns `Int?`.** `computeModuleHere { ssboSizer = { N * 16 } }` —
   not `{ N * 16L }` (that's *"Return type mismatch: expected Int?, actual Long"*).
4. **Reflected accessors resolve after attach.** `mesh.foo = { buffer }` /
   `step.foo = { buffer }` bind reflected push-constant members; attach the mesh to
   its module first (`module += mesh; renderer += module`) — mirror the
   `canonical_graphics` boxes.
5. **Compute draws nothing.** A compute-only box leaves the GPU window black, so a
   `native-window` screenshot fails "visual validation". That's expected — capture
   the *result* (read-back / a `display(...)` card), not the window.
6. **A uniform block name becomes a generated class that shadows your imports.**
   `moduleHere()` generates one Kotlin class per uniform block, capitalising the block's
   name (`shaders.kt`, `replaceFirstChar { uppercase() }`), straight into the box's
   preamble. So a GLSL `uniform camera { … }` makes the box declare its own `Camera`,
   and a declaration in the box beats *any* import — including the `afield.four.util.*`
   every box already gets, and including an explicit `import afield.four.util.Camera`.
   Symptom: `Unresolved reference 'at' on receiver of type
   'Docs_…__preamble__2.Camera'`. Fix: fully-qualify (`afield.four.util.Camera()`, as
   `afield_four_graphics.md` §1b does — that verbosity is load-bearing, don't "tidy" it)
   or rename the uniform block.

---

## Framing note (editor video)

`capture="box"` + `video=` records the **editor pane with `--open-outputs`**.

- **Without** `graphics-context`, the recording frames the box's output area, so a
  `display("probe", html)` card fills the clip.
- **With** `graphics-context`, the recording frames the *code editor* of the
  macro-created child box, so the card ends up off-frame (you get the source + a
  tiny "ok"). The fix is to **correct the framing in the hook** so the genuinely GPU-driven probe
  card is captured. Do **not** work around it by rendering the card synthetically on the CPU — that
  would be faking the capture (see the Principle at the top).

---

## Known limitation: compute + draw in ONE box

Combining `computeModuleHere` **and** `moduleHere` in a single box (to simulate
and draw a particle system in one fence) fails at the first reflected-accessor
assignment:

```
null cannot be cast to non-null type <root>.<box>__preamble__2
```

**What we verified** (minimal repros under a scratch page, reading
`.field-backdoor/logs/*.jsonl`):

- A **compute-only** box with a scalar push constant, a `buffer_reference` push
  constant, or `{% include "fieldProbe" %}` — **all execute cleanly**. So generic
  compute push-constant accessors are fine headlessly.
- Adding a **graphics module to the same box** reintroduces the null-cast at the
  *compute* accessor (`step.positions = { positions }`). So the trigger is *two
  module macros in one box*, not push constants or `buffer_reference` per se.

**Mechanism, as far as it is actually established:**

- Push-constant accessors (`var ComputeMesh.<m> by ComputeMesh.BoundFromStructure`)
  are generated by the `computeModuleHere`/`moduleHere` **macros**
  (`shaders.kt`: `macro_computeModuleHere` → `buildComputeInsert`; graphics
  `insertForShader`) and written into the box `preamble`
  (`shaders.kt#fun applyInsertToPreamble`).
- The box `code` is compiled as Kotlin **REPL snippets**; the preamble is its own
  snippet, named by `KotlinScriptEngine.fieldSourceName` as
  `<boxid>__preamble_$N` (e.g. `<boxid>__preamble_$2`). A later snippet (the body)
  references the preamble snippet's *instance*, so a preamble snippet that is
  compiled but never instanced makes that reference `null` and the cast throws.

> ### Retraction, 2026-08-25 — the causal chain that used to be here was false when it was written
>
> Until today this section carried a further four bullets naming a specific engine and a specific
> missing cache reset, and a *"red herring we ruled out"*. **Three of its load-bearing facts were
> already false on 2026-07-11**, the day the diagnosis was written (`36e61332d`), and no guard in this
> repo could say so, because every file it named really existed. That is what
> `dev_docs/impossibility_claims.md` exists to prevent, and this is the claim it was written for.
>
> | The chain said | Actually, and since when |
> |---|---|
> | *"the **async engine** used by `run`/`execute-all` (`box.ki`, `Boxes.kt` `makeKI`)"* | the `execute-all` handler builds the **synchronous** engine — `executing.kt:65#makeKIHere(box, reason = "execute-all handler reset")` — since `8b5ea3a356a`, **2026-05-26**, six weeks before |
> | *"`executeAll` does **not** reset the `_preamble` cache, whereas `launchAll` does"* | the same handler clears it two lines later — `executing.kt:66#box.properties["_preamble"] = ""` — since `52a1d563e`, **2025-11-03**, the first commit of this build layout |
> | *"`makeKI` … simply **never executes the box preamble at startup**"* | `makeKIHere` execs it (`Boxes.kt#preamble.let { ki.exec(it, implicits = listOf(box)) }`) and `makeKI` clears the cache itself (`Boxes.kt#box.properties.remove("_preamble")`) since `0c66dcdb7`, **2026-06-18** |
>
> The proposed fix — *"make `executeAll` reset the `_preamble` cache like `launchAll`"* — was therefore
> a description of code that had already shipped, twice. Six weeks of "known limitation" rested on it.

**The duplicate-`Structure` explanation was never actually ruled out.** The retracted paragraph said
that unifying the stage-specific `*-pc-structure:` keys — so the four insert builders' identical
`var Structure.<name> by Structure.FromStructure<Any>()` dedups — had been *"tested and disproven"*.
What that test appears to have exercised is the neighbouring `var Structure.<name>` line, and not the
generated `class <Block> : Structure` block that `applyInsertToPreamble` merges under a separate
`graphics-structure-type:` key. Those are different declarations under different keys, and the second
one is the one a compute-plus-graphics box duplicates. So the elimination does not stand, and
re-testing it is the first thing to try. The nearest existing evidence covers the graphics stage only —
it pins the dedup of exactly that shape, one `class Params(` and one `graphics-pc-structure:params`,
and there is no compute-plus-graphics equivalent
(reproducer: `ShaderTypedStructurePreambleSmokeTest.kt#fun main(args: Array<String>)`).
(conjecture, 2026-08-25 — the re-test has not been run; this replaces a false elimination with an
open question, not with a new finding.)

The reproduction at the top of this section — a compute-plus-graphics box failing at the first
reflected-accessor assignment — was observed and is left as written. What is retracted is the
explanation, not the symptom. Whether the symptom still reproduces on today's `main` has **not** been
re-checked: it needs a live `--graphics-context` session, so it is not reachable from the docs gates.
(conjecture, 2026-08-25)

**Workarounds today:**

- **Use compute-only boxes** for probe/stats captures (all `field_probes` needs).
- **Split compute and draw into two boxes** sharing a buffer, the way
  `canonical_graphics` does (compute box: `prop<FrameBufferGroup>(outputPositions)
  = outData`; draw box: `m.inData = { outputPositions }`). This is the verified path:
  [`agents/transcripts/threebox.yaml`](./agents/transcripts/threebox.yaml) creates a
  renderer, compute child, and draw descendant; launches them in dependency order;
  captures startup output; and waits at explicit barriers. The correct Field idiom is
  for the parent to declare and set `prop<T>(name)`, while descendants read `name`
  directly rather than redeclaring the property.

**There is no named one-box fix any more.** The one that used to be written here — reset the
`_preamble` cache in `executeAll`, or exec the preamble from the async `makeKI` — described code that
already existed on three separate paths (see the retraction above), so it cannot be the outstanding
work. What is genuinely open, in the order worth trying:

1. **Re-run the elimination that was retracted**: does unifying the `*-pc-structure:` and
   `*-ubo-member:` keys, *and* the stage-prefixed `<stage>-structure-type:` block that carries the
   generated structure class (`shaders.kt:2774#-structure-type:`), change the null-cast? The previous
   *"verified not sufficient on its own"* rested on the neighbouring declaration, not this one.
   (conjecture, 2026-08-25)
2. **Re-check whether the symptom still reproduces at all** on today's `main`, before spending anything
   on a cause. Nine weeks of `execute-all` changes have landed since it was last observed.
   (conjecture, 2026-08-25)

---

## Hook fix already landed

`public/docs_hooks.py`, editor-capture branch (`capture_mode == "editor"`): it used
`set @last code` / `launch @last`, but under `graphics-context` the box is created
via a macro and connected to `agent_graphics_context`, so `@last` is stale →
*"@last is not available yet"* and no video. Now it targets `default_target_uid`
(the real box uid under graphics-context; still `@last` otherwise, preserving the
non-graphics editor-video path). This is what lets a graphics-context editor video
run at all.

---

## Macro/transcript cache coverage

`compute_digest` currently hashes `macroFile` as a path string. It does not hash the referenced
YAML file's contents. An incremental build therefore cannot infer that a transcript edit invalidates
the pages driven by that transcript.

After editing a transcript YAML, recapture its pages explicitly in `always` mode. The known mappings
are:

| transcript | page |
| --- | --- |
| `afield_four_traditional_triangle.yaml` | `afield_four_graphics.md` |
| `hello_display.yaml` | `diff-player.md` |
| `threebox.yaml` | `funFeatures.md` |
| `docs_capture/remote_ar_kotlin_plane.yaml` | `ar_reference.md` |

The cache-key follow-up is to hash the resolved macro file's bytes next to `source_refs_digest`.
Until that lands, a complete `always` build bypasses the gap. GPU and native-window captures follow
the same one-attempt contract as every other capture: readiness must be expressed through renderer,
frame, selector, or output conditions, and a timeout is a build failure.

---

## Debugging recipe

1. Write a minimal scratch page `public/docs/zz_x.md` with one fence + a source-box
   shader under `public/docs/assets/canonical_graphics/zz-x_compute_.glsl`.
2. Build it with `--page zz_x` in `strict` mode (fails hard on first error).
3. Read the newest `.field-backdoor/logs/*.jsonl` for the box uid; grep for
   `set-string … "property":"preamble"` (when accessors land), `null cannot be
   cast`, `Unresolved reference`, `Return type mismatch`, and the `"kind":"error"`
   compute-compile outputs. The preamble `set-string` timing vs the body errors is
   the whole story.
4. Extract an mp4 frame to eyeball a video capture:
   `ffmpeg -ss <t> -i x.mp4 -frames:v 1 frame.png`.
5. Clean up the `zz_*` scratch files afterward.
