# Capture the ForthField frame spinner

- **Handoff:** `d5a6c3cb-2899-4f65-8ea1-914ed2b58289` (project)
- **Filed from:** https://openendedgroup.com/field4/forth_examples/
- **Filed by:** human, 2026-10-10 18:14
- **Close with:** `python tools/docs_notes.py handoff-close d5a6c3cb "what happened"`

`public/docs/forth_examples.md`, section "Launch mode and `frame`", shows a `spinner` word that animates a line each tick but has no capture (a plain `diffstatic` fence). Every UI feature gets a real capture: give it a `capture="ui"` video or a short frame sequence from a launched ForthField box through the runner (no hand recording). The fence text may stay as it is.
