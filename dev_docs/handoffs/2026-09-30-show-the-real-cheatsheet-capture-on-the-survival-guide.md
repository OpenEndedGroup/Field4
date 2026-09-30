# Show the real ? cheatsheet capture on the Survival Guide

- **Handoff:** `3a15966f-b71b-4976-991d-0b7027fd73ac` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/keybindings/; also affects survivalGuide.md
- **Filed by:** agent:claude, 2026-09-30 20:42
- **Close with:** `python tools/docs_notes.py handoff-close 3a15966f "what happened"`

## The notes that led here

- `9a0dfb28` (public/docs/keybindings.md:14): This image is a better image than the one that starts on the survival guide?
  > Press

## What was asked

Note `9a0dfb28` on `public/docs/keybindings.md` (the paragraph introducing the `?` cheatsheet
capture): "This image is a better image than the one that starts on the survival guide?" Read as:
the survival guide should show this real capture of the `?` card instead of what it has now.

## Why this is bigger than a docs edit on this page

The change is to another page, `public/docs/survivalGuide.md`, which this pass does not own:

- `survivalGuide.md` lines 5–22 say "Right now it looks like this:" and then show a hand-written
  block of `<span class="leftKey">` rows between two `<hr>`s. It is not a picture of the card and is
  not generated from the bindings, so it can drift (it already says `command-space` for autocomplete,
  which Monaco binds as `Ctrl+Space`, and "groups"/"ungroups" for `g`/`u`, which the command list
  calls Connect/Disconnect).
- `keybindings.md` has a real runner capture of the card: the hidden fence `name="keybindings-cheatsheet"`
  → `assets/keybindings/cheatsheet.png`, which resets the watcher to the defaults, unrolls the card,
  presses `?`, and fails if the card did not open.
- A separate open handoff, "Recapture the survival-guide intro as a capture=ui video"
  (`dev_docs/handoffs/2026-09-30-recapture-the-survival-guide-intro-as-a-capture-ui-video.md`),
  covers the `intro.apng` figure just below; this one is the cheatsheet block above it.

## Proposed approach

Replace the hand-written `<span>` block in `survivalGuide.md` with the same capture. Options:

1. Reference the existing asset: `![Field's ? cheatsheet](assets/keybindings/cheatsheet.png)`.
   Cheapest; the image stays owned by the keybindings fence (check the docs build does not prune
   assets referenced only from another page).
2. Copy the hidden capture fence into the survival guide with its own `screenshot=` path, so each
   page owns its figure. Duplicated fence, but independent.

Keep a short text list of the five or six shortcuts a beginner needs (n, select, F1, alt-0,
alt-return, alt-click) if Marc wants skimmable text next to the picture — the full card is long.

## Questions for a human

- Replace the text block entirely, or picture plus a trimmed text list?
- Share the asset (option 1) or own a second capture (option 2)?

## What this page needs once done

Nothing; `keybindings.md` already has the capture. Close note `9a0dfb28` when the survival guide
shows it.
