# Ship sound_path_map as a welcome-screen starting point, or drop the section

- **Handoff:** `56eede23-d014-4acb-bd54-ede60304636b` (project)
- **Filed from:** https://openendedgroup.com/field4/sound_paths/
- **Filed by:** agent:claude, 2026-09-30 20:33
- **Close with:** `python tools/docs_notes.py handoff-close 56eede23 "what happened"`

## The notes that led here

- `57ec0c32` (public/docs/sound_paths.md:256): this is not a good way of linking to anything. It's either a starting place or users can't get to it
  > A box that plays the comparison
A loadable Field document is committed at public/docs/assets/sound_path_map/. Open public/docs/assets as the workspace and sound_path_map as the document, then run the boxes top to bottom: the first prints the map, the second plays the three renders back to back and plots their short-time brightness so you can see the filters as well as hear them.

## What was asked

The last section of `public/docs/sound_paths.md`, `## A box that plays the comparison`, tells the
reader to open `public/docs/assets` as the workspace and `sound_path_map` as the document. The human:
"this is not a good way of linking to anything. It's either a starting place or users can't get to it."

## Why this is bigger than a docs edit

A reader of the published site does not have the repo, so a repo path is unreachable. Other pages
point at welcome-screen **starting points** instead (`drawing_and_notation.md` "Open
**drawing_notation** from the welcome screen's starting points … Or browse it in the offline viewer",
`pure_data.md`, `studies.md`, `simulation_clock.md`). Those are shipped via the agent-transcript
gallery (`public/docs/assets/agent_transcripts/<name>/`, see `public/docs/agent_transcripts.md` and
`dev_docs/agents/transcripts/`). `public/docs/assets/sound_path_map/` already holds `metadata.edn`,
`transcript.edn` and two box sidecars (`sound_map_paths_code_.kt`, `sound_map_compare_code_.kt`), so it
is close to that shape but is not registered as a starting point. Registering it means touching the
transcript gallery / welcome-screen list, outside this page.

## Proposed approach

1. Promote `sound_path_map` to a starting point (move or register it with the agent-transcript
   gallery so the welcome screen lists it), then rewrite the section as "Open **sound_path_map** from
   the welcome screen's starting points", plus an `offline.html?file=…` browse link like
   `drawing_and_notation.md`.
2. Alternative: delete the section from the page and leave the document as internal test material.

Note the dependency on the other handoff from this page ("replace the comparison input"): the
`compare` box plays those three renders, so if the input changes, the starting point should ship the
new one.

## Questions for a human

- Starting point, or drop the section?

## What the page needs afterwards

Replace the paragraph under `## A box that plays the comparison` with the welcome-screen wording (or
remove the section and the `---` before it).
