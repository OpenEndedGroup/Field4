# Guard population hygiene

Field's build carries a lot of guards. This page is about the way they go wrong, which is not by
breaking. It is by staying green over a population that no longer resembles the one their name
describes.

The engineering journal records this being rediscovered from a new angle repeatedly. Each
rediscovery finds a genuine instance, fixes it, and does not leave behind the shape, so the next
pass starts from scratch. What follows is the shape, the checks that find it, and the measured
results of running those checks over the guard family on 2026-08-27 — including two places the
checks came back clean, which are worth recording for the same reason.

## The three shapes

### 1. A floor far below the population it floors

A guard asserts `count >= MIN`. `MIN` was measured on the day the guard landed and never moved. The
population grew.

`ProseNumeralContractTest.MIN_MARKED_NUMERALS` was 6, set against a founding measurement of 7. By
2026-08-27 the corpus held 13 markers across 6 pages, so seven markers could be removed — four of
the six guarded pages' worth — and the build stayed green. Both numbers were true the whole time.
The floor had simply stopped describing the corpus it sat under.

A floor is a statement about a ratio, not about a count. Write down the measurement and the margin
in the same comment, and the next reader can tell 12-against-13 from 6-against-13 without running
anything.

Corollary worth stating separately: **count the shape as well as the size.** Thirteen markers on one
page and thirteen across six pages are the same number and not the same coverage, and shedding a
whole page is cheaper than thinning six.

The related case is a floor that is not merely loose but **dominated** — another assertion in the
same class already guarantees more than it asks for, so it has no reachable failure.

`CitationContractTest.MIN_UNIQUE_ANCHORS` was 184 against a measured 403. That looks like an
ordinary loose floor. The sharper problem is that the per-page floors in `GUARDED_PAGES` sum to 327,
and `everyGuardedPageKeepsItsCitations` asserts every one of them, so the corpus is already
guaranteed at least 327 anchored citations. A corpus-wide floor of 184 unique anchors could only
bind if more than 143 of those went non-unique, and `MAX_FRAGILE = 0` forbids the worst of that
band. It was not a weak assertion so much as one that could not fire. Ratcheted to 360 on
2026-08-27, which puts it above the 327 sum, so it binds on the property it names — anchor
uniqueness — rather than on corpus volume that something else already covers.

Test for it: compare each floor against what the *other* assertions in the same class already
guarantee, not only against today's measurement.

### 2. A number printed but not asserted

A guard computes something, prints it into the build log, and moves on. The number looks like
evidence in CI output. Nothing fails when it goes to zero.

`ProseNumeralContractTest` computed `bidi` — how many of its derivations are pinned in both
directions — printed it, and asserted nothing about it. Two of thirteen were bidirectional, and
deleting the two `candidates` lambdas that made them so was a four-line edit with no red build
anywhere. The same file's own documentation argues at length that the backward direction is the only
one that catches a stale row.

The rule: if a number is worth printing, it is worth a floor near it. If it is not worth a floor,
say in the output that it is a measurement, the way
`CitationContractTest.unguardedCitationsAreCountedNotIgnored` does.

### 3. An undeclared task input

A Gradle test task reads a file that is on no classpath and in no declared input tree. Editing that
file changes what the guard should say and changes nothing Gradle can see, so the task reports
`UP-TO-DATE` and the previous run's verdict is served as though it were fresh.

Measured on 2026-08-27. `ProseNumeralContractTest` scrapes head-latency span names out of
`native/field-visionos/Field4Vision/Pose/PoseLatency.swift` and holds `public/docs/vision.md`'s
published "five" to that population. With a sixth span planted in the Swift file and nothing else
changed:

```text
> Task :citationGates UP-TO-DATE
BUILD SUCCESSFUL in 924ms
```

Exit code 0. Adding `inputs.files(fileTree("native") { include("**/*.swift") })` makes the identical
plant fail on the count.

**The exit code is not the signal — the `UP-TO-DATE` marker is.** A task that did not run and a task
that ran and passed both exit 0, and any A/B on input declarations that reads only the exit code is
testing the wrong thing with the mechanism under test.

Scope, so nobody spends a night fixing nine tasks that are already fine: this hazard belongs to
`Test`-typed tasks. The nine `JavaExec`/`Exec` gates `docsGates` depends on —
`checkCodebaseReference`, `checkKDocAttachment`, `checkSourcePartition`, `checkSourcePages`,
`checkAgentTranscripts`, `checkAgentTranscriptIndex`, `checkCompletionCorpusFixtures`,
`checkAgentDemos`, `checkPythonTools` — declare neither `inputs` nor `outputs`, so Gradle gives them
no up-to-date check and they re-run every time. Checked 2026-08-27. `citationGates` and `jvmTest`
are the two that can serve a cached verdict.

Two rules follow. Grep the guard's source for every `File(root, …)` it opens and check each against
the declared input set; anything outside `src/**/*.kt` wants a declaration, because the Kotlin
classpath is the only thing invalidating that task for free. And when adding a guard that reads a new
kind of file, declare the input in the same commit — the transcript-yaml corpus needed exactly this
on the day `DocumentedApiEvaluationTest` landed, because `dev_docs/**/*.md` does not match a `.yaml`.

## The measurement that keeps being got wrong: compiled is not run

The fourth instance is different in kind and worth its own section, because two separate passes at
it have produced a number nobody could act on.

`DemoTranscriptExecSmokeTest` sorts the transcript corpus into `EXECUTE`, `KOTLINJS` and
`COMPILE_ONLY`, and `AgentTranscriptIndexGuard` publishes the middle level as "compiled, not run".
Both are correct, and compiling a windowed transcript through the real K2 path still catches the
class of defect the harness was built for. The consequence nothing states is that an API a published
page teaches can be covered by a green gate that never calls it.

The obvious way to measure that is to ask which documented APIs appear only in `COMPILE_ONLY`
transcripts. That answer over-reports by about 3x, because a transcript is one of three places code
gets evaluated in this tree. Measured 2026-08-27 over camelCase call sites:

```text
camelCase APIs called by the transcript corpus         506
  also named as a call on a published page             421   <- the denominator
  reached by an EXECUTE or KOTLINJS transcript         407
  reached only by a COMPILE_ONLY transcript             14   <- the tempting number
  of those 14, evaluated by a smoke or a jvmTest         9
  evaluated by something, anything, in CI              416
  evaluated by nothing at all                            5   <- the actionable one
```

Those nine are the whole argument. They are APIs no `EXECUTE` transcript reaches and a smoke or a
`jvmTest` does, and a scan that stops at the transcript corpus reports every one of them as a
problem.

The nightly brief that commissioned this work made the same mistake in miniature. Its example was
`fromContext`: three transcripts demonstrate it, all three are `COMPILE_ONLY`, five published pages
teach it. `ContextSearchOrderLedgerSmokeTest.kt:84` evaluates `fromContext<AtomicInteger>()` in live
box Kotlin as the FIELD_TEST `examples.context.search-order-ledger`. The example was wrong and the
class of defect it described was not.

So the denominator is the union of three populations: `EXECUTE`/`KOTLINJS` transcripts, jvm-main
`FIELD_TEST` smokes, and `jvmTest` sources. `DocumentedApiEvaluationTest` computes it and holds the
remainder to a ledger read in both directions — undeclared entries fail forward, and a row whose API
has since acquired an evaluator fails backward.

Three false-evaluator traps showed up while building it, all of them the same defect one level down:

- `afield/onnx/Clip.kt` writes `clipEncode(` three times in its own KDoc, so the declaration site
  scored as its own evaluator. Comments have to be stripped, and string literals have to survive —
  every live-box smoke in this tree holds its box Kotlin in a `"""` constant, so stripping strings
  would discard the strongest evidence available.
- `DemoTranscriptExecSmokeTest.kt` mentions `clipEncode` in the comment explaining that it is
  deliberately not evaluated. The compile-only harness is not an evaluator.
- The new guard's own prose scored as an evaluating `jvmTest` on its first run, which is how the
  `afield/docs` package came to be excluded from every evaluator population.

## Two negative results, so the next pass can skip them

Both were checked on 2026-08-27 and both came back clean. Recording a clean result is worth as much
as recording a defect, because the alternative is somebody spending a night rediscovering that there
was nothing there.

**The smoke corpus does not have the exit-code-only shape.** All 150 `FIELD_TEST` blocks in `src/**`
pin at least one observable beyond `exitCode: 0` — `stdoutContains` (136), `outputContains` (12),
`eventKinds` (12), `stdout` (2), `screenshot` (2), `stdoutNotContains` (3). None passes purely on a
process exiting.

**The nine non-test `docsGates` dependencies re-run unconditionally**, as above: they are
`JavaExec`/`Exec` with no declared inputs or outputs, so Gradle gives them no up-to-date check.

Both of those numbers took three attempts to get right, and both times for the same reason: the
first scan used an incomplete vocabulary. The exit-code census read 14 before it learned that
`stdout:` and `stdoutContains:` are both spellings of the same thing, then 12 before it learned
about `outputContains` and `eventKinds`, then 0. The documented-API census read 39 before excluding
English words, then 8, then 5. **A census over a naming convention is only as good as the census's
list of names, and the first draft of that list is always short.** Print the vocabulary the scan
used, next to the count it produced.

## The checklist

When writing or reviewing a guard:

1. Print the population size next to the floor, in the same line. `total marked: 13 (floor 12)`
   reads wrong at a glance in a way `13` and `MIN = 6` in different files do not.
2. Assert every number worth printing, or label it a measurement.
3. Prefer a ledger to a ceiling when the population is small. At n=5 a ceiling of 8 admits three
   regressions and a ceiling of 5 is a ledger with the names left out.
4. Read every ledger backwards. A row that is no longer true has to fail, or the file becomes a list
   of problems that used to exist.
5. Separate "the thing was fixed" from "the thing vanished from the census" in the backward
   direction. They want opposite repairs, and a red build that misnames its cause teaches a reader
   to skim the next one.
6. Cross-check the guard's file reads against the task's declared inputs.
7. Print the vocabulary a census matched on, beside the count. A census over a naming convention is
   only as good as its list of names, and every scan on this page was wrong on its first draft for
   exactly that reason.
8. Demonstrate the red. Delete the marker, empty the set, drop the input, run the gate, watch it
   fail, restore. Record the message. A guard that has only ever been observed green is a guard
   whose failure path has never been executed.

Point 8 is the one that pays. Every defect on this page was found by an A/B, and three of them were
found in guards written the same night by someone actively looking for this failure mode.

## Where the instances are

| Shape | Instance | Fixed |
|---|---|---|
| Floor below population | `ProseNumeralContractTest.MIN_MARKED_NUMERALS` 6 vs 13 | 2026-08-27, ratcheted to 12 with a page floor beside it |
| Dominated floor | `CitationContractTest.MIN_UNIQUE_ANCHORS` 184, under a 327 already guaranteed elsewhere in the class | 2026-08-27, ratcheted to 360 |
| Printed, not asserted | `ProseNumeralContractTest` bidirectional-derivation count | 2026-08-27, `MIN_BIDIRECTIONAL_DERIVATIONS` |
| Undeclared input | `citationGates` over `native/**/*.swift` | 2026-08-27, `guardedNativeSources` |
| Undeclared input | `citationGates` over `.gitmodules`, `tools/code-use-semantic-sidecar` | 2026-08-27, by argument rather than A/B |
| Compiled, not run | 5 documented APIs with no evaluator anywhere | 2026-08-27, `DocumentedApiEvaluationTest` ledger |

One row of that ledger is a real gap rather than a justification.
`src/jvmMain/kotlin/afield/examples/four/FieldProbeSmokeTest.kt` opens a Window and exercises
`ShaderFieldProbe`, and carries no `FIELD_TEST` block and no Gradle task, so nothing runs it — while
`public/docs/field_probes.md` and `public/docs/afield_four_graphics.md` both teach the API.
