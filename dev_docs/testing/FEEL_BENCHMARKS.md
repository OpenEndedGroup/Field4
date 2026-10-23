# Feel benchmarks: how long a person waits

`tools/feel_bench.py` measures the waits a person meets in their first minutes with Field: the
first run of a box in a cold JVM, the same run again, warm reruns, editing and rerunning, the
Alt+Enter edit of a running loop, launching a renderer, and the shader loop: edit a fragment
shader, Alt+Enter (Reload Shader), see it. It drives a small set of
real starting points, and for every step it records three things:

- the client's wall time, from the command being sent to its verdict or condition (what a person
  waits for, websocket included);
- the time to the box's first output (`output` / `set-output` event);
- the server's `LatencySpans` that started inside the step, so a wall time splits into engine
  build, bootstrap, compile, eval, shader compile and pipeline creation.

```bash
uv run --project . --group backdoor python tools/feel_bench.py run                       # all scenarios, one JVM
uv run --project . --group backdoor python tools/feel_bench.py run --fresh-jvm-per-scenario   # every scenario sees a cold JVM
uv run --project . --group backdoor python tools/feel_bench.py run --scenario kotlin-println --reps 8
uv run --project . --group backdoor python tools/feel_bench.py run --no-graphics
uv run --project . --group backdoor python tools/feel_bench.py compare                   # last two runs on this host
```

It boots its own Field (`./gradlew runJvm`, first free port from 9530) on a fresh workspace
`build/feel-bench/workspace` holding copies of `templates/first-steps` and
`templates/first-graphics`, and stops it with SIGINT afterwards. Full results go to
`build/feel-bench/<stamp>.json`; a summary line is appended to the machine-local ledger
`~/.afield/feel-bench/ledger.jsonl`, with commit, dirty flag, host, CPU, memory and whether the
machine was on AC power. The ledger is outside the repo on purpose: numbers compare only on one
machine.

The run exits non-zero when a step fails.

## Scenarios

| Scenario | Starting point | Steps |
| --- | --- | --- |
| `kotlin-println` | a new box running `println("hello")` | first run, same code again, warm reruns, edit + rerun (a new literal each time) |
| `first-steps` | `templates/first-steps`, the welcome page's "Start here" | first launch, Alt+Enter `var petals = N` while it runs (`execute-fragment`), stop, second launch, warm relaunches |
| `first-graphics` | `templates/first-graphics`, "Next: native graphics" | renderer launch, its first frames, triangle launch, its frames, same relaunch, fragment edit + Reload Shader, fragment edit + Update Shader, fragment edit + relaunch, Kotlin edit + relaunch |

The shader steps send what the editor sends. Alt+Enter in a `fragment`/`vertex` editor (and
alt-r) is `set-string` with the edited text and then `perform-action moduleHere_reload`;
Alt+Shift+Enter is the same with `moduleHere_update`, which keeps the module when the shader's
interface is unchanged. Neither replies, so the step ends when the server's `shader.reload` /
`shader.update` span ends.

Not covered yet: a compute shader (canonical lesson "3. compute shader" is the candidate, its
output is text), Python boxes (need `FIELD_PYTHON_VENV`), opening a document, the browser's
own paint of an output. "Renderer first frames" and "triangle frames" are `wait-native-renderer
--frames 3`, whose polling sets a floor of a few hundred ms; read them as an upper bound.

## Spans

`afield.app.diagnostics.LatencySpans` keeps the last 8192 spans in a ring buffer (always on;
one `nanoTime` pair per span at sites that cost milliseconds). `GET /api/perf/spans?since=<seq>`
returns those after a sequence number, with `jvmStartEpochMillis` and `readyMillis` (JVM uptime
when the runloop first ticked). Start times are epoch microseconds, so they line up with a
client's clock on the same machine. `tools/test_feel_bench.py` checks this table against the
names the source records.

| Span | Measures |
| --- | --- |
| `engine.create` | `createKotlinScriptEngine` for a box, `detail` async (`makeKI`) or sync (`makeKIHere`) |
| `engine.bootstrap` | `ki.bootstrap(intro)`: compiling and evaluating the stdlib intro into a new engine |
| `engine.bootstrapWait` | an `exec` waiting on the engine's bootstrap gate (non-zero when a box runs before its async bootstrap finished) |
| `engine.reset` | `execute-all`'s `makeKIHere`: the replacement engine, a spare's preamble or a whole create + bootstrap + preamble |
| `engine.spare` | building a box's spare engine in the background after a run's verdict (`SpareEngines`; its bootstrap is a separate `engine.bootstrap`) |
| `kotlin.compile` | one K2 snippet compile (`detail` kind and length) |
| `kotlin.compile.cacheHit` | an instant: the rerun compile cache served the snippet |
| `kotlin.eval` | one snippet evaluation |
| `execute.queue` | `execute-all` received to its body running on the main runloop |
| `execute.all` | `execute-all` received to its `execution-verdict` |
| `execute.fragment` | `execute-fragment` (Alt+Enter) received to its verdict |
| `launch.all` | `launch-all` received to its verdict (setup complete; a `loop` keeps running) |
| `shader.compile` | one shaderc GLSL → SPIR-V compile (`detail` kind and source name) |
| `shader.slangc` | one external `slangc` run |
| `shader.reload` | the Reload Shader action (Alt+Enter in a shader editor, alt-r) for one box, `detail` its success |
| `shader.update` | the Update Shader action (Alt+Shift+Enter) for one box, `detail` its success |
| `pipeline.create` | `vkCreateGraphicsPipelines` / `vkCreateComputePipelines` for a new module |
| `pipeline.update` | the same, for a module rebuilding its pipeline |
| `renderer.firstRender` | the first `render()` of a `Renderer.run` invocation |
| `renderer.toFirstFrame` | `Renderer` construction to the end of its first frame (starts before the launch step, so the harness attributes it to no step; it is in the raw spans) |

## Baseline, 2026-10-23

Apple M1 Max, macOS 26, **on battery** (re-take on AC power before comparing against it),
commit `a48ac3f44` plus this instrumentation, `--reps 5`. Medians in ms; "cold" is
`--fresh-jvm-per-scenario`, "shared" is one JVM running the scenarios in table order.

Boot: Gradle up-to-date check ~2.9 s, JVM start to `/health` ~2.6 s, JVM start to first
runloop tick ~2.1 s.

| Step | shared | cold | where the time goes (cold) |
| --- | ---: | ---: | --- |
| println: first run | 1513 | 1565 | `engine.reset` 1324 (of it `engine.bootstrap` 1205) |
| println: same code again | 469 | 471 | `engine.reset` 354 |
| println: warm rerun | 372 | 370 | `engine.reset` 265, user compile ~90 |
| println: edit + rerun | 301 | 333 | `engine.reset` 221 |
| first-steps: first launch | 507 | 1869 | `engine.bootstrapWait` 1231, compile 529 |
| first-steps: Alt+Enter edit | 38 | 61 | compile 47 |
| first-steps: warm relaunch | 104 | 133 | compile 108 |
| first-graphics: renderer launch | 1135 | 2634 | `engine.bootstrapWait` 1314, eval 764 (Vulkan instance, device, window) |
| first-graphics: triangle first launch | 780 | 1020 | compile 557, `shader.compile` 282 (4 compiles) |
| first-graphics: same relaunch | 442 | 527 | compile 231, `shader.compile` 225 (4 compiles) |
| first-graphics: fragment edit + relaunch | 438 | 473 | `shader.compile` 220, compile 173 |
| first-graphics: Kotlin edit + relaunch | 438 | 491 | `shader.compile` 241, compile 165 |

The first cut of this page said a fragment edit needs a relaunch. It doesn't: the probe wrote
the property and never ran Reload Shader, which is what Alt+Enter in a shader editor does. That
loop is now measured; see below.

## Shader loop: one shaderc compiler, 2026-10-23

Measured the same way, `first-graphics --reps 5`, on battery. Every `shader.compile` took
~57 ms whatever the shader, because `ShaderSPIRVUtils.compileShader` created and released a
shaderc compiler per call. Releasing the last one finalizes glslang, which drops its built-in
symbol tables, so each compile rebuilt them. One compiler for the life of the JVM, never released:

| Step | before | after |
| --- | ---: | ---: |
| fragment edit + Reload Shader (Alt+Enter) | 127 | 10 |
| fragment edit + Update Shader (Alt+Shift+Enter) | 125 | 14 |
| triangle relaunch (same code) | 531 | 329 |
| fragment edit + relaunch | 497 | 269 |
| Kotlin edit + relaunch | 507 | 262 |

A compile is now ~1.5 ms; the first one in a JVM still builds the tables (~100 ms, in
"triangle first launch").

## What the baseline says to fix next

1. **`execute-all` rebuilds the box's engine on every run.** `executeAllRequest` replaces the
   engine with `makeKIHere` and re-bootstraps the stdlib intro synchronously, so a one-line
   `println` costs ~370 ms warm, ~265 of it the reset. It also discards the engine `makeKI`
   already bootstrapped in the background, and defeats the rerun compile cache, which lives in
   the engine. Candidates: reuse a pre-bootstrapped spare engine, cache the compiled intro across
   engines, or keep the engine and reset only its state.
2. **A launch compiles each shader stage twice.** `shaders.kt` compiles a stage to introspect it,
   and `Module` compiles it again when it is not handed that introspection: four `shader.compile`s
   per triangle launch for two stages. (Reload Shader compiles each stage once, the unchanged
   vertex shader included.) Cheap since the shared compiler, but still double.
3. **The first engine's bootstrap in a cold JVM, ~1.2 s,** shows up as `engine.bootstrapWait`
   when a newcomer launches before it finishes. Warming one engine at server start, or the
   AOT cache training a box run (it only waits for `/health` today), would hide it.
