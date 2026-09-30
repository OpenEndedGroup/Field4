# Lua (LuaJIT) in Field — mechanics, measurements, traps

Added 2026-09-30. User-facing page: `public/docs/lua.md`. Loadable scene and welcome-screen starting
point: `dev_docs/agents/transcripts/lua_demo.yaml`, materialized into
`public/docs/assets/agent_transcripts/lua_demo/` (open **lua_demo** from the welcome screen, or with the
backdoor `macro write lua_demo --file dev_docs/agents/transcripts/lua_demo.yaml`, then `macro run lua_demo`).
It went into the corpus by `dev_docs/GUARD_CORPORA.md`'s "adding one transcript" list: `EXECUTE` in
`DemoTranscriptExecSmokeTest` (only the Kotlin root is executed, and it needs no LuaJIT), an entry on
`public/docs/agent_transcripts.md` (totals 165 / 85), the bundle, and 13 completion-corpus rows. The
generated table in `dev_docs/GUARD_CORPORA.md` was **not** edited: it was already red at the base
(163 vs 164, owned by another branch that night) and now wants 165 transcripts / 175 recursive / 159
bundles.

## Pieces

| file | what |
| --- | --- |
| `src/jvmMain/kotlin/afield/lua/LuaNative.kt` | `LuaLibrary` (locate: `-Dafield.lua.library`, `AFIELD_LUA_LIBRARY`, Homebrew `/opt/homebrew/lib`, `/usr/local/lib`, Linux lib dirs; `describe()` says `brew install luajit`) and `LuaNative`: FFM downcalls for ~35 functions of the Lua 5.1 C API, and one upcall stub (`dispatchStub`) for every Kotlin function. No wrapper library. |
| `src/jvmMain/kotlin/afield/lua/LuaState.kt` | one `lua_State`: lock, marshalling, `run`/`load`/`call`, `runGuarded` (budget + line recording), `tick`, `callGuarded`, `close`. |
| `src/jvmMain/resources/afield/lua/prelude.lua` | the Lua half of the API (`print`, `display`, `box` proxies, `field.named`, `line`/`lines`, `slider`/`checkbox`, `spawn`/`wait`, the guard/recorder hook, the message handler, completion). |
| `src/jvmMain/kotlin/afield/lua/LuaLanguage.kt` | `LuaExecution` (main language `lua`, code_language `Lua` → Monaco's stock `lua` grammar), the box handlers, line notes via `LastRunNotes`, completion. |
| `src/jvmTest/kotlin/afield/lua/` | `LuaStateTest` (13), `LuaBoxTest` (12) and `LuaCatalogTest` (2), every native test an `Assume` on `LuaLibrary.isAvailable`, so without LuaJIT they are *skipped*, not passed. 27/27 passed, 0 skipped on 2026-09-30. |
| `src/jvmMain/kotlin/afield/lua/LuaCatalog.kt`, `src/jvmMain/resources/afield/lua/lua-stdlib.txt`, `lua-field.txt` | completion signatures: 146 Lua 5.1/LuaJIT functions and 34 Field ones, one `signature \| doc` a line. `LuaCatalogTest` holds both to a live state with the prelude in both directions (180 live = 180 catalogued). |
| `tools/field_languages.py`, `tools/build_zensical_docs.py` | `lua` needs a language root; `requires="luajit"` is a docs native prerequisite. |

## Lifecycle

One `lua_State` per box, opened on first run and kept across runs (a box accumulates, like Datalog and
Python). It is owned by `box.runScopes.parent` as `ReleasePolicy.KEYED`, so stop leaves it and
retirement (delete, document close) closes it — `LuaBoxTest.theBoxsStateClosesWhenTheBoxRetiresAndNotOnStop`.
Leaving the language (`applyRuntimeToggle`'s `onDetach`) closes it too. Each run happens inside a
`RunScope` as a Kotlin box's does (run: `RUN_STRAND`, superseding the last; launch: its strand;
fragment: ambient), so a slider callback registered from Lua is `ACCUMULATING` and does not pile up.
`LuaState.openStates` / `liveHandlers` count states and handlers for leak tests.

All box Lua runs on the main Runloop thread (`inMain`); slider callbacks hop there too. The lock in
`LuaState` is belt and braces for tests.

## Sound

A Lua box sequences; Field's pattern voice sounds it. `play(seq, opts)` turns a Lua list (numbers MIDI
notes, `false`/`"~"` rests, nested lists divided steps; `field.mini` does the conversion) into
mini-notation and hands `pat(…)` to `patSynth`, registered with `FieldAudioEngine.shared().register(audio(launch))`.
Lua never renders a sample: the patSynth feeder schedules ahead, and the engine thread renders the
bank (AGENTS.md §5 — nothing samples the graph from the Runloop). The registration and feeder are
claimed by the executing `RunScope` as `RUNNING`: the census showed `liveRunning 2` on the sequencer box
while playing and `0` after `stop`. `hush()` and a second `play` replace what the box plays.
`field.render` is `patSynthRenderStereo` — the same bank rendered offline block by block — shown as a
player in the `sound` panel, optionally written as a `.wav`, and measured (`frames`, `seconds`, `peak`,
`rms`, `crossings`).

Measured (`LuaBoxTest.aSequenceBuiltInLuaIsHeardAtThePitchesItNamed`): `{60, 64, 67, 72}` built in Lua,
sine voice, decay 0.15, 0.5 cps, is heard by zero crossings at 260.0 / 330.0 / 395.0 / 525.0 Hz against
261.6 / 329.6 / 392.0 / 523.3 (asserted within 2%); `field.render{69}` writes a one-second stereo wav and
counts 439 crossings a second, peak 0.21, rms 0.150. **Trap:** `PatSoundDocs.estimateHz` read those same
renders an octave or two low (130.8, 65.7, 65.4 Hz) — use zero crossings on a sine voice instead. With a
long decay (2 s) consecutive notes overlap and crossings go wrong too (348 for 392 Hz).

## Traps

- **A Lua error must never unwind through a Java frame.** LuaJIT raises by unwinding to the nearest
  `lua_pcall`. An upcall (a Kotlin `Handler`) that raised — directly, or by touching a table whose
  metamethod raises — would unwind across FFM upcall frames. So: `dispatch` catches everything and
  returns `false, message`; the prelude's `checked` wrapper raises at the caller's line (level 2 from a
  non-tail wrapper — `LuaStateTest` pins the line). Marshalling inside an upcall uses only `lua_rawget`,
  `lua_next`, `lua_rawgeti`. Every call from Kotlin is a `lua_pcall` under `__field_msgh`, so nothing
  reaches the panic handler (which would `abort()` the JVM).
- **A compiled loop never calls a count hook.** Measured (`LuaStateTest.aCompiledLoopEscapesTheCountHook`):
  a count hook every 1000 instructions fires 30000 times over 1e7 loop turns interpreted and **0** times
  once `jit.on()`. The first version of the runaway test, JIT on, hung the test JVM in `lua_pcall`
  (jstack). Box code runs on the main thread, so a hot `while true do end` would freeze Field for good.
  Hence the JIT starts **off** in every state (`jit.off()` in the prelude) and the count-hook guard
  works; `jit.on()` is the opt-in. Interpreted vs compiled on this machine: 0.25–0.49 s vs 0.05–0.10 s
  for 3e7 turns, about 5×.
- The budget is read by the hook as it runs, not when the run starts, so `field.budget = 1` at the top
  of a chunk applies to that chunk (the first cut read it up front; the live runaway box ran 5 s).
- Hooks are recorded per function for line counts: Lua also reports a line event when a call returns
  into a line, so a line is counted only when its own function moves onto it. Consequence: a loop on
  one line counts once, and a statement that spans lines and calls a function (`spawn(function() … end)`)
  can count twice. Known, not fixed.
- Recording is bounded (50000 hook events per run), after which the run continues unobserved and the
  first unrecorded line says so. `AFIELD_LUA_LINE_NOTES=0` turns it off.
- `lua_tolstring` converts a number in place (and confuses `lua_next`); `toKotlin` checks the type first.
- Error lines: `box.error(message, "code", execute.line + chunkLine)` — `Execute.line` is 0-based and the
  chunk line 1-based, the same sum Pd uses. Verified live: `print(t.missing.field)` on line 3 is marked on
  line 3; the runaway loop on line 4 of its box on line 4. Errors are painted after the run's output.
- A box made outside a document has no `root` (`inside` is `lateinit`); `findBox`/`named` fall back to the
  connected graph, and tests link boxes by editing `children`/`parents` directly (`connect` needs a session).
- Driving a slider from Playwright needs `window.updateFieldSlider(el, v, {send: true})`; without `send`
  it only moves the knob.

## Verification, 2026-09-30

Backdoor on port 9721, a fresh replay of the corpus `lua_demo.yaml`, every box run one at a time
(final run: `.nightly-tmp/final2-outputs.txt` in that night's worktree): root (`LuaJIT at
/opt/homebrew/lib/libluajit-5.1.dylib`), hello (`hello from LuaJIT 2.1.1774896198`, `x is 42`), this box
(twice: `run 1`, `run 2`), drawing (PNG checked), wave (launched, PNG checked, stopped), spawned (launched:
four ticks 0.5 s apart, `done`), an error (line 3), runaway (stopped after 1 s, line 4), jit
(`interpreted 0.264 s, compiled 0.048 s: 5x`), accumulates (then the fragment `greet("Field")` →
`"hello, Field"`; `x * 2` on hello → 84), controls (slider moved to 85 → circle redrawn, orange pixels
24612 → 110272), sequencer (`notes 65 64 64 77 57 64 60 77`, `2 s, peak 0.47, rms 0.139`, the player PNG
checked, census `liveRunning 2` → `0` on stop), line notes (hello's recorded notes, `x = 42` on line 4).
`DemoTranscriptExecSmokeTest` filtered to `lua_demo`: PASS, applied=26. The docs page's ten captures came
from `FIELD_DOCS_RUNNER_MODE=always|incremental --page lua.md`, each opened.

## Editor captures: pane height and the caret (fixed 2026-09-30, found by the human)

lua.md's first editor figures were a whole pane tall (box.png, error.png, spawn.png: 2440 device px
for 4–8 lines) and lost their first letter (`box` read `ox`). Both were general, not Lua's:

- A line note is drawn at the foot of the editor pane, screenshot prep makes the pane 840–1200 CSS px,
  and the clip keeps the note (a note is part of what the editor shows). Every Lua run leaves a note,
  so every Lua editor figure was pane-sized; any language whose runs leave notes would be. Fix:
  `fitEditorPaneToContentAndNote` in `src/jsMain/kotlin/afield/Main.kt`, on the `editorOnly` capture
  path only and only when a note is visible, shortens the pane to text + output zones + note + 36 px and
  relayouts Monaco before clipping — `ui.editorHeight(px)` done automatically. Live: pane 1200 → 327
  CSS px, figure 2440 → 702 px. `EditorCaptureHeightTest` holds every committed editor figure to
  600 + 120 px per fence line (with a negative control on the 2440/4 case).
- Field's caret is a red block, and a capture holds it solid (1617a1e95, 09-28); after a run it sits at
  line 1, column 1 and covered that glyph. The capture now also draws the caret `line-thin` and restores
  the editor's own style afterwards. Pd's `typo.png` (09-28) shows the same covered first glyph; it and
  any editor figure captured since 09-28 would change on recapture.
- A trap on the way: after editing Main.kt, `compileKotlinJs` failed with the K2 JS backend's
  "IrClassSymbolImpl is unbound. Signature: kotlin/Unit" — an incremental-cache fault, not the code:
  `./gradlew compileKotlinJs --rerun` built it cleanly.

### Pending: the recapture and the completion figure (blocked by the power gate)

The fix above is committed and verified through the backdoor, but lua.md's six editor figures are still
the pre-fix captures, and the completion figure the human asked for does not exist yet: the docs runner
refuses a capture unless the machine is on AC or at ≥ 80% battery (`tools/verification_environment.py`),
and it was on battery (78% → 56%) for the whole of this pass. No capture was faked. To finish, on AC:

```diffstatic {data-lang=bash}
git apply dev_docs/lua/pending_completion_capture.patch        # the completion fence, into lua.md
rm public/docs/assets/lua/{hello,box,spawn,error,runaway,jit}.png
FIELD_DOCS_RUNNER_PORT=9721 FIELD_DOCS_RUNNER_MODE=incremental \
  uv run --project . --group docs python tools/build_zensical_docs.py --page lua.md
```

then open every PNG (editor figures should be a few hundred px, caret a thin line; completion.png the
popup at `p` with pairs/pcall/play/print and the doc panel, 2×), run `EditorCaptureHeightTest`, and
commit the patch's lua.md change with the new assets. The setup transcript the fence uses is committed
at `public/docs/assets/transcripts/lua_completion_setup.yaml`; the popup it produces was checked live
(`field.` prefix: rows with catalogue signatures, the doc panel beside).

## Not done

- Lua reaches sound only through the pattern voice (no DSP graph, no Pd bridge from Lua), and nothing uses LuaJIT's `ffi`.
- `afield/lua` is not a `StdlibReferenceGenerator` source root, so it has no reference page (adding it
  needs a reference regeneration).
- Windows/Linux discovery paths are written and untested.
