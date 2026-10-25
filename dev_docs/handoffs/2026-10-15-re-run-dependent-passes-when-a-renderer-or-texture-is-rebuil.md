# Re-run dependent passes when a renderer or texture is rebuilt

- **Handoff:** `75d54059-86c6-4843-87e0-b888ef201d81` (project)
- **Filed from:** https://openendedgroup.com/field4/aperture_playground/; also affects flow_grain.md
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close 75d54059 "what happened"`

Rule 25 sharp edges on `public/docs/aperture_playground.md`:

> You can also launch the renderer alone, then execute boxes 2–8 in number order. Relaunching only the renderer creates a fresh graphics context, so the passes must be executed again. ... After changing a texture's size, execute its downstream resource boxes again.

and on `public/docs/flow_grain.md`:

> Relaunch stages in their numbered order after rebuilding the resources box.

Both are manual dependency bookkeeping a tool could do: when a renderer box is relaunched, or an RTT/resource box is rebuilt with a new size, re-execute (or mark stale and offer to re-run) the boxes beneath it in graph order. Delete these sentences in the same change.
