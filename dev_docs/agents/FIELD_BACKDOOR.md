# Field backdoor: stable contracts and code ownership

`tools/field_backdoor.py` is the agent-facing client for running Field code,
observing websocket events, and asking Field's JVM-owned Playwright browser for
DOM state or captures. This page records the few contracts that are easy to
misunderstand. It is not a second command reference.

## Code ownership

- CLI options, command syntax, and dispatch:
  `tools/field_backdoor.py`, especially `build_command_parser()`,
  `parse_command()`, and `dispatch_command()`.
- Managed-server startup and JDWP environment:
  `BackdoorSession.ensure_server()` in the same file.
- Server RPC handlers used by Playwright, native captures, and JVM entrypoints:
  `src/jvmMain/kotlin/afield/Server.kt`.
- Semantic document operations and their guarantees:
  `src/jvmMain/kotlin/afield/operation_transcript.kt` and
  [`OPERATION_TRANSCRIPT.md`](OPERATION_TRANSCRIPT.md).
- Smoke catalog runner: `tools/run_field_tests.py`.
- Browser regression runner: `tools/run_ui_regressions.py`.
- Docs capture integration: `public/docs_hooks.py` and
  [`../DOCS_RUNNER_GPU_CAPTURES.md`](../DOCS_RUNNER_GPU_CAPTURES.md).
- Which verification layer to choose:
  [`../testing/TESTING_STRATEGY.md`](../testing/TESTING_STRATEGY.md).

Read the parser or run `python tools/field_backdoor.py --help` for current
top-level flags. The interactive prompt prints the current command names. When
the prose here and the parser disagree, the parser is authoritative.

## Safe starting point

Use the repository `uv` environment and a dedicated high port:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py \
  --json --port 9477 --command status
```

`status` reports whether this invocation owns the server and gives the session
and managed-server log paths. A backdoor-owned Field enables JDWP on port 5005
unless `AFIELD_JDWP_PORT` overrides or disables it. A plain
`./gradlew runJvm` does not enable JDWP by default.

When the backdoor starts Field, it always supplies an explicit checkout-local
workspace under `build/field-automation-workspace` unless `--workspace` was
given. Automated servers also decline to update the interactive
`~/.afield/last-workspace.txt` preference. This is deliberate: a machine-global
preference can name a deleted sibling worktree, while scratch state belongs to
the checkout that launched it.

Do not use the default Field port for isolated verification: an already-running
server is reused, and it may contain stale code or human work. Reuse one custom
port within a test session; choose a new one when a fresh JVM matters.

Minimal execution:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py \
  --json --port 9477 \
  --command 'new-box --name Hello' \
  --command 'run @last --value "println(\"hello world\")"'
```

In JSON results, inspect `ok`, `timedOut`, and `events`. For
`run-jvm-main`, inspect `data.exitCode`, `data.stdout`, and `data.stderr`.
The launched Java command is omitted unless that inner command receives
`--include-command`.

## Session and execution contracts

- Scratch is the default and is scoped to one `field_backdoor.py` process.
  Put dependent commands in the same invocation: reusing `--port` in a later
  process starts a different scratch document, even when it reconnects to the
  same Field server. Use `--document <name>` when state must span invocations;
  the name is relative to the Field workspace, not an absolute path.
- A box reference not present in the attached document is an error. In
  particular, never reuse a uid printed by an earlier scratch invocation.
- `@last` means the most recently created box in this backdoor session.
- `run <uid>` and `launch <uid>` use the box's stored `code` when neither
  `--value` nor `--file` is supplied. The flags replace stored code only when
  present.
- `run` waits for one execution to finish. `launch` starts the running form and
  is required for loops such as `start(renderer::run)`; stop it with `stop`.
- `connect <parent> <child> [...]` makes every later uid a child of the first.
- Each invocation writes a unique JSONL session log under
  `.field-backdoor/logs/`; a server it launches gets a sibling
  `.managed-server.log`. These are disposable diagnostic artifacts.

For non-trivial Kotlin, use stdin or a file instead of shell-escaped `--value`:

```bash
cat <<'EOF' | uv run --project . --group backdoor \
  python tools/field_backdoor.py --json --port 9477 \
  --command 'new-box --name Hello' \
  --command 'run @last --file -'
println("hello from stdin")
EOF
```

The process reads stdin once and reuses it if multiple commands name
`--file -`.

## Browser and capture contract

Python does not need the Playwright package. `--with-playwright` and
`playwright-open` control the browser owned by the running Field JVM.

Attach it before the `run` or `launch` that creates output DOM:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py \
  --json --port 9477 --with-playwright \
  --command 'new-box --name Visual' \
  --command 'run @last --value "display(\"main\", \"<div>hello</div>\")"' \
  --command 'screenshot-box @last /tmp/field-smoke.png'
```

Attaching later cannot reconstruct DOM that was never rendered in that page.
The capture commands wait for normal page/element races, but not for missing
application state.

The parser defines the options for these capture families:

- `screenshot-window`, `record-window`: full browser viewport; optionally
  select a box, show its editor, and open outputs.
- `screenshot-editor`, `record-editor`: the visible Monaco pane. Call
  `editor-open` before execution when inline editor visualizers must exist.
- `screenshot-box`, `record-box`: one box and its disclosures.
- `native-screenshot`: the native renderer window rather than browser DOM.
- `page-eval`: JSON-serializable DOM probes. Use `--file` for anything with
  meaningful quoting; `--body` needs an explicit JavaScript `return`.

`record-window`, `record-editor`, and `record-box` accept `--frame-synchronous`.
In that mode the recorder captures one painted browser image per completed Field
runloop frame and advances its virtual clock by exactly `1 / fps` afterward.
`--restart-target` relaunches the selected box only after that capture hook is
active, so a seeded animation begins at frame zero. Docs video fences supply both
flags automatically. Omit them for real-time recordings of live UI interaction.

Browser screenshots hide notifications and, when Monaco is hidden, clear box
selection by default so ordinary docs captures are clean. Use
`--include-notifications` when the toast/notification stack is the evidence, and
`--preserve-selection` when the selected outline is the behavior under test.
Both flags are available on `screenshot-window` and `screenshot-box`.

Inspect the produced PNG or MP4. Clip coordinates are CSS pixels and may not
equal output pixel dimensions on a high-DPI browser. For a blank iframe, inspect
both the iframe and its parent disclosure, including its intrinsic-size data.

## Graphics topology

`--graphics-context` creates and launches a renderer parent and connects later
`new-box` boxes beneath it. That graph edge is what makes inherited `renderer`
and `window` context available; sleeping longer cannot replace it.

For transcript-driven graphics, run
`transcripts/graphics_renderer_launch.yaml`, then use
`wait-native-renderer agent_graphics_context --frames 3`, create the example
box, connect `agent_graphics_context -> example`, and run the example.

Coroutine work must resolve renderer context before crossing the async boundary
and pass the renderer explicitly:

```kotlin
val renderer: Renderer = fromContext()
start { renderScene(renderer) }
```

Do not add `--graphics-context` to a saved document that already creates its own
`Window` and `Renderer`; launch that document's window box instead. Keep the
session attached while it renders. Detaching from the document disposes its box
renderers.

## Semantic automation

Use `macro write`, `macro run`, and the other `macro`/`transcript` subcommands
for reusable document operations. Their YAML format and execute/launch/save
semantics belong to [`OPERATION_TRANSCRIPT.md`](OPERATION_TRANSCRIPT.md), not
to the backdoor transport. Curated examples are in `transcripts/`.

Use backdoor capture commands after a macro when the result needs visual or
output evidence.

## Suite entrypoints

```bash
# Executable FIELD_TEST catalog
uv run --project . --group backdoor python tools/run_field_tests.py \
  --refresh-catalog

# Browser/Monaco regressions
uv run --project . --group backdoor python tools/run_ui_regressions.py \
  --port 9490 --timeout 45

# Full docs recapture
FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 \
  uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

`run_field_tests.py` reads per-test timeouts from `FIELD_TEST.timeoutSec`; it
does not accept the backdoor's `--timeout` option. Use `--id` for a focused UI
regression. UI artifacts live under
`build/ui-regressions/artifacts/<test-id>/`.

If a timed-out snippet leaves later liveness checks wedged, restart the
backdoor-owned Field on a fresh port before treating subsequent failures as
independent defects.
