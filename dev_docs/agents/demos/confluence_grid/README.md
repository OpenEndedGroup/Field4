# Confluence in 3D: 5 terms x 4 schedulers

**Open this to watch twenty λ-term reductions run at once, in 3D, and land on the same shapes.**

```bash
./field.sh dev_docs/agents/demos -d confluence_grid
```

One box. Rows are terms, columns are schedulers — `FIRST_FOUND`, `LEFTMOST_BY_ID`,
`RIGHTMOST_BY_ID`, `HIGHEST_PAIR_ENERGY`. All twenty cells step on the same beat.

## What it is showing

Interaction nets are **strongly confluent**, so a row's four cells do not merely agree on the
answer — they take the *same number of reactions* and finish together. Measured:

```
succ two  3/3/3/3     mul 2 3  7/7/7/7    pred 3  23/23/23/23
2^3      26/26/26/26  3^3     36/36/36/36
```

What differs is *which* reaction fires at each beat. There is real choice: on `3^4`, 52 of 53 steps
have more than one active pair available, mean 8.5, max 13. So the columns visit different
intermediate shapes and different peak sizes (`3^3` peaks at 34/30/34/38) on the way to one answer.

The box prints `one answer: true` and `identical pose: true` per row. Both are checked against a
capture-avoiding normal-order substitution evaluator that shares nothing with the net — see
`stdlib.lambda-terms.smoke` and `dev_docs/INTERACTION_NETS_LAMBDA.md`.

## Two things it has to get right to say that honestly

**The layout must not invent difference.** `Net.relax` is 2D and path-dependent, so four identical
graphs draw four different ways and the picture claims the opposite of the theorem. The final pose
here is built from a three-round Weisfeiler-Leman refinement — a node's label is its type folded
with its neighbours' labels, in port order — so the ordering is a structural invariant. Nothing
reads an id or an insertion order. A BFS-depth-only pose was not enough: it agreed on four rows and
broke on `3^3`, where 16 nodes and 10 `DUP`s leave sibling order ambiguous.

**The landing must be legible.** While a cell reduces it runs its own 3D physics; on reaching normal
form it eases over 40 frames onto the canonical pose.

## Poking it

- The term list is at the top of the box. Add one and it joins the grid.
- `stepEvery` and the `Thread.sleep` set the tempo; the slowest row is about 12 s.
- Orbit the viewer — the grid is a plane of constellations, and the rows recede in depth.

## Two traps worth knowing

- **This does not use `frames { }`.** A frames loop in a box with no `Window`/`Renderer` gets *zero*
  ticks, because the frame clock is the render loop. The box drives its own clock with a sleep loop.
- **The output panel opens at 480 x 174 and ignores the size the viewer asks for.** That is a real
  defect, not this demo: drag the panel once and the size sticks. `dev_docs/OUTPUT_PANEL_SIZING.md`
  has the whole chain, and the seven hypotheses that were wrong about it.
