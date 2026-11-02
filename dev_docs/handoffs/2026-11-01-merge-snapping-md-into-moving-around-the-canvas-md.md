# Merge snapping.md into moving_around_the_canvas.md

- **Handoff:** `5d65dd5b-ade7-4f3e-8d6b-10e0b5844042` (crosscutting, blocking: notes on the page wait for this)
- **Filed from:** https://openendedgroup.com/field4/snapping/; also affects moving_around_the_canvas.md, staging.md
- **Filed by:** agent:claude, 2026-11-01 14:24
- **Close with:** `python tools/docs_notes.py handoff-close 5d65dd5b "what happened"`

## The notes that led here

- `b0938f3c` (public/docs/snapping.md:154): now you are just typing. Perhaps this whole page can get trimmed down and merged with another page
  > When to use it
Snapping helps when you arrange a document for presentation, or tidy a graph after a run of exploratory edits. It gets in the way while sketching, which is why it is off until you turn it on.

## What was asked

Note b0938f3c on `public/docs/snapping.md` asked for the page to be trimmed and merged into another page. The human chose the target in the thread: merge it into `public/docs/moving_around_the_canvas.md`.

## Why this is bigger than a docs edit

It changes two pages, the navigation, and the inbound links. That is a page merge, and my files for this pass are only `snapping.md`.

## Evidence

- `public/docs/snapping.md` (146 lines after this pass's trim). Its sections are "Turn snapping on", "What snaps", "Thresholds" and "Related features". It has a `capture=ui` video fence (`assets/snapping/snapping.mp4`, setup `assets/transcripts/snapping_setup.yaml`).
- `public/docs/moving_around_the_canvas.md:505` "Related features" already links snapping, at line 510.
- Nav entry: `public/zensical.yml:120` (`- Snapping: snapping.md`), and the comment on line 121 refers to it.
- Inbound links: `public/docs/staging.md:28`, `public/docs/moving_around_the_canvas.md:510`. `public/docs/funFeatures.md:817` embeds an older `assets/snapping.mp4`.

## Proposed approach

Add a `## Snapping` section to moving_around_the_canvas.md, after "Framing the selection" or "Bookmarks". Move in the capture=ui fence unchanged (same asset path and setup, so the existing capture is reused), the toggle instructions, what snaps, and the thresholds table. Then:

- delete snapping.md and its nav entry;
- repoint staging.md to `moving_around_the_canvas.md#snapping`;
- drop the self-link at line 510.

Add a redirect or stub if the site keeps old URLs.

Alternative: keep snapping.md as a one-line stub that points to the new section.

## Questions for the human

- Where in moving_around_the_canvas.md should the section go?
- Should the old `/field4/snapping/` URL redirect?

## What the page needs once done

Nothing. snapping.md goes away.
