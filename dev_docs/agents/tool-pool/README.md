# Tool pool

A source library for ordinary Field `aiTool` provider boxes. AI4, MCP and direct Kotlin
calls continue to use the same document registry and execution path. Installing this
recipe adds pool operations through `aiTool`, not a second tool dispatcher.

Run [tools.kt](tools.kt) in a normal Field box. Its card shows curated versions and
this document's registered tools, attached checks and recent calls. AI4 can discover,
read, inspect, test and explicitly curate providers using the `pool.*` tools.

Each provider can attach `test(name, arguments) { result -> check(...) }` examples to
its tool definition. Tests invoke the actual parameter binder and action. They can
have real side effects: use a disposable document for mutating tools. The
[bounds provider](bounds.kt) is a small executable example with no external dependencies.

Use `testFailure(name, arguments, messageContains = "specific diagnostic")` when
the correct behavior is to refuse an input. Both thrown exceptions and structured
tool errors can satisfy it. A success or an unrelated error fails the example;
cancellation propagates. The expected text must be nonblank, so an unavailable
compiler cannot accidentally pass a test for rejecting invalid geometry.

Curated versions live locally under `~/.afield/tool-pool`; publishing here does not
publish to Git or the network. A version contains the complete provider source,
tool schemas, original checks and provenance. Reading a version never executes it.
Run its source in a destination provider box and test it there. Original-project
checks are evidence, not certification of the destination.

Calls and test outcomes are attached to the exact registered source hash under the
owning document's `.tool-evidence/` directory. Errors, timings and bounded arguments
and results survive a provider reload. Editing the provider clears its apparent pass.
Images remain in the existing image/transcript path; raw image payloads are not
copied into this diagnostic store. Arguments and results can contain private data.

`pool.note` appends observations, known issues and lessons to an exact curated
version, with an author, originating document and evidence references. `pool.read`
includes its ten most recent notes; `pool.notes` pages through the retained history.
Corrections are additional notes. Notes are stored separately from immutable source
and checks, so a lesson cannot silently change code or turn an earlier failure green.
The card displays those notes beside each curated version. The note tools themselves
are ordinary `aiTool` definitions in this recipe; installing them needs no new dispatcher.

For a repair cycle, see [Debugging and improving tools](DEBUGGING.md).

Source identity covers the provider box, not its full dependency closure. State,
external files, native libraries and other boxes are not bundled. Describe those
dependencies when curating and rerun checks in the destination. Empty suites remain
untested. This is a local trusted-source workflow, not an execution sandbox.

## Verification

Run `uv run --project . --group backdoor python tools/verify_tool_pool.py`.
It creates two fresh named documents, registers and tests a real provider, curates
it, imports its exact source into the other document, retests, records a deliberate
failure, reloads evidence and checks that an edit invalidates the earlier pass.
It also verifies versioned notes, source-hash refusal and pagination against an
isolated pool under its output directory, leaving the user's curated library alone.
`--port N --attach-existing` uses an explicitly chosen server without stopping it.
The report is `build/tool-pool-verification/evidence.json`.

The focused JVM suite is `./gradlew jvmTest --tests afield.ai.tools.ToolPoolTest`.
