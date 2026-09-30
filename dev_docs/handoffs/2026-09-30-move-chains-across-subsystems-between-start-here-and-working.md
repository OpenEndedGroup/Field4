# Move Chains Across Subsystems between Start Here and Working in a Document

- **Handoff:** `5faa9ab7-ed73-406f-8d9c-9cced54ffb18` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/chain_polytope_render/; also affects staging.md
- **Filed by:** agent:claude, 2026-09-30 20:50
- **Close with:** `python tools/docs_notes.py handoff-close 5faa9ab7 "what happened"`

## The notes that led here

- `5ca314a3` (public/docs/chain_polytope_render.md:1): Chains across sub systems should be between start here and working in a document

## What was asked

The note on `public/docs/chain_polytope_render.md` (anchored to its H1) reads: "Chains across sub
systems should be between start here and working in a document". It asks to move the whole
**Chains Across Subsystems** nav section, not to change this page.

## Why this is bigger than a docs edit

The section is defined in `public/zensical.yml`, which this pass does not own. It holds 13 pages,
not just this one, so the move changes the site's prev/next order for all of them.

## Evidence

- `public/zensical.yml`: the top-level sections run `Home`, `Start Here` (ending with the Tour and its
  exit ramp `Where to Go Next: staging.md`), `Working in a Document`, `Seeing Values`, `Drawing From Data`,
  `Networks & Dynamics`, `Small Languages`, `Graphics, GPU & Headsets`, **`Chains Across Subsystems`**,
  `AI & Agents`, `Reference`, `Internals`.
- The comment above `- Chains Across Subsystems:` explains the current placement: the chains "sit
  after the sections they draw on rather than inside any one of them". The human's note overrides
  that reasoning, so the comment has to be rewritten, not just moved.
- The comment inside `Start Here` says "Where to Go Next" is the exit ramp and "nothing may follow it
  inside this section". Putting Chains in as a separate top-level section right after Start Here
  obeys that rule. Nesting it inside Start Here would break it.
- Inbound prose: `public/docs/agent_transcripts.md` links the section by name ("[Chains Across
  Subsystems](chain_polytope_render.md) is where they are"). Its wording does not depend on nav
  position.

## Proposed approach

1. In `public/zensical.yml`, cut the `- Chains Across Subsystems:` block, including its comment,
   and paste it between the end of `Start Here` and `- Working in a Document:`.
2. Rewrite the comment to give the new reason. For example: a reader leaving the Tour sees several
   subsystems working together before choosing one to learn in depth, so the chains act as a showcase
   and not as a capstone.
3. Update `staging.md` ("Where to Go Next") if it lists the sections in order, so its map matches the
   new nav. At filing time it has no mention of the chains.
4. Rebuild the docs HTML (`FIELD_DOCS_RUNNER_MODE=never`) and check the prev/next links at both
   boundaries: staging → chain_polytope_render, and chain_drawn_score → spatial_navigation.

Alternatives:
- Nest the chains inside Start Here, after the Tour. Rejected: the nav comment says nothing may follow
  "Where to Go Next".
- Move only a few chains up front (e.g. polytope, CA sound) and leave the rest where they are.
  Smaller, but it splits the family.

## Questions for the human

- Should all 13 chains move, or only a curated few?
- Should the section keep its title, "Chains Across Subsystems"?
- The chain pages open assuming the reader knows the subsystems they cross (e.g. this page names
  `afield.stdlib.polytope`, `afield.manifold` and `afield.four.fabricated` in its first paragraph).
  Right after the Tour, should each page's intro point back to the pages that introduce those
  subsystems?

## What this page needs afterwards

Probably nothing. If the answer to the last question is yes, this page's opening paragraph needs a
sentence linking to the polytope, Manifold and path-tracer pages for readers arriving straight from
the Tour.
