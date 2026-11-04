# Add transcript and lesson links with side previews

- **Handoff:** `78b1717f-71b4-4e22-b0b6-357249ecd7f5` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/afield_four_graphics/; also affects agent_transcripts.md
- **Filed by:** agent:claude, 2026-10-31 14:11
- **Close with:** `python tools/docs_notes.py handoff-close 78b1717f "what happened"`

## The notes that led here

- `097a9644` (public/docs/afield_four_graphics.md:539): make this an openable link with a side preview like the agent transcript gallery
  > dev_docs/agents/transcripts/threebox.yaml
- `bc7f5f89` (public/docs/afield_four_graphics.md:901): again, add preview, offline views on the right, it will help with the lack of screenshots if nothing else.
  > The graphics-scene transcripts threebox and spin_glass in agent transcripts are runnable versions of the same producer/consumer shape.
- `1b1e9c17` (public/docs/afield_four_graphics.md:1840): embedded preview links here please instead
  > For the two-box shape, the threebox and spin_glass agent transcripts are runnable versions.
- `69379b5f` (public/docs/afield_four_graphics.md:537): gl_VertexIndex/6 and gl_VertexIndex%6 seem very differently syntax highlighted here
  > gl_VertexIndex/6 picks the particle and gl_VertexIndex%6

Marc asks, three times on `public/docs/afield_four_graphics.md`, for mentions of the `threebox` and `spin_glass` transcripts (and `dev_docs/agents/transcripts/threebox.yaml`) to become openable links with a preview on the right, "like the agent transcript gallery". He also asks for offline-viewer previews where captures are missing. The places are the end of "Compute particles, then draw them", the end of "Render to texture", and "Where these examples come from".

This is bigger than a docs edit because the page has no inline component for this. The gallery in `public/docs/agent_transcripts.md` renders its previews through its own runner fences (`hidden=true`, `macro-file=...`). A reusable "transcript link with side preview" (or "open this lesson box in the offline viewer" link, e.g. `offline.html?file=assets/canonical_graphics/metadata.edn` plus a box uid) needs a hook or CSS in `public/docs_hooks.py` / `public/docs/stylesheets/extra.css`, and it would be used on other pages too.

A separate site issue from the same page: in the inline code `gl_VertexIndex/6` and `gl_VertexIndex%6` (paragraph after the particle vertex shader), Marc sees the two highlighted very differently. Inline code should not be language-highlighted inconsistently; check what inline highlighting the site applies (if any) and why `%` changes it.

Proposed approach: a small Markdown extension or hook that turns a marked link (for example `[threebox](transcript:threebox)` or `[lesson](lesson:<uid>)`) into a link plus a floating preview: the gallery capture if one exists, otherwise an offline-viewer iframe on that box. Alternative: link straight to the gallery anchors (`agent_transcripts.md#threebox`), which needs no code but gives no preview.

Questions: what syntax for authors; should the preview be the gallery's capture, the offline viewer, or both; does this go on every page that names a transcript?

Once it exists, this page should switch its three transcript mentions to it.
