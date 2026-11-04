# Fix weak or wrong captures on path_tracing.md (clamp_off.png)

- **Handoff:** `9c5d0185-0ee4-4bf2-9c1d-d70b115ca8c4` (project)
- **Filed from:** https://openendedgroup.com/field4/path_tracing/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 9c5d0185 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/path_tracing/clamp_off.png (with clamp_on.png)` — not-what-it-says, high

**Problem.** The clamp-off and clamp-on captures cannot be told apart: RMSE between them is 0.28%. The alt text promises 'bright isolated specks scattered over the spheres and floor' with the clamp off, but side by side at column width both images show the same smooth render. The prose says the clamp value was chosen 'so the difference shows at this size', and it does not show.

**Fix.** Choose a scene or sample count where fireflies actually appear, such as a small bright emitter, caustics through the glass sphere, or 1-4 spp, and/or add a cropped, magnified inset of the same region in both images. Check that the two captures differ visibly before keeping them.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page path_tracing.md` and look at each new asset at column width before closing.
