# Recapture the interactive guide notes in their finished state

- **Handoff:** `841a2c36-7125-4cf3-9af2-c9321d0668f0` (project)
- **Filed from:** https://openendedgroup.com/field4/interactive_guides/
- **Filed by:** human, 2026-10-03 18:32
- **Close with:** `python tools/docs_notes.py handoff-close 841a2c36 "what happened"`

The three guide captures on `public/docs/interactive_guides.md` show the wrong state, by their own alt text:

- `basic_slideshow.png` is caught part-way through the note's reveal animation: the bullets have markers but no text, and the Home/Continue links have not appeared. Wait for the animation (or pass `animated = false` in the capture) so the finished note shows.
- `callbacks_and_templates.png` shows every `{{version}}`, `{{clickCount}}`, `{{userChoice}}` slot empty. The section is about live template values, so the capture shows the feature not working. Find out whether template expansion from script variables is broken (a runtime bug) or the capture fires before expansion, and fix that, then recapture.
- `slide_chat.png` prints `\"templateId\"` with literal backslashes because the fence escapes quotes inside a Kotlin raw string. Change the fence to plain quotes (fence bytes are pinned by `tools/completion-corpus/rank-carets.tsv`; update it) and recapture.

Recapture through the runner only.
