# Make Simplify keep shared key times across linked keyframe lanes

- **Handoff:** `94893ad9-35c4-46c7-8449-4da7ca8bd622` (project)
- **Filed from:** https://openendedgroup.com/field4/keyframe_editor/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close 94893ad9 "what happened"`

Rule 25 sharp edge on `public/docs/keyframe_editor.md`. Two paragraphs exist only to warn that Simplify is not lane-aware:

> A take from a gesture pad bakes into two lanes whose keys share their times. Smoothing a lane leaves its times alone, but simplifying the lanes one at a time does not. To thin out a take, smooth it and bake it again, as in [A shaky take](#a-shaky-take).

> Using **Simplify** on each lane separately would give each lane different key times.

Fix in code: make `simplify(tolerance)` (and the curve-menu Simplify slider) keep shared key times across linked lanes, for example by simplifying lanes that share key times jointly (keep a time if any lane needs it), or by giving the editor/`bakeGesture` lanes a joint `simplify` entry point. Then delete both paragraphs in the same change, and shorten the "A shaky take" recipe if the bake-smooth-rebake round trip is no longer needed.
