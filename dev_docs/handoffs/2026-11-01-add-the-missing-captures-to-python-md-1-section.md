# Add the missing captures to python.md (1 section)

- **Handoff:** `3d04801a-892e-42fb-bbfa-f532739af9ba` (project)
- **Filed from:** https://openendedgroup.com/field4/python/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 3d04801a "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### A notebook is a column of boxes (`public/docs/python.md:194`, low, needs-capture-system-work)

- **Point made:** Importing a .ipynb makes a python root with a column of Python cells and Markdown notes boxes beside them, but the reader never sees what that layout looks like.
- **Capture:** A window screenshot of the canvas after a real import of a small committed .ipynb, showing the python root, the connected cells and the notes boxes beside them. This needs a drive script or transcript op that calls the import action, since the import is a drag-and-drop menu choice.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
