# Add the missing captures to loris.md (1 section)

- **Handoff:** `0ff52ea1-962a-4c61-baee-8637bc467401` (project)
- **Filed from:** https://openendedgroup.com/field4/loris/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 0ff52ea1 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Time versus frequency resolution (`public/docs/loris.md:284`, high, docs-runner)

- **Point made:** A fine-time pass (windowWidthHz = 160) and a fine-frequency pass (windowWidthHz = 40) are different readings of the same source, and a small hop cannot recover events blurred by a long window.
- **Capture:** Two stacked box captures of the dialogue (or a percussive) source analysed at windowWidthHz 160 and 40, using the same partial-track panel as the page's other loris captures. Ideally each has audio= resynthesis so the smeared attacks can be heard.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
