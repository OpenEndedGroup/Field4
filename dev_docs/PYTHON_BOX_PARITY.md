# Python box parity with Kotlin boxes

Audit of 2026-10-15 (nightly), driven through the backdoor on real Python boxes. What a Kotlin box
gets and whether a Python box now gets it.

| Feature | Before | Now |
| --- | --- | --- |
| Runtime error marked on the raising line | Always the fragment's first line: JEP's Java stack reports line 1 for `exec` | Innermost traceback frame compiled from the cell (`PythonErrors.SOURCE`, `_field_error_location`) |
| Syntax error line | `-1` (fragment end): `ast.parse` used `<unknown>` and the regex expected `<string>` | Python's reported `lineno`; `ast.parse` is given the cell's `<repl:{id}>` filename |
| `display("channel", "<html>")` (Kotlin form) | Printed both strings as `<pre>` objects in `main` | Two strings, a bare channel name then markup, are taken as Kotlin's form |
| `slider` / `checkbox` with callbacks | Absent | Collected during the run, shown as one `controlGroup` per channel at the end; callbacks run on the main thread with `thisBox` set; values survive a re-run |
| `print` beside its line, last value, line notes | Already present | unchanged |
| Inherited properties / `fromContext` | Only through `PythonInterface.thisBox` and Java calls | Not done (see below) |

## How the pieces fit

- `src/jvmMain/kotlin/afield/python/PythonErrors.kt`: Python source for `_field_error_location`,
  plus `boxLine`/`describe`. `_exec_with_return2` and `aexec_with_return` record
  `_field_last_error` in their `except` blocks; `runOnce` reads it after a `JepException` before
  falling back to the old JEP-message regexes. Tested under plain `python3` by `PythonErrorsTest`.
- Controls: the Python callable stays in `_field_controls[id]`; `_field_pending_controls[box id]`
  holds specs. `runOnce`'s `finally` calls `PythonInterface.flushPythonControls`, which builds the
  `controlGroup`s; each control's callback is `invokePythonControl(box, id, value)` (dispatched
  `inMain`, so never re-entrant into a running cell).

## Not done, and why

- **Inherited properties for Python.** The Kotlin side is `prop<T>` / `Inheritance` plus K2 type
  information; a Python accessor would be a thin wrapper over raw property reads, which the box-code
  idioms discourage. Worth a design pass rather than a nightly.
- **No JVM test drives the controls path.** It needs JEP; the evidence is the backdoor run recorded
  in the worklog of branch `nightly/2026-10-15/1-python-box-parity` (slider moved to 7.5 and checkbox
  ticked from the page reached the Python callbacks; a re-run kept both).
- **Docs captures** for the two new fences were generated with `--page python.md` (mode always) and inspected.

Demo: `dev_docs/agents/transcripts/python_box_parity.yaml` and the saved document
`dev_docs/agents/demos/python_box_parity/`.
