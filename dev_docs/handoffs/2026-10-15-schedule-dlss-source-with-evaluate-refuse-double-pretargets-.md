# Schedule DLSS source with evaluate; refuse double preTargets registration

- **Handoff:** `32d3f20f-1687-4364-9023-d17e60091019` (project)
- **Filed from:** https://openendedgroup.com/field4/reconstruction_plumbing/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 32d3f20f "what happened"`

Rule 25 sharp edge on `public/docs/reconstruction_plumbing.md` ("Record producer, then consumer"):

> Do not also register `source` independently in `preTargets`: that would render it twice. Likewise, binding `neural.output` alone will not call `evaluate` for you.

Fix in code: make `DlssReconstruction` own scheduling of its source (e.g. a `neural.schedule(source)` / Target that records source.render then evaluate), or detect and refuse a source RTT that is also registered in `renderer.renderList.preTargets` while being evaluated, with a clear error. Delete the paragraph's warning sentences in the same change.
