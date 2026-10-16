# Make in correct for a wrapped polar rangeSlider band

- **Handoff:** `d0e92f1a-caa7-49bd-9a36-43c436b03506` (project)
- **Filed from:** https://openendedgroup.com/field4/visualizers/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close d0e92f1a "what happened"`

Rule 25 sharp edge on `public/docs/visualizers.md` (section "The same controls, on a dial"). The page has to warn:

> A wrapped band has `start > endInclusive`, which Kotlin's own `in` reads as an empty range, so test it with `arcContains`. On a dial a range means an arc, so use it for values that are already circular: a phase, a hue, a window into a loop.

A polar `rangeSlider` hands back a `ClosedRange` whose `contains`/`in` silently answers wrong for a wrapped band. Fix in the controls API (`afield/ui/...` rangeSlider / `arcContains`): give polar range sliders a value type (e.g. an `Arc` with `start`, `end`, and a `contains` operator that wraps) so `x in band` is correct, keeping `arcContains` as a deprecated alias if needed. In the same change delete the first sentence of that paragraph (keep the advice about circular values) and update the sequencer-polar example only if it uses `arcContains` (that fence is captured, so recapture it).
