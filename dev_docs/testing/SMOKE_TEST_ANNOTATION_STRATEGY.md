# `FIELD_TEST` annotation reference

`tools/discover_field_tests.py` discovers explicit `FIELD_TEST` comment blocks
and writes `build/field-tests/catalog.json`. `tools/run_field_tests.py` executes
that catalog through the backdoor and writes results, generated snippets, and
captures below `build/field-tests/`.

The scripts are the schema authority. This page records the stable authoring
contract without duplicating their implementation.

## Discovery roots and required fields

Default roots are exactly:

```text
src/jvmMain/kotlin/afield
dev_docs
public/docs
metadata_store
```

Every block requires a unique `id` and a `runner`. Supported runners are only
`jvm-main` and `backdoor-box`; `jvm-main` additionally requires `entrypoint`.
Discovery is marker-based, not filename-based or AST-based.

```kotlin
/*
FIELD_TEST
id: stdlib.graph-connectivity.smoke
runner: jvm-main
entrypoint: afield.examples.stdlib.GraphConnectivitySmokeTest
tags: [smoke, stdlib]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "smoke tests passed"
*/
```

The annotation parser accepts its small YAML-like mapping/list/scalar subset; it
is not a general YAML loader. Duplicate ids and malformed blocks are discovery
errors.

## Binding executable code

For `jvm-main`, `entrypoint` names the standalone JVM entrypoint and optional
`args` are forwarded as repeated `run-jvm-main --arg` values.

In Kotlin source, a `backdoor-box` block must be followed immediately by a
triple-quoted `val` or `var`; that string is the snippet. `trimIndent()` and
`trimMargin()` forms are recognized.

In Markdown, the next nonblank line must open a code fence. Ordinary fences use
their whole body. Diff fences select `after`, then `static`, then `before` unless
the annotation specifies `section`. Fabricate scene fences are replaced by the
registered renderer harness and require a source image path. This association is
syntactic and intentionally does not infer arbitrary example functions.

````html
<!--
FIELD_TEST
id: docs.hello
runner: backdoor-box
timeoutSec: 20
expect:
  outputContains: ["hello"]
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/hello.png" capture="box"}
println("hello")
```
````

## Backdoor-box execution fields

The runner creates a box and executes the generated snippet with these useful
optional fields:

- `property` (`code` by default), `boxName`, `x`, `y`, `w`, `h`;
- `execution`: `run` (default) or `launch`; launches are stopped after evidence
  collection;
- `document` or `scratch`;
- `withPlaywright`; a screenshot expectation also enables Playwright;
- `graphicsContext`: `true` or a mapping, which enables the transient renderer
  parent;
- `waitSec` (or `wait`) before file/screenshot evidence;
- `timeoutSec`, applied to the backdoor and individual operations.

For an editor screenshot, the runner sets the property, opens Monaco before
execution, and then captures. Other screenshot kinds execute first.

## Expectations actually evaluated

Common text/event keys are:

- `exitCode`;
- `stdoutContains`, `stdoutNotContains`, `stderrContains`,
  `stderrNotContains`;
- `outputContains`, `outputNotContains` across rendered backdoor events;
- `eventKinds`;
- `allowExecutionErrors` and `allowMissingOutput` escape hatches.

`expect.screenshot` may be `true` (box) or a mapping. Supported effective kinds
are `box`, `editor`, `window`, and `window-editor`, with options including
`padding`, `openOutputs`, `withEditor`, and `panIntoView`.

`expect.image` selects `path` or the screenshot artifact and supports simple
pixel evidence: `regionsDifferent`, `lumaComparisons`, and `regionLuma`. Rects
are `[x,y,w,h]`; values all within `0..1` are normalized, otherwise they are
pixels. These checks compare region means, not perceptual snapshots or a general
visual-diff baseline.

The runner also fails ordinary execution errors and missing expected output
unless explicitly allowed.

## Commands

```bash
uv run --project . --group backdoor python tools/discover_field_tests.py --pretty
uv run --project . --group backdoor python tools/run_field_tests.py \
  --refresh-catalog --port 9477
```

Selection flags are `--id`, `--tag`, and `--runner` (repeatable). There is no
`--id-prefix` and no top-level `--timeout`; duration belongs in `timeoutSec`.
`--no-prewarm` tells the runner to rely on a server already serving `--port`
rather than starting and retaining one for the run.

Platform `skipOn` entries are reported as skips. Keep opt-outs narrow and leave
the reason beside the owning annotation.

## Placement rule

Put runtime assertions near the source or example they protect. Use a browser UI
regression when Monaco/focus/panel interaction is the behavior, and use an
executable public-doc fence when the capture itself is user documentation. Do
not annotate every code fence: opt in only when the snippet has a stable,
explicit execution context and useful assertions.
