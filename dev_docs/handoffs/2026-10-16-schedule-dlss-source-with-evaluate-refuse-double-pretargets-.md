# Schedule DLSS source with evaluate; refuse double preTargets registration

- **Handoff:** `69d0c231-ac75-4d15-b001-9b893a43be5d` (project)
- **Filed from:** https://openendedgroup.com/field4/reconstruction_plumbing/
- **Filed by:** human, 2026-10-16 08:48
- **Close with:** `python tools/docs_notes.py handoff-close 69d0c231 "what happened"`

Refiled from the code batch. Not started on 2026-10-16. DLSS runs only on NVIDIA and this session was on macOS.


Rule 25 sharp edge on `public/docs/reconstruction_plumbing.md` ("Record producer, then consumer"):

> Do not also register `source` independently in `preTargets`: that would render it twice. Likewise, binding `neural.output` alone will not call `evaluate` for you.

Fix in code: make `DlssReconstruction` own scheduling of its source (e.g. a `neural.schedule(source)` / Target that records source.render then evaluate), or detect and refuse a source RTT that is also registered in `renderer.renderList.preTargets` while being evaluated, with a clear error. Delete the paragraph's warning sentences in the same change.

## Completed on Linux/NVIDIA, 2026-10-17

The source-owning evaluate overload records producer then reconstruction and rejects independent preTargets registration, including late registration. Real SR controls, rerun and Stop passed.

Evidence and reproduction: [Linux/NVIDIA verification](../lighting/ELSEWHERE_20261017.md). The originating annotation store is absent on this host; this tracked record carries the outcome for closing there after landing.
