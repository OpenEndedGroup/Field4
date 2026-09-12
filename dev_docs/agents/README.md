# Agents & Field — the automation path

This directory documents how an agent drives Field: run code, capture output and
errors, take screenshots and video, build and save documents, and read and rewrite the
macros users have saved. It is the map; the linked docs are the detail.

The canonical model:

- **`OperationTranscript`** is the semantic automation layer — a YAML language for
  building, editing, launching, and saving documents. This is what you reach for to
  *do things* to a document.
- **The agent backdoor** (`tools/field_backdoor.py`) is the transport and capture
  harness — server lifecycle, output/error capture, Playwright, screenshots, video,
  JVM smoke. It carries transcripts and takes the pictures.

Two hard boundaries keep that split clean:

- The backdoor keeps its screenshot/video/Playwright/JVM-main support — those are not
  transcript responsibilities and stay in the harness.
- The backdoor does **not** grow a second durable operation language. Semantic mutations
  belong in `OperationTranscript`; the [`FIELD_MCP.md`](./FIELD_MCP.md) bridge is
  deliberately thin (it translates MCP calls into the same backdoor/transcript messages,
  not a new op vocabulary).

The repo-wide entry point is [`../../AGENTS.md`](../../AGENTS.md); this folder is the
detailed reference it points into.

## I want to…

| Goal | Read | In one line |
|---|---|---|
| **Run code and see its output/errors** | [`OPERATION_TRANSCRIPT.md`](./OPERATION_TRANSCRIPT.md) → [`FIELD_BACKDOOR.md`](./FIELD_BACKDOOR.md) | `execute` awaits; Kotlin-only launch groups use the awaited batch path; failures are returned explicitly |
| **Take a screenshot or record a video** | [`FIELD_BACKDOOR.md`](./FIELD_BACKDOOR.md) | `screenshot-*` / `native-screenshot` / `record-*` with `--with-playwright` |
| **Collect errors deterministically** | [`OPERATION_TRANSCRIPT.md`](./OPERATION_TRANSCRIPT.md#observability) | read `success` / `errors` / `output` from the run result |
| **Build a document from nothing** | [`OPERATION_TRANSCRIPT.md`](./OPERATION_TRANSCRIPT.md) | `create` + `connect` + `launch` on a `--scratch` doc |
| **Save what you built as a document** | [`OPERATION_TRANSCRIPT.md`](./OPERATION_TRANSCRIPT.md#save) | the `save` op writes a reopenable `metadata.edn` |
| **Drive native `afield.four` graphics** | [`OPERATION_TRANSCRIPT.md`](./OPERATION_TRANSCRIPT.md#worked-example-b--afieldfour-vulkan-renderer--compute--draw), [`transcripts/`](./transcripts/) | launch a renderer parent, connect children beneath it |
| **Write `afield.four` graphics code** | [`canonical_graphics/LESSONS.md`](../../public/docs/assets/canonical_graphics/LESSONS.md), [`afield_four_graphics.md`](../../public/docs/afield_four_graphics.md) | ~33 worked lessons against a live renderer; adapt one rather than inventing a call sequence |
| **Understand a macro a user saved** | [`OPERATION_TRANSCRIPT.md`](./OPERATION_TRANSCRIPT.md#reading-and-rewriting-a-users-saved-macros) | `macro list` / `macro read <name>` |
| **Rewrite a macro for your own ends** | [`OPERATION_TRANSCRIPT.md`](./OPERATION_TRANSCRIPT.md#reading-and-rewriting-a-users-saved-macros) | edit the YAML, `macro write`, `macro run` |
| **Copy-paste a working example** | [`transcripts/README.md`](./transcripts/README.md) | curated, runnable transcript corpus |
| **Talk to Field from Claude Code/Desktop** | [`FIELD_MCP.md`](./FIELD_MCP.md) | local stdio MCP server over the backdoor; `uv sync --group backdoor` then approve the `field` server |
| **Capture examples for the docs site** | [`../DOCS_RUNNER_GPU_CAPTURES.md`](../DOCS_RUNNER_GPU_CAPTURES.md) | `diffstatic_runner` fences, incl. GPU/compute/probe |
| **Check you are driving the right workspace** | [`AGENT_SURFACE_TRUTHFULNESS.md`](./AGENT_SURFACE_TRUTHFULNESS.md) | verify `serverWorkspace`, never `requestedWorkspace` — of the old four-field check, three fields could disagree with you and the one you were told to trust could not |

## The core docs

- **[`OPERATION_TRANSCRIPT.md`](./OPERATION_TRANSCRIPT.md)** — the canonical automation
  format: the full op vocabulary, the loud-`launch` contract, observability, the
  build→run→capture→save→reopen lifecycle, and worked canvas + Vulkan examples. **Start here.**
- **[`FIELD_BACKDOOR.md`](./FIELD_BACKDOOR.md)** — the backdoor CLI: server lifecycle,
  batch/interactive modes, `--graphics-context`, screenshots, video, `page-eval`, logs.
- **[`AGENT_QUICK_REF.md`](./AGENT_QUICK_REF.md)** — a one-page cheat-sheet of the most-used commands.
- **[`AGENT_SURFACE_TRUTHFULNESS.md`](./AGENT_SURFACE_TRUTHFULNESS.md)** — which `status` fields are facts about the server and which are your own arguments echoed back, why an attached server's workspace cannot be changed, the three causes of `boxes: []`, and how the smoke runner's JVM reaper now prints its attribution evidence.
- **[`FIELD_MCP.md`](./FIELD_MCP.md)** — expose Field to Claude Code/Desktop as a local stdio MCP server (`tools/field_mcp.py` + `.mcp.json`), including the `uv sync --group backdoor` prerequisite.
- **[`transcripts/README.md`](./transcripts/README.md)** — the runnable example corpus.

## Supporting infrastructure

- **[`CODE_USE_TELEMETRY.md`](./CODE_USE_TELEMETRY.md)** — which snippets compiled/ran, with what result.
- **[`SEMANTIC_RETRIEVAL.md`](./SEMANTIC_RETRIEVAL.md)** — natural-language code/doc discovery.
- **[`TRANSCRIPT_SELF_IMPROVEMENT_LOOPS.md`](./TRANSCRIPT_SELF_IMPROVEMENT_LOOPS.md)** — turning real runs into reviewed Field improvements.

> The manual, on-disk way to edit a workspace — writing `boxid_code_.kt` sidecar files
> and letting Field's `FileWatchManager` pick them up — is a *file-editing* convenience,
> not the automation path. It's documented as a component in
> [`../FileWatchManager.md`](../FileWatchManager.md) and
> [`../workspace/`](../workspace/). To *drive* Field, use the transcript/backdoor path above.
