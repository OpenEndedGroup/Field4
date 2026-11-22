# Recapture text frame editing (WYSIWYG) and the marking menu

- **Handoff:** `2e4cee7a-52eb-4117-80c1-3ba899b526c9` (project)
- **Filed from:** https://openendedgroup.com/field4/text_frames/
- **Filed by:** agent:claude, 2026-11-22 10:12
- **Close with:** `python tools/docs_notes.py handoff-close 2e4cee7a "what happened"`

## The notes that led here

- `66d660f6` (public/docs/text_frames.md:34): the image that comes after this paragraph is out of date — we've moved to WYSIWYG and a small tool bar
  > The inline editor uses Field's normal Iosevka at 12px. Reading uses Iosevka Etoile, a text face with Field's reverse-slashed zero, including light, regular, bold and italic faces. Headings, emphasis, links, lists, tables, fenced code, images, and inline or display math are supported.
- `575fc47f` (public/docs/text_frames.md:200): I'd love an action shot of the marking menu here
  > Shift-select two boxes and press G to connect them. ⌥-click a frame and move down to choose New text frame below: a same-width child appears 32 world units below, with matching typography. Use the Hide / Reveal submenu to hide descendants or ancestors, optionally including this frame, or to reveal them again. This walks the whole branch, handles cycles, and preserves connections. Back or Esc returns one menu level. Reveal All Boxes in the palette is the recovery path if you hide everything. Hiding is session visibility; reopening the document or changing layers may reset it, and active layer filters still apply.

Two captures on `public/docs/text_frames.md` need work in the capture transcript
`dev_docs/agents/transcripts/docs_capture/text_frames.yaml`, which this docs pass does not own.

1. `assets/text_frames/editing.png` (the `text-frames-editing` runner fence after "## Markdown,
   then typeset text") shows the old whole-frame plain-Markdown editor. The default editor is now
   WYSIWYG with a small toolbar that follows the caret. The human says the image is out of date.
   Proposal: change the fence to double-click into the formatted text so the toolbar shows, and
   add a second capture of the Markdown source editor (Shift-double-click / **Edit Markdown**) if
   the page still needs one. The prose before it now says "Markdown source editor".
2. "## Connected writing": the human wants an action shot of the ⌥-click marking menu showing
   **New text frame below** and the **Hide / Reveal** submenu. You need a drive step that holds
   the marking menu open while the screenshot is taken.

Questions: should the editing figure show only WYSIWYG, or WYSIWYG plus source? Is a still enough
for the marking menu, or should it be a short video?

Once this is done, the page needs alt text that matches each new capture.
