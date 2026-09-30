# Promote the Agent Transcript Gallery: Tour link, front page, nav position

- **Handoff:** `e8eabeba-be93-4340-b1a0-ec5c8ff2b6e4` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/agent_transcripts/; also affects automating_field.md, index.md, funFeatures.md
- **Filed by:** agent:claude, 2026-09-30 20:46
- **Close with:** `python tools/docs_notes.py handoff-close e8eabeba "what happened"`

## The notes that led here

- `f98ed9a0` (public/docs/agent_transcripts.md:1): This page is so absurdly wonderfully 2026 only kinds of awesome it needs to go much higher in our hierarchy or at least get a forward link from the initial tour, maybe even the front page
  > Agent transcript gallery

## What was asked

The note on `public/docs/agent_transcripts.md:1` asks that the Agent Transcript Gallery "go much
higher in our hierarchy or at least get a forward link from the initial tour, maybe even the front
page". Every one of those changes lands outside this page: the nav (`public/zensical.yml`), a Tour
page, and `public/docs/index.md`. The gallery page itself needs no change to be promoted.

## Evidence

- Nav position: `public/zensical.yml:285` puts it as the **last** entry under "AI & Agents", after
  Studies, AI Tool-Use History, AI Documentation Polish and Notes on the Documentation. The comment
  above it (`zensical.yml:283`) records that `mobile_controllers.md` was moved out of this exact
  slot into the Tour because it "was found by nobody" there. The same reasoning applies here.
- The Tour (`zensical.yml:27-44`) ends with "Letting an Agent Drive" (`automating_field.md`) and
  then "Where to Go Next" (`staging.md`). `automating_field.md` does **not** link to
  `agent_transcripts.md` at all, even though the gallery's own intro says the transcripts are "the
  worked examples an agent copies, runs, and adapts when automating Field" and links back to it.
- The only Tour-arc link is in `staging.md:65` (the exit ramp), one bullet among many.
- `index.md`, `funFeatures.md` ("What Field Is") and `aiFeatures.md` do not link to it.
- Inbound links today come from topical pages only (`studies.md`, `keyframer_examples.md`,
  `vision_geometry.md`, `flow_grain.md`, generated `reference/*.md` "Used in" lines).

## Proposed approach (smallest first)

1. **Forward link from the Tour** (recommended; cheap): in `automating_field.md`, where it
   introduces operation transcripts / macros, add a sentence pointing to the gallery as the
   corpus of runnable, openable scenes an agent built. Possibly also one line near the end of
   `funFeatures.md` since it is "What Field Is".
2. **Front page**: a sentence or tile in `index.md` next to the AI paragraph (`index.md:9`)
   — "a gallery of documents agents built from a blank canvas, each openable" — ideally with one
   of the gallery's existing real figures.
3. **Nav**: move the entry up. Options:
   a. First entry of "AI & Agents" (above AI Overview) — a showcase before the explanations.
   b. Into the Tour right after "Letting an Agent Drive", before "Where to Go Next" — makes the
      arc end on "here is what an agent made". Costs: the Tour comment says each stop is "one
      faculty per page"; the gallery is a showcase, not a faculty, and it is long.
   c. Leave the nav slot, do (1) and (2) only.
   Note the nav rule at `zensical.yml:24`: a page appears once, prev/next follow position, so a
   move changes neighbours' prev/next links; regenerate the site, do not hand-patch.

## Questions for the human

- Which nav placement: (a) top of AI & Agents, (b) inside the Tour, or (c) unchanged?
- Front page: a text link, or a figure/tile? If a figure, which scene should represent it?
- Should `funFeatures.md` link too, or only `automating_field.md`?

## What this page needs afterwards

Nothing structural. If it moves into the Tour, its intro paragraph (`agent_transcripts.md:3-5`)
may want a first sentence that reads as a Tour stop ("the previous page showed how an agent
drives Field; these are documents agents built that way") — a small follow-up edit on this page.
