# Add the missing captures to keyframer_examples.md (2 sections)

- **Handoff:** `294771d3-1009-4969-9e2c-8f456718e174` (project)
- **Filed from:** https://openendedgroup.com/field4/keyframer_examples/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 294771d3 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### One value shared by a local, a control and a key (`public/docs/keyframer_examples.md:139`, medium, needs-capture-system-work)

- **Point made:** Recalling a key now moves the slider and checkbox together with the local, which fixes the stale-slider bug, but the reader only sees the code and never the control following the key.
- **Capture:** Run the keys.slider/keys.checkbox box under a controlGroup and capture the box output before and after applying a saved key, so the slider position and the onChange println both visibly change. A drive script applies the key between the two screenshots.

### Seeing what it decided (`public/docs/keyframer_examples.md:1168`, medium, docs-runner)

- **Point made:** display(plan) renders the blend breakdown as a table of sites and midpoints, and the browser has a Code blend button, but neither appears; the section says the plan is the thing to read.
- **Capture:** Run planStackSLCodeBlend(warm, cool, RUNTIME) and display(plan) in a runner box with capture="box" open-outputs, so the page shows the real table.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
