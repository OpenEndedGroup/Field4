# Fold the one-page 'Clouds & Deformations' nav group into a neighbour

- **Handoff:** `ff9c380d-70c1-40af-88d7-12920b520057` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/flow_matching/; also affects projection_examples.md
- **Filed by:** agent:claude, 2026-09-30 20:51
- **Close with:** `python tools/docs_notes.py handoff-close ff9c380d "what happened"`

## The notes that led here

- `5b578f33` (public/docs/flow_matching.md:1): Not sure that this should have it's own parent category
  > Flow Matching

## What was asked

Note 5b578f33 on `public/docs/flow_matching.md` (on the page title) says: "Not sure that this should
have it's own parent category". The page is the only entry in a nav group of its own.

## Why this is bigger than a page edit

The group lives in the site navigation, not on the page. `public/zensical.yml:109-113` creates a
"Clouds & Deformations" group under "Drawing From Data" whose only child is
`Flow Matching: flow_matching.md`. The comment above it (`public/zensical.yml:109-111`) explains
the choice: the input is an unordered cloud and the output is a deformation, "a shape problem
rather than a calculus one". A second comment, on Neural Particle Systems
(`public/zensical.yml:232-236`), names "Clouds & Deformations" by title, so it has to change too.
The partition for this pass owns only `flow_matching.md`.

The group above it has the same shape. "Distances & Projections" (`public/zensical.yml:104-108`)
also holds one page, `projection_examples.md`, so the note probably applies to both.

## Proposed approach

Put the two one-page groups together in one group, for example "Point Sets & Shape"
(or "Clouds, Distances & Deformations"), holding `projection_examples.md` and then
`flow_matching.md`. Both pages take an unordered set of points as input, which is the reason each
comment gives for keeping its page out of "Time & Signals" and "Functions & Derivatives". Update
both comments, and the reference in the Neural Particle Systems comment.

Alternatives:

1. Move `flow_matching.md` into "Geometry & Solids" (`public/zensical.yml:118-126`), beside
   "Voronoi, Contours & Clusters", which also works on point clouds.
2. Move it under "Rendering & GPU" directly before `neural_particles.md`. That page calls flow
   matching "the CPU half of tier one and worth reading first" (`public/docs/neural_particles.md:560`).
   The current comment rejects this because the CPU page is about shape, not the GPU.
3. Leave "Distances & Projections" alone and put only flow matching into "Distances & Projections"
   under a wider title.

## Questions for the human

- Which placement? A merged point-set group, Geometry & Solids, or next to Neural Particles?
- Does the note also cover the one-page "Distances & Projections" group (and "Lighting",
  `public/zensical.yml:142-143`, which is also a single page)?

## What the page needs afterwards

Nothing on `flow_matching.md` depends on the group name. The page does not mention its nav
section, and `reference/machine-learning.md` and `neural_particles.md` link to it by filename.
Only the zensical.yml comments name the group.
