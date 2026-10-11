# Capture a rewrite rule's expansion preview on pattern_rewrite.md

- **Handoff:** `610bd11b-984c-456f-ad03-ac5c0d4f36a1` (project)
- **Filed from:** https://openendedgroup.com/field4/pattern_rewrite/
- **Filed by:** human, 2026-10-11 22:34
- **Close with:** `python tools/docs_notes.py handoff-close 610bd11b "what happened"`

`public/docs/pattern_rewrite.md` describes how a `rewrite { … }` rule expands the source before the
compiler sees it, but the page has no picture of that. The editor shows an expansion preview of a
rule, and that is the thing to show.

Add a runner capture (a `capture="ui"` fence or an operation-transcript drive) with one rule from
the page open in the editor and its expansion preview visible, framed at column width with no empty
editor below it. Use a rule the page already teaches; `neg(x) → -x` or the `twice(3 + 4)` precedence
example are good choices. Filed when handoff e04ae701 closed (2026-10-11), which linked the page's
two demos as starting points and left out this suggested capture.
