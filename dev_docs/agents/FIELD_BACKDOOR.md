# Field backdoor: stable contracts and code ownership

`tools/field_backdoor.py` is the agent-facing client for running Field code, 
collaborating with users, observing websocket events, and asking Field's JVM-owned 
Playwright browser for DOM state or captures. This page records the few contracts 
that are easy to misunderstand. It is not a second command reference.

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

With no `--port` at all the backdoor picks the first free automation port from 9477
(`select_automation_port`, skipping 9092, the same rule as the smoke and docs runners),
says so on stderr, and reports it as `port` / `portSource: "automation-default"` in
`status`. Before 2026-09-28 the default was 9092, the user's own Field. Reaching that
server now takes `--attach-existing` (whose default port is `$PORT` or 9092) or an
explicit `--port 9092`. `tools/field_mcp.py` keeps 9092 as its default because it only
ever attaches.

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

Do not point isolated verification at 9092: a *fresh* same-checkout server there is
reused (a stale or foreign one is refused), and it holds human work. Reuse one custom
port within a test session; choose a new one when a fresh JVM matters.

### Coordinated live coding in the user's visible Field

There is one deliberate exception to the isolated-port rule: when the user is
actively watching a known Field document and has invited the agent to work alongside
them, the backdoor can attach to that visible session (normally port 9092). This is a
useful collaboration mode, not just a test shortcut. Confirm the checkout and document
first, make semantic edits through the backdoor, and let the user supply immediate
visual judgment from the native renderer. Never attach this way to an unidentified
session.

**Name both halves of a real document, and get the workspace half from the server.** An
already-running Field resolves a document name against *its own* workspace — by default a
machine-global preference (`~/.afield/last-workspace.txt`) that has been observed pointing
into an unrelated checkout. The backdoor's `--workspace` cannot change that: it only ever
configures a server this process launches. So on an attach it must either **match** the
server's workspace or be omitted, and a disagreeing value is now a hard error rather than
silently ignored.

This paragraph used to claim the opposite — that omitting `--workspace` made
`--document dust_three` open `build/field-automation-workspace/dust_three` even on port
9092. It does not: with no `--workspace`, the session key is
`<the server's workspace>/dust_three`, measured on a live Field. Read the server's real
value from `serverWorkspace` in `status`, or directly from
`curl -sk https://localhost:9092/api/instance-info`. Start a live collaboration with a
read-only preflight such as:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py \
  --json --port 9092 --attach-existing \
  --workspace /Users/marc/Documents/AFieldWorkspace \
  --document dust_three \
  --command status
```

Before sending `set`, `launch`, or `launch-batch`, inspect `serverWorkspace`,
`documentName`, `sessionKey`, and `boxCount` in the status result — the four fields that
can disagree with you. `sessionKey` should read as `serverWorkspace` + `/` +
`documentName` and must name the intended on-disk document; a mature document should not
unexpectedly report zero boxes. Do **not** read `requestedWorkspace` as verification: it
is your own `--workspace` argument echoed back, it was the field called `workspace` until
2026-09-12, and an agent following that older instruction wrote five documents into a
different checkout while believing it had checked. Since 2026-09-28 a server that reports
`serverWorkspace: null` (it predates the field) is refused under `--attach-existing`;
`--allow-unverified-workspace` is the explicit override when you have checked by other means.
`reusedExistingServer: true` confirms attachment, but does **not** prove that the document
namespace is correct. Full contract:
[`AGENT_SURFACE_TRUTHFULNESS.md`](./AGENT_SURFACE_TRUTHFULNESS.md).

`set <uid> <property> --file <path>` is the durable live-coding edit. It waits for the
backend's `agent-set-property` acknowledgement, which applies Field's ordinary metadata
and separated-sidecar persistence rules before returning. This matters for `code`,
`vertex`, `fragment`, and `compute`: editing a generated sidecar file directly can appear
to work until the next document load replaces it with the value still held by Field.
After setting shader text, use the box's reload action or relaunch it as usual.

`--attach-existing` is the explicit authorization for this collaboration mode. It
requires a server already listening on the requested port, still rejects a Field from a
different checkout, and bypasses only the source-newer-than-server freshness check. This
matters during live coding: edits made after the user's Field booted are expected, while
an unattended verification must continue to fail closed and start from a fresh port.

Preserve Field's execution distinction in that loop:

- `run <uid>` is a one-shot execution.
- `launch <uid>` starts the animated form. Its immediate `data.running` records the
  initial server signal and may be transient while code compiles or a setup box returns.
- `launch-batch <uid> <uid> [...]` is the command-line form of a canvas chord. It sends
  one ordered `launch-batch` request, so the server's shared launch queue—not timing in
  the Python client—keeps evaluation ordered and gives each preceding box runloop turns
  before the next evaluates. A second batch extends that same queue.
- `wait-running <uid> --state running --settle 0.5` confirms that a renderer/animation
  box remains live long enough to own the frame loop. A setup box can correctly stop
  after installing callbacks or modules; verify those through telemetry or rendering.
- Re-launching a live-coding box should normally be treated as idempotent. Use `stop`
  first only when that box's own contract requires it.

Field box actions are available without inventing a parallel RPC. For example,
`action <uid> moduleHere_reload --property vertex` invokes the same shader reload action
as `alt-r`; follow it with `wait-output` when a concrete compile or telemetry result is
needed.

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

**Since 2026-09-14, `ok` for `run` / `launch` / `continue` / `launch-batch` carries the
box's own verdict.** A box that failed to compile, or threw, gives `ok: false`, process
exit 1, `data.errors` (each distinct diagnostic) and `data.error` (the same, flattened to
one string). Before that they reported `ok = not timed_out`, so a hard Kotlin compile
error returned `ok: true`, `running: false`, exit 0 — with the diagnostic present and
unread in that same result's `events`. Pass `--no-fail-on-error` when the failure is the
point: the verdict is waived, `data.errors` is still filled in, and
`data.executionErrorsIgnored` records the waiver. The smoke catalog's
`expect.allowExecutionErrors` and a docs fence's `expect-error=true` add it for you.

`macro run` / `macro undo` (and their `transcript` aliases) also fail when the
returned box output contains an execution error (empty clear packets are ignored).
`data.operationSuccess` preserves the server's operation verdict, while `data.executionErrors`, `data.errors`,
and `data.error` preserve the box diagnostics. `--no-fail-on-error` explicitly waives
the execution verdict, not a failed operation, and records `data.executionErrorsIgnored`.
Successful operations are not rolled back by this reporting change.

`wait-native-renderer <uid>` stops immediately on an active execution error from
that box, including while a screenshot request is pending. The result preserves the
diagnostic and sets `data.terminal: true`. Cleared errors and other boxes' errors do
not abort the wait; without a target uid, the wait cannot attribute box errors and
continues using screenshot readiness and its timeout.

Native captures also report `terminal: true` when a queued request's **own window**
has not reached its capture point for five seconds. `wait-native-renderer` returns
that diagnostic without retrying. Expired/cancelled queued requests are removed;
another window's frames cannot hide the stall, and image encoding after dequeue
does not count as a stopped frame loop.

`native-screenshot --timeout N` now forwards `N` seconds to the server as
`captureTimeoutMs`. That is one budget for renderer readiness, settling and capture;
the client allows one additional second for the reply. `wait-native-renderer` still
caps the whole wait at its own deadline. Older requests without the new field retain
the 15-second server default. The five-second no-frame watchdog is independent of
this budget: a larger authored timeout does not permit a stopped renderer to queue
requests indefinitely.

`wait-native-renderer <uid>` now forwards the target to the server. Use
`native-screenshot <path> --uid <box>` to capture that box's window or its nearest
connected renderer parent. Selection stays within the current document; another
document opening a window cannot redirect the capture. Without `--uid`, exactly
one distinct live window must be bound in the current document. Multiple windows
or ambiguous parents fail explicitly instead of selecting whichever opened last.
Unknown target uids fail without falling back to a different renderer.

**`open <name>` must exist unless you say `--create`.** Opening a name that is not in the
server's workspace is how a document gets *created* in Field, so the server answers with a
snapshot either way — and `open` used to look identical whether it had loaded `alife_walls`
or invented it. Since 2026-09-28 the `open` command refuses a name the server did not have
(and fails closed against a server too old to report `documentExisted`); pass
`open <name> --create` when a new document is what you want. `--must-exist` is still
accepted and now means the default. The results still carry `documentExisted` and
`attachedToLiveDocument` (both answered by the server) and, for a `--create` that invented
one, `createdNewDocument: true` plus a stderr line. The top-level `--document <name>` flag
still creates, because automation that spans invocations relies on it; it prints the same
stderr line when it does. The MCP bridge's `field_open_document` has the same default and a
`create` argument.

Both changes, with their populations and their traps:
[`AGENT_SURFACE_TRUTHFULNESS.md`](./AGENT_SURFACE_TRUTHFULNESS.md).

`run-jvm-main <entrypoint> [--arg VALUE]... [--timeout N] [--include-command]`.
**An argument value that begins with a dash must use `--arg=VALUE`**, not
`--arg VALUE`: argparse refuses a separate value starting with a dash and dies with
`argument --arg: expected one argument`, which reads as though the flag itself were
wrong. So `--arg=--verbose`, but `--arg /some/path`. The same rule is why
`run_field_tests.py` emits `=` only for dash-leading values — see the comment on
`build_backdoor_command` there.

One more shape worth stating, since `--json` output invites it: **each `--command`
prints its own JSON object**, concatenated with no enclosing array. `json.load` on the
whole stream raises `Extra data`. Decode it as a sequence with
`json.JSONDecoder().raw_decode` in a loop, or pass one `--command` per invocation.

## Session and execution contracts

- Scratch is the default and is scoped to one `field_backdoor.py` process.
  Put dependent commands in the same invocation: reusing `--port` in a later
  process starts a different scratch document, even when it reconnects to the
  same Field server. For automation state that must span invocations, use
  `--document <name>` relative to the default automation workspace. For a real saved
  document, always pair `--workspace <parent-directory>` with the relative
  `--document <name>`; neither an absolute document path nor the existing server's
  launcher state supplies that pairing.
- A box reference not present in the attached document is an error. In
  particular, never reuse a uid printed by an earlier scratch invocation.
- `@last` means the most recently created box in this backdoor session.
- `run <uid>` and `launch <uid>` use the box's stored selected string property (`code` by default)
  when neither `--value` nor `--file` is supplied. `--property` also works for custom live-coding
  properties omitted from the compact snapshot; the backdoor fetches their actual value rather than
  silently executing an empty string. The flags replace the selected stored property only when present.
- `launch-batch <uid> <uid> [...] [--property name] [--strand name]` launches the
  listed boxes as one ordered chord using each box's stored property. Its result waits
  for every box's pending queue signal; those signals do not imply persistent stripes.
- `run` waits for one execution to finish. `launch` starts the running form and
  is required for loops such as `start(renderer::run)`; stop it with `stop`.
- `wait-running <uid> [--state running|stopped] [--settle seconds]` distinguishes
  a persistent animation stripe from the transient signal that acknowledges a launch.
- `action <uid> <action-id> [--property name]` invokes an action already exposed by
  that box, such as `moduleHere_reload`; it does not implement a separate reload path.
- `connect <parent> <child> [...]` makes every later uid a child of the first.
- Each invocation writes a unique JSONL session log under
  `.field-backdoor/logs/`; a server it launches gets a sibling
  `.managed-server.log`. These are disposable diagnostic artifacts. A session log is capped at
  16 MiB and the directory is pruned toward 256 MiB by default, configurable with
  `AFIELD_BACKDOOR_LOG_MAX_BYTES` and `AFIELD_BACKDOOR_LOG_TOTAL_MAX_BYTES`; reaching either budget
  drops diagnostics rather than interrupting the websocket receiver or the work it is observing.

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

An empty `output`/`peer-output` packet with `kind=error` and `line=-1` is Field's
wire representation for *clearing* an editor error. The backdoor tracks it as state
removal: it is neither returned as ordinary output nor allowed to abort `wait-output`
or `wait-file`. A non-empty error at line -1 remains a real failure.

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

### You cannot ask a subcommand for its own usage

Every subparser is built with `add_help=False`, so `native-screenshot --help` does
**not** print that command's arguments. It prints the *top level* usage — the bare list
of command names — and then an error that reads as though your arguments were wrong.
There is no `-h` either
(reproducer: `test_field_backdoor.py#def test_a_subcommand_cannot_report_its_own_usage`).
The table below is the substitute; `build_command_parser()` in `tools/field_backdoor.py` is the
authority behind it, and `tools/test_field_backdoor.py::CaptureSignatureDocTests` fails if the two
disagree.

### Capture command signatures

**The output path is a positional argument on every one of these — there is no
`--output`, `--out`, or `--path` flag.** Where the box goes differs by family, and that
asymmetry is the other easy mistake: the `-window` commands take the box through
`--uid`, while `-box` and `-editor` take it as the *first positional*.

<!-- BEGIN CAPTURE SIGNATURES (checked by tools/test_field_backdoor.py) -->
| command | positional arguments | notable options |
| --- | --- | --- |
| `screenshot-window` | `path` | `--uid --property --with-editor --open-outputs --expect-outputs --no-pan --wait-ms --preserve-selection --include-notifications --timeout` |
| `screenshot-editor` | `uid path` | `--property --padding --open-outputs --expect-outputs --no-pan --timeout` |
| `screenshot-box` | `uid path` | `--padding --open-outputs --no-open-outputs --expect-outputs --no-pan --preserve-selection --include-notifications --timeout` |
| `native-screenshot` | `path` | `--uid --wait-frames --timeout` |
| `wait-native-renderer` | `[uid]` | `--frames --timeout` |
| `record-window` | `path` | `--uid --property --with-editor --open-outputs --expect-outputs --no-pan --duration --fps --frame-synchronous --restart-target --timeout` |
| `record-editor` | `uid path` | `--property --padding --open-outputs --expect-outputs --no-pan --duration --fps --frame-synchronous --restart-target --timeout` |
| `record-box` | `uid path` | `--property --padding --no-pan --duration --fps --frame-synchronous --restart-target --timeout` |
<!-- END CAPTURE SIGNATURES -->

```bash
--command 'native-screenshot /tmp/frame.png --wait-frames 40'   # native renderer window
--command 'screenshot-box @last /tmp/box.png'                    # one box, uid first
--command 'screenshot-window /tmp/all.png --uid @last'           # whole viewport, box via --uid
```

What each family actually captures:

- `screenshot-window`, `record-window`: full browser viewport; optionally
  select a box, show its editor, and open outputs.
- `screenshot-editor`, `record-editor`: the visible Monaco pane. Call
  `editor-open` before execution when inline editor visualizers must exist.
- `screenshot-box`, `record-box`: one box and its disclosures.
- `native-screenshot`: the native renderer window rather than browser DOM. Needs a
  live native renderer, so `wait-native-renderer <uid> --frames 3` first.
- `page-eval`: JSON-serializable DOM probes. Use `--file` for anything with
  meaningful quoting; `--body` needs an explicit JavaScript `return` (without one the
  result carries a `warnings` entry and a stderr line, since it will be null).

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
equal output pixel dimensions on a high-DPI browser; screenshot results therefore carry
`imagePixelWidth` / `imagePixelHeight` (read from the PNG) and, for browser captures,
the page's `devicePixelRatio`. For a blank iframe, inspect both the iframe and its parent
disclosure, including its intrinsic-size data.

**Captures say when they cannot show a run.** The backdoor records, per box, whether
Playwright was attached to the current document and whether `editor-open` had been done
when that box last ran (`run`, `launch`, `continue`, `launch-batch`). A `screenshot-*` or
`record-*` of a box that ran before Playwright attached — or, for editor captures, before
`editor-open` — carries `captureWarnings` in its result and repeats them on stderr. It is a
warning rather than an error (the server may still fail the capture on its own, as it does
for an output area with no visible content), and a `record-*` with `--restart-target` is
exempt because relaunching regenerates the output. Boxes executed by `macro run` are not
tracked.

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

# Browser/Monaco regressions (port 9490 and a 30 s per-command timeout are the defaults)
uv run --project . --group backdoor python tools/run_ui_regressions.py

# Full docs recapture (FIELD_DOCS_RUNNER_PORT defaults to the first free port from 9477)
FIELD_DOCS_RUNNER_MODE=always \
  uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

`run_field_tests.py` reads per-test timeouts from `FIELD_TEST.timeoutSec`; it
does not accept the backdoor's `--timeout` option, and says so if given one. Use `--id` for a focused UI
regression. UI artifacts live under
`build/ui-regressions/artifacts/<test-id>/`.

If a timed-out snippet leaves later liveness checks wedged, restart the
backdoor-owned Field on a fresh port before treating subsequent failures as
independent defects.

# Shared Field live-coding from an external Codex task

When the user is actively watching and editing a Field document, an external Codex task can productively live-code alongside them through `tools/field_backdoor.py`. This is a viable collaboration mode, not merely a test technique.

- Coordinate explicitly around the user's visible Field instance and real document. In this mode, attaching to the developer session on port 9092 can be intentional; the normal advice to choose an isolated high port still applies to unattended verification or whenever ownership is uncertain.
- Use the backdoor for semantic operations (`set`, `launch`, `stop`, output waits) while the user supplies immediate visual judgment from the native renderer. Source edits, focused compilation, document edits, and runtime feedback can form one tight loop.
- Preserve live-coding semantics: `run` is one-shot, while `launch` establishes the animated execution and running stripe. If rendering depends on a window or renderer animation loop, make sure that owning box is actually launched. Re-launching boxes should be treated as safe and idempotent unless the box documents otherwise.
- Distinguish persistent animation boxes from setup boxes. A setup box may finish and lose its stripe after installing render modules, callbacks, stereo bindings, or RTT hooks; that is healthy if a launched renderer loop continues servicing them. Verify this with observable telemetry or rendered output rather than stripe presence alone.
- Re-launch after source or document changes instead of assuming old runtime objects picked them up. Wait for concrete output from the live box (for example frame/decode telemetry), and let the user confirm the actual image.
- Do not mistake Field's error-clear wire packet for a new failure: an empty `output`/`peer-output` with `kind = error` and `line = -1` means "clear the error for this property." Automation should remove that active per-property error and keep waiting; a non-empty error at line -1 is still a real failure.
- Live shader work fits this workflow especially well: seed built-in GLSL into editable box properties using the established `moduleHere`/`simplePLYSupport` macro pattern, then rely on Field's ordinary shader reload action. This lets the built-in implementation become a starting point while retaining reflected uniforms, caller-supplied parameters, RTT routing, and hot reload.
- Treat the collaboration as a shared performance state: do not stop the visible server/window at handoff unless asked, do not take over an unidentified session, and report which boxes are genuinely launched versus merely configured.

Validated on the `dust_two` document: the renderer/window box was launched, the Gaussian-splat module rendered into the selected RTT, stereo camera matrices were bound, playback telemetry advanced, and editing/reloading the generated splat shader worked while the user watched the result.

## Source and execution provenance

`help` and `help <command>` return command-language help without exiting the session.
Launch/run/continue now wait for a correlated Kotlin setup verdict, including its source
revision. They fetch current live source instead of trusting an earlier snapshot. A
successful setup still requires a rendered frame or later telemetry to verify animation.
For revision-safe editing, shared AI4 tools, reset, export and capture workflows, see
[Live graphics workflow](light_infrastructure/AGENT_WORKFLOW.md).
