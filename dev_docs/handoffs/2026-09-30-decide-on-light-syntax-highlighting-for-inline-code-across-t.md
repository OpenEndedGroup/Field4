# Decide on light syntax highlighting for inline code across the docs site

- **Handoff:** `400854a2-e541-4896-a9a0-7b92c6b6e2c2` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/functions_examples/
- **Filed by:** agent:claude, 2026-09-30 20:41
- **Close with:** `python tools/docs_notes.py handoff-close 400854a2 "what happened"`

## The notes that led here

- `b2ed2428` (public/docs/functions_examples.md:829): didn't we have a little light syntax highlighting on our `foo(1,2,3.4).bar` inline code?
  > ramp(start, end)

## What was asked

On `public/docs/functions_examples.md` ("#### ramp", the opening `ramp(start, end)`), the human
asked: *didn't we have a little light syntax highlighting on our `foo(1,2,3.4).bar` inline code?*

## What exists today

- **On the docs site, no.** `public/zensical.yml` enables `pymdownx.inlinehilite`, and lists it
  twice. That extension only highlights a span that starts with a language shebang
  (`` `#!kotlin ramp(start, end)` ``), and no page under `public/docs/` uses one. Inline code
  renders as a plain pill, styled in `public/docs/stylesheets/extra.css` (the
  `.md-typeset :is(p, li, td, blockquote) > code` rules). None of those rules colour tokens.
- **Inside Field, yes.** Commit `601f63d73` ("AI chat: ... inline code highlighted", 2026-09-27)
  sends inline `code` in the AI chat pane through the same highlight.js pass as fenced blocks
  (`src/commonMain/resources/outputareas2.js`, `field.css`). That is probably what the human
  remembers, and it has never been on the docs site.

## Why this is bigger than a page edit

Adding `#!kotlin` to spans on this one page would make it inconsistent with every other page.
Inline code on the docs site is also mixed: Kotlin expressions, shell flags, file paths, property
names. So any automatic highlighting has to decide which spans are code at all. That is a
site-wide decision, and the fix lives in the hooks, CSS or JS. Fenced code also skips Pygments
here (it has its own diffstatic treatment), so Pygments inline output might not match the fence
colours anyway.

## Proposed approaches

1. **Client-side, matching the fences.** Add a small script that runs the fence highlighter's
   Kotlin tokenizer over inline `code` spans that parse as expressions (an identifier followed by
   `(`, `.`, or an infix keyword), with light token colours taken from the fence theme. Paths,
   flags and single words are left alone.
2. **Build-time in `public/docs_hooks.py`.** Apply the same heuristic and emit token spans with
   the fence classes. Nothing runs on the client, and captures and search are unaffected.
3. **Opt-in `#!kotlin`.** Authors mark the spans they want highlighted. This works today with no
   code change, but it is noisy in the source and nobody will do it consistently.

## Questions for the human

- Should all inline Kotlin be highlighted automatically, or only spans marked by the author?
- How light is "light"? Colour only identifiers and calls vs. numbers, or the full fence palette?

## What the page needs afterwards

Nothing, if the highlighting is automatic. If it is opt-in, mark the API spans on
`functions_examples.md` (the `#### ramp` intro and the other reference sections).
