# Three defects visible in `interactive_guides.md`'s committed figures

Found 2026-09-24 by opening the four PNGs under `public/docs/assets/interactive_guides/`
and reading them against the fences that produce them. None of the three is faked over:
the `alt=` on each fence describes what the picture actually shows, including the wrong
parts. All three need a capture run to close, which is why they are here and not on the
page.

When `dev_docs/docs_capture_staleness.md` grows its **Known-stale committed assets**
table (written on `docs/voice-2026-09-23e`, unlanded as of this note), fold rows 1 and 2
into it and delete them from here.

## 1. `basic_slideshow.png` is captured part-way through the note animation

`interactive_guides.md:63` asks for `wait="1.5"`, and its note is built with
`animated = true` (`:86`). The committed PNG shows the heading and the opening sentence,
then **three bullet markers with no text beside them**, and no `[[home]]`/`[[next]]`
footer links at all. The fence's markdown has text in all three bullets and both links.

**There is a control for this on the same page.** `annotations.png` (`:252`) is also
`animated = true` (`:271`) and also has bullets and links — and it asks for `wait="5"`.
Its bullets and its annotation lines all render. So this is the reveal animation
outrunning the wait, not a `markdownNote` list bug, and not a general slideshow problem.
`slide_chat.png` and `callbacks_and_templates.png` render their bullets at `wait="1.5"`
because neither passes `animated`.

**Fix:** raise `:63`'s `wait` to 5, matching `:252`, and re-capture. An opener-only
change, so it does not disturb `tools/completion-corpus/rank-carets.tsv`.

## 2. `callbacks_and_templates.png` shows every template slot empty

The figure is the illustration for *"Add callbacks and live template values"*. The three
callback links render correctly. The three `{{…}}` slots do not: the note reads
`Version:` with nothing after it and `Count: Choice:` with neither value.

The snippet declares `version = "1.0.0"`, `clickCount = 3` and `userChoice =
"Balanced Mode"` as locals immediately above the `slideShow { … }` block
(`interactive_guides.md:112-114`), and `SlideShow.kt:372`'s `processTemplates` does look
in exactly the right place for them — `hostBox.properties` first (`:375`), then
`hostBox.ki.context.vars` (`:382`), then `knownValues`/`templates`, then `""`. So the
page's prose claim at `:165`, that a template value can come from a Kotlin variable still
in scope, is **describing the implementation correctly**. The figure is the thing that
disagrees.

The likely reason is that the whole example, declarations included, is one snippet whose
`runBlocking { show.start() }` renders the first note *before* the snippet that declares
those variables has finished, so nothing has reached `ki.context.vars` yet. That would
also explain why the callbacks each write `here.properties[…]` before re-rendering: the
properties path is the one that works from inside a step.

**Not fixed here**, because it is not yet certain which of two things is wrong — the
example (declare the values into `thisBox.properties` up front, and the figure comes
right) or the timing contract (`ki.context.vars` should be populated before a snippet's
own `runBlocking` body runs). Guessing and editing the fence would change a
`rank-carets.tsv`-indexed body and leave the committed asset disagreeing with its source.

**To settle it:** run the fence's code in a box with one added line writing `version`
into `thisBox.properties` and see whether that slot alone fills.

## 3. `interactive_guides.md:195` escapes quotes inside a Kotlin raw string

    Use `withChat(id = \"templateId\")` when multiple template instances should share one thread.

That line is inside a `"""…"""` raw string, where `\"` is not an escape — it is a
backslash followed by a quote. The published figure faithfully renders
`withChat(id = \"templateId\")`, backslashes and all, to every reader. Two lines below it
the real call is spelled correctly (`:201`).

**Fix:** delete the two backslashes and re-capture. This one *is* a fence-body edit, so it
moves a recorded caret prefix in `tools/completion-corpus/rank-carets.tsv`
(`interactive_guides` has two entries) and has to be done with `check_fences.py` in hand.

## Open

- Nothing asserts that a `markdownNote` capture waited long enough for its own animation.
  A capture of a half-rendered note is indistinguishable from a capture of a short note,
  which is how defect 1 survived. A cheap guard would be for the docs runner to refuse
  `animated = true` with a `wait` under some floor.
