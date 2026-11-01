# Add the missing captures to output_requests.md (1 section)

- **Handoff:** `c7ee1a74-fb31-4633-81c4-819ee425792e` (project)
- **Filed from:** https://openendedgroup.com/field4/output_requests/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close c7ee1a74 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Connecting an output iframe to its box (`public/docs/output_requests.md:1`, medium, needs-capture-system-work)

- **Point made:** An iframe page in a box's output can send a request to the box's Kotlin callback and get a JSON reply, but the page shows only the two code halves and never the round trip working.
- **Capture:** A runner capture=box fence: the box registers outputRequests("editor"), displays a srcdoc iframe with a button that calls model.request({operation:'inspect'}) and writes the reply into the page, and the capture is taken after clicking it (drive script or page-eval click) so the output panel shows the real JSON reply.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
