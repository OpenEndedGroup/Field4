# Confluence in 3D: 5 terms x 4 schedulers

**Open this to watch twenty λ-term reductions run at once, in 3D, and land on the same answer.**

```bash
./field.sh dev_docs/agents/demos -d confluence_grid
```

One box. Rows are terms, columns are schedulers — `FIRST_FOUND`, `LEFTMOST_BY_ID`,
`RIGHTMOST_BY_ID`, `HIGHEST_PAIR_ENERGY`. All twenty cells step on the same beat.

## What it is showing

Interaction nets are **strongly confluent**, so a row's four cells do not merely agree on the
answer — they take the *same number of reactions*. In the current run that is 35, 86, 127, 86 and
123 reactions for `succ two`, `mul 2 3`, `pred 3`, `2^3` and `3^3`; the box prints each row's four
counts.

What differs is *which* reaction fires at each beat. There is real choice: on `3^4`, 52 of 53 steps
have more than one active pair available, mean 8.5, max 13. So the columns visit different
intermediate shapes and different peak sizes (`3^3` peaks at 34/30/34/38) on the way to one answer.

Per row the box prints three verdicts. `one answer` checks all four readbacks against a
capture-avoiding normal-order substitution evaluator that shares nothing with the net (see
`stdlib.lambda-terms.smoke` and `dev_docs/INTERACTION_NETS_LAMBDA.md`). `landed exactly` checks
that every cell snapped onto its target pose. `matching positioned graph` compares the four final
drawings node for node and edge for edge. The first two are true on every row; the third currently
holds only for `succ two` and `3^3`.

## Two things the picture has to get right

**The layout should not invent difference.** `Net.relax` is 2D and path-dependent, so four
identical graphs draw four different ways and the picture claims the opposite of the theorem. The
final pose here comes from three rounds of Weisfeiler-Leman refinement — a node's label is its type
folded with its neighbours' labels, in port order — plus BFS layers, and nothing reads an id. It is
a heuristic, not a canonical labelling: nodes with equal labels can keep their own order, which is
why three rows still draw differently and why the box checks positioned edges rather than
coordinates alone.

**The landing must be legible.** While a cell reduces it runs its own 3D physics; on reaching normal
form it freezes and eases over 40 frames onto its target pose, snapping exactly on the last frame.

## Poking it

- The term list is at the top of the box. Add one and it joins the grid.
- `stepEvery` and the `Thread.sleep` set the tempo.
- Orbit the viewer — the grid is a plane of constellations, and the rows recede in depth.

## Two traps worth knowing

- **It paces itself with a blocking sleep loop.** That keeps it free of any native `Window` or
  `Renderer`; each frame's geometry streams into the Three.js viewer it already opened.
- **The output panel opens at 480 x 174 and ignores the size the viewer asks for.** That is a real
  defect, not this demo: drag the panel once and the size sticks. `dev_docs/OUTPUT_PANEL_SIZING.md`
  has the whole chain, and the seven hypotheses that were wrong about it.
