# Publish gpu_state with captured output areas for the offline viewer

- **Handoff:** `261404da-d628-40a7-915f-55e6bf5e8ad9` (project)
- **Filed from:** https://openendedgroup.com/field4/gpu_state/
- **Filed by:** agent:claude, 2026-11-01 14:22
- **Close with:** `python tools/docs_notes.py handoff-close 261404da "what happened"`

## The notes that led here

- `deb4cdfd` (public/docs/gpu_state.md:374): embed with our offline viewer with captured outputareas
  > It ships as a starting point, so the shortest route is the welcome screen, or open the document directly:

The human asked (note deb4cdfd, `public/docs/gpu_state.md` "## Open it") for the gpu_state document to be embedded in the offline viewer *with its captured output areas*.

The iframe embed is now on the page (`offline.html?file=assets/agent_transcripts/gpu_state/metadata.edn`), but it shows boxes and code only. Captured outputs come only through a web publication: `src/commonMain/resources/offline-outputs.js#resolvePublication` looks the file up in `offline-publications.json` and requires an entry under `assets/web_publications/<name>/document/` with an `outputs.json` beside it. No such publication exists for gpu_state (`public/docs/assets/web_publications/` has none).

Producing one means running the real three-box scene (renderer, sim, draw) on a GPU and capturing its output channels, chiefly the `probe` card from `fieldProbeHere` — the same run handoff e4d82043 needs for its screenshot. A capture must exercise the real code (AGENTS.md §3).

Proposed approach: publish gpu_state through the same pipeline as the existing `assets/web_publications/*` entries, after the particle scene settles, and point the page's iframe at the published file (or rely on the registry redirect, which already happens for the current `file=` path once registered). Alternative: do it together with e4d82043 in one capture session.

Questions: should the native window snapshot also be published as an output (as `fieldml_suspend_demo` does with `snapshotWindow`)? Which machine profile owns the capture?

Once done: confirm the iframe shows the probe card and, if needed, add `&amp;focus=outputs` to its src.
