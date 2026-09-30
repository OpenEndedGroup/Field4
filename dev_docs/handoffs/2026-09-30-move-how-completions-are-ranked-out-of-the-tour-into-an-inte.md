# Move How Completions Are Ranked out of the Tour into an internals section

- **Handoff:** `c310370a-e829-4fbc-aaa9-90b7e39d0278` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/completion_ranking/; also affects completion_doc_browser.md
- **Filed by:** agent:claude, 2026-09-30 20:43
- **Close with:** `python tools/docs_notes.py handoff-close c310370a "what happened"`

## The notes that led here

- `2b6c6bd8` (public/docs/completion_ranking.md:3): again, another "internal behind the scenes" kind of page --- not earning it's place this far up the hierarchy for intro to users
  > Press ctrl-space in a Field editor and something has to choose which of a few hundred candidates you see first. This page is about that choice: where it is made, what it is made from, and how a class you write, in a Field box or in Field's own source, gets a say in it.

## What was asked

On `completion_ranking.md` the human wrote: "again, another 'internal behind the scenes' kind of
page — not earning its place this far up the hierarchy for intro to users". The word "again"
suggests the same complaint has come up on other Tour pages.

## Why it is bigger than a docs edit

The page's position is set in `public/zensical.yml`, which this partition does not own. It sits
inside **Start Here → A Tour**, third level, directly after `completion_doc_browser.md`
(`public/zensical.yml:30-34`). The YAML comment there explains that it was placed to answer the
browser page's "why *this* candidate first". Moving it changes the Tour's prev/next chain, since
Zensical resolves prev/next from nav position. The inbound link at `public/docs/agent_transcripts.md:1325`
is by filename and survives a move.

## Evidence that it is internal

The page is written for someone reading Field's source: `rankItems` in
`src/commonMain/resources/fieldSuggest.js`, the `COMMON_MEMBER_BASE`/`CommonMemberPrior` literals,
K1 vs the AA sidecar, and a pointer to `dev_docs/completion_aa_sidecar.md`. Only the
`@CommonMembers` section ("how a class you write gets a say") speaks to a box author.

## Proposed approach

Remove it from A Tour and file it under a developer/internals section (wherever the site keeps
"how Field works inside" pages, next to other editor-internals pages if one exists). Optionally
leave a single sentence on `completion_doc_browser.md` that links to it for readers who want to know
why the order is what it is.

Alternative: split it. A short user-facing section on `@CommonMembers` (how to promote your own
members) could go on the browser page, and the rest moves to internals.

## Questions for the human

1. Which section should it go to? Is there an existing internals/developer group in the nav?
2. Move it whole, or split out the `@CommonMembers` part for box authors?
3. "again" — which other Tour pages do you consider behind-the-scenes, so they can be moved in the
   same pass?

## What the page needs afterwards

Its opening ("Press ctrl-space…") reads as a Tour step. Once it is filed as an internals page, the
intro could say so and drop the Tour framing.
