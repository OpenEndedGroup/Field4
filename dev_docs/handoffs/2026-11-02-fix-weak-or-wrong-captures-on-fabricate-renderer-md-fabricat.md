# Fix weak or wrong captures on fabricate_renderer.md (fabricate-tonemap-bloom.png)

- **Handoff:** `6e38dd67-6d75-4efd-a349-72e46f284cd9` (project)
- **Filed from:** https://openendedgroup.com/field4/fabricate_renderer/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 6e38dd67 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/fabricate-tonemap-bloom.png` — not-what-it-says, high

**Problem.** The prose says the left half is raw HDR with highlights blown out to flat white and the right half is bloom + ACES with glowing highlights. The two halves are nearly the same. Neither half has blown-out white areas and neither has visible bloom haloes. The left half only has a slightly lighter background. The alt text describes yet another pairing (tone mapping alone vs bloom added), so the prose and alt text also disagree with each other.

**Fix.** Light the scene hot enough that the raw left half really clips, and turn bloom up until the haloes are plainly visible on the right. Then make the prose and alt text describe the same pair of renders.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page fabricate_renderer.md` and look at each new asset at column width before closing.
