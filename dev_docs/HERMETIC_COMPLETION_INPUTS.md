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

**The rank case is still red, and should stay red.** With a hermetic index `moveTo` at a bare `f.` is
genuinely off-screen — worse than the 12 the machine index reported, and worse than the 10 the fixture
records. Re-baselining it to "off-screen" would retire the exact signal the test exists for ("the
answer a person typed is among the rows a person can see"). What changed tonight is that it is now red
for a reproducible reason instead of a different reason every run.

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
