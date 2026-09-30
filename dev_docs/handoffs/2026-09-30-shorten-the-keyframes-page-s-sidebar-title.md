# Shorten the keyframes page's sidebar title

- **Handoff:** `afd3f44e-7b3a-4f1c-9a70-2440b9cd370b` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/keyframer_examples/; also affects keyframe_autosweep.md, interpolants_examples.md
- **Filed by:** agent:claude, 2026-09-30 20:42
- **Close with:** `python tools/docs_notes.py handoff-close afd3f44e "what happened"`

## The notes that led here

- `ec77145e` (public/docs/keyframer_examples.md:1): title on the left hand side index is too long and wraps
  > Keyframes: capturing, persisting and replaying live state

## What was asked

The note on the H1 of `public/docs/keyframer_examples.md` says: "title on the left hand side index is
too long and wraps".

## Why this is bigger than an edit to the page

The left-hand navigation does not use the page's H1. It uses the explicit label in
`public/zensical.yml:96`:

    - Capturing & Replaying Live State (Keyframes): keyframer_examples.md

That file belongs to no single page partition. Changing the H1 alone would leave the sidebar as it is.
Changing the H1 also changes the "Used in" titles in `public/docs/reference/graphics.md:607`,
`:1059` and `public/docs/reference/index.md:220-221`. Those are generated, and a stale
`reference/index.md` blocks every commit, so it would have to be regenerated rather than
hand-edited.

The same sidebar group has neighbours that probably wrap too: `Values Over Time (Interpolants &
Keyframes)` (`zensical.yml:90`) and `Sweeping a Keyframe Space Overnight` (`zensical.yml:99`). Other
pages link to this one by its old nav wording, e.g. `keyframe_autosweep.md:6`
("Capturing & Replaying Live State") and `agent_transcripts.md:1013`.

## Proposed approach

1. Shorten the label at `zensical.yml:96` to something that fits on one line, e.g. `Keyframes` or
   `Capturing Live State`. Keep the filename and URL, so no anchors or links break.
2. Optionally shorten the H1 to match, e.g. `# Keyframes: capturing and replaying live state`, then
   regenerate the reference pages so their "Used in" titles follow.
3. Check the two neighbouring labels at the same sidebar width, and shorten them in the same pass
   if they wrap.

An alternative is to leave the labels alone and widen the sidebar in `extra.css`. That affects
every page.

## Questions for a human

- Which short label? (`Keyframes` is shortest. `Capturing Live State` keeps the verb.)
- Should the H1 change too, or only the sidebar label?
- Should the other long labels in "Time & Signals" be fixed in the same pass?

## What the page needs once done

Nothing, if only the nav label changes. If the H1 changes, the link texts on
`keyframe_autosweep.md:6` and `agent_transcripts.md:1013` may need to follow.
