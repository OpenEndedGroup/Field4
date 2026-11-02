# Fix weak or wrong captures on imaging_examples.md (04-corners.png, 07-pyramids.png)

- **Handoff:** `bb0e148b-dcc0-40ea-994f-f18fb8059ccd` (project)
- **Filed from:** https://openendedgroup.com/field4/imaging_examples/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close bb0e148b "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/imaging_gallery/04-corners.png` — weak-illustration, medium

**Problem.** Two of the four panels show nothing. harrisResponse (coolwarm, signed) is a flat pale grey square with a faint outline, and shiTomasiResponse (viridis) is a solid purple square. The response peaks at the four corners, which are the point of these two panels, cannot be seen at all.

**Fix.** Normalise each response map to its own max (or log/gamma-scale it) before colour-mapping, so the four corner peaks show up as bright spots.

## `assets/imaging_gallery/07-pyramids.png` — weak-illustration, medium

**Problem.** The source is a fine diagonal pattern that aliases into moiré, so Gaussian levels L0 to L4 look almost identical. The Laplacian bands are faint red/blue noise. The figure does not show detail separating out by scale. At column width the per-panel captions are about 6 px tall.

**Fix.** Use a natural image or a source with structure at several scales (edges plus fine texture), so each Gaussian level visibly blurs and each Laplacian band holds different detail. Use fewer panels or larger tiles.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page imaging_examples.md` and look at each new asset at column width before closing.
