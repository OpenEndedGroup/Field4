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
- `field_screenshot_box` — render a box's output as an image.

ai4 registry tools (discovered dynamically once attached; the same registry the in-app
OpenAI harness uses — see [`SEMANTIC_RETRIEVAL.md`](./SEMANTIC_RETRIEVAL.md)). Flattened
into the `field_*` namespace, currently **Tier-1** only:

- `field_semantic_retrieval_search` / `_status` / `_rebuild`
- `field_scratchpad_read` / `_update`, `field_checklist_read` / `_update`

Tier-2 ai4 tools (CompileKotlin, EvaluateKotlin, ReloadShaders, CodeUse\*, …) are gated
off until `installAi4()` is refactored to expose a shared context factory; the registry
gates hide any tool whose provider is not wired, so they simply do not appear yet.

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
