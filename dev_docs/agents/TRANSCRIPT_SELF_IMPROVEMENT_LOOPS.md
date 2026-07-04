# Transcript-Driven Self-Improvement Loops

This note describes the medium-term goal for Field's agent infrastructure: use real operation transcripts, docs captures, smoke-test runs, and user-facing failures as evidence for concrete improvements to Field.

The intent is not to make agents guess what users want from a pile of logs. The intent is to make Field's own operational history easier to inspect, summarize, test, and turn into small reviewed changes.

## Evidence Sources

Useful evidence should come from the paths that already exercise Field semantically:

- `OperationTranscript` YAML macros that create boxes, edit properties, connect graphs, launch code, stop code, and undo changes.
- Backdoor JSON runs that include command results, stdout/stderr, output events, timeout status, and screenshot/capture paths.
- MkDocs runner captures, especially examples using `graphics-context=true`, because those validate real renderer topology rather than pasted screenshots.
- `FIELD_TEST` catalog runs through `tools/run_field_tests.py`.
- User-reported workflows converted into small transcript examples under `dev_docs/agents/transcripts/`.

When possible, examples should preserve graph topology. For `afield.four`, that means launching a renderer parent, waiting until it is running, connecting child boxes beneath it, and resolving `Renderer` through `fromContext()` before async work starts.

## Improvement Loop

A good loop has five phases:

1. Capture a real workflow as a transcript or docs runner block.
2. Replay it in a fresh Field session with a non-default port.
3. Store enough structured output to explain what happened: commands, return status, emitted errors, output events, capture files, and relevant log paths.
4. Analyze recurring friction: missing context, slow waits, unclear errors, fragile screenshots, repeated setup boilerplate, or places where agents reach for browser automation instead of semantic Field operations.
5. Propose and build small improvements: new transcript verbs, better diagnostics, stronger docs-runner validation, smaller examples, or clearer public docs.

The loop should prefer improvements to Field over one-off transcript hacks. If three examples need the same workaround, the workaround probably belongs in the backdoor, runner, transcript event set, or Field runtime.

## What To Collect

For each replay or docs capture, keep the machine-readable facts close to the artifact:

- transcript name and digest;
- Field port and whether the server was managed by the runner;
- document name or scratch-session marker;
- operation names and stable box ids;
- box graph edges created by the transcript;
- command status, timeout status, and error previews;
- output events used as waits or assertions;
- screenshot/video paths and visual-validation result;
- relevant session log and managed-server log paths.

Do not store huge websocket payloads or full HTML/SVG blobs in long-lived logs. Store previews, paths, hashes, and enough metadata to rerun the scenario.

## Good Suggestions

An agent-generated improvement suggestion should be specific enough to review:

- name the transcript, docs page, or test that exposed the problem;
- state the observed failure or friction in Field terms;
- point to the smallest subsystem that should own the fix;
- include a replay command;
- include the expected post-fix behavior;
- say whether the change needs a smoke test, docs capture, or both.

Examples:

- A graphics docs block failed with `must pass in a value for context parameter afield.four.Renderer`; fix the transcript topology or the docs runner's `graphics-context=true` setup.
- Several captures used fixed sleeps only to wait for display output; add or use an event wait such as `wait-output`.
- A native screenshot was black even though the window existed; prefer Field's renderer-owned screenshot path and report renderer frame progress in the failure bundle.

## Guardrails

These loops should not silently rewrite user work. Agents should use scratch sessions or explicitly named test documents unless the user asks to operate on a real document.

Generated suggestions are not commits. They are evidence-backed proposals. A change should still pass the normal gates: focused diff review, Kotlin compile when relevant, Python syntax checks for runner/backdoor changes, and an appropriate MkDocs build mode.

The automation should make failure messages better, not merely longer. A useful failure says which operation failed, which box was running, what Field emitted, where the logs are, and what replay command reproduces it.

## Near-Term Backlog

- Add a compact replay summary file for every docs-runner capture.
- Add a small analyzer that groups failed captures by root cause: compile error, runtime error, missing context, timeout, visual validation, or missing output.
- Extend `OperationTranscript` replay with first-class assertion events where they remove ad hoc waits.
- Teach docs runner failures to include transcript names, graph edges, and output-event excerpts by default.
- Add a public-facing "how these screenshots are generated" page once the staging text settles.
