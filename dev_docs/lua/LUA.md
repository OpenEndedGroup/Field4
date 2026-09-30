# Lua (LuaJIT) in Field — mechanics, measurements, traps

Added 2026-09-30. User-facing page: `public/docs/lua.md`. Loadable scene: `dev_docs/lua/lua_demo.yaml`
(backdoor: `macro write lua_demo --file dev_docs/lua/lua_demo.yaml`, then `macro run lua_demo`). It is
deliberately *not* in `dev_docs/agents/transcripts/`, so the transcript-corpus guards (index, layouts,
completion-corpus manifest, `DemoTranscriptExecSmokeTest`) do not yet know it; adding it there is a
follow-up that has to satisfy `AgentTranscriptIndexGuard`.

## Pieces

| file | what |
| --- | --- |
| `src/jvmMain/kotlin/afield/lua/LuaNative.kt` | `LuaLibrary` (locate: `-Dafield.lua.library`, `AFIELD_LUA_LIBRARY`, Homebrew `/opt/homebrew/lib`, `/usr/local/lib`, Linux lib dirs; `describe()` says `brew install luajit`) and `LuaNative`: FFM downcalls for ~35 functions of the Lua 5.1 C API, and one upcall stub (`dispatchStub`) for every Kotlin function. No wrapper library. |
| `src/jvmMain/kotlin/afield/lua/LuaState.kt` | one `lua_State`: lock, marshalling, `run`/`load`/`call`, `runGuarded` (budget + line recording), `tick`, `callGuarded`, `close`. |
| `src/jvmMain/resources/afield/lua/prelude.lua` | the Lua half of the API (`print`, `display`, `box` proxies, `field.named`, `line`/`lines`, `slider`/`checkbox`, `spawn`/`wait`, the guard/recorder hook, the message handler, completion). |
| `src/jvmMain/kotlin/afield/lua/LuaLanguage.kt` | `LuaExecution` (main language `lua`, code_language `Lua` → Monaco's stock `lua` grammar), the box handlers, line notes via `LastRunNotes`, completion. |
| `src/jvmTest/kotlin/afield/lua/` | `LuaStateTest` (13) and `LuaBoxTest` (11), every native test an `Assume` on `LuaLibrary.isAvailable`, so without LuaJIT they are *skipped*, not passed. 24/24 passed, 0 skipped on 2026-09-30. |
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

Backdoor on port 9721, a fresh replay of `lua_demo.yaml` (`applied=24 skipped=0 errors=0`), every box run
one at a time: root (`LuaJIT at /opt/homebrew/lib/libluajit-5.1.dylib`), hello (`hello from LuaJIT
2.1.1774896198`, `x is 42`), this box (twice: `run 1`, `run 2`), drawing (PNG checked), wave (launched,
PNG checked, stopped), spawned (launched: four ticks 0.5 s apart, `done`), an error (line 3), runaway
(stopped after 1 s, line 4), jit (`interpreted 0.248 s, compiled 0.048 s: 5x`), accumulates (then the
fragment `greet("Field")` → `"hello, Field"`), controls (slider moved to 85 → circle redrawn, orange
pixels 24612 → 110272), line notes (the recorded notes for hello, `x = 42` on line 4). The docs page's
nine captures came from `FIELD_DOCS_RUNNER_MODE=always|incremental --page lua.md`, each opened.

## Not done

- Lua does not reach Field's audio or pattern layers, and nothing uses LuaJIT's `ffi`.
- `afield/lua` is not a `StdlibReferenceGenerator` source root, so it has no reference page (adding it
  needs a reference regeneration).
- Windows/Linux discovery paths are written and untested.
