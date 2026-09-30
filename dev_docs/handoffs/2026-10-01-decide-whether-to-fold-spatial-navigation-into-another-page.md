# Decide whether to fold Spatial Navigation into another page

- **Handoff:** `ccdbe841-40f5-4f4e-8e8c-7734136d7eec` (crosscutting, blocking: notes on the page wait for this)
- **Filed from:** https://openendedgroup.com/field4/spatial_navigation/; also affects keybindings.md
- **Filed by:** agent:claude, 2026-10-01 00:34
- **Close with:** `python tools/docs_notes.py handoff-close ccdbe841 "what happened"`

## The notes that led here

- `40a0f539` (public/docs/spatial_navigation.md:1): I'm not actually sure this is worth a whole page
  > Spatial Navigation

## Asked

Note 40a0f539 on `public/docs/spatial_navigation.md:1`: "I'm not actually sure this is worth a whole page."

## Why this is bigger than a docs edit

Deciding whether the page survives means merging, moving or deleting it. That touches the nav (`public/zensical.yml`) and the pages that link here: `staging.md`, `snapping.md`, `document_history.md`, `capture_limits.md` and `keybindings.md`. The note sits on the title, so editing the prose would not answer it.

## Evidence

- The page is 203 lines. Sections: Cmd Cmd and hold (l.8), What the overlay draws (l.27, most of the page, with captures), How the four are chosen (l.147), Bookmarks (l.157), Slots inside the overlay (l.176), Persistence (l.185), Limits (l.191), Related features (l.199).
- `keybindings.md` already lists the gesture, and the page's own Bookmarks section overlaps other navigation material.

## Proposed approach

1. Fold "Cmd Cmd and hold" and "How the four are chosen" into a short section of a canvas-navigation or keybindings page, with one capture.
2. Move Bookmarks and Persistence to wherever bookmarks are documented.
3. Drop or shorten the long "What the overlay draws" walkthrough.
4. Redirect or delete `spatial_navigation.md`, and fix the inbound links and the nav.

Alternative: keep the page but cut it to about 40 lines.

## Questions for the human

- Should it be merged, and into which page? `keybindings.md`, a canvas/navigation page, or a new "moving around the canvas" page?
- Do the overlay captures stay, and if so how many?
- Where do bookmarks belong?

## Page afterwards

Either the page is gone and the inbound links point at the new home, or it has been trimmed.
