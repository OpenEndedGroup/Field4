# Extend the reference-table inline highlighter to prose inline code

- **Handoff:** `b4bdc0a0-a7e0-4625-b4dd-7529b2bcc56a` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/faust/; also affects functions_examples.md
- **Filed by:** agent:claude, 2026-09-30 20:43
- **Close with:** `python tools/docs_notes.py handoff-close b4bdc0a0 "what happened"`

## The notes that led here

- `356b042d` (public/docs/faust.md:439): this syntax highlighting is much better than the one we (don't) get with `foo.bar(10.0)` in the rest of the site
  > FaustLaunch.press(label)

## What was asked

On `public/docs/faust.md` (the `## Reference` table, the `FaustLaunch.press(label)` row), the human
wrote: *this syntax highlighting is much better than the one we (don't) get with `foo.bar(10.0)` in
the rest of the site.* The request is to give inline code in prose the highlighting that reference
tables already get.

This is the same request as handoff `400854a2` ("Decide on light syntax highlighting for inline
code across the docs site", filed from `functions_examples.md`). It adds one fact that handoff is
missing. **The site already has an inline highlighter.** It only runs inside reference tables.

## Evidence

- `public/docs/assets/code-diff-player/code-diff-player.js`, `highlightReferences()` (called from
  `mountAll()`): it selects `[data-code-lang] td code:not(.highlight)` and runs highlight.js over each
  span. The language comes from the enclosing container's `data-code-lang`, and `kotlin` maps to a
  `kotlin-reference` grammar. The comment there says: "Language is an author choice, never guessed
  from a short identifier."
- `public/docs/faust.md`: the table is wrapped in
  `<div class="field-reference" data-code-lang="kotlin" markdown>`. About 29 pages under
  `public/docs/` use the same wrapper.
- `public/docs/assets/code-diff-player/code-diff-player.css`: the `[data-code-lang] .hljs-*` token
  colours. They are scoped to `[data-code-lang]`, so they would not apply to a span outside such a
  container.
- Prose inline code (`p > code`, `li > code`) is never selected, so it renders as a plain pill
  (`public/docs/stylesheets/extra.css`).

## Why it is bigger than a page edit

Wrapping prose paragraphs in `data-code-lang` containers on one page would be a hack, and it would
also colour paths and flags. The change belongs in the shared JS and CSS and affects every page.

## Proposed approach

1. Extend `highlightReferences()` to prose `code` spans that are clearly Kotlin: they contain `(` or
   `.` followed by an identifier, and no `/` or leading `-`. Use the same `kotlin-reference` grammar,
   and lift the token colours out of the `[data-code-lang]` scope, or add a page-level
   `data-code-lang` on `<article>`. That reuses a grammar and palette the human already likes.
2. Alternative: opt in per page with a page-level attribute (front matter to `data-code-lang` on the
   article), so authors choose the language at page granularity. This keeps the rule "never guessed".
3. Alternative: `#!kotlin` spans via `pymdownx.inlinehilite` (see `400854a2`). This works today, but
   it uses Pygments colours, not the reference palette.

## Questions for the human

- Automatic heuristic (1) or a page-level opt-in (2)? Option 1 breaks the existing "never guessed"
  rule in the comment.
- Should the result be one handoff with `400854a2`? Close one into the other.

## What the page needs afterwards

Nothing on `faust.md`, since its prose spans (`voices = 8`, `compute(0, …)`) would pick it up
automatically. Under option 2 it needs the page-level attribute.
