# Move line notes off the language-extensions page

- **Handoff:** `5309c1a8-2309-4642-a39a-5f75b8df3b20` (project)
- **Filed from:** https://openendedgroup.com/field4/language_extensions/
- **Filed by:** human, 2026-10-11 12:10
- **Close with:** `python tools/docs_notes.py handoff-close 5309c1a8 "what happened"`

`language_extensions.md` is a page for people writing a browser-side language extension (the
`FieldLanguageExtensions` JavaScript registry). Its "Line notes: what a line did" section
(anchor `#line-notes`) is a user feature of the editor for every language — StackSL, RectSL, Forth,
Clojure, Python, FieldML, Datalog, Pd — and is roughly half the page. A user looking for "what does
this line evaluate to" will not look under "language extensions", and the extension author does not
need the Python `__repr__` policy.

Proposal: move the section to its own page (or to the editor/running-code section of the nav),
keep a one-paragraph pointer here saying an extension can register a line-note provider, and update
inbound links (`forth_examples.md`, `python.md`, `clojure.md`, `datalog.md`, anything linking
`language_extensions.md#line-notes`). The `line-note-changed` capture fence moves byte-identical.
