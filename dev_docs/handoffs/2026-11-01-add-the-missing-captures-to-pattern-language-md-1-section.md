# Add the missing captures to pattern_language.md (1 section)

- **Handoff:** `5d8a5eab-a0c5-41a5-b183-aa739635e60e` (project)
- **Filed from:** https://openendedgroup.com/field4/pattern_language/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 5d8a5eab "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Playing a pattern (`public/docs/pattern_language.md:972`, low, docs-runner)

- **Point made:** respond{} calls back with wall-clock onsets for each event, but the printed onset, value and gain lines are never shown, so the reader cannot see the scheduling.
- **Capture:** Turn the existing respond snippet into a diffstatic_runner capture="box" with screenshot=, run for about 2 s so the box output shows the printed lines like '0.0s bd gain=...' and '0.125s sd ...'. Optionally add audio= with the same pattern.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
