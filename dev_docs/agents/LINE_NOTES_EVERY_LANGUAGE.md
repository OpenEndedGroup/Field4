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
- **`LineNotePictures`**: Java2D stack and table pictures, PNG-encoded with `PngEncoder`.

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

## AI4

`ReadLineNotes(lines)` (`LineNoteTools.kt`), registered through `AgentToolExtensions`, offered when the
focused box's property has a provider and is not a plain Kotlin box's `code`. It returns captions and
the pictures as tool images.

## Verification

- `LastRunNotesTest` (8 tests) and `LineNotesTest` (3), pure.
- End to end on a running Field (port 9540) with the demo document
  `dev_docs/agents/line_notes_demo/metadata.edn`: each box run through the backdoor, then real
  `line-note` requests via `raw-send`, and the editor driven with Playwright (`page-eval` placing the
  caret) and captured with `screenshot-editor`. Crops are in `dev_docs/agents/line_notes_demo/evidence/`.
- The editor screenshot clip (`clipForRenderedEditorContent` in `Main.kt`) trimmed to the text and
  cut off the note at the foot of the pane; it now extends to a visible `.hoverlay-left` that holds a
  line note (only then, so other editor captures keep their clip).
