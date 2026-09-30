# Move running_code.md out of the Tour (it is behind-the-scenes mechanism)

- **Handoff:** `7a6d9f40-8481-4333-b70c-a91ae6a9e1f8` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/running_code/; also affects funFeatures.md, survivalGuide.md, animation_time_model.md
- **Filed by:** agent:claude, 2026-09-30 20:45
- **Close with:** `python tools/docs_notes.py handoff-close 7a6d9f40 "what happened"`

## The notes that led here

- `7c1c261f` (public/docs/running_code.md:3): This page is more of an 'internal behind the scenes' kind of thing than things that users need to immediately know
  > "Run this" is the gesture you make most often in Field, and it means more than one thing. This page is the whole path, from the keystroke to the frame. Start at whichever end you need.

## What was asked

Note 7c1c261f on `public/docs/running_code.md` (the opening paragraph): "This page is more of an
'internal behind the scenes' kind of thing than things that users need to immediately know."

## Why it is bigger than a docs edit

The page body is mechanism: fragments, macro expansion into an `Insert`, the ` /**/` preamble
tag, `Inheritance` / `InheritanceFromContext` delegates, `@exported` aliasing, generated GLSL
accessors, `runLaunchSetupBlocks` in `execution.kt`. But the nav puts it second in the
read-in-order "A Tour" arc, straight after "What Field Is" (`public/zensical.yml:27-29`), and the
comment there justifies that with "running_code is the mechanic every later page rests on"
(`public/zensical.yml:21`). Addressing the note properly means moving the page in the nav and
changing how other pages hand readers to it, which is outside this page.

In this pass I only reframed the page's own intro, which now says it is behind-the-scenes and not
needed to start writing boxes.

Evidence of how other pages send readers here:
- `public/docs/funFeatures.md:20` links "in [Kotlin](running_code.md)" as the place to go and write
  and run Kotlin code — that is a beginner's link to a mechanism page.
- `public/docs/survivalGuide.md:32-34` calls it "that distinction in full — every way to execute
  something", but the page does not list every way to execute; it points back to the Survival Guide
  and the F1 palette for that.
- `public/docs/animation_time_model.md:112` ("This page picks up where Running code in a box
  stops") and the "Frames and the Animation Loop" entry in the Tour both assume running_code comes
  before it in the arc.
- `public/zensical.yml:314-326` defines "Internals" as "about mechanism rather than task. Read these
  when you are extending Field or working out why it did something, not when you are writing a box",
  which fits this page's body well.

## Proposed approach

Move `running_code.md` out of "A Tour" into "Working in a Document", beside `why.md`
(`public/zensical.yml:56`); `why.md` already links into running_code's inheritance section. Then:
- repoint `funFeatures.md:20`'s "Kotlin" link at `kotlin_overview.md`, keeping the running_code
  link at `funFeatures.md:263`/`:655`, where readers are already asking about mechanism;
- rewrite `survivalGuide.md:32-34` to describe the page as what Field does to your text before
  compiling it, not "every way to execute something";
- rewrite the Tour comment at `public/zensical.yml:21` and check that
  `animation_time_model.md:112` still reads correctly once running_code is no longer the page
  before it in the arc.

Alternatives:
- Move it under "Internals" (`public/zensical.yml:326`). That fits the section's own test, but it is
  a very long way from where a box author hits "why can't my child see `renderer`".
- Split it: a short user-facing page in the Tour (running vs launching, `//%-` as a practical tool,
  "connect under the renderer"), with the macro/preamble/`@exported` mechanism moved to Internals.

## Questions for the human

1. Where should it live: "Working in a Document" beside `why.md`, "Internals", or split into two
   pages?
2. If it leaves the Tour, what is the Tour's second step: `completion_doc_browser.md` directly,
   or a short running/launching page?
3. Should `funFeatures.md:20`'s "Kotlin" link go to `kotlin_overview.md` instead?

## What the page needs once this is done

If it moves, the page's first bullet ("Running versus launching", which points to the Survival
Guide) and its link to Frames and the Animation Loop may need re-reading, because they were written
for a Tour position. If it is split, its practical sections (the `//%-` worked example, the
"sibling of the renderer" gotcha) go to the user-facing half.
