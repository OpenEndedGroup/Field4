# Make the function conversation saved-chat image a runner capture

- **Handoff:** `5a20a546-1948-48a5-b95a-89f1fc24f122` (project)
- **Filed from:** https://openendedgroup.com/field4/function_conversation/
- **Filed by:** agent:claude, 2026-10-31 14:16
- **Close with:** `python tools/docs_notes.py handoff-close 5a20a546 "what happened"`

## The notes that led here

- `e0143316` (public/docs/function_conversation.md:112): ai chat screenshot is out of date; capture it again
  > model call.

## What was asked

Note e0143316 says `public/docs/assets/function_conversation/saved-chat.png` (embedded at `public/docs/function_conversation.md:114`) is out of date. Marc agreed it should become a docs-runner capture instead of a committed PNG, and is willing to pay for a doc build.

## Why it is bigger than a docs edit

The image is a committed hand capture. A runner capture needs a capture block that opens `function_conversation_example`, selects **The function — talk here (AI4)**, opens AI4 chat (ctrl/command-4), scrolls to the move-and-stretch exchange and screenshots the window. That is capture-system work (a `drive` script or transcript) plus a recapture build, and this pass may not run builds. The paragraph after the image (`function_conversation.md:116-120`, about "Model not recorded" badges) may be stale since the run-model header work, so it has to be checked against what the new capture shows.

## Proposed approach

Follow whatever produces `assets/stacksl_conversation/saved-chat.png` if that is already a runner capture. Otherwise write a drive script that loads the example document and opens chat through the backdoor's Playwright or Electron commands. Recapture with `FIELD_DOCS_RUNNER_MODE=always ... --page function_conversation.md`.

## Questions for the human

- Which turn should the capture be scrolled to? Is move-and-stretch still the right one?

## What the page needs afterwards

Replace the image line with the capture block, then check the paragraph after it against the new picture.
