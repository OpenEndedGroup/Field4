# Box code revision pass

How the published box code — transcripts, starting points, demos, lessons, docs fences — gets
checked and improved, and what an agent revising it is asked.

## What is enforced (the gate)

- `tools/check_box_idioms.py` flags, and with `--fix` rewrites, the idioms agents keep writing:
  a redundant `val renderer: Renderer = fromContext()` (the renderer box publishes
  `prop<Renderer>(renderer)`), `mesh.push.bindPushConstant("n", { e }) { throw ... }` instead of
  `mesh.n = { e }`, `Box.context.get()!!` (that is `thisBox`, and the box is the implicit
  receiver, so `thisBox.foo` is `foo`), and the spaced `// %-` marker.
- `./gradlew checkBoxCodeCompiles` compiles, without evaluating, the Kotlin of every transcript
  it is pointed at, through `DemoTranscriptExecSmokeTest`'s compile-only path.
- Landing runs both over the files the landing changes only (`AFIELD_GUARD_CHANGED_PATHS_FILE`),
  so the existing debt does not block anyone, and any file a landing touches has to come out
  clean and compile. Running the code, GPU work and docs captures stay with the smoke catalog
  and the docs runner (the nightly).

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
- **The creative values are on top.** A number someone will want to change (a speed, a colour,
  a count) is a named `var` near the top of the box, or a control, and not a literal buried in
  a lambda deep inside the code.
- **Terse.** We are trying to convince the user that this is code they can a) discover, b) bear
  to type and c) bear to change. Ceremony counts against it: explicit types the context already
  supplies, defensive lookups of things that are inherited, null checks with nothing to recover
  from, wrapper lambdas around a single expression.
- **Fragments.** Never add a `//%-` break and never remove one; the author placed them. Where one
  is already there, a comment above it says why (what has to have run before the next fragment
  compiles, or which expensive setup the part being iterated on can rerun without).
- **No alias for the box.** `val historyOwner = thisBox` (or `= Box.context.get()!!`) goes, and
  so does the verbosity it brings: `historyOwner.foo` is `foo`, and where the box is an argument
  it is `thisBox`.
- **Commented where a reader would stop.** A short comment on what a longer chunk does (what
  the `loop` advances, why a cleanup is owned by the run), and nothing on a line that already
  says it.

The pass should also bring back ideas, as notes rather than edits, for **helpers and
conventions that remove typing**: a pattern that appears in many boxes and could be one call,
a preamble declaration that would make a lookup disappear, a default that would delete a line
from every box that uses it. These go to a human; they are API changes.
