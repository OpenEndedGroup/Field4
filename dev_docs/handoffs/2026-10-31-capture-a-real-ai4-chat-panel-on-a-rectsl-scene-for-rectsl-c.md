# Capture a real AI4 chat panel on a RectSL scene for rectsl_conversation Start

- **Handoff:** `99b9aff6-cffc-40ea-a161-3e2a650f41b9` (project)
- **Filed from:** https://openendedgroup.com/field4/rectsl_conversation/
- **Filed by:** agent:claude, 2026-10-31 14:11
- **Close with:** `python tools/docs_notes.py handoff-close 99b9aff6 "what happened"`

## The notes that led here

- `81692fba` (public/docs/rectsl_conversation.md:18): in this section we need a screenshot of some AI4 chat panel, otherwise nobody believes us
  > In any document, select a box (press n to make one) and choose Make a RectSL Scene from the command palette. A root box, RectSL window, appears above it and opens a window; the box becomes a scene, a wall with one turning gold square, and plays in it. Select the scene and press ctrl/command-4 to open AI4 chat. Configure an AI provider through Field's welcome page if you have not already; see AI features.

## What was asked

The human's note on `public/docs/rectsl_conversation.md` in "## Start" (the paragraph that ends "press **ctrl/command-4** to open AI4 chat") says: "in this section we need a screenshot of some AI4 chat panel, otherwise nobody believes us".

## Why this is bigger than a docs edit

A docs pass agent may not run the docs build or Field, and AGENTS.md section 3 forbids faking a capture. A believable picture shows the AI4 panel open on a RectSL scene with a real request and the model's reply. Making that picture takes a live AI provider and a real conversation, so it has to be captured in a session that has both.

## Evidence / precedent

- `public/docs/stackauto_conversation.md:455` already ships a real AI4 chat screenshot (`assets/stackauto_conversation/saved-chat.png`, a restored chat). Copy how that one was made.
- `public/docs/ai4_chat_archive.md:18` captures a chat outline through a runner fence from a saved conversation. That is a second route that does not call the model during the build.

## Proposed approach

1. Hold a short real conversation on a fresh **Make a RectSL Scene** box (for example, asking it to add a second square), and save the chat with the box.
2. Capture the Field window with the scene selected, the AI4 panel open, and the reply visible. Use the restored saved chat, as stackauto_conversation does, so recaptures don't call the model.
3. Put the image in `public/docs/assets/rectsl_conversation/` and place it right after the Start paragraph, with alt text that describes what the request and reply actually say.

Alternative: if a saved conversation can be rendered through the runner, use the `ai4_chat_archive` style of fence instead.

## Questions for the human

- Should it show a fresh first exchange, or a turn from the page's own conversation further down?
- Should it be a window capture (scene and chat together) or the chat panel only?

## What the page needs afterwards

One image line after `rectsl_conversation.md` "## Start" paragraph 1. No other prose changes.
