# Register a study's source property as persistent automatically

- **Handoff:** `9af69daf-7dff-4dbf-b7aa-2a8b59ee3d58` (project)
- **Filed from:** https://openendedgroup.com/field4/studies/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 9af69daf "what happened"`

Rule 25 sharp edge on `public/docs/studies.md`, the note "Saving the property":

> A property is written to the document on save only if its name is in
> `Box.ioPersistentProperties`, and a name you make up, such as `rings`, is not. The study's
> revisions are files under `studies/` and survive, but the box's own text does not unless you add
> the name: `Box.ioPersistentProperties.add("rings")`. The starting point below does this in its
> first box.

A study whose source is lost on save is a trap. Fix: `sourceFramesStudy(property, ...)` (and `SourceStudy` generally, in `afield.study.source`) should register its property as persistent itself. Then drop the manual `add` from the studies_rings starting point and delete this note in the same change. The page's fences and the transcript may need a recapture if the first box's code changes.
