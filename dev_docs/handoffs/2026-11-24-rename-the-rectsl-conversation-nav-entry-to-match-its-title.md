# Rename the rectsl_conversation nav entry to match its title

- **Handoff:** `6723c210-7fc3-4f30-b12c-b91394a912e3` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/rectsl_conversation/
- **Filed by:** agent:claude, 2026-11-24 19:07
- **Close with:** `python tools/docs_notes.py handoff-close 6723c210 "what happened"`

## The notes that led here

- `f563e43d` (public/docs/rectsl_conversation.md:1): We updated the title, but we didn't update the title in the nav

The note on `public/docs/rectsl_conversation.md` asks for the nav entry to match the page's new
title. The page heading is already "Talking a RectSL scene into existence" (retitled in an earlier
pass, note 9d98673a). The nav entry is set by hand in `public/zensical.yml:520`:

    - Talking a Scene into Existence: rectsl_conversation.md

That file is shared by every page, and this pass owns only the page, so the change goes here.

Proposed change: edit line 520 to
`- Talking a RectSL Scene into Existence: rectsl_conversation.md`. That keeps the Title Case its
neighbours in the nav use. The alternative is to drop the explicit title so the nav uses the H1.
That depends on whether Zensical takes the H1 for a bare entry, and it would break the nav's
casing convention.

Question for the human: Title Case in the nav ("RectSL Scene"), or the page's sentence case?

Once it is done, the page needs nothing more.
