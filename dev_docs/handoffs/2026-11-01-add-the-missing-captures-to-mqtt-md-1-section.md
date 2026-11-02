# Add the missing captures to mqtt.md (1 section)

- **Handoff:** `8842663f-aed7-4b82-a703-8cbf751c5409` (project)
- **Filed from:** https://openendedgroup.com/field4/mqtt/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 8842663f "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### A sensor feed (`public/docs/mqtt.md:12`, medium, docs-runner)

- **Point made:** Subscribing with a wildcard shows the latest temperature from each matching topic as live markdown output.
- **Capture:** A runner capture with execution=launch against the local test broker, where a companion publish to sensors/kitchen/temp and sensors/hall/temp feeds it, then a screenshot of the box's markdown output (same setup as the retained.png capture later on the page).


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
