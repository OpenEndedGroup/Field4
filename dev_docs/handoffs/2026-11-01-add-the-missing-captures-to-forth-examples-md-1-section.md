# Add the missing captures to forth_examples.md (1 section)

- **Handoff:** `6399e246-2f84-4391-bed9-c05b1b7f5688` (project)
- **Filed from:** https://openendedgroup.com/field4/forth_examples/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 6399e246 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### `again`, diagnostics, and display helpers (`public/docs/forth_examples.md:260`, medium, docs-runner)

- **Point made:** Says the error includes the current token, a stack snapshot and the word call stack, and describes what it names, but never shows the error itself (the display helpers part of the section is captured, the error is not).
- **Capture:** An editor capture with inline outputs of the 'inner nope' box run with expects-error=true, showing the red marker and the stack and call-path text, like remotejs's error capture.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
