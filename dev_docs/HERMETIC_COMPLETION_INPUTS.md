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

### The fix

`FilestoreDir` is the single resolution point:

| precedence | source |
| --- | --- |
| 1 | `-Dafield.filestore.dir=<path>` |
| 2 | `AFIELD_FILESTORE_DIR=<path>` |
| 3 | `<user.dir>/.field/filestore` — per checkout, so two checkouts cannot fight |
| 4 | `~/.afield/filestore` — only if it already exists and (3) does not, so an existing install keeps its caches |

`FilestoreDir.withDirectory(path) { … }` seeds a fixture index for a test. See
`FilestoreDirTest` and `FieldSuggestPresentationTest`.

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
