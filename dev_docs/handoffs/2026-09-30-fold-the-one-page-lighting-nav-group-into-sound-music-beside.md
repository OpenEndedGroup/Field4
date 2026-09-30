# Fold the one-page Lighting nav group into Sound & Music beside OSC

- **Handoff:** `eef10075-2b66-4895-a8db-4c857df94556` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/dmx_lighting/
- **Filed by:** agent:claude, 2026-09-30 20:51
- **Close with:** `python tools/docs_notes.py handoff-close eef10075 "what happened"`

## The notes that led here

- `9b408d8e` (public/docs/dmx_lighting.md:1): Not sure this get's its own subsection

## What was asked

Note 9b408d8e on the H1 of `public/docs/dmx_lighting.md`: "Not sure this get's its own
subsection." The subsection is the navigation group, not a heading on the page: `public/zensical.yml:142-143`
gives the page a one-entry group, `- Lighting:` holding only
`"Driving Lights: Art-Net and sACN": dmx_lighting.md`. It sits between "Sound & Music" (which ends with
`osc.md`, lines 139-141) and "Audio AI" (line 144).

## Why it is bigger than a docs edit

The fix is to `public/zensical.yml`, which the per-page agent does not own. The page itself has no
heading that could be merged away.

## Evidence

- `public/zensical.yml:142-143`: the only one-page group in that part of the nav.
- `public/zensical.yml:139-141`: the comment on `osc.md` already puts "a box talking a network
  protocol to an outside peer" at the end of Sound & Music. DMX (Art-Net/sACN over UDP) is the same
  kind of page: a box talking a wire protocol to outside hardware, and Field acting as a node.
- `public/docs/chain_scene_to_light.md` (nav line 269, under the chains) is the only other lighting
  page, and it links back here (`chain_scene_to_light.md:253`).

## Proposed approach

Drop the `Lighting:` group. Move the entry to follow `OSC and TUIO` at the end of "Sound & Music", with
a one-line YAML comment in the style of the neighbours explaining why it sits there (the same shape as
OSC: a network protocol out of a box, to any peer).

Alternatives:
1. Rename "Sound & Music" to something like "Sound, Music & Performance I/O" so lighting is not
   filed under sound. This touches a group label other pages and comments refer to.
2. Make a new "Talking to Devices" group holding `osc.md`, `dmx_lighting.md` and possibly
   `mobile_controllers.md`. That moves pages out of the Tour and out of Sound & Music, so it is a
   larger reorganisation.
3. Leave it as is, if more lighting pages are planned (fixture profiles, a sequencer/cue page).

## Questions for the human

- Is lighting OK under "Sound & Music", or does it read as misfiled there?
- Are more lighting pages coming? If so, the one-page group is a placeholder and can stay.

## What the page needs afterwards

Nothing. The page does not mention where it sits in the nav. If it moves beside OSC, a "see also"
link from `osc.md` would help, but that page belongs to another agent.
