# Box code revision pass

How the published box code — transcripts, starting points, demos, lessons, docs fences — gets
checked and improved, and what an agent revising it is asked.

## What is enforced (the gate)

- `tools/check_box_idioms.py` flags, and with `--fix` rewrites, the idioms agents keep writing:
  a redundant `val renderer: Renderer = fromContext()` (the renderer box publishes
  `prop<Renderer>(renderer)`), `mesh.push.bindPushConstant("n", { e }) { throw ... }` instead of
  `mesh.n = { e }`, `Box.context.get()!!` (that is `thisBox`, and the box is the implicit
  receiver, so `thisBox.foo` is `foo`; it cannot be spelled `this`), and the spaced `// %-`
  marker. A box alias (`val owner = thisBox`) and a renderer copy in a box that starts coroutines
  are printed as notes: judgement calls for the pass below, not failures.
- It reads what git tracks: transcripts, each demo's sidecars, `build.yaml` / `verify.yaml` and
  `document.md` mirror, lessons, and docs fences. A demo's `backups/` and `operations.yaml` are
  history and are skipped. `--fix` on a demo also rewrites its `document.md` fences and digests;
  `./gradlew regenerateAgentDemos -Ponly=<demo>` then refreshes `metadata.edn`.
- `./gradlew checkBoxCodeCompiles` checks top-level transcripts through
  `DemoTranscriptExecSmokeTest`'s existing classification: `COMPILE_ONLY` compiles without
  evaluation, `EXECUTE` replays the transcript (later boxes may need evaluated declarations),
  and `KOTLINJS` compiles for its head. It does not check arbitrary sidecars, docs fences or GLSL.
- Landing runs both over the files the landing changes only (`AFIELD_GUARD_CHANGED_PATHS_FILE`),
  so the existing debt does not block anyone: touched corpus files must pass the idiom checker,
  and touched top-level transcripts must pass the compile/replay guard. Broader execution,
  GPU work and docs captures stay with the smoke catalog and the docs runner (the nightly).

## Census and rehearsal

`uv run --project . --group backdoor python tools/inventory_box_code.py --out build/box-code-revision/inventory`
reads tracked teaching sources and writes `report.md`, `targets.tsv` and `inventory.json`.
The manifest records box/property locations, content hashes, document groups, exact content
matches, language declarations, generated copies, inline fallbacks and historical exclusions.
Code in YAML fences and metadata-only properties is included. Review counts are occurrences,
not unique programs or promises that every docs snippet executes on its own.

The [first rehearsal](box_code_revision_pilot/README.md) records three review outcomes and real
runtime evidence, including an Alt-Enter declaration/assignment comparison. It also records
the limits of the current census and the verification needed before a larger pass.

## Imports

A box is compiled with `intro` (`Boxes.kt`) prepended, so most imports agents write add nothing.
`analyzeBoxImports` (`afield/internal/boxImports.kt`) says of each import whether it is redundant,
unused, needed, or kept for want of evidence, and `formatBoxImports` removes the first two and sorts
the rest; nothing adds an import. Format Kotlin in the editor does this before ktfmt, and the editor
folds the remaining block (`importFolding.js`). For the corpus:

- `./gradlew formatBoxCode --args="--imports [--k2] <files>"` applies it with the other formatting;
- `./gradlew boxImportCensus --args="[--k2] [--out dir] [paths]"` changes nothing and reports every
  import's verdict, the imports boxes keep that the intro lacks (by name and by package), and for a
  package the class names that would clash with the intro and the boxes using them: the evidence for
  growing the intro, which is Marc's call.

`--k2` asks the code-use sidecar for resolution, which is the only evidence that can drop a star
import; it resolves Field's sources but no libraries, so library-heavy boxes keep their stars.

In the pass, imports are a deterministic step before ktfmt, compile-checked with everything else;
the agents never edit them.

## What the revision pass asks (not yet built)

The plan is a `code-review` note kind in `tools/docs_notes.py`. Notes come from the checker
above and from a model's reading, and `revise` resolves them: one agent per file, and each
edited box must compile, and run where it can, before its note resolves. The model reading
starts from one question:

**Does this code live-code well?** Can a user quickly edit part of it, rerun all of it or one
fragment, and see the result soon? In particular:

- **Rerunnable.** Running it twice works without hand-unregistering whatever the first run
  registered. Cleanup comes from Field's lifecycle (`RunScope` ownership, a module replaced on
  re-run), not from a teardown block the user has to maintain.
- **Teach the normal API.** `setPropertyRaw` is an escape hatch, and almost always a code
  smell in teaching code. Declare a typed property with `prop<T>(name)`, assign it by name,
  and let children read the inherited property directly. Raw reads and writes need a specific
  reason to remain. Shortening a raw call is not a successful revision. If a normal task
  requires an escape hatch, report the missing API or convention as a helper idea.
- **The creative values are on top.** A number someone will want to change (a speed, a colour,
  a count) is a named `var` near the top of the box, or a control, and not a literal buried in
  a lambda deep inside the code.
- **Useful lines can run alone.** A declaration and a later assignment to the same value can
  deliberately have the same initial value. In `var x = 10; x = 10; frames { x++; print(x) }`,
  the assignment is a live reset point: change its `10` to `11` and Alt-Enter that line to
  update the variable the running loop captured. Re-executing the declaration creates a new
  variable while the loop keeps the old one. Preserve these reset points, and identify which
  lines can change a running example without rebuilding its setup. Judge an edit by the time
  to find the creative value, get feedback and rerun confidently; line count is only a clue.
- **Terse.** We are trying to convince the user that this is code they can a) discover, b) bear
  to type and c) bear to change. Ceremony counts against it: explicit types the context already
  supplies, defensive lookups of things that are inherited, null checks with nothing to recover
  from, wrapper lambdas around a single expression.
- **Fragments.** Never add a `//%-` break and never remove one; the author placed them. Where one
  is already there, a comment above it says why (what has to have run before the next fragment
  compiles, or which expensive setup the part being iterated on can rerun without).
- **Usually no alias for the box.** `val historyOwner = thisBox` is a smell, though not always
  wrong: where it only saves typing it goes, and so does the verbosity it brings —
  `historyOwner.foo` is `foo`, and where the box is an argument it is `thisBox`.
- **Commented where a reader would stop.** A short comment on what a longer chunk does (what
  the `loop` advances, why a cleanup is owned by the run), and nothing on a line that already
  says it.

Read `BOX_CODE_HELPER_IDEAS.md` first: its **Already in Field** section lists helpers to use
in place of hand-written loops (`breakNext()`, `data(...)`, `FLine.rect`, `structure[name]`,
generated push-constant properties), and its **Declined** section lists proposals not to make again.

The pass should also bring back ideas, as notes rather than edits, for **helpers and
conventions that remove typing**: a pattern that appears in many boxes and could be one call,
a preamble declaration that would make a lookup disappear, a default that would delete a line
from every box that uses it. These go to a human; they are API changes.

The pass edits code blocks only. When an agent finds that a transcript's `description:` (or a
docs page that embeds its code) disagrees with the code, or narrates history, it reports it, and
the run files it as a handoff, which joins the docs revision queue:

```bash
uv run python tools/docs_notes.py handoff "Fix <transcript>'s description: <what>" --scope project --from-page agent_transcripts.md --body-file -
```
