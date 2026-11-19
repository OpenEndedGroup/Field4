# Landmark (ALT) A* — measurements and notes

Source: `src/jvmMain/kotlin/afield/data/graph/graph_connectivity.kt` (`graphAStarSearch`,
`GraphLandmarks`, `graphFarthestPointLandmarks`, `vec2GridGraph`); tests in
`src/jvmTest/kotlin/afield/data/graph/LandmarkAStarTest.kt`; user page
`public/docs/graph_connectivity_examples.md` ("Seeing what A* explored" onwards).

Field already had A* (`graphAStarPath`, `vec2AStarPath`, `vec3AStarPath`) before this work; it is
extended, not duplicated. Method: Goldberg & Harrelson, "Computing the shortest path: A* search
meets graph theory" (SODA 2005); explained interactively by Red Blob Games
(redblobgames.com/pathfinding/heuristics/differential.html). No figures reused.

## Measurement

"Expanded" = distinct nodes taken off the open queue, goal included (`expandedCount`).
Single query each, numbers from the page's second fence (2026-11-17):

| graph | nodes | Dijkstra | straight line | 1 landmark (at start) | 4 farthest | 8 farthest |
|---|---|---|---|---|---|---|
| 36×24 grid, wall at col 18 rows 4–23, (5,12)→(31,12) | 864 | 713 | 567 | 449 | 291 | 261 |
| Delaunay of 600 uniform points (seed 4), leftmost→rightmost | 600 | 600 | 146 | 397 | 142 | 102 |

On a Delaunay graph edge weight = Euclidean length, so straight line is already a strong bound;
landmarks only pay off at 4+. On a walled grid they win at 1.

## Traps

- `lines(...)` draws with y down here: grid row 0 is the top of the figure.
- Farthest-point placement starting at node 0 on a grid puts the first landmark in a corner.
- A landmark on the route (between start and goal) gives a bound near zero along it; that is the
  page's "does not help" panel.
