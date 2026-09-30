# Move Querying Object Graphs out of Reference in the nav

- **Handoff:** `1c190053-6614-4a3c-8e44-8ec21d981985` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/graph_query/; also affects datalog.md
- **Filed by:** agent:claude, 2026-09-30 20:52
- **Close with:** `python tools/docs_notes.py handoff-close 1c190053 "what happened"`

## The notes that led here

- `88dc5672` (public/docs/graph_query.md:1): This deserves to be out of Reference
  > Querying object graphs

## What was asked

Note 88dc5672 on `public/docs/graph_query.md` ("Querying object graphs"): "This deserves to be out of Reference".

## Why it is bigger than a page edit

The page's place in the site is set by the navigation, not by the page. It is listed at
`public/zensical.yml:313`, under `Reference → Libraries & APIs`, beside `linear_algebra.md` and
`linear_algebra_api.md`. The docs-pass agent owns only `graph_query.md`, and moving it means editing
`zensical.yml`, the shared nav file. Nothing in the page mentions "Reference" or "Libraries", so
the page itself needs no edit to move.

The comment on `Reference` (`public/zensical.yml:286-287`) says the section is "Generated from source
rather than written: extracted KDoc and codegen output". `graph_query.md` is hand-written: it has
four `diffstatic_runner` captures and a narrative that opens with "Most of the time you know exactly
which field you want". By the section's own rule it does not belong there. The Internals comment
(`zensical.yml:317-320`) already moved pages out of Reference for the same reason.

## Evidence for where it belongs

- `public/docs/datalog.md:6` opens by defining itself against this page: "([Querying Object
  Graphs](graph_query.md)) can address a slot by pattern, but it cannot follow an ...". And
  `datalog.md:309` consumes this page's `graphPaths` output as facts. The two pages are a pair.
- `public/docs/reference/index.md:303` and `reference/synthesis.md:1982` link to it as a "Used in"
  page. Those are name-matched links and stay valid wherever the page sits in the nav.
- `public/docs/staging.md:75` links to it from the themed map. The link survives a move, but check
  that the surrounding text does not say which section the page is in.

## Proposed approach

Move `- Querying Object Graphs: graph_query.md` out of `Reference → Libraries & APIs` and into
`Small Languages`, directly **before** `Datalog in a Box` (`zensical.yml:168`). Add a nav comment in
the file's usual style: this page is how you address slots by path pattern, and Datalog is what you
use when a pattern cannot follow a relation, so it reads first. Keep the filename, so no links move.

Alternatives:
1. Put it under `Working in a Document`, beside `why.md` ("Why Does This Value Resolve Here?").
   Both are lookups over live structure. But `why.md` is about box inheritance, and this page is
   about arbitrary Kotlin object graphs (`rig.osc.filter.cutoff`), so the fit is weaker.
2. Put it under `Networks & Dynamics`. That fits badly: those pages simulate graphs, and this one
   queries them.
3. Once this page is gone, `Libraries & APIs` holds only the two linear-algebra pages. It may be
   worth asking whether they should leave Reference too, since `linear_algebra.md` is also
   hand-written.

## Questions for the human

- Is "Small Languages, before Datalog" the right home? Would you rather make it a peer of Datalog
  under a new group, for example "Querying"?
- Should the linear-algebra pages leave Reference along with it (alternative 3)?

## What the page needs afterwards

Probably nothing. If it lands next to Datalog, add a one-line forward pointer near the end of
`graph_query.md` ("when a pattern is not enough, see Datalog in a Box"), so the pair reads
both ways.
