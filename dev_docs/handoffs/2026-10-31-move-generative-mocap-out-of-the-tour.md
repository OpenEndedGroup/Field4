# Move Generative Mocap out of the Tour

- **Handoff:** `2eb0ab9c-8fa3-4632-a0e4-304ab7c690b2` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/motion_recordings/
- **Filed by:** agent:claude, 2026-10-31 14:16
- **Close with:** `python tools/docs_notes.py handoff-close 2eb0ab9c "what happened"`

## The notes that led here

- `44da8312` (public/docs/motion_recordings.md:1): This page should not be in the tour --- refile somewhere else
  > Generative Mocap — ARDY and CLoSD

## What was asked

Marc's note on `public/docs/motion_recordings.md` (the H1, "Generative Mocap — ARDY and CLoSD"):
"This page should not be in the tour --- refile somewhere else".

## Why this is bigger than a page edit

The page's placement is in `public/zensical.yml:37`, inside `Start Here → A Tour`, between
"Frames and the Animation Loop" and "Drawing with FLines". The page needs two separately
installed tools (ARDY, CLoSD; see `optional/motion/README.md`), which is why it does not fit a
read-in-order first-contact arc. Moving it is a nav edit outside this page's partition, and the
Tour's prev/next chain changes with it.

## Proposed approach

Move the entry to `Drawing From Data → Time & Signals` in `public/zensical.yml`, directly after
"Springs & Character Movement" (`movement_examples.md`, around line 193), with a nav comment:
recorded and generated human motion is the bigger cousin of springs and steering, and it reads
`MotionRecording` clips as values over time.

Alternatives:
- `Graphics, GPU & Headsets` (line 366), since the replays render bodies on the canvas.
- `AI & Agents` (line 431), since ARDY/CLoSD are generative models.

After the move, check that no Tour page links to it as "the next stop" (grep `motion_recordings`
in `tour_*.md`, `animation_time_model.md`, `fline_examples.md`; currently only
`agent_transcripts.md` and generated reference pages link to it, which stay valid).

## Questions for the human

1. Which section: Time & Signals, Graphics, or AI & Agents?
2. Should the Tour get anything in its place (e.g. a one-line pointer from
   `animation_time_model.md`), or simply lose the stop?

## What the page needs afterwards

Nothing structural; its filename and anchors stay, so `agent_transcripts.md` links keep working.
