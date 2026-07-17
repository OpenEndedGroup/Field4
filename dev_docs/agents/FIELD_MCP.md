# Field as a local MCP server

`tools/field_mcp.py` exposes Field to MCP clients (Claude Code, Claude Desktop) as a
**local stdio MCP server**. It is a thin bridge, not a new automation surface: every
MCP `tools/call` is translated into the same WebSocket messages the agent backdoor
already speaks (`agent-snapshot`, `agent-open-document`, `macro-transcript-command`,
`agent-create-box`, `agent-box-screenshot`, and the ai4 tool registry). All semantics —
the operation-transcript op vocabulary, the awaited launch/settle barrier, output
capture — live in the JVM and are reused verbatim. See
[`OPERATION_TRANSCRIPT.md`](./OPERATION_TRANSCRIPT.md) and [`FIELD_BACKDOOR.md`](./FIELD_BACKDOOR.md).

The bridge talks to an **already-running** Field; it never launches the JVM. The point
is to converse about the document you already have open. If Field is not running (or has
no document open) tools return a friendly error telling you to open it.

## Prerequisite: the repo virtualenv

The server is launched as `.venv/bin/python3 tools/field_mcp.py` (see
[`.mcp.json`](../../.mcp.json)), so the repo virtualenv must exist with the `backdoor`
dependencies (`websockets`, `PyYAML`). Create it once from the repository root:

```bash
uv sync --group backdoor
```

If the `uv` command is missing, install it first (see <https://docs.astral.sh/uv/>),
e.g. `curl -LsSf https://astral.sh/uv/install.sh | sh` or `brew install uv`, then rerun
`uv sync --group backdoor`. Do not install the dependencies into an ad-hoc virtualenv;
`.venv` at the repo root is the interpreter every Field Python tool expects (see
[`FIELD_BACKDOOR.md`](./FIELD_BACKDOOR.md#uv-dependency-recipes)).

`tools/field_mcp.py` also self-heals: if it is started under an interpreter without
`websockets`, it re-execs into `.venv` (or `uv run`). Pointing `.mcp.json` directly at
`.venv/bin/python3` keeps the MCP stdout stream clean (pure JSON-RPC), so that is the
recommended form as long as the venv is present.

## Registering with Claude Code

The repo ships a project-scoped [`.mcp.json`](../../.mcp.json) with **relative paths only**
(`command: .venv/bin/python3`, `args: [tools/field_mcp.py]`) — Claude Code launches a
project MCP server with the project root as its working directory, so nothing
machine-specific is baked in and the file travels with the repo.

To activate it: **restart Claude Code in this repo and approve the `field` server** when
prompted (project `.mcp.json` servers require a one-time trust confirmation; clear it
with `claude mcp reset-project-choices`). Verify with `claude mcp list`.

To register manually instead (e.g. user scope, available in every project):

```bash
claude mcp add -s user -e PORT=9092 field -- "$(pwd)/.venv/bin/python3" "$(pwd)/tools/field_mcp.py"
```

For the classic **Claude Desktop** chat app, add an `mcpServers` entry to
`~/Library/Application Support/Claude/claude_desktop_config.json` (absolute paths there —
that app does not launch from the repo directory):

```json
{
  "mcpServers": {
    "field": {
      "command": "/ABSOLUTE/PATH/field4/.venv/bin/python3",
      "args": ["/ABSOLUTE/PATH/field4/tools/field_mcp.py"],
      "env": { "PORT": "9092" }
    }
  }
}
```

## Tools

Native bridge tools (always present):

- `field_status`, `field_list_open_documents`, `field_open_document` — connection and
  document targeting (auto-attaches to the single open document if unspecified).
- `field_snapshot` (full document; `include_code=false` for a graph overview),
  `field_read_box` — read the attached document.
- `field_create_box`, `field_run_transcript` (inline operation-transcript YAML),
  `field_write_macro` / `field_run_macro` / `field_list_macros` / `field_read_macro`,
  `field_transcript_page` — build/edit/run.
- `field_outline` — a token-efficient one-line-per-box orientation map of the document.
- `field_read_note` / `field_write_note` — read/write a box's persistent `notes` property.
- `field_screenshot_box` — render a box's output as an image.
- `field_debug_probe` — inspect a live variable's value at runtime (see
  [Runtime debug probe](#runtime-debug-probe) below).

ai4 registry tools (discovered dynamically once attached; the same registry the in-app
OpenAI harness uses — see [`SEMANTIC_RETRIEVAL.md`](./SEMANTIC_RETRIEVAL.md)). Flattened
into the `field_*` namespace, currently **Tier-1** only:

- `field_semantic_retrieval_search` / `_status` / `_rebuild`
- `field_scratchpad_read` / `_update`, `field_checklist_read` / `_update`

`CompileKotlin` and `EvaluateKotlin` are already wired into the backdoor tool context
(`Ai4BackdoorTools.buildContext` sets `compileKotlin`/`evaluateKotlinExpression`), so
they appear as `field_compile_kotlin` / `field_evaluate_kotlin_expression` once attached.
The remaining Tier-2 tools (`ReloadShaders`, `ListDefinitions`, `EditFile`, `CodeUse*`,
and `workspaceSymbolSearch`) stay `null` in that same `buildContext` call and are hidden
by the registry gate until `installAi4()` is refactored to expose a shared context factory.

## Runtime debug probe

`field_debug_probe` lets an agent **ask the running JVM what a variable's value is at
runtime, and get the answer as data.** It installs a *non-suspending* JDWP probe (via
the JDI debug helper) that watches one symbol at one source line and samples its value
each time that line executes. It never pauses Field's render/compute loop
(`SUSPEND_EVENT_THREAD`, resume immediately), so it is safe against a live session. The
result carries the observed `samples` (value text, type, thread, resolution) plus any
in-scope `scope` symbols — the same data the editor's debug-probe card shows, minus the
HTML.

Two targeting modes (give exactly one):

- **Source mode — anywhere in loaded `src/`.** Pass `file` (path or basename, e.g.
  `Server.kt` or `src/jvmMain/kotlin/afield/Server.kt`) and/or `class` (fully qualified,
  e.g. `afield.Server`), with `line` the real file line and `symbol` the variable. This
  is the "what is X here?" tool for framework code (line numbers illustrative):
  `{"file": "Server.kt", "line": 1761, "symbol": "now"}`.
- **Box mode — a running box's snippet.** Pass `box` (uid/name), `line` within the box
  `property` (default `code`), and `symbol`. Run the box first so its snippet is
  compiled and loaded.

Optional args: `max_hits` (default 16), `timeout_ms` (default 5000, how long to wait for
the line to execute), `thread` (only sample hits on one thread name), `note_box` (a box
uid/name — appends a one-line summary of the probe to that box's `notes`, so a debug
session leaves a readable trail in the document itself).

### Prerequisite: remote debugging (JDWP), which is opt-in

The probe attaches over JDWP, which is **off by default** — a plain `./gradlew runJvm`
opens no debug port. It turns on when `AFIELD_JDWP_PORT` is set, and **the backdoor
launcher sets it automatically**: any Field started by `tools/field_backdoor.py` (the
recommended way for agents to start Field — see [`FIELD_BACKDOOR.md`](./FIELD_BACKDOOR.md))
gets JDWP on port 5005. So if you launched Field through the backdoor, probes just work.

If you attach to a Field launched some other way, the probe returns a clear, actionable
`isError` ("This Field process was launched without remote debugging…") telling you to
relaunch through the backdoor or set `AFIELD_JDWP_PORT=5005` before `./gradlew runJvm`.
Relaunching is cheap — reopen the document and probe again.

Preconditions: the target class must be loaded (or load within `timeout_ms`), and the
running JVM must be built from the source whose line numbers you pass. Failure modes that
are genuine *probe outcomes* rather than setup errors (`timed_out_waiting_for_hit`,
`symbol_not_visible`, …) come back as data with an `errorHelp` hint, not an `isError`.

## Quick check

```bash
# Field must be running with a document open (e.g. via runJvm), then:
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  | .venv/bin/python3 tools/field_mcp.py --port 9092
```

`tools/list` should return the native tools plus the ai4 tools once attached. All
diagnostic logging goes to stderr; stdout carries only JSON-RPC frames.
