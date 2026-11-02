# Add the missing captures to functions_examples.md (2 sections)

- **Handoff:** `ef01c833-5f5c-4b54-8f4a-98249f589d09` (project)
- **Filed from:** https://openendedgroup.com/field4/functions_examples/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close ef01c833 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Browsing a function through its component graph (`public/docs/functions_examples.md:3625`, high, docs-runner)

- **Point made:** browseGraph() opens an interactive component browser with local/effect/muted/cascade/sensitivity views over the final curve, but the reader never sees the browser.
- **Capture:** diffstatic_runner box capture with open-outputs of gesture.browseGraph(channel = "components"), ideally with a node selected in effect view (drive script / page-eval click on the sidebar node) so the gray final and cyan trace both show.

### Watching a rewrite happen (`public/docs/functions_examples.md:3660`, high, docs-runner)

- **Point made:** browseRewrite draws before (dashed), after (cyan) and delta (orange) lanes per rule, a purely visual claim left unshown.
- **Capture:** Box capture of the gesture.browseRewrite(...) snippet with outputs open, showing the rewrite channel lanes for the two rules.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
