# Smoke Test Annotation Strategy

Canonical testing map: `dev_docs/testing/TESTING_STRATEGY.md`. This document is
the detailed reference for `FIELD_TEST` annotations and the smoke catalog.

This document defines a pragmatic way to mark executable examples and smoke tests so they can be:

- discovered procedurally
- run by one orchestrator
- split across fast JVM-only checks and richer Field/backdoor checks
- reused later for docs and visual capture

The important constraint is that the repository already contains several different kinds of "example code":

- plain JVM smoke tests such as `*SmokeTest.kt` and some `*Test.kt`
- larger example files under `src/jvmMain/kotlin/afield/examples/**`
- Field-facing snippets in docs and notes
- template and metadata examples

Those should not all be forced into one runtime shape. The catalog should be unified, but the executor can vary per test.

## Principles

1. Discovery must be explicit, not heuristic.
2. The annotation format should be grep-friendly.
3. The first version should avoid AST parsing.
4. The test id should be stable and human-readable.
5. The catalog should support multiple execution backends.
6. Only opt-in examples become runnable smoke tests.

This means:

- naming conventions such as `*SmokeTest.kt` are useful hints, but not authoritative
- `rg` over explicit annotation markers is the source of truth
- the runner dispatches by `runner`, not by filename

## Recommended Annotation Format

Use a short machine-readable block comment with a single anchor token: `FIELD_TEST`.

For Kotlin files:

```kotlin
/*
FIELD_TEST
id: stdlib.graph-connectivity.smoke
runner: jvm-main
entrypoint: afield.examples.stdlib.GraphConnectivitySmokeTest
tags: [smoke, stdlib, graph]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "smoke tests passed"
*/
object GraphConnectivitySmokeTest {
    ...
}
```

For Markdown docs, use an HTML comment immediately before the code fence:

````html
<!--
FIELD_TEST
id: docs.kotlin-overview.hello-world
runner: backdoor-box
tags: [docs, kotlin, hello]
timeoutSec: 20
expect:
  outputContains:
    - "hello world"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/hello-world.png" capture="box"}
println("hello world")
```
````

For public MkDocs pages, prefer `diffstatic {data-lang=...}` for visible static examples and `diffstatic_runner {data-lang=...}` for executable/captured examples. Avoid plain public `kotlin`, `bash`, or `text` fences unless the page is intentionally demonstrating raw Markdown.

This gives us:

- one marker for `rg`
- one parser shape for Kotlin and Markdown
- enough room for richer metadata later

## Runner Types

The catalog should support these runners from day one:

### `jvm-main`

For plain JVM smoke tests that already have a `main()` or a clean object entrypoint.

Required fields:

- `entrypoint`

Example:

```yaml
runner: jvm-main
entrypoint: afield.examples.graphics.FLineInstructionTrajectoryTrackerSmokeTest
```

This is for tests that do not need Field UI state, documents, or Playwright.

### `backdoor-box`

For Kotlin snippets that should run inside a scratch Field box through `tools/field_backdoor.py`.

Typical use cases:

- docs snippets with `println(...)`
- examples that use `display(...)`
- shader/code-property smoke tests

Expected execution:

1. attach or cold-start Field
2. open scratch
3. create a box
4. inject snippet
5. run it
6. collect JSON events
7. optionally capture screenshots

Suggested fields:

- `property` default `code`
- `withPlaywright` default `false`
- `capture` optional
- `outputChannel` optional

### `backdoor-document`

For real saved documents or templates where the smoke test means "open this document and run a known box".

Suggested fields:

- `document`
- `box`
- `action` such as `run` or `launch`
- `withPlaywright`
- `capture`

Use this for example workspaces in `metadata_store/` or curated docs projects.

## Expectation Schema

Keep expectations small and additive.

Recommended common fields:

- `exitCode`
- `stdoutContains`
- `stdoutNotContains`
- `outputContains`
- `outputNotContains`
- `eventKinds`
- `screenshot`

Examples:

```yaml
expect:
  exitCode: 0
  stdoutContains:
    - "smoke tests passed"
```

```yaml
expect:
  outputContains:
    - "hello world"
  eventKinds:
    - output
```

```yaml
expect:
  screenshot:
    kind: box
    openOutputs: true
```

```yaml
expect:
  screenshot:
    kind: editor
    padding: 10
```

Do not make the v1 expectation language too clever. Substring checks plus "did the process succeed" are enough to start.

## Discovery Rule

The authoritative discovery query should be something like:

```bash
rg -l 'FIELD_TEST' src/jvmMain/kotlin/afield/examples dev_docs public/docs metadata_store
```

The runner then:

1. finds files containing `FIELD_TEST`
2. parses each annotation block
3. resolves the code or entrypoint associated with that block
4. emits a generated catalog, for example `build/field-tests/catalog.json`

The annotation marker is the contract. The generated catalog is a build artifact.

The phase-1 discovery script lives at `tools/discover_field_tests.py`.
The phase-1 runner lives at `tools/run_field_tests.py`.

Examples:

```bash
uv run --project . --group backdoor python tools/discover_field_tests.py --pretty
uv run --project . --group backdoor python tools/discover_field_tests.py --output build/field-tests/catalog.json --pretty
uv run --project . --group backdoor python tools/run_field_tests.py --refresh-catalog
```

## What To Annotate First

Start with the low-ambiguity, already-runnable cases.

### Phase 1: Existing JVM smoke tests

These are already close to runnable:

- `src/jvmMain/kotlin/afield/examples/rectsl/RectSLExamples.kt`
- `src/jvmMain/kotlin/afield/examples/rectsl/RectSLWorkout.kt`
- `src/jvmMain/kotlin/afield/examples/rectsl/RectSLEditorExtensionWorkout.kt`
- `src/jvmMain/kotlin/afield/examples/stdlib/GraphConnectivitySmokeTest.kt`
- `src/jvmMain/kotlin/afield/examples/stdlib/SmileStdlibSmokeTest.kt`
- `src/jvmMain/kotlin/afield/examples/graphics/FLineInstructionTrajectoryTrackerSmokeTest.kt`
- `src/jvmMain/kotlin/afield/examples/graphics/PolynomialTreeFittingSmokeTest.kt`
- `src/jvmMain/kotlin/afield/examples/ExportedAnnotationTest.kt`
- a few non-example but similar files such as `CompiledSnippetTest.kt` and `PlyLoaderUnitTest.kt`

These should use `runner: jvm-main`.

RectSL is the model for keeping user docs clean while preserving executable coverage. The public `rectsl_examples.md` page should show the RectSL language examples, not a visible backdoor command. The executable coverage lives in the source `FIELD_TEST` blocks:

- `rectsl.examples.smoke` compiles the public-facing example programs from `RectSLExamples`.
- `rectsl.language.workout` exercises the larger RectSL parser/geometry/material/operator surface from `RectSLWorkout`.
- `rectsl.editor-extension.workout` checks the RectSL editor/language-extension support.

Reason:

- lowest migration cost
- fast feedback
- no backdoor coupling required
- immediately proves the catalog/discovery system works

### Phase 2: Curated Field snippets

Next, annotate examples that genuinely benefit from the backdoor:

- snippets that use `display(...)`
- examples that should prove code compiles in a real Field box
- examples that produce useful screenshots for docs

These should use `runner: backdoor-box`.

Important: do not try to infer runnable snippets from arbitrary functions like `fun Box.exampleSlideShow()`.

Instead, add a small explicit harness snippet next to the example, for example:

```kotlin
/*
FIELD_TEST
id: slideshow.simple-interactive.snippet
runner: backdoor-box
withPlaywright: true
expect:
  screenshot:
    kind: box
*/
private val SIMPLE_INTERACTIVE_EXAMPLE_SNIPPET = """
simpleInteractiveExample()
""".trimIndent()
```

That keeps the runnable test surface explicit and avoids fragile extraction from arbitrary source structure.

When a `backdoor-box` snippet needs a live `Renderer`/`Window` context from a parent box, add:

```yaml
graphicsContext: true
```

The runner passes `--graphics-context` to `tools/field_backdoor.py`. That mode creates and launches a transient parent box called `renderer`, waits briefly for the render loop to start, and automatically connects later transient test boxes underneath it before executing the snippet. Use this when the snippet needs inherited graphics context such as a valid `renderer` variable for testing `afield.four`.

### Phase 3: Docs fences

After the pipeline works for Kotlin source, annotate only selected Markdown fences.

Rules:

- opt-in only
- one `FIELD_TEST` block per runnable docs snippet
- prefer snippets that are stable, representative, and useful for docs screenshots
- do not add visible "verification" or "smoke command" sections to public docs pages just to expose internal coverage

Do not attempt "every fenced Kotlin block in docs is executable". That will create noise and constant breakage.

## Test ID Convention

Use dotted ids:

```text
<area>.<topic>.<name>
```

Examples:

- `stdlib.graph-connectivity.smoke`
- `graphics.fline-tracker.smoke`
- `docs.kotlin-overview.hello-world`
- `workspace.agent-demo.example-shader`

This makes filtering straightforward:

- `--tag smoke`
- `--id-prefix stdlib.`
- `--id-prefix docs.`

## Minimal Metadata To Require

Require only these fields at first:

- `id`
- `runner`
- one runner-specific location field:
  - `entrypoint`
  - or inline snippet/code fence association

Everything else should be optional.

If the metadata burden is too high, people will not keep it updated.

## How The Backdoor Fits

The backdoor should be treated as one execution backend inside a broader smoke-test runner.

That runner can eventually do:

- `jvm-main` by asking `tools/field_backdoor.py --json` to execute the annotated entrypoint and return stdout/stderr/exit status
- `backdoor-box` by driving `tools/field_backdoor.py --json`
- `backdoor-document` by opening named docs and running named boxes

This keeps the system coherent without forcing algorithm-only tests through a browser/document runtime.

## Proposed Migration Order

1. Annotate all current `runTests()`/`main()` smoke-test objects.
2. Build a discovery script that emits a catalog.
3. Build a runner that supports `jvm-main`.
4. Add `backdoor-box` support for a few curated snippets.
5. Add screenshot expectations only after plain output expectations are stable.
6. Add selected docs fences last.

## Non-Goals For V1

Do not do these yet:

- automatic extraction of arbitrary example functions
- automatic execution of every Markdown code fence
- visual diffing as a hard requirement
- AST-level Kotlin parsing
- reflection-based classpath scanning as the source of truth

Those can come later if the explicit annotation approach becomes too limiting.

## Practical Recommendation

The first implementation should be boring:

- comment-block annotations
- `rg` discovery
- YAML parsing
- generated catalog
- one runner with two backends: `jvm-main` and `backdoor-box`

That is enough to turn "example code floating around the repo" into something we can actually inventory, execute, and later reuse for documentation capture.
