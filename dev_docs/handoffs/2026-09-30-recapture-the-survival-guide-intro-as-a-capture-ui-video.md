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

## Progress, 2026-10-01 (overnight captures session)

The capture system now has what this needs: `viewport="WxH"` and `clip="x,y,w,h"` on a `capture="ui"` fence, and `ui.moveMouse`, `ui.clickAt`, `ui.typeSlowly` on the drive. The draft fence below ran end to end in the docs runner: `n` makes a box under the mouse, a click opens its editor, Alt-Enter prints `hello world`, Alt-0 prints `55`, and the closing `check` passed. It is not on the page yet, for two reasons seen in the frames: the recording ends (15.1 s) before the `55` shows, so the last pause needs lengthening or the recorder is losing frames at the end; and the editor opens in the lower half of the window, so `clip` cuts it at 380 px. The Toggle Keypress Display command would be good here, but its caption sits under the persistent notification stack at this window size. The capture was blocked from going further by the docs runner's 8 GiB disk floor.

~~~~text
```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="760x560" clip="0,0,760,380" video="assets/survival_intro.mp4" fps="20" padding="0" wait="0" timeout="180" embed=true hidden=true name="survival-intro" alt="The mouse moves onto the empty canvas and n makes a new box there; a click selects it and opens its empty editor. A println of hello world is typed one key at a time and Alt-Enter runs that line, printing hello world underneath it. Two more lines are typed, val total = (1..10).sum() and println(total), and Alt-0 runs the whole box, printing 55 under the last line."}
// Lay the editor pane out for this window, as a user's browser would: screenshot prep pins it
// to a fixed size and place, which at this window size would cover the canvas. The clip stops
// above the notification stack in the bottom-left corner.
ui.eval("""
    const c = document.querySelector(".monaco-editor-container")
    if (!c) throw new Error("no editor container")
    for (const k of ["width", "height", "top", "right", "left", "bottom", "transform"]) c.style[k] = ""
    if (window.globalEditor && window.globalEditor.layout) window.globalEditor.layout()
    return c.getBoundingClientRect().left
""".trimIndent())
ui.pause(1000)
ui.moveMouse(170.0, 130.0, 12)
ui.pause(500)
ui.press("n")
ui.pause(900)
ui.moveMouse(180.0, 140.0, 4)
ui.clickAt(180.0, 140.0)
ui.pause(900)
ui.clickSelector(".monaco-editor")
ui.pause(400)
ui.typeSlowly("println(\"hello world\")", 70)
ui.press("Escape")
ui.pause(500)
ui.press("Alt+Enter")
ui.pause(1800)
ui.press("End")
ui.press("Enter")
ui.typeSlowly("val total = (1..10).sum()", 70)
ui.press("Escape")
ui.press("Enter")
ui.typeSlowly("println(total)", 70)
ui.press("Escape")
ui.pause(500)
ui.press("Alt+0")
ui.pause(2500)
val text = ui.eval("return document.querySelector('.monaco-editor-container').innerText").toString()
check("hello world" in text && "55" in text) { "the intro's runs did not print hello world and 55: " + text.take(400) }
```
~~~~
