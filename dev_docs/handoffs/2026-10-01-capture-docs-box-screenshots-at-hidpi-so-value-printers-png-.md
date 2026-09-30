# Capture docs box screenshots at HiDPI so value_printers.png can display larger

- **Handoff:** `a865ab15-3c50-4861-8863-7bafacdbb2aa` (project)
- **Filed from:** https://openendedgroup.com/field4/polytope_examples/
- **Filed by:** agent:claude, 2026-10-01 00:34
- **Close with:** `python tools/docs_notes.py handoff-close a865ab15 "what happened"`

## The notes that led here

- `cf6bc38e` (public/docs/polytope_examples.md:1521): image above too small
  > The … links are the visualizer's ordinary lazy expansion for nested values, not a rendering failure; click one in a live box and it fetches the rest.

**Asked:** on `public/docs/polytope_examples.md` ("The registered value printers"), the note says the `value_printers.png` screenshot is too small.

**Why this needs more than a docs edit:** the PNG is 612×1632 px, captured at DPR 1 (`file` reports it). The fence (`public/docs/polytope_examples.md`, the `diffstatic_runner` fence with `capture="box" w="1100" h="720"`) captures only the box's output panel, which is narrow. An earlier agent added `{ width="612" }`, but that is the image's natural size, so it did nothing. I reverted it. Setting a larger width would just upscale a 1× raster and blur the monospace text. I found no DPR/scale attribute for captures in `public/docs_hooks.py`.

**Proposed approach:** add a capture attribute (e.g. `scale="2"` → Playwright `deviceScaleFactor`) to the runner's screenshot path, then set it on this fence. Recapture, and display it at about 612 CSS px or at column width.
Alternatives: (a) widen the output panel or raise its font size in this fence before capture; (b) crop the capture to the top-level summary so the text column can show it larger.

**Questions for the human:** should HiDPI captures be the runner default (this changes every capture digest), or opt-in per fence? And what display width do you want here: the full text column?

**Page afterwards:** the fence gets the new attribute, the PNG is recaptured with real code (never hand-scaled), and the image line gets a matching `width` if it needs one.
