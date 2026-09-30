# Relax the minWeight snapshot figure so its components untangle

- **Handoff:** `5f46be25-2417-49f6-a20d-4bf934b7c37e` (project)
- **Filed from:** https://openendedgroup.com/field4/dynamic_graph_systems_examples/
- **Filed by:** agent:claude, 2026-09-30 20:41
- **Close with:** `python tools/docs_notes.py handoff-close 5f46be25 "what happened"`

## The notes that led here

- `2a638b8f` (public/docs/dynamic_graph_systems_examples.md:644): this example could, I assume, be laid out better --- some kind of relaxation should untangle those blobs and show their structure
  > At minWeight = 1.2 the snapshot contains zero edges between groups, and its components are exactly the background mesh and the three clusters, at exactly their true sizes. The group array in the code above is used only to check that, never to build the graph, which sees nothing but positions and a clock. The dim purple streamers in the top two panels are the entire population of transient links, and they are what the threshold removes.

## What was asked

Section 6 ("Snapshots, and the `minWeight` cut") of `public/docs/dynamic_graph_systems_examples.md`
shows four panels of the same graph at rising `minWeight` thresholds. The human's note: "this
example could, I assume, be laid out better — some kind of relaxation should untangle those blobs
and show their structure."

## Why this is bigger than a docs edit

The figure is a live capture (`screenshot="assets/dynamic_graph_systems_examples/snapshot.png"`)
of a `diffstatic_runner` fence. Changing the layout means changing the executed code and
recapturing, which needs the docs runner; the per-page docs agents may not run it. It may also
want a library helper that does not exist yet.

## Evidence

- The fence draws `dynamicGraphFLines(state.points, kept, ...)` — the edges at the *physical*
  positions of the final frame. The three 22-point clusters are discs of radius 0.07 sitting on top
  of the 240-point background mesh, so at thresholds 0.0 and 0.5 everything overlaps in one blob,
  and even at 1.2 / 2.0 the clusters are drawn over the mesh they have just separated from.
- The page already has a spring relaxation: `SpringMotionProcess` in
  `src/jvmMain/kotlin/afield/stdlib/dynamic_graph_systems.kt` (used in the morphogenesis section).
  `graph_connectivity.kt` (the `PointGraph` side) may have or want a force-directed layout.

## Proposed approach

Keep the physical-position panel (it is what the run actually did) and add a second row, or
replace the panels, with a force-directed layout of each `toPointGraph(minWeight = cut)` result:
a few hundred iterations of spring + repulsion starting from the physical positions. At high
thresholds the four components would then separate visibly; at low thresholds the transient
streamers would show as bridges between them.

Alternatives: (a) do the relaxation inline in the fence with `SpringMotionProcess` over a copy of
the snapshot; (b) add a reusable `PointGraph.relaxedLayout(...)` helper to `graph_connectivity.kt`
and use it here (and possibly on `graph_connectivity_examples.md`); (c) colour by component
instead of re-laying out.

## Questions for the human

- Replace the physical-position panels, or show both (physical above, relaxed below)?
- Inline code in the fence, or a library helper?

## What the page needs afterwards

Updated fence and recaptured `snapshot.png`, a new alt text, and the paragraph after the table
("The dim purple streamers in the top two panels ...") rewritten to describe the new picture. The
table numbers do not change unless the simulation itself changes.
