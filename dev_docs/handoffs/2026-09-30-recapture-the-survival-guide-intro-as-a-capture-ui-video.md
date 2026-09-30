# Recapture the survival-guide intro as a capture=ui video

- **Handoff:** `8b04f95b-2b06-478a-8146-dc1918a9a110` (project)
- **Filed from:** https://openendedgroup.com/field4/survivalGuide/
- **Filed by:** agent:claude, 2026-09-30 20:38
- **Close with:** `python tools/docs_notes.py handoff-close 8b04f95b "what happened"`

## The notes that led here

- `a0ad3ac8` (public/docs/survivalGuide.md:28): The screenshot above seems half size. It's important, and yet it's hard to read the code. It's also inside a webbrowser and out of date. Can we somehow capture this with a operation transcript .yaml and a video (a custom capture job for sure).
  > There

## What was asked

Note a0ad3ac8 on `public/docs/survivalGuide.md` (the `![intro](assets/intro.apng)` figure just
above "There are a large number of other ways of executing code"): the intro animation reads as
half size, its code is hard to read, it shows a web browser's chrome, and it is out of date. Marc
asks for it to be recaptured by the pipeline — an OperationTranscript `.yaml` plus a video, "a
custom capture job for sure".

## Why this is bigger than a prose edit

- `public/docs/assets/intro.apng` is a hand-made 844×778 APNG (6.4 MB), last touched in
  `4910d59dad` (2026-01-19). No fence produces it, so nothing in the docs runner can refresh it.
  The rule in AGENTS.md §3 ("A capture exercises the real code, or it does not exist") means the
  replacement has to be a real runner capture, and doc-pass agents may not run the docs build to
  produce or check one.
- The pieces nearly exist. `capture="ui"` (`public/docs_hooks.py`, `ensure_ui_drive`, and the
  attribute parsing near the "capture="ui" runs the fence body as a `drive` transcript" comment)
  already records a **video and/or** a still from one scripted Playwright session, clipped by
  `region=box|window|window-editor|editor`. The same page's `command-palette` and `autocomplete`
  fences use `capture="ui"` for stills. But **no page yet uses `capture="ui"` with `video=`**
  (grep `public/docs/*.md`), so this would be the first; expect to find bugs in that path.
- The intro has to show the whole workflow: press `n` for a new box, select it, type code, run it
  with `alt-0` / `alt-return`, see inline output. The existing ui fences start from an existing
  box (`ui.openEditor(id)`), so creating the box by keypress — and framing canvas and editor
  together at a readable scale — is new capture-system work.

## Proposed approach

1. Write the drive script as a `capture="ui"` fence on this page, `hidden=true`,
   `video="assets/intro.mp4"`, `region="window"` (canvas plus editor), at a device pixel ratio or
   window size that keeps the code legible at text-column width. Type with a per-key delay so the
   typing reads.
2. If the fence body gets too long or needs canvas gestures `drive` lacks (press `n` on the
   canvas, click a box), put the steps in a transcript under `dev_docs/agents/transcripts/`
   (`survival_intro.yaml`) and reference it with the fence's `transcript=`/`macro-file=`
   attribute (both parsed in `docs_hooks.py`).
3. Swap `![intro](assets/intro.apng)` for the embedded video, then delete the APNG once nothing
   else references it (`grep -rn intro.apng public/`).

Alternatives: a still sequence (several `screenshot=` stills) instead of a video — cheaper, but
it loses the "muscle memory" feel the page is about; or a hand-recorded screen video, which is not an option
because AGENTS.md section 3 forbids hand-made captures.

## Questions for a human

- Canvas plus editor (`region="window"`), or the editor alone at a larger scale?
- Which code should the intro type? The old animation's snippet, or something that shows inline
  printing and a launch as well?
- Should it end with a launch (`alt-up`) so the running/launching distinction in the next
  paragraph has a picture?

## What the page needs afterwards

Only the image line changes (image → video embed). The paragraph after it ("There are a large
number of other ways of executing code…") can stay as it is.
