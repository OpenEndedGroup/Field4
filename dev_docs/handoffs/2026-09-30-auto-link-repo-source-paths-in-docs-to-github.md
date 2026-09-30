# Auto-link repo source paths in docs to GitHub

- **Handoff:** `9b508085-5e78-4ca1-95ea-973eb5d526f4` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/mesh_examples/; also affects puppet_browser.md, computed_ray_geometry.md
- **Filed by:** agent:claude, 2026-09-30 20:53
- **Close with:** `python tools/docs_notes.py handoff-close 9b508085 "what happened"`

## What was asked

A note on `public/docs/mesh_examples.md` (the "Source:" line under "Field integration") asks: "why isn't this and others like it a link?" The page cited `src/...` and `dev_docs/...` paths only as inline code. I fixed that page by hand: each repo path now links to `https://github.com/OpenEndedGroup/Field4/blob/main/<path>`. The same pattern (a repo path in backticks, not linked) runs through most of `public/docs/`, so fixing it everywhere is past what one page agent should do.

## Evidence

- Only two pages link repo files to GitHub: `public/docs/puppet_browser.md:64` (plus three more lines there) and one line in `public/docs/computed_ray_geometry.md`. That makes 6 occurrences in 2 files (grep for `github.com/OpenEndedGroup/Field4/blob/main/(src|dev_docs)`).
- Most other pages give `src/jvmMain/...` and `dev_docs/...` paths as plain inline code.
- No hook turns these into links: `public/docs_hooks.py` only uses source paths for capture digests.

## Proposed approach

1. Preferred: add a Markdown hook, or a zensical/pymdownx extension setting, that turns an inline-code span into a GitHub `blob/main` link when it is exactly a repo path (starts with `src/`, `dev_docs/`, `tools/`, `public/` or `server/`) and that path exists in the checkout. The hook resolves only against paths that exist, so a stale path stays plain text. Report stale paths in the build log, or fail the build on them.
2. Alternative: a one-off rewrite across pages, like the hand edit on mesh_examples. It is cheap, but new pages will fall behind, and a moved file leaves a dead link that nothing checks.
3. Either way, pin links to `main` or to the built commit. Pinning to the commit keeps line anchors stable but makes the URL noisier.

## Questions for the human

- Is `github.com/OpenEndedGroup/Field4` public, and is it the right base URL for readers? (`puppet_browser.md` assumes it is.)
- Should `dev_docs/` links exist on the public site at all, or should dev notes stay unlinked?
- Link to `main` or to the commit the docs were built from?
- When the in-Field docs iframe is showing a local checkout, should these open the local file instead?

## What the page needs afterwards

If option 1 lands, the hand-written links on mesh_examples.md, puppet_browser.md and computed_ray_geometry.md can go back to plain backticks. Or they can stay: the hook should leave spans that are already links alone.
