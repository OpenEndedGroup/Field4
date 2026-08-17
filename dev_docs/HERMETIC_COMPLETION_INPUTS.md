# Hermetic completion inputs

Two of Field's completion gates look deterministic and are not. Their answers depend on state that
lives outside the checkout, so two people running the same command on the same commit get different
results — and, because both results are plausible, the disagreement is repaired by re-baselining,
which fixes nothing and hides the mechanism for another round.

This page is the record of one such round costing three commits, and of what now stops it.

## 1. `checkCompletionCorpusFixtures` and the personal layer

### The symptom

`./gradlew checkCompletionCorpusFixtures` reported nineteen fixtures drifted, each by exactly one
line:

```
DRIFT spin_glass/sg_draw: harvested preamble differs from the fixture
    + import afield.four.util.Window
```

The line was removed in `559348acc`, restored in `10f3fbaf5`, removed again in `389e1d8ff`, and was
red again at `90230b1f4`. Nobody was careless: the same command really does produce both answers.

### The mechanism

`prop<Window>(window)` expands to a preamble fragment plus an import, and the import comes from
`kotlinImportStatementFor` (`src/jvmMain/kotlin/afield/internal/importHelp.kt`). Its rule is:

> emit an import only if **exactly one** importable class has this simple name.

Ambiguity yields *no import at all* — deliberately, since a macro that guessed between two same-named
classes would write a wrong import into a preamble. The candidate list is a whole-classpath
`ClassGraph` scan, and that scan deliberately includes `PersonalLayer.current`, because `src/personal`
must be visible to live completion.

`src/personal` is gitignored. On the machine where these fixtures were last written it contains

```
src/personal/kotlin/plans/rectgame.kt:1710:  data class Window(
```

so the candidate list there has a fourth entry. The line below is the real warning, from a worktree
with one probe `Window` compiled into the personal layer to stand in for `rectgame.Window`:

```
warning,import  multiple classes called Window =
    [afield.four.util.Window, java.awt.Window, nightlyprobe.Window, org.sqlite.Function$Window]
```

— two importable candidates after filtering (`java.awt.Window` is removed by the `*awt*` blacklist
pattern, `org.sqlite.Function$Window` because a nested name cannot be imported bare) — so no import.
In a clean checkout only `afield.four.util.Window` survives the filter, so the import is emitted.

That is the whole disagreement. Two checkouts, two classpaths, two correct answers.

### Observed, not argued

From one clean worktree at `90230b1f4`, same command both times:

| state | `checkCompletionCorpusFixtures` (before the fix) |
| --- | --- |
| no `src/personal` | **FAILED** — 19 drifts, each `+ import afield.four.util.Window` |
| one `class Window` in `src/personal`, `./gradlew personalJar` | **SUCCESSFUL** — "fixtures match the corpus" |

Reproduce it in five lines:

```bash
mkdir -p src/personal/kotlin/probe
printf 'package probe\nclass Window\n' > src/personal/kotlin/probe/probe.kt
./gradlew --no-daemon personalJar
./gradlew --no-daemon checkCompletionCorpusFixtures     # answer flips
rm -rf src/personal build/personal
```

### How big is this, really

`Window` was not special. It was the one collision a fixture happened to pin. Measured 2026-08-16:

**On a stock classpath, with no personal layer at all:**

| | count |
| --- | --- |
| distinct simple names | 75,104 |
| resolvable — exactly one importable candidate, so an import is emitted | 40,031 |
| **contested — two or more, so `prop<T>` emits no import at all** | **11,315 (22.04%)** |
| nothing importable (all nested or blacklisted) | 23,758 |

So better than **one importable type name in five** on Field's classpath already cannot be imported
by `prop<T>`. The macro writes `by Inheritance<T>()` and silently omits the import; the only trace is
an `lprintln("warning,import", …)`. Floored at 9,000 with a 40% ceiling in
`ImportResolutionTest.aMeasurableShareOfEveryImportableNameIsAlreadyContested`, with
`Window`/`Renderer`/`Box` as scan sentinels so the floor cannot pass on junk.

**And a personal layer takes more on top.** `ImportResolution.personalLayerCasualties()` is a set
difference over the two indexes — names resolvable in a clean checkout and contested once
`src/personal` joins the classpath. Reproducing the real layer's *namespace footprint* (the 829
distinct top-level class names in that machine's `afield-personal.jar`, regenerated as empty classes
in a scratch worktree; no code copied) the harvest reports:

```
this machine's personal layer removes the import for 124 more type name(s) that a clean checkout can import:
  AnalysisSettings, AnimationReport, Arrow, BinaryDoubleTrack, Cell, ChatKt, Cluster, Color4,
  CompileResult, ConstantTrack, ConstraintViolation, ContactManifold, ContactPair, Corner, Coverage, …
```

**`Window` was 1 of 124.** `Arrow`, `Cell`, `Color4`, `Float2`, `HalfEdge`, `Kernel`, `LineSegment`,
`GroupNode`, `FlowField` and `Formula` are all types someone might plausibly write a `prop<T>` for,
and on that machine none of them gets an import. The harvest prints this list where it applies.

Blunt version: **the code Field generates into a user's box preamble depends on what is in their
gitignored personal directory.** That is defensible for resolution — a user with their own `Window`
really does have two — but it was invisible, and it reached a committed artifact.

### Where else this shape reaches

The tree has exactly two `ClassGraph` scans:

| site | includes personal? | feeds |
| --- | --- | --- |
| `importHelp.kt` | **yes**, explicitly `PersonalLayer.current` | `prop<T>` imports (**committed** — the corpus fixtures), auto-import completion, `execution.kt`'s "did you mean" suggestions, `ForthInterpreter` word lookup |
| `di.kt` | via the context classloader, but `acceptPackages("afield")` | `@RunsAt` startup wiring — personal code reaches it only by declaring an `afield.*` package |

`StdlibReferenceGenerator`, which produces the *other* committed generated artifact
(`public/docs/reference/`), reads explicit `src/jvmMain/kotlin/...` roots and never `src/personal`.
**The completion-corpus fixtures were the only committed artifact exposed to this.**

### The guard that makes the direction stick

The harvest **refuses** to check or write fixtures when hermetic mode is off *and* a `prop<T>` type
resolves differently here than in a clean checkout — the exact state that produced the three
commits. Watched red, with the 829-name layer in place:

```
$ AFIELD_IMPORT_HERMETIC=0 ./gradlew --no-daemon checkCompletionCorpusFixtures
imports   machine (personal layer: present (afield-personal.jar); blacklist: $user.dir/.field/fieldPreferencesJson, 7 patterns)
IllegalStateException: refusing to check fixtures non-hermetically while 1 prop<T> type(s) resolve
differently here than in a clean checkout (Window).
```

With hermetic mode on — the default — the same tree is green. So the resolution mechanism is:
**the harvest excludes the personal layer *and* records that it did so (`imports   hermetic (…)`, an
assertion in its `FIELD_TEST` block), and refuses to run at all if someone turns that off while the
classpath is actually skewing the answer.**

### A second input on the same line

`findClassesNamed` filters candidates through `currentFieldPreferences().importClassBlacklist`, which
`PersistentString` reads from `$user.dir/.field/fieldPreferencesJson` — also gitignored, also absent
in a fresh worktree. Dropping `*awt*` from it un-hides `java.awt.Window` and suppresses the same
import for a completely different reason, producing a byte-identical nineteen-line diff. So closing
only the classpath leg would have left the gate one preferences edit from reopening.

### The fix

`ImportResolution` (in `importHelp.kt`) is now the single point where both inputs are resolved:

- `ImportResolution.hermetic` — resolve against core Field alone and against
  `defaultImportClassBlacklist`, ignoring both the personal layer and the machine's preferences.
  Settable in code or with `AFIELD_IMPORT_HERMETIC=1`.
- `ImportResolution.describe()` — one line naming both inputs.
- `ImportResolution.divergences(names)` — the names whose import differs between the two modes, with
  the candidate list that made them differ.

`CompletionCorpusHarvest` turns hermetic mode **on by default** (a checked-in fixture should describe
the repository, not the machine), prints the environment as `imports   hermetic (…)`, and prints any
divergence by name. Its `FIELD_TEST` block asserts the banner, not just `HARVEST DONE`: a harvest that
silently fell back to the machine classpath would still print `HARVEST DONE` and could still match the
fixtures — on that machine.

Live completion is unchanged and still sees personal classes; `PersonalLayerSmokeTest` covers that.

After the fix, both states above are green, and the second one additionally prints:

```
imports   hermetic (personal layer: present but EXCLUDED (afield-personal.jar); …)
  import resolution: 1 of 6 prop<T> types resolve differently on this machine. …
    Window: clean=import afield.four.util.Window | here=no import (ambiguous)
      candidates on this classpath: afield.four.util.Window, java.awt.Window, probe.Window, org.sqlite.Function$Window
```

### The check that can say which side was wrong

A fixture diff is symmetric: "the fixture differs from the harvest" never says which one to believe,
which is precisely why the repair went round three times. So the harvest now also checks each fixture
**on its own terms**:

> a preamble that declares `by Inheritance<T>()` and never brings `T` into scope describes a context
> in which `T` does not resolve.

`CompletionCorpus.typesDeclaredWithoutAnImport` counts a type as in scope if it is default-imported,
imported explicitly, declared inline in the preamble (the `/*supermap*/` and
`/*graphics-structure-type*/` forms do that), or covered by a star import — the last resolved through
the class index rather than guessed, so `import afield.four.buffers.*` excuses `FrameBufferGroup` and
does not excuse `Window`.

Run against `389e1d8ff`'s fixtures it flagged exactly the same nineteen files and exactly one type,
from a completely independent angle. Two agreeing checks plus a reproduced classpath flip is what
"nineteen identical failures" needed before it could be believed: a uniform failure across every item
is usually the checker, and this one had to be ruled in rather than assumed.

## 2. `~/.afield/filestore` — one directory, every checkout

`KotlinSourceIndex`, `GlslIncludeLibraryIndex` and `Ai4CompletionDoc` each hardcoded
`~/.afield/filestore`. It is machine-global, outside every checkout, and rewritten by whichever Field
runs last — a docs build, a sibling worktree, a nightly agent. `KotlinSourceIndex.runSync` indexes
`src/{jvmMain,personal,generatedLinalg}/kotlin` relative to `user.dir`, so two checkouts index
different trees into the same file and overwrite each other.

Completion reads it: `classifyMetadata` → `KotlinSourceIndex.getCompletionCategoryForSymbol`
(`KotlinCompletionSupport.kt`), and the category feeds ranking. A test that reads through it is
measuring the last Field to run, not its own input.

### Observed, not argued

`FieldSuggestPresentationTest`'s rank case, run on 2026-08-16 at `2b35d7c9f`. Same recorded payloads,
same commit, same command; only the index state differs:

| case | recorded in the fixture | the machine's `~/.afield` index | no index at all | built from this checkout |
| --- | --- | --- | --- | --- |
| FLine bare dot, `moveTo` | 10 | 12 | 15 | off-screen |
| List bare dot, `filter` | 57 | 63 | 64 | 47 |
| Math qualifier, `toRadians` | 52 | 45 | off-screen | 45 |
| kotlin.math, `sin` | off-screen | off-screen | 73 | off-screen |

Four states, four answers, one payload of 249 candidates. **So `moveTo` "falling from rank 10 to 12"
was never a code change: it was `~/.afield/filestore` being rewritten between the day the fixture was
cut and the day it was next run.** The recorded 10 corresponds to no state reachable today.

### The fix

`FilestoreDir` is the single resolution point:

| precedence | source |
| --- | --- |
| 1 | `-Dafield.filestore.dir=<path>` — the build forwards the whole `afield.` namespace to test workers |
| 2 | `AFIELD_FILESTORE_DIR=<path>` |
| 3 | `<user.dir>/.field/filestore` — per checkout, so two checkouts cannot fight |

On first use of (3) in a checkout that has none, `~/.afield/filestore` is copied in if it exists —
once, logged, best-effort. Not a move: another Field may still be reading it. The copy is not
tidiness, it is money: `filestore_cache.json` maps content hashes to OpenAI file IDs, and discarding
it re-uploads every source file.

`FilestoreDir.withDirectory(path) { … }` pins it in-process, which is how a test seeds a fixture
index. Pinning alone is not enough: `KotlinSourceIndex.latestIndex` holds the index a live Field
synced at startup, so it now remembers **which directory it was loaded from** and returns null when
that is no longer the one in force. Without that guard the disk reads move and the in-memory answer
does not — the same machine-global answer, one level up, and invisible. `FilestoreDirTest`
`aLoadedIndexStopsAnsweringWhenTheDirectoryMoves` covers it, and was checked against a mutant with
the guard removed: it fails there, so it is testing the guard and not the disk path beside it.

### What the rank case says now

Hermetic: `FieldSuggestPresentationTest` builds the index from `src/jvmMain/kotlin` +
`src/generatedLinalg/kotlin` (`KotlinSourceIndex.scanDefinitions` — `runSync`'s parse with no network
and no upload) into a temp directory and pins it. Three runs across two different ambient machine
states now produce byte-identical ranks. Dropping `src/generatedLinalg` was measured too and moves
none of the six ranks, so its inclusion is fidelity, not load-bearing.

**The rank case was red, deliberately, and is now GREEN because the cause was fixed** (2026-08-17).
Superseding what this section used to say: with a hermetic index `moveTo` at a bare `f.` was genuinely
off-screen, and re-baselining it to "off-screen" would have retired the exact signal the test exists
for. That instruction held for a day and is what forced a diagnosis instead of a fixture edit.

What it turned out to be — and it was never a *ranking* problem:

- `KotlinSourceIndex.getCompletionCategoryForSymbol` was keyed by **bare simple name** and ended with
  `categories.singleOrNull() ?: categories.firstOrNull()`. That reads as "only answer if unambiguous"
  and is identical to `firstOrNull()`: with one category `single == first`; with several, `single` is
  null and the elvis takes the first anyway. The ambiguity guard was written and neutralised on one
  line. In this tree `scale` is declared in **42** categorised files, `close` in 21, `copy` in 16.
- So `FLine.moveTo` was filed under **Math** — its only categorised namesake being
  `stdlib/Functions.kt` — and `close`/`rot`/`scale`/`copy` under Solvers/Patterns/Synthesis/Audio.
  Because the popup **groups** by category, those rows rendered in foreign *sections*, below every
  Graphics row, where no ranking term can reach them. `lineTo`/`cubicTo`/`here` were correct all along
  purely because they collide with nothing and fell through to path inference — so the name lookup was
  worse than its own fallback.
- The answer was already in the tree, twice: `parseTopLevelDefinitions` stores members **qualified**
  (`"$owner.$name"`), so `FLine.moveTo` was always a key this index held, and the sidecar already sends
  `declaredIn` per candidate. The lookup discarded both. It now resolves by declaring container, scopes
  a top-level extension's vote to `declaredIn`'s package, and **declines** rather than borrowing a
  namesake's category when `declaredIn` names something outside this tree.

Measured with the payloads held constant — `declaredIn` was added to the recorded rows and nothing
else changed, so the classification is the only variable:

| case | before | after |
| --- | --- | --- |
| FLine bare dot, `moveTo` | off-screen | **1** — top six are its `@CommonMembers`, in declared order |
| FLine prefixed `f.m`, `moveTo` | 6 of 10 | **1** |
| List bare dot, `filter` | 57 | 71 |
| Math qualifier, `toRadians` | 52 | off-screen |

**The last two got worse, and are accepted, because the fix is telling the truth.** All 109
`java.lang.Math` rows now classify as `External` — correctly and *uniformly* — so they tie at
`CATEGORY_WEIGHT` and the list collapses to the server's alphabetical order (`E`, `IEEEremainder`,
`PI`, `TAU`, `abs`…), putting `toRadians` past row 80 of 110. Its 52 was misclassification *noise*
handing some rows a nonzero weight, not a ranking decision. A type you cannot annotate has no prior
and no weight, so a bare dot on it is alphabetical — the same argument that says nobody reaches
`moveTo` by scanning 249 rows. Both are re-cut with that reasoning in the fixture's `note`, and the
case now measures the standing `CATEGORY_WEIGHT` defect (11 of the 16 categories the server sends
score 0) honestly, instead of being accidentally compensated for it.

**The rule that produced this outcome, for next time:** a rank case may be re-cut once the *cause* is
diagnosed and either fixed or explicitly accepted in the fixture's `note`. It may never be re-cut
because it is red.

### Is the hermeticity asserted, or just observed?

Asserted, and mutation-tested. Reverting `KotlinSourceIndex.indexPath` to the hardcoded
`~/.afield/filestore/kotlin_source_index.json` makes **four** assertions fail:

| test | message |
| --- | --- |
| `FilestoreDirTest.twoIndexesGiveTwoAnswersAndThePinDecidesWhich` | `expected:<Math> but was:<null>` |
| `FilestoreDirTest.anEmptyFilestoreDoesNotFallBackToTheMachine` | `actual value is not null` |
| `FilestoreDirTest.aLoadedIndexStopsAnsweringWhenTheDirectoryMoves` | `the in-memory index is not being consulted at all` |
| `FieldSuggestPresentationTest` (rank) | `the pinned filestore is not the one being read: seeded 38996 definitions into …` |

Removing only the `latestIndex` staleness guard fails the third alone. So neither the disk path nor
the in-memory cache can quietly go machine-global again without a red test.

## A third instance of the same shape: the failure count itself

Two nightly branches measured the baseline at `90230b1f4` on the same machine and got **different
sets**: this one saw 5 failures, branch 1 saw 3, and the two extra were
`PropMacroRegressionTest`'s pair failing with `OutOfMemoryError` inside a ClassGraph scan.

Neither measurement was flaky in itself — 5 runs in isolation here failed 5/5. The heap is the
explanation, and the threshold is what matters:

| test-worker heap | `PropMacroRegressionTest` |
| --- | --- |
| 512m (Gradle's default — no Test task set one) | 2 failures |
| 768m | 0 failures |
| 1g / 1536m / 2g | 0 failures |

The flip is between **512m and 768m**. A knife-edge that narrow is decided by how much is on the
runtime classpath, which differs between two worktrees at the same commit — different `build/`
contents, different added test classes. So the *count of failing tests at a given commit* was itself
not reproducible across checkouts, for a reason of exactly the kind this page is about. `maxHeapSize
= "4g"` moves it off the edge.

The lesson generalises past heap: when two honest agents report different baselines for the same
commit, suspect a threshold before suspecting either of them.

## The general shape

Both defects are the same shape, and it is worth naming because Field's completion path is full of
places it could recur:

> a gate whose answer depends on a file that is not in git, in a program that never says which file
> it read.

The repair has three parts, and all three are needed:

1. **one resolution point** for the outside-the-checkout input, with a per-checkout default;
2. **an explicit override**, so a test can pin it;
3. **the program says what it resolved**, so the next disagreement is one line of output rather than
   three commits.

A census of the remaining machine-global reads on the completion path is at the end of
`NIGHTLY_WORKLOG.md` for 2026-08-16.
