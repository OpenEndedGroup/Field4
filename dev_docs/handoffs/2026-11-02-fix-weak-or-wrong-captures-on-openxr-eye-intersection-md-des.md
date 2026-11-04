# Fix weak or wrong captures on openxr_eye_intersection.md (desktop.png)

- **Handoff:** `18885072-0981-40e7-af2f-990c1afe2ca7` (project)
- **Filed from:** https://openendedgroup.com/field4/openxr_eye_intersection/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 18885072 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/openxr_eye_intersection/desktop.png` — legibility, medium

**Problem.** This 4112x2524 native-window capture draws the three reference cubes and the dashed EXT ray as 1-px hairlines on black. At column width the cubes are specks and the ray is invisible, though the alt text names both. Most of the frame is empty black above the horizon.

**Fix.** Frame the camera tighter on the cubes and ray, draw thicker or brighter lines (or downsample with line thickening), and crop the empty sky.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page openxr_eye_intersection.md` and look at each new asset at column width before closing.
