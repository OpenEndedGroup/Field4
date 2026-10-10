# Rewrite the stack.auto box fences without fromContext and stdlib imports

- **Handoff:** `024951fb-68fb-49da-9f27-9ce394478976` (project)
- **Filed from:** https://openendedgroup.com/field4/stacksl_examples/
- **Filed by:** human, 2026-10-10 09:11
- **Close with:** `python tools/docs_notes.py handoff-close 024951fb "what happened"`

`public/docs/stacksl_examples.md`, section "`stack.auto`: push-constant-style injection", has two fences that break the box-code rules (DOCS_WRITING_RULES 7):

- the box `code` fence finds the renderer with `fromContext()` and the prose before it explains the parent walk; a box beneath the renderer already has `renderer` from the preamble;
- the "Its numbers, live" fence constructs `afield.stdlib.KeyframeScope()` with a fully qualified stdlib name.

Rewrite both fences the way a box is written, run them beneath a renderer (nothing in the docs build runs them today), update the rank-carets pins, then trim the sentence "`fromContext()` finds the renderer by walking up the box's parents…" to match.
