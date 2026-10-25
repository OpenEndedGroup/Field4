# Re-run dependent passes when a renderer or texture is rebuilt

- **Handoff:** `850bfef4-3d0e-42a3-8e60-6c72a2e24e21` (project)
- **Filed from:** https://openendedgroup.com/field4/aperture_playground/
- **Filed by:** human, 2026-10-16 08:48
- **Close with:** `python tools/docs_notes.py handoff-close 850bfef4 "what happened"`

Refiled from the code batch. Not started on 2026-10-16. This needs a dependency model for graphics resources: passes that know which renderer or texture they were built against, and re-run when it is rebuilt. That is a renderer-lifecycle design change rather than a fix.


Rule 25 sharp edges on `public/docs/aperture_playground.md`:

> You can also launch the renderer alone, then execute boxes 2–8 in number order. Relaunching only the renderer creates a fresh graphics context, so the passes must be executed again. ... After changing a texture's size, execute its downstream resource boxes again.

and on `public/docs/flow_grain.md`:

> Relaunch stages in their numbered order after rebuilding the resources box.

Both are manual dependency bookkeeping a tool could do: when a renderer box is relaunched, or an RTT/resource box is rebuilt with a new size, re-execute (or mark stale and offer to re-run) the boxes beneath it in graph order. Delete these sentences in the same change.
