# The private boundary

`src/personal/` is the author's own work: pieces, sketches, and the private Kotlin packages they are
built from. Field may **read** it. Nothing committed may **depend** on it.

That is the whole rule. `PersonalBoundaryTest` enforces it, in `citationGates`, on every `check`.
This page is the part its KDoc cannot be: what you read before you have hit the failure, and what to
do when you have.

## What the rule stops, and why `.gitignore` does not stop it

`.gitignore` carries the rule `src/personal/`, and today `git add -A` cannot reach anything under it. That
is not the same as the directory never having been tracked, and the difference is this page's own
subject. Twenty-nine files under the private tree **were** tracked, across fifteen commits, until
`9353c17e4` (2026-08-31) removed them from the index — the commit is the citable evidence here, and
naming the subdirectory they lived in is exactly the move assertion 2 forbids. (This paragraph
reddened `citationGates` on its first draft for doing precisely that.) They are still on the author's
disk, still gitignored, and now invisible to every `git grep`-based guard while remaining visible to
every guard that walks the filesystem. Both halves of that sentence matter below.

The failure runs the other way. Somebody studies the private work to generalise a lesson from it, and
writes the lesson into a **tracked** file that imports a private package, cites a private path, or
quotes private code. The private tree stays untracked. The private work is in the public repository
anyway, as the study rather than as the studied. Every individual file is in a location it belongs
in, which is why review does not catch it.

Three costs, in the order they matter:

1. **A published page nobody else can build.** A code fence on
   `public/docs/afield_four_graphics.md`, and box files in the loadable `canonical_graphics` bundle,
   named packages that existed on one machine. That is a correctness bug for every other reader.
2. **A citation nobody can follow.** Prose that cites a private path offers evidence no reader can
   open. It also creates bookkeeping: before this guard,
   `src/jvmTest/kotlin/afield/graphics/CloseNodeCitationDriftTest.kt` carried an exemption list
   naming three private files purely to record that CI could not check them.
3. **A general tool promoting one artwork.** The intent is to study private work for the *general*
   lesson, and a lesson that arrives carrying the name of the piece it came from has not been
   generalised. The corollary that makes the rule liveable: a private survey may still support a
   claim about a *population*. "The only caller in the private tree is a deprecated shim" is a
   finding worth publishing. It just may not be cited as a location.

## The assertions

Six `@Test` methods, in `src/jvmTest/kotlin/afield/docs/PersonalBoundaryTest.kt`. Three of them are
the rule, two keep the ledger honest, and one does not run outside the author's machine.

### 1. Import direction, and why the allowlist is inverted

`PersonalBoundaryTest.kt:239#no committed file imports a package outside the public roots`.

The obvious rule is "do not import `drowning`, `euclid`, `organic`, `splat`". It needs a list of
private package names that their author renames without telling anybody, and that cannot be derived
on a machine where the private tree is absent. A guard whose population is a list that silently goes
stale is the failure this repository has recorded most often.

So the rule is inverted. Every one of Field's own packages is under `afield.`, so a **single-segment**
import root is by construction either a third-party library or private work.
`PersonalBoundaryTest.kt:117#private val PUBLIC_IMPORT_ROOTS` lists what is *permitted*. It fails
closed: a private package invented tomorrow is caught with nobody updating anything.

Measured over the corpus on this branch, replicating the guard's own regex and roots:

| | |
|---|---|
| single-segment import matches | **15 012** |
| distinct roots they use | **28** |
| entries in `PUBLIC_IMPORT_ROOTS` | **29**<!--count:publicImportRoots--> |
| entries never used | **0** |

The measured rows are from 2026-09-05, when the set held 28. The twenty-ninth is `sun`, added
2026-09-29 for `sun.misc.Signal` in `InterruptSignal.kt`: that JDK-supported (`jdk.unsupported`)
class is the only public way to install a signal handler, and Field needs one to exit on SIGINT when
it was started with the signal ignored (`dev_docs/SIGINT_SHUTDOWN.md`).

Every permitted root earns its place, and thirteen of the twenty-eight are used fewer than ten times,
so the allowlist is mostly tail. That thin tail is what makes the inverted form practical: the
signal a private package produces is not lost in a crowd.

One correction to what is written down about that set. The class KDoc says "22 legitimate roots
accounting for 15 000+ import statements". The import count is exact. The root count is 28, and 22 is
not reproducible from any subset of the literal.

The 29 in the table above is derived rather than typed: `ProseNumeralContractTest` scrapes the set
out of `PersonalBoundaryTest.kt` on every `citationGates` run and fails if the page and the code
disagree. Pinned because this number has already been wrong once in the place a reader would look for
it, and nothing was checking either statement of it. Adding a genuine new public dependency will now
turn this page red until the sentence moves with the code, which is the point.

The regex is `PersonalBoundaryTest.kt:186#private val IMPORT_RE`, and its alternation
`(?:^|\\n|")` is the interesting part. In a Kotlin raw string `\\n` is a literal backslash followed
by `n`, so the middle branch matches an **escaped** newline in the file's *text*. That is what
catches an import inside an `#afield/file-inline` string in a `metadata.edn`, where the whole box
program is one EDN string with `\n` between its lines.

Getting a sample to exercise that branch takes one deliberate choice, and the first version of this
page got it wrong. In `{:code #afield/file-inline "import org.example.Thing\nval x = 1"}` the import
sits immediately after the opening quote, so it is the `"` branch that fires, at offset 27 — the
`\n` branch is never reached. Move the import off the first line and the match comes from the
branch this paragraph is about: given `{:code #afield/file-inline "val x = 1\nimport
org.example.Thing"}` the regex reports the root `org` at offset 37, matching on the literal
backslash-n. Substitute a private package for `org` and that is the branch that catches the embedded
copy of a leaking box — but only from the second line onward, which is worth knowing before trusting
it.

This paragraph could not be written with a real private package name in it, because the `"` branch
of the same regex would have matched the example and reddened this page. The guard's own first catch
was itself: the original ledger reasons quoted the private paths they described, and were fixed by
rewording rather than by exempting the file.

### 2. Citation depth

`PersonalBoundaryTest.kt:273#no committed file cites a path inside the private tree`.

Naming the root is legal. Reaching inside it is not. `git grep -l "src/personal"` returns **40**
tracked files, **26** of them under `src/`, and they are all mechanism: Field offers
`src/personal/kotlin` as an optional source root, from `KotlinSourceIndex` to the completion sidecar.
Two of them name it in order to *exclude* it, both as an `EXCLUDED_ROOTS` list —
`src/jvmMain/kotlin/afield/docs/KDocAttachmentGuard.kt:128#private val EXCLUDED_ROOTS` and
`src/jvmMain/kotlin/afield/docs/SourcePartitionGuard.kt:70#private val EXCLUDED_ROOTS`. Depth is the
discriminator: a root reference survives the directory being absent, a path inside it does not.

`PersonalBoundaryTest.kt:189#private val DEEP_PERSONAL_RE` is the root plus **two** more segments.
`PersonalBoundaryTest.kt:136#private val ALLOWED_PERSONAL_REFERENCES` holds eight strings that are
excused if the regex does produce them.

Seven of those eight are dead weight, and two are provably unreachable. The regex requires two path
segments after `src/personal/`, so it can never emit the bare `src/personal` or `src/personal/`, and
the five one-segment entries are reachable only through a trailing `/.` (the text
`src/personal/kotlin/.` matches and then trims back). Running the guard's own citation logic over
either checkout, exactly one allowlist entry is load-bearing: **`src/personal/kotlin/probe`**, and it
is not one of the author's files. It exists because `dev_docs/HERMETIC_COMPLETION_INPUTS.md:69-70`
tells you to create `src/personal/kotlin/probe/probe.kt` as a completion fixture, so the deepest
citation in the tree is a setup instruction.

A hit *count* is deliberately not quoted here. This page cites that path three times in the course of
explaining it, so any number printed above would be a number this page had changed by existing — the
failure documented at the end of [What the failures actually look like](#what-the-failures-actually-look-like).
`dev_docs/HERMETIC_COMPLETION_INPUTS.md` tells the *reader* to create a throwaway `probe` package
under the private root and delete it again. That is a recipe that works on any machine, which is a
different thing from a dependency on somebody's content. The distinction is written at the
allowlist, which is the right place for it.

The other seven entries cost nothing and are cheap insurance against a future regex. Knowing they
are inert saves the next reader from concluding that the tree cites `src/personal/docs` somewhere.

### 3. The population floor

`PersonalBoundaryTest.kt:221#the corpus this guard reasons about is really there`.

Assertions 1 and 2 both report an *absence*, and an absence over an empty corpus is free.
`PersonalBoundaryTest.kt:184#private const val MIN_SCANNED_FILES` is 2000 and stops that. It also
catches a `SCAN_ROOTS` entry that has been renamed out from under the guard, which is the same
failure arriving quietly instead of loudly.

The floor's own comment says "Measured at 2712 when written". The tracked population inside
`SCAN_ROOTS` at the commit that introduced it was **2667**:

```console
$ git ls-tree -r --name-only 6fcf177d2 \
    | grep -E '^(src/commonMain|src/jvmMain|src/jvmTest|src/jsMain|dev_docs|public/docs|templates)/' \
    | grep -E '\.(kt|kts|md|edn|yaml)$' | wc -l
    2667
```

2712 matches neither that nor the 3461 the same commit's message quotes. Nothing is broken by it —
the floor is 2000 and both numbers clear it — but see *The population is not the same in two
checkouts* below, because the 3461 is not a mistake and the two numbers are measuring two different
trees.

### 4 and 5. The ledger, in both directions

`PersonalBoundaryTest.kt:161#private val GRANDFATHERED` is the debt list: path, and why it is here.
It lives nowhere else. There is no JSON, no Markdown, no issue tracker row.

Its contract is two-sided, and the second side is the half most debt lists omit:

- `PersonalBoundaryTest.kt:326#the grandfathered ledger does not grow quietly` holds it at
  `PersonalBoundaryTest.kt:178#private const val MAX_GRANDFATHERED`, currently **0**. It may shrink
  without ceremony. Growing it means editing a constant, in a commit somebody will read.
- `PersonalBoundaryTest.kt:308#grandfathered entries are still real` fails if an entry is *no longer*
  a violation. Fix a leak, leave the excuse behind, and the build goes red. Without this a ledger
  becomes a description of a tree that no longer exists, and the next reader believes a leak is still
  there.

That second assertion is what did the accounting when the debts were paid: nine were fixed, and it
named exactly those nine as stale.

### 6. The collision check, which does not run here

`PersonalBoundaryTest.kt:346#no private package name collides with a permitted public root`.

Assertion 1 is only as good as the claim that no private package shares a name with something
`PUBLIC_IMPORT_ROOTS` permits. If a private package were called `field`, then `import field.*` in a
committed file would sail through. Nothing in the tracked tree can check that, because the tracked
tree cannot see the private packages.

So this one walks `src/personal/` if it is there, and returns if it is not. It is the only assertion
in the class whose subject is the private tree rather than the committed tree. Its output on this
branch, from the baseline `citationGates` run:

```text
PersonalBoundaryTest: no src/personal/ here, collision check skipped (expected off-machine)
PersonalBoundaryTest: scanned 2666 committed files across 7 roots
```

Six tests, six passes, and the skipped one ran in 0.001s against 1.174s for the import assertion,
which is what a no-op looks like in the timing column.

Because it cannot run in CI or in any worktree, it was worth running by hand. Replicating it over
the primary tree, which is the one machine that has the subject:

| | |
|---|---|
| private `.kt` files | **221** |
| distinct private top-level packages | **19** |
| collisions with `PUBLIC_IMPORT_ROOTS` | **0** |

The nineteen are `collapse demo drowning euclid gp house kiwi light organic paths pattern patterns
personal pivot plans plans2 rectgame sentences sheet`. None of them is on the twenty-eight-entry
allowlist, so assertion 1 has no hole in it today.

The margin is thinner than zero collisions makes it sound. Both lists are single ordinary English
words. Four permitted roots are words a private package could plausibly be named without anybody
thinking twice: `field` (15 uses), `ai` (14), `web` (8) and `space` (20). A private `field` package
would make `import field.*` legal in any committed file, silently, and the only thing that would say
so is assertion 6 on the one machine that can run it. That is why running it by hand is worth the
five minutes rather than reading "expected off-machine" and moving on.

## What the guard's subject is when the private tree is absent

`src/personal/` is untracked, so **no worktree and no fresh clone has it**. This one does not, and
neither will CI.

The guard is unaffected, and this is the important thing to understand about it: its subject is the
**committed tree**, not the private tree. `PersonalBoundaryTest.kt:92#private val SCAN_ROOTS` names
`src/commonMain`, `src/jvmMain`, `src/jvmTest`, `src/jsMain`, `dev_docs`, `public/docs` and
`templates`, and `src/personal` is not among them. Assertions 1, 2 and 3 look for references *out
of* the committed tree and find them, or do not, regardless of whether the destination exists.
Assertions 4 and 5 read a Kotlin constant. Only assertion 6 needs the directory, and it prints and
returns.

That means the rule is enforceable everywhere and its safety net is enforceable in one place. Say
this out loud when reasoning about the guard, because the intuitive reading — "a guard about
`src/personal` cannot mean much where `src/personal` is absent" — is wrong for five of the six
assertions and right for the sixth.

## The population is not the same in two checkouts

`scanned()` at `PersonalBoundaryTest.kt:203#if (!dir.isDirectory) emptyList() else dir.walkTopDown()`
walks the **filesystem**. It does not consult the git index. Two consequences, and the second is a
trap.

First, `.gitignore` is honoured only where the guard hard-codes it.
`PersonalBoundaryTest.kt:210#.filterNot { (rel, _) -> rel.startsWith(PERSONAL_ROOT) }` excludes the
private tree by path. Every other ignore rule is invisible to it.

Second, and measured over the same commit in two checkouts:

| checkout | files in the guard's population | of which untracked |
|---|---|---|
| a clean worktree of this commit | **2 667** | 0 |
| the author's primary tree, measured 2026-09-05 | **3 480** | **794** |

The first number is a property of the commit and reproducible from any checkout. The second is not: it
counts whatever happens to be on one machine on one day, and it moved from 3 460 to 3 480 in the six
hours between this page being drafted and being reviewed. Quoting it at all is a compromise, and it is
dated for that reason — the ratio is the durable part, not the digits.

790 of those 794 are gitignored box-code backups under `templates/*/backup/`, excluded by
`.gitignore`'s `templates/*/backup/` rule, which sits a few lines above the `src/personal/` rule the
same guard does honour. (Both are cited by rule text rather than by line: this page moved those line
numbers itself when a sibling branch rewrote that region of `.gitignore` on 2026-09-05.) The
remaining four are the author's uncommitted work under `dev_docs/agents/demos/`.

So on the author's machine this guard is **stricter** than in CI, over a population 30% larger that
nobody chose. A box that once said `import drowning.*` and was fixed still has the leaking revision
sitting in `templates/<name>/backup/`, and that copy is inside the population. Today no backup
violates: a scan of `templates/` for an import of any of the nineteen private packages returns 0.
That is luck rather than design, and the failure it would produce is the confusing kind, red for one
person and green for everyone else.

The 2026-09-01 journal recorded the same asymmetry from the other side, after `9353c17e4` untracked
29 force-added files under one package of the private tree. In the primary tree those files are now
present-but-untracked, so `git grep`-based guards stopped seeing them while filesystem-walking guards
still do. It produced a real disagreement within the hour: the same predicate over `src/` counted 16
in the primary tree and 11 in a worktree, and the extra five were in an untracked private file. An
unqualified `grep ... src/` is not a stable population on this project.

`dev_docs/guard_population_hygiene.md` collects the ways a guard's population drifts from the one its
name describes. This is a fourth shape for that page: **a population that differs between two
checkouts of the same commit.**

## When it runs

`PersonalBoundaryTest` is in `citationGates`, declared at `build.gradle.kts:1419#"afield.docs.PersonalBoundaryTest"`,
and excluded from the broad `jvmTest` task. That is not about speed. `citationGates` is the only task
that declares Markdown and the three box-code forms as **inputs**, so it is the only one that cannot
go `UP-TO-DATE` after an edit that touches no Kotlin. A guard that can pass stale is worse than no
guard, because it reads as evidence.

The three forms matter here more than for most of that task's tenants, because box code is committed
three ways and a leak through any one of them is equally broken for a reader:

- a loose `<uid>_code_.kt` sidecar,
- an `#afield/file-inline` string inside `metadata.edn`,
- a fence on a published page.

`PersonalBoundaryTest.kt:102#private val SCAN_EXTENSIONS` is therefore `kt, kts, md, edn, yaml`, with
`yaml` covering operation transcripts, which carry box programs as literal text.

At commit time the pre-commit hook runs Gradle, so a staged `.kt` costs a couple of minutes. At
`check` time `citationGates` runs as a dependency. To run only this class:

```console
$ ./gradlew --no-daemon citationGates --tests '*PersonalBoundary*'
```

## What the twelve debts were

The ledger opened with twelve entries on 2026-09-04 and reached zero the same evening, across three
commits. The shape of the payoff is more useful than the list.

| what | how many | how it closed |
|---|---|---|
| published page or box file with a live private import | 3 | two were **dead** imports over code `afield` already had; one was real and the class moved into `afield` |
| the `metadata.edn` file-inline copies of those box files | 1 | edited alongside the sidecars |
| dev_docs prose citing a private path as evidence | 4 | the finding kept, the path dropped |
| a test pinning line numbers inside private files | 1 | the exemption list it needed was deleted with the citations |
| a test citing a private file in a comment | 1 | de-pathed |
| an agent snippet importing `drowning.*` | 1 | moved into the private tree |
| a recipe telling the reader to create a private path | 1 | reclassified as allowed, not excused |

Two things in that table are the interesting ones.

**Nine of the twelve closed by keeping the finding and dropping the path.** "The only caller in the
private tree is a deprecated shim whose own public functions have no callers" is a claim about a
population and it survives without a path no reader can open. Each page now says that explicitly, so
the next person does not restore the citation thinking it was an oversight.

**Two of the three private imports were dead.** `canonical_graphics/072074d3`'s entire body was
`import euclid.*` and nothing else. `de832c1f`'s `import splat.*` bought it nothing:
`readPlyAsFloatTable` and `subsetFloatBufferColumns` are both in
`src/jvmMain/kotlin/afield/four/modules/LoadPly.kt`, the box constructs no private types, and the
private tree does not shadow the function. Both leaks had been there long enough to look load-bearing
and were not.

The last three were one dependency: `organic.ShaderDlaSystem`, which section 8 of
`public/docs/afield_four_graphics.md` teaches `installInto()` through. Moving it was the right call
rather than dropping the section, because the lesson is general and the vehicle was incidental. It is
now `afield.stdlib.ShaderDlaSystem` in `src/jvmMain/kotlin/afield/stdlib/alife_dla.kt`, and the tell
that this is where it belonged is that three of its imports became redundant on arrival: `Vec4`,
`attachShader` and `vec` are `afield.stdlib` members. The private file became a two-line typealias
shim so that a live box saying `import organic.*` still opens. Private code depending on public code
is the permitted direction, so the shim is not a leak.

One footnote on the guard's hit rate. Four `dust_*_check.kt` snippets were removed in that pass, and
the guard had caught **one** of them. Only `dust_particle_seed_check.kt` imported a private package;
the other three were found by a human looking at what the first one was for. The guard finds
references, not relevance.

## What the failures actually look like

Three of the six assertions were made to fire on purpose while this page was being written, and
reverted afterwards. The commit that introduced the guard demonstrated assertion 1 by planting an
import; these are the other ones, so the failure paths that had never been executed now have been.

### Assertion 2, planted

An HTML comment appended to this page, naming two things: the source root, which is legal, and one
file inside it, which is not. The file chosen was the dependency the ledger retired on its last day.

```text
PersonalBoundaryTest > no committed file cites a path inside the private tree FAILED
    java.lang.AssertionError at PersonalBoundaryTest.kt:284

java.lang.AssertionError: committed files cite paths inside src/personal/:
  dev_docs/private_boundary.md → src/personal/kotlin/…

A citation is evidence a reader is meant to be able to open, and nobody else can open
this one. State the finding in terms of afield.* and cite the afield.* code it applies
to; if the only evidence is private, the claim belongs in a commit message or the
nightly journal rather than in a guarded document.

6 tests completed, 1 failed
BUILD FAILED in 9s
```

One elision, and it is the guard's doing rather than an editorial choice. The real message ends that
line with a package name, and reproducing it here would re-plant the violation in the page that
documents it. **A guarded document cannot quote this guard's failure message verbatim.**
`PersonalBoundaryTest.kt:276#for (m in DEEP_PERSONAL_RE.findAll(text))` runs over raw file text, so a
fenced code block is not exempt and neither is an HTML comment. That is a small, permanent
consequence of enforcing a rule with a literal string match, and it is the reason the block above is
the only thing on this page that is not exact.

Two things to read off it. The legal reference on the same planted line is **not** reported, so depth
is genuinely the discriminator and not an approximation of one. And what the elision hides is a
*package*, not a file: `DEEP_PERSONAL_RE` has exactly two segment groups, so the match stops one
level above the `.kt` that was planted. The failure tells you which private package you leaned on
rather than which file, which is the more useful of the two and is not obvious from reading the
regex.

### Assertions 4 and 5, planted

One entry added to the empty ledger, pointing at a clean file, with an excuse that reads plausibly.
Both halves of the ledger contract fire, separately:

```text
PersonalBoundaryTest > the grandfathered ledger does not grow quietly FAILED
java.lang.AssertionError: GRANDFATHERED has 1 entries against a ceiling of 0. Adding a
new exemption is a decision; say why in the commit message and raise the ceiling.

PersonalBoundaryTest > grandfathered entries are still real FAILED
java.lang.AssertionError: GRANDFATHERED excuses violations that no longer exist — delete
these entries:
  dev_docs/private_boundary.md

6 tests completed, 2 failed
```

The second is the one worth having. Raising the ceiling would silence the first and leave the second
red, because the entry describes a violation that is not there. An excuse cannot be parked; it has to
correspond to something.

### The derived count, planted

The pin on `PUBLIC_IMPORT_ROOTS` was checked the same way, because a pin nobody has seen fire is a
pin nobody should trust. One extra root added to the set, page untouched:

```text
ProseNumeralContractTest > everyMarkedNumeralMatchesItsDerivation FAILED
java.lang.AssertionError: 1 number(s) stated in prose disagree with the code they describe:
  dev_docs/private_boundary.md:61 `28` <!--count:publicImportRoots--> — the page says 28,
  the code has 29 single-segment package roots a committed file may import
Recount by deriving, not by grepping — every recorded failure of this kind was a grep that
missed a wrapper.

14 tests completed, 1 failed
```

### Afterwards

Every plant was reverted and `git status --porcelain` returned empty each time, so the tree is
byte-identical to the commit each was made against.

## What to do when you hit it

The failure message tells you which of the two it is. Both come with the same instruction, and the
instruction is the point of the rule rather than a workaround for it.

**An import.** Take the lesson, express it against `afield.*`, and leave the private names behind. If
the code has no `afield` equivalent, you have found the interesting case, and you have three options
in descending order of preference: move the implementation into `afield` (as `ShaderDlaSystem` was),
teach the lesson through an `afield` vehicle instead, or drop the section. Adding the root to
`PUBLIC_IMPORT_ROOTS` is not on the list; that set is for genuine new public dependencies, and adding
a private package to it silently disables the guard for that package forever.

**A citation.** State the finding in terms of `afield.*` and cite the `afield.*` code it applies to.
If the only evidence is private, the claim belongs in a commit message or the nightly journal rather
than in a guarded document. Write down that you did this, in the page, so the next reader does not
restore the citation as a repair.

**A ledger entry.** Adding one means raising `MAX_GRANDFATHERED` from 0, which is a deliberate act
and should say why in the commit message. Removing one is free, and required once the leak is gone.

## What this guard does not see

Stated so that a green run is not read as more than it is.

- **A private path written without its prefix.** `DEEP_PERSONAL_RE` keys on the literal string
  `src/personal/`. A path relative to the private source root does not carry it and is invisible.
  There is a live instance, and it is inside another guard:
  `src/jvmMain/kotlin/afield/docs/KDocAttachmentGuard.kt:124#Scanning it made` explains why it
  excludes the private tree by reciting three files it found there, as `kiwi/MapType.kt`,
  `organic/fakeDivs.kt` and `pattern/polyheadra.kt`. Three private paths, in a tracked file, past a
  green guard. Harmless here, because the point of the passage is that those files could not be
  fixed from any other checkout, but it is the same shape the rule exists to stop and nothing would
  catch a worse version of it.
- **Hardcoded absolute paths.** Ten files in the `canonical_graphics` bundle carry `/Users/marc`
  paths. That is the same failure class — a committed artifact that only works on one machine — and
  nothing checks it.
- **A page built on a private piece without naming it.** Two published `audio_ai_*_embeddings` pages
  are built on the author's own work. No import, no path, nothing to match.
- **Python.** `.md` carries Python fences and this scans file text rather than parsing fences, so
  `urllib`, `matplotlib` and `asyncio` are on the allowlist. The soft edge is deliberate: the private
  tree is Kotlin, so a Python-shaped hole cannot hide it.
- **Renames.** The guard checks references out of the committed tree, not that the private tree still
  contains what a comment says it does.

## Related

- `dev_docs/guard_population_hygiene.md` — how guards go stale without going red. The population
  asymmetry above is a fourth shape for it.
- `dev_docs/citation_contracts.md` — how a `path:line` in a dev_doc becomes a checked fact. This page
  is under that machinery.
- `dev_docs/HERMETIC_COMPLETION_INPUTS.md` — the one recipe that legitimately names a path under the
  private root.
