# Add the missing captures to functions_domains.md (2 sections)

- **Handoff:** `01e4e697-657a-4cf0-8915-84602f109ed7` (project)
- **Filed from:** https://openendedgroup.com/field4/functions_domains/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 01e4e697 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Safe to enumerate: `finiteSweep()` and `plotSafe()` (`public/docs/functions_domains.md:105`, medium, docs-runner)

- **Point made:** An unbounded oscillator plotted with no window previews over a finite fallback range instead of hanging, while an explicit domain samples exactly that interval.
- **Capture:** Make the existing static fence a diffstatic_runner with capture="box" open-outputs="true": plot sin(3.0) with domain(0.0..2.0) in one channel and with no window (the plotSafe fallback) in another, so the reader sees the two different spans side by side.

### The exception: `gaussian` stays local (`public/docs/functions_domains.md:125`, low, docs-runner)

- **Point made:** gaussian is a local bump with a finite default domain, while sin is defined everywhere, and that difference limits any expression the bump enters.
- **Capture:** A runner box that plots gaussian(), sin(), and gaussian()*sin() with no explicit window, showing the product clipped to the bump's domain. The plot is the proof of the intersection rule.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
