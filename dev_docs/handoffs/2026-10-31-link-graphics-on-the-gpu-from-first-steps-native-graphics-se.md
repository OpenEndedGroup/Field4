# Link Graphics on the GPU from first_steps native graphics section

- **Handoff:** `fe6cc1a3-27d4-44bf-8340-c06fc3b07a30` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/tour_gpu_graphics/; also affects first_steps.md
- **Filed by:** agent:claude, 2026-10-31 14:09
- **Close with:** `python tools/docs_notes.py handoff-close fe6cc1a3 "what happened"`

## The notes that led here

- `ff9a3c60` (public/docs/tour_gpu_graphics.md:1): is this linked from the "first five mins"
  > Graphics on the GPU

Note ff9a3c60 asks whether this page is linked from the five-minute guide (`public/docs/first_steps.md`). It is not: a grep for `tour_gpu_graphics` in `first_steps.md` finds nothing. That page's section "Try a native graphics window" (about lines 141-150) sends the reader to the `first-graphics` starting point and stops. The link only goes the other way: `tour_gpu_graphics.md` line 10 links to `first_steps.md#next-a-native-graphics-window`.

The link belongs in `first_steps.md`, which this agent does not own.

Proposal: end that section with one sentence, e.g. "When the triangle runs, [Graphics on the GPU](tour_gpu_graphics.md) goes on to shaders and live edits." The alternative is a closing "where next" list on first_steps.

Question for a human: should the link go in that section, or in a closing list?
