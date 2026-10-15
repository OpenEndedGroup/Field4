# Rewrite the OpenDLSS-NR study boxes without fromContext and stdlib imports

- **Handoff:** `be52bc6a-fce4-4a2d-b117-1730a2a8dfac` (project)
- **Filed from:** https://openendedgroup.com/field4/open_dlss_nr/
- **Filed by:** human, 2026-10-11 12:37
- **Close with:** `python tools/docs_notes.py handoff-close be52bc6a "what happened"`

`public/docs/open_dlss_nr.md` shows the two study boxes (cube study and ink study) and the record box with
`val owner = fromContext<Renderer>()`, `import afield.document.context.fromContext`, and
`import afield.math.linalg.Vector4f` (DOCS_WRITING_RULES 7). The boxes sit beneath the renderer, so they
should use the preamble's `renderer`; the stdlib import should go. The study fences also print the
full 30-line control setup unfolded (rule 8). Rewrite the fences together with the downloadable
assets (`assets/opendlssnr/study.kt`, `cubes.kt`, `record.kt`) and the transcripts
`dev_docs/agents/transcripts/open_dlss_nr.yaml` / `open_dlss_nr_cubes.yaml`, then recapture on
linux-nvidia (`--page open_dlss_nr.md`, `FIELD_DOCS_CAPTURE_PROFILES=linux-nvidia`).
