# Recapture the function conversation saved-chat image

- **Handoff:** `be78afd3-d97c-40a1-94d2-e0de05866976` (project)
- **Filed from:** https://openendedgroup.com/field4/function_conversation/
- **Filed by:** agent:claude, 2026-11-22 10:14
- **Close with:** `python tools/docs_notes.py handoff-close be78afd3 "what happened"`

## The notes that led here

- `e0143316` (public/docs/function_conversation.md:113): ai chat screenshot is out of date; capture it again
  > model call.

## What was asked

Note e0143316 on `public/docs/function_conversation.md` (the `saved-chat.png` figure under "A worked conversation") says the AI chat screenshot is out of date again and asks for a fresh capture.

## Why this is not a docs edit

The figure `public/docs/assets/function_conversation/saved-chat.png` was last captured on 2026-11-01 (commit dca8e1510c) by `tools/capture_function_conversation.py`, which drives a live Field. This docs pass may not start Field, run the docs build or run Gradle, so the capture cannot be taken here. Hand-editing the PNG is not allowed (AGENTS.md §3, "A capture exercises the real code").

## Proposed approach

1. Run `tools/capture_function_conversation.py` against a current build, keeping the scroll position it uses now ("move-and-stretch").
2. Look at the new PNG at column width. Check that the chat chrome matches the current AI4 UI, and that the run-record model badges ("gpt-6-astra · OpenAI (from the run's record)") still appear.
3. If the script fails because the AI4 chat DOM has changed, fix the script's selectors. Do not fake the image.

The alternative is to make it a docs-runner capture, so it refreshes automatically when the UI changes. The earlier handoff 5a20a546 kept it as a script on purpose, to match the sibling conversation pages.

## Questions for a human

- Keep the script capture, or move this figure (and its siblings) into the runner now that it has gone stale twice?

## What the page needs afterwards

Nothing beyond the new PNG, unless the badge text changes. If it does, update the paragraph above the figure (lines ~110–113).
