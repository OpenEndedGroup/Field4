# Line notes for every language that can say (2026-09-28)

`afield.stdlib.LineNotes` (added 2026-09-27, `d349eaefb`) gave the editor a note per line — beside the
cursor and at the top of the completion popup — but only StackSL and RectSL registered providers.
This extends it to ForthField, Clojure, Python and Datalog, and to the AI4 tool surface.

## Shape

- **The registry falls through.** Accepting providers for a property are asked newest first until
  one returns a note. Before, the first *accepting* provider answered even with null, so the
  embedded StackSL/RectSL providers (which accept every `code` box) and a language's own provider
  would have shadowed one another depending on `@RunsAt(plugins)` order. `EmbeddedRectSL` no longer
  calls `EmbeddedStackSL` itself.
- **`LastRunNotes`** (`src/jvmMain/kotlin/afield/stdlib/LastRunNotes.kt`) is the record for languages
  whose partial programs are not side-effect free. A runtime calls `record(box, "code", code,
  execute.line, produced, stoppedAt, stopReason, notReached)` after a run; one provider for `code`
  (`installLastRunLineNotes`) reads it back. Entries keep the text of the lines they were recorded
  against: a note follows its lines when an edit above moves them (nearest match), says "changed
  since the last run" when the line's own text differs, and a fragment run replaces only the
  fragment's lines.
- **`Note.volatile`** (and `LineNoteReply.volatile` on the wire): the editor's `lineNoteCache` keys on
  the text hash, and pressing run does not change the text, so a volatile note is re-asked for rather
  than cached. Every last-run and Datalog note is volatile.
- **`LineNotePictures`**: Java2D stack and table pictures, PNG-encoded with `PngEncoder`, drawn at 2×
  (`SCALE`) and sent with `imageScale = 2`; the editor shows them through `srcset="… 2x"`, at their CSS
  size, so a Retina display does not upscale them (before/after: `line_notes_demo/evidence/forth-stack-1x-vs-2x.png`).
- **The note is split for the eye.** `Note.meta` (where it came from: "last run, 12 s ago") and
  `Note.value` (runs of text, results `strong`) travel as `LineNoteReply.meta`/`value`; the editor sets
  the value in the value-visualizer face (Iosevka 12 px) with the results bold, over a muted meta line.
  `caption` is still the whole sentence, which `ReadLineNotes` returns. StackSL/RectSL notes carry no
  value and show as before. Bold text rather than `.h` HTML of the value (the human's choice,
  2026-09-28): real `.h` would mean keeping the value object past the run — memory, and a mutable
  object would show today's state rather than the last run's.
- **Forth's `ok` marker** says what the run left: `ok · <3> 3.0 100.0 "done"`, the top value bold
  (`forthOkHtml`). Bold needs HTML, and the editor renders HTML output as a block under the line; a
  plain-text marker would stay inline beside it but could not be bold. A bare `ok` for an empty stack.

## Per language

| language | where | how |
| --- | --- | --- |
| Forth | `ForthInterpreter.onTopLevelStep`, `evalRecordingLineNotes` in `ForthInterface.kt` | top-level steps (a word, a literal, a whole `: … ;`, an `if … then`) report their line span; steps that share a line merge; the stack is snapshotted after each |
| Clojure | `ClojureHost.evalPage(onFormValue = …)` | each top-level form's `pr-str` under `*print-length*` 12 / `*print-level*` 4; an unrealised `LazySeq` is named, not printed |
| Python | `PythonLineNotes.SOURCE` (spliced into the startup text as `_field_line_notes`) | skips a leading docstring and the `__future__` imports; the recorder is bound under a fresh name that occurs nowhere in the cell, only while it runs; `repr` only for builtin scalars/short strings/small containers of them, else the type name; `AFIELD_PYTHON_LINE_NOTES=0` turns it off. Runs *after* `_capture_last_value_inplace`, so the captured last value is unchanged (verified: `x*2` → `6`, `a, b = 4, 5` → `4`, a trailing `def` → `ok`); JSON taken through `_exec_with_return2.__kwdefaults__['_line_notes'].take` after the run. `PythonLineNotesTest` runs the same source under plain `python3` (no JEP) for each of the review's five cases |
| FieldML | `Evaluator.onTopLevelLet`, `recordFieldMLLineNotes` in `FieldMLInterface.kt` | the evaluator walks the spine of top-level lets itself and reports each binding; the typed tree's `TExprLet` chain (same order) supplies the lines; the final expression is recorded when the run finishes. Launch (`launchAll`) does not record |
| Datalog | `DatalogLineNotes`, `DatalogParser.statements` | no record: queries the box's model on the main thread (2 s timeout); rules compared by text with anonymous variables renumbered |

Kotlin box code: not done. The K2 REPL compiles a box as one snippet and hands back its final value;
per-statement values would need the source rewritten before compilation. Morpho and Nano: not done —
Morpho's program is a circuit description whose result is one netlist, and Nano's VM would want a
register trace per instruction, which is a larger change than a note justifies tonight.

## What it costs

Measured 2026-09-28 on the branch's Field (M-series Mac). Method: each program run through the
language's own evaluator with and without recording, best of 15 after 5 warm-up runs (Forth:
`ForthInterpreter.eval` vs `evalRecordingLineNotes`; Clojure: `ClojureHost.evalPage` without and with
`onFormValue`; FieldML: `Evaluator.evaluate` without and with `onTopLevelLet` rendering each value — all
three inside a Kotlin box; Python: parse, rewrite, compile and exec of the cell under plain `python3`,
best of 30, 5 for the loop). The "before" column is the branch before `1244e69cc`. Populations are the
programs named in each row, nothing else; the scripts are
`.nightly-tmp/forthbench.txt`, `bench2.txt` and `pybench.py` in the nightly worktree (not tracked).
The public page states only the structural fact — per top-level step, never per loop iteration;
pictures on request — because nothing guards a number in prose.

| case | before | after |
| --- | --- | --- |
| Forth demo (7 lines) | +2.05 ms | +0.05 ms |
| Forth, 200 lines of `1 2 + drop` | +33.8 ms | ≈0 (noise) |
| Forth, one loop of 100k iterations | +0.6 ms | +0.08 ms |
| Forth, 2000 literals on one line | +87.8 ms | ≈0 (noise) |
| Forth, 200 lines leaving a 200-deep stack | +181.5 ms | +0.97 ms |
| Python demo (7 lines) | +0.064 ms | +0.043 ms |
| Python, 200 top-level assignments | +2.67 ms | +1.68 ms |
| Python, 5000 top-level statements | +70.9 ms | +51.6 ms |
| Python, a 1e6-iteration loop in one statement | ≈0 | ≈0 |
| Clojure demo (5 forms) / 200 defs / a 1e6 loop | — | +0.04 ms / ≈0 / ≈0 |
| FieldML demo (4 lets) / 200 lets | — | +0.001 ms / +0.03 ms |

Forth was paying for a PNG per line at record time (0.7 ms each at 1×, 3 ms at 2×) and for copying the
whole stack after every top-level step (quadratic in depth). Now a line keeps only its top 8 values and
its depth, snapshotted once per line (`endsLine`), and the picture is drawn only when that line's note is
asked for, then kept. Python's cost is the rewrite, about 10 µs per top-level statement (it was ~14):
recorder calls are four nodes built with their locations, so `ast.fix_missing_locations` has nothing to
fill in them; the walk over the larger tree is most of what is left. Nothing is added per loop iteration
in any language — only per top-level step.

## AI4

`ReadLineNotes(lines)` (`LineNoteTools.kt`), registered through `AgentToolExtensions`, offered when the
focused box's property has a provider and is not a plain Kotlin box's `code`. It returns captions and
the pictures as tool images.

## Docs figures

Pipeline captures, not the evidence PNGs: `public/docs/assets/forth_examples/line_note_stack.png` (the
note with its 2× stack picture, and the run's `ok · <3> …` marker), `public/docs/assets/python/line_note.png`
(`total = 55 · int`), `public/docs/assets/language_extensions/line_note_changed.png` (a stale note after
typing into the line). Each is a `capture="ui"` fence whose setup transcript
(`public/docs/assets/transcripts/line_notes_{forth,python}_setup.yaml`) builds the language root and box;
the drive runs the box with its own key (`Alt+0`) where it must, and moves the caret with
`PageUp`/`Home`/`ArrowDown` — the keys a person uses — then waits for the note's DOM. No new capture-system
option was needed. Forth fences with `data-lang=forth` are wrapped as a Kotlin call
(`renderForthDocsSnippet`), not run under a Forth root, so a plain `capture="editor"` Forth fence cannot
show the Forth `ok` marker; that is why the marker is in the `ui` figure.

## Verification

- `LastRunNotesTest` (8 tests) and `LineNotesTest` (3), pure.
- End to end on a running Field (port 9540) with the demo document
  `dev_docs/agents/line_notes_demo/metadata.edn`: each box run through the backdoor, then real
  `line-note` requests via `raw-send`, and the editor driven with Playwright (`page-eval` placing the
  caret) and captured with `screenshot-editor`. Crops are in `dev_docs/agents/line_notes_demo/evidence/`.
- The editor screenshot clip (`clipForRenderedEditorContent` in `Main.kt`) trimmed to the text and
  cut off the note at the foot of the pane; it now extends to a visible `.hoverlay-left` that holds a
  line note (only then, so other editor captures keep their clip).
