# Add the missing captures to syphon.md (1 section)

- **Handoff:** `3dc1b06d-4bc1-4e04-98a2-b5c177beadaa` (project)
- **Filed from:** https://openendedgroup.com/field4/syphon/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 3dc1b06d "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Try it (`public/docs/syphon.md:100`, high, needs-capture-system-work)

- **Point made:** The syphon_bridge starting point does a full round trip (publish 'Field pattern', receive it back and display it), but the page has no image of it at all.
- **Capture:** A box capture of the receiving box's output showing the pattern that came back through Syphon, run from the syphon_bridge transcript. Needs buildSyphonNative on macOS, so it would be a macOS-only retained capture profile.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
