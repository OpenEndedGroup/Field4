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

## Decision guide (what works today)

| You want… | Do this | Status |
|---|---|---|
| A GPU **compute** result / a `fieldProbe`/`fieldStats` card | **one compute-only box** (`computeModuleHere` + `ComputeMesh`, no draw), `graphics-context=true` | ✅ works |
| A **graphics** still (vertex/fragment) | one graphics box (`moduleHere` + `Mesh`), `capture="native-window"` | ✅ works (see `afield_four_graphics.md`) |
| An **editor/output video** (a live `display(...)` card) | `capture="box"` + `video=` (auto-rewritten to editor recording), `execution="launch"`, a `frames { while(true){…; frame() } }` loop | ✅ works |
| A **compute + draw** particle system **in one box** | — | ❌ see "Known limitation" |
| A **video of the GPU window** | — | ❌ not supported; native surface is stills-only |
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

**Known compromise to fix — the `field_probes.md` CPU probe card.** It currently builds a
`FieldProbeSnapshot(...)` from a stand-in distribution on the CPU and captures `.summaryHtml()`,
because the genuinely GPU-driven probe card is mis-framed by the hook (see "Framing" below). It
renders the *same* card component, but it is **not** exercising the real GPU probe path — so it is
a stopgap, not a pattern to copy. The right fix is to capture the real GPU-driven card (fix the
framing); until then this stand-in should be treated as a documented gap, per the Principle above.

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

---

## Framing note (editor video)

`capture="box"` + `video=` records the **editor pane with `--open-outputs`**.

- **Without** `graphics-context`, the recording frames the box's output area — so a
  `display("probe", html)` card fills the clip. This is how the `field_probes` CPU stand-in card
  is currently captured (a compromise — see above; it does not drive the real GPU probe).
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

**Why (causal chain, from the source):**

- Push-constant accessors (`var ComputeMesh.<m> by ComputeMesh.BoundFromStructure`)
  are generated by the `computeModuleHere`/`moduleHere` **macros**
  (`shaders.kt`: `macro_computeModuleHere` → `buildComputeInsert`; graphics
  `insertForShader`) and written into the box `preamble` (`applyInsertToPreamble`).
- The box `code` is compiled as Kotlin **REPL snippets**; the preamble is its own
  snippet, named by `KotlinScriptEngine.fieldSourceName` as
  `<boxid>__preamble_$N` (e.g. `<boxid>__preamble_$2`). A later snippet (the body)
  references the preamble snippet's *instance*.
- The **async engine** used by `run`/`execute-all` (`box.ki`, `Boxes.kt` `makeKI`)
  simply **never executes the box preamble at startup** — that step is absent from
  `makeKI` (compare the sync `makeKIHere`, which does
  `preamble.let { ki.exec(it, implicits = listOf(box)) }`). And `executeAll`
  does **not** reset the `_preamble` cache, whereas `launchAll` does via
  `resetLaunchSetupState`.
- Net: when a second module macro perturbs preamble generation/segmentation, the
  compute accessor's preamble snippet ends up compiled-but-not-instanced on the
  live engine, and `checkPreambleAndExecute` (`macros.kt`) skips re-evaluating it
  (its diff vs the stale `_preamble` cache is empty). The body's reference to that
  snippet is `null` → the cast throws.

**A red herring we ruled out:** the four insert builders each emit an identical
`var Structure.<name> by Structure.FromStructure<Any>()` under *stage-specific*
keys (`compute-pc-structure:` vs `graphics-pc-structure:` vs `mesh-`/`ray-`, in
`shaders.kt`). A box with a shared push-constant member name across compute+graphics
therefore gets a **duplicate `Structure` extension declaration**. Unifying that key
(so the merge dedups it) is a legitimate latent-bug fix, **but it does not fix the
null-cast** — tested and disproven. The real cause is the preamble-snippet
lifecycle above.

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

The remaining one-box fix is to make `executeAll` reset the `_preamble` cache like `launchAll`
   (`resetLaunchSetupState`), or have the async `makeKI` exec the box preamble at
   startup (the way `makeKIHere` does), so late-injected accessor snippets are instantiated
   on the live engine. Unifying the `*-pc-structure:` (and `*-ubo-member:`) preamble keys across stages would also let
   identical `var Structure.<name>` declarations dedup (prerequisite cleanup;
   verified *not* sufficient on its own).

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
