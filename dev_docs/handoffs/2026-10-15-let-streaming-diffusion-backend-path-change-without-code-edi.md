# Let streaming diffusion backend path change without code edit

- **Handoff:** `9c7ee800-2465-4134-97dc-40a7e2b89250` (project)
- **Filed from:** https://openendedgroup.com/field4/streaming_diffusion/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 9c7ee800 "what happened"`

Rule 25 sharp edge on `public/docs/streaming_diffusion.md`, "Install the backend":

> If Field is already running, replace the curves box's `root` expression with `java.io.File("/path/to/sd2")` and run the box again.

This is a workaround for the backend path being read only from the environment Field started with. Fix in code: let the starting point / `StreamDiffusionSupport` take the backend root from a setting a running Field can change (e.g. a box property or text control in the curves box, or re-reading a user config file), so editing code is not required. Delete the workaround sentence in the same change (the starting point in dev_docs/agents/transcripts/streaming_diffusion.yaml may need regenerating).
