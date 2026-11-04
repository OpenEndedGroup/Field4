# Fix weak or wrong captures on vision.md (fline-viewer.jpg)

- **Handoff:** `97431e94-2385-4535-b631-52a6984aba0c` (project)
- **Filed from:** https://openendedgroup.com/field4/vision/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 97431e94 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/vision/fline-viewer.jpg` — not-what-it-says, medium

**Problem.** fline-viewer.jpg and fline-editing.jpg are nearly the same frame (RMSE about 1.4%): the same room, the same orange helix with anchor dots, the same triangle and green curve. The viewer figure, which is meant to show the plain viewer before edit mode, looks the same as the edit-mode figure. The cyan Bézier control handles and control-polygon lines that the edit caption promises cannot be seen in the editing frame either. The FLine content is a small part of a 1600x900 room photo.

**Fix.** Recapture the viewer figure with edit mode off. Recapture the edit figure on a curve that has visible cyan control points, cropped or zoomed in on the FLine cage so the handles can be read at column width.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page vision.md` and look at each new asset at column width before closing.
