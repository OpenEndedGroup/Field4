# Graph Connectivity Library

Self-contained graph / computational-geometry library (KNN, Orthant, RNG, Gabriel,
Beta-Skeleton, MST, Urquhart, Maximum-Cut, Complete graphs, plus JTS-backed
Voronoi/Delaunay/Convex-Hull, and BFS/DFS/A* path search) over `Vec2`/`Vec3`
(`afield.stdlib.linalg`) point sets.

The per-algorithm reference lives as KDoc next to the code — read it there rather than
duplicating it here:

- **Source + KDoc for every function**: `src/jvmMain/kotlin/afield/stdlib/graph_connectivity.kt`
  (file-level KDoc gives the algorithm map and the custom-distance-metric rules — note that
  Orthant graphs honor `customDistanceMetric` for their nearest-neighbour ranking within each orthant)
- **FLine rendering integration**: `src/jvmMain/kotlin/afield/stdlib/fline_graph_connectivity.kt`
- **Smoke tests**: `src/jvmMain/kotlin/afield/examples/stdlib/GraphConnectivitySmokeTest.kt`
- **Live rendered examples**: `public/docs/graph_connectivity_examples.md`
