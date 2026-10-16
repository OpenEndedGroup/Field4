# Stop animateEvolution's recorder falling behind under a geometric ramp

- **Handoff:** `4dcf4374-f5f3-4299-b775-a2f724736c37` (project)
- **Filed from:** https://openendedgroup.com/field4/dynamic_graph_systems_examples/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close 4dcf4374 "what happened"`

Rule 25 sharp edge on `public/docs/dynamic_graph_systems_examples.md`, aside "Why the morphogenesis clip records for longer than it runs":

> `animateEvolution` with a geometric `ramp` makes the last frames' step batches the
> largest, so the recorder falls furthest behind at the end. The clip records for
> `duration = 13` against `seconds = 10` so that it reaches the settled result; at
> `duration = 11` the take stopped before the final step.

The workaround (over-recording by a hand-tuned margin) belongs in
`src/jvmMain/kotlin/afield/simulation/agents/alife_animate.kt`: the recorder should
record until the last scheduled step has rendered (or derive duration from the
schedule), so `duration` can equal `seconds`. Then set the fence back to matching
values (a fence change plus recapture) and delete the aside in the same change.
