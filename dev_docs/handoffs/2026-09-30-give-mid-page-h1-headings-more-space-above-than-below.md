# Give mid-page h1 headings more space above than below

- **Handoff:** `0f9c1609-bbe7-481a-b497-6c733130c4ee` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/funFeatures/
- **Filed by:** agent:claude, 2026-09-30 20:35
- **Close with:** `python tools/docs_notes.py handoff-close 0f9c1609 "what happened"`

## The notes that led here

- `7e7926da` (public/docs/funFeatures.md:615): The spacing is odd, I would expect more of a space _before_ this heading than after it, for visual grouping.
  > Making bigger things

## What was asked

Note 7e7926da on `public/docs/funFeatures.md` ("# Making bigger things"): "The spacing is odd, I
would expect more of a space _before_ this heading than after it, for visual grouping."

## Why this is bigger than a docs edit

The page is correct Markdown: a blank line before and after the heading. The spacing comes from
the site stylesheet, `public/docs/stylesheets/extra.css`, which has the `.md-typeset h1` rules
and the `h1 + h2` / `h1 + h3` adjacency rules near the top. Changing it changes every page that
uses a mid-page `#` heading. On this page alone that is "# Multi-language, multi-paradigm",
"# Keyframes and timelines", "# Making bigger things" and "# Collaborating", and other pages
likely do the same. That reaches past this page's files.

## Proposed approach

In `extra.css`, give an `h1` that is not the first child of the article a larger `margin-top`
than its `margin-bottom`. For example: `.md-typeset h1:not(:first-child) { margin-top: 2.5em;
margin-bottom: 0.6em; }`, tuned against the existing h1 rules. Check it on a few long pages.

Alternative: demote the mid-page `#` headings on this page to `##`, and the sections under them
one level. That is local, but it changes the page outline and the anchors, including
`#making-bigger-things`, which this page links to from "Kotlin, with macros".

## Questions for a human

- Should this be a site-wide CSS fix, or should the tour stop using mid-page h1s?

## What the page needs afterwards

Nothing if CSS is used. If the headings are demoted, the in-page anchor links have to be updated.
