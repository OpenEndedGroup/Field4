# Agent Backdoor Cleanup Plan

Status: active cleanup, July 2026.

## Direction

`OperationTranscript` is the canonical semantic automation layer for Field. Agent-facing tools should use it for document operations such as edits, moves, connections, execution, launch, stop, macro save, macro run, and undo.

The agent backdoor remains the low-level process and inspection harness. It should own server attach/startup, scratch document setup, snapshots, Playwright attachment, DOM input, screenshots, video capture, JVM entrypoint smoke runs, output collection, and time/frame waits. It should not grow a second durable operation language.

MCP server experiments are out of Field for now. They can come back later after the transcript/backdoor split is clean.

## Current Stack

- `tools/discover_field_tests.py` discovers `FIELD_TEST` snippets.
- `tools/run_field_tests.py` turns discovered tests into backdoor commands.
- `tools/field_backdoor.py` owns agent session startup, websocket RPCs, Playwright/capture commands, and compatibility operation replay.
- `src/jvmMain/kotlin/afield/agent_backdoor.kt` exposes low-level `agent-*` handlers.
- `src/jvmMain/kotlin/afield/workspace/operation_transcript.kt` records and applies semantic operations and macros.
- `Server.kt` exposes `macro-transcript-command` and records normal UI edits/moves into `OperationTranscript`.
- AI4 already uses `OperationTranscript` for macro list/read/write/run.

## Cleanup Decisions

1. Keep the field test discovery and runner stack. It is a useful ladder rather than a duplicate implementation.
2. Keep the agent backdoor for low-level lifecycle, capture, Playwright, and JVM process probes.
3. Make transcript macros the preferred backdoor automation path.
4. Treat `replay-operations` / `play-operations` in `tools/field_backdoor.py` as a temporary compatibility surface until tests and docs can migrate to transcript macros.
5. Remove Field-side MCP server attempts and stale Gradle launchers now.
6. Leave personal scratch code outside the shared build contract unless it blocks compilation.

## Prior Dead-Code Audit Findings To Fold In

Immediate removals:

- `src/jvmMain/kotlin/afield/ai/deprecated/SimpleMCP.kt`: abandoned MCP server.
- `src/jvmMain/kotlin/afield/ai/deprecated/SimpleMCP2.kt`: MCP server experiment still wired by stale Gradle task despite living under `deprecated`.
- `src/jvmMain/kotlin/afield/ai/deprecated/Hello.kt`: abandoned AI experiment with unsafe credential material.
- `src/jvmMain/kotlin/afield/KotlinJS.kt`: dead JVM-side placeholder.
- Gradle tasks `runai`, `runai2`, and `runmcp`: stale or personal launchers in the shared build file.

Deferred audit items:

- `jsChatMain` / guide chat route wiring was stale in the shared server route. The broken static route and source roots have been removed; older image-upload docs are marked stale until chat/image upload is re-audited.
- Offline viewer assets exist in more than one place. Collapse only after confirming the current distribution path.
- Monaco/font/static asset duplication likely exists between dev docs, common resources, JS resources, and public output. Defer until the asset pipeline is mapped.
- Kotlin completion and K1/K2 support contain duplicated logic. Treat that as a dedicated refactor, not part of the backdoor cleanup.
- Demo/static pages and empty placeholder files have been removed from shipped common resources where high-confidence: `foo.js` was deleted; manual/demo HTML moved to `dev_docs/`.

## Implementation Steps

1. Add a direct transcript macro command to `tools/field_backdoor.py`.
2. Update backdoor docs so agents prefer transcript macros for semantic automation.
3. Remove Field MCP server source and stale Gradle launcher tasks.
4. Add smoke coverage for macro write/run/undo through the backdoor.
5. Migrate any existing agent examples using `replay-operations` to transcript macro YAML.
6. Delete the compatibility replay executor after no docs/tests depend on it.
7. Ensure backdoor-created semantic mutations are recorded in `OperationTranscript` or routed through existing transcript-recording server handlers.

## Boundaries

Do not remove screenshot/video/Playwright/JVM-main support from the backdoor. These are not transcript responsibilities.

Do not reintroduce an MCP server until the canonical transcript automation path is stable and tested.
