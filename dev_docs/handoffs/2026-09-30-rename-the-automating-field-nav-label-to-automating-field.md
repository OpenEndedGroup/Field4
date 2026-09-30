# Rename the automating_field nav label to Automating Field

- **Handoff:** `3c3f70bd-bf15-4b87-835f-868061a7ee79` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/automating_field/; also affects capture_limits.md
- **Filed by:** agent:claude, 2026-09-30 20:48
- **Close with:** `python tools/docs_notes.py handoff-close 3c3f70bd "what happened"`

## The notes that led here

- `321ed304` (public/docs/automating_field.md:1): title appears as "letting an agent drive" in the margin, "automating Field" is a better title
  > Automating Field: macros, transcripts, and the backdoor

## What was asked

Note 321ed304 on `public/docs/automating_field.md`: the page shows as "Letting an agent drive" in
the margin (the nav). "Automating Field" is the better title.

## Why this is bigger than an edit to the page

The page's H1 already says "Automating Field: macros, transcripts, and the backdoor"
(`public/docs/automating_field.md`, line 1). The margin label is not taken from the H1. It comes
from the explicit nav entry in `public/zensical.yml`:

    - Letting an Agent Drive: automating_field.md      (public/zensical.yml:44)

That file is shared by every page and is outside this partition. One other page uses the old
label as link text:

- `public/docs/capture_limits.md:4`: `[Letting an Agent Drive](automating_field.md) is how that is ...`

The other inbound links (`agent_transcripts.md`, `staging.md`, `functions_examples.md`,
`kotlin_in_the_head.md`) already say "Automating Field".

## Proposed approach

1. Change `public/zensical.yml:44` to `- Automating Field: automating_field.md`. The filename and
   URL stay the same, so no anchors or generated references move.
2. Change the link text in `public/docs/capture_limits.md:4` to "Automating Field", and adjust
   the rest of that sentence if it no longer reads right.
3. Rebuild the docs (HTML only is enough) and check the Tour nav.

Alternative: use the full H1 as the nav label. It is too long for the margin, so I don't
recommend it.

## Questions for the human

- Should the nav label be just "Automating Field", or should it include a subtitle?

## What the page needs afterwards

Nothing. The H1 already matches.
