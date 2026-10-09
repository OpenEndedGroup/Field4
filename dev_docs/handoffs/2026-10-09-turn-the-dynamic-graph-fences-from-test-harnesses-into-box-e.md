# Turn the dynamic graph fences from test harnesses into box examples

- **Handoff:** `58408074-9c95-4276-a8e5-e66512055672` (project)
- **Filed from:** https://openendedgroup.com/field4/dynamic_graph_systems_examples/
- **Filed by:** human, 2026-10-09 09:14
- **Close with:** `python tools/docs_notes.py handoff-close 58408074 "what happened"`

The prose of `public/docs/dynamic_graph_systems_examples.md` was rewritten for users (2026-10-09 language sweep); the fences were left byte-identical and still read as verification harnesses rather than box code.

- The decay fence prints closed-form comparisons to nine decimal places and a ttl expiry check. A user needs: make an edge, decay it, plot it. Move the closed-form checks to `DynamicGraphSystemsTest` (they are already asserted there) and shorten the fence; recapture `decay.png`.
- The hysteresis fence combines a two-point walk with the wobbling-cloud comparison; consider showing only the cloud, with the walk in a fold.
- Fence comments in capitals ("NEVER MOVES", "HELD AT ZERO", "the OTHER way") and test-voice comments ("otherwise the picture is a plot of the documentation") should become plain comments.
- The six-systems table still types measured counts that `DynamicGraphSystemsTest.pageFigureOneSixSystemsTable` pins; decide whether to keep the table or derive it.
- Consider publishing the morphogenesis scene as a starting point and linking it.
