# Fix orthant-graph KDoc: code builds the union, KDoc says mutual

- **Handoff:** `6c077804-15c4-4edf-872e-6f244c3d2a69` (project)
- **Filed from:** https://openendedgroup.com/field4/graph_connectivity_examples/; also affects reference/graphics.md
- **Filed by:** agent:claude, 2026-09-30 20:53
- **Close with:** `python tools/docs_notes.py handoff-close 6c077804 "what happened"`

**What was asked.** The note on `graph_connectivity_examples.md` asked whether Field has an orthant neighbourhood graph. It does, and this pass added a section showing it. While checking the code I found that its KDoc describes a different graph than the code builds. The KDoc is in `src/`, which this docs pass is not allowed to edit.

**Evidence.**
- `src/jvmMain/kotlin/afield/stdlib/graph_connectivity.kt` — the KDoc on `vec2OrthantGraph` (and on `vec3OrthantGraph`) says "Two points are connected if they are *mutual* nearest neighbors in the same orthant."
- The body of `vec2OrthantGraph` gives every point `i` an edge to its nearest point in each non-empty quadrant, and adds it to a `mutableSetOf<GraphEdge>()`. It never checks that `j` also picks `i`. `GraphEdge.equals` ignores direction (same file, `data class GraphEdge`), so the set only removes duplicates. The graph is the **union** of the per-point choices, not the mutual intersection. `vec3OrthantGraph` works the same way over octants.
- `GraphConnectivitySmokeTest.test2DOrthantGraph` expects the centre point to have degree 4. That holds under both readings, so the test cannot tell them apart.

**Proposed approach.** Change both KDocs to say what the code does: "Each point is connected to its nearest neighbour in each orthant (quadrant/octant) that contains one; the graph is the union of those edges." The regenerated `public/docs/reference/graphics.md` then follows.

**Alternative.** If the mutual version was the intent, change the code to keep an edge only when both endpoints choose each other. Add a smoke check that separates the two versions. For example, with three collinear-ish points where only one of the two choices is mutual, the union has one more edge than the mutual graph. Callers that depend on today's denser graph would then change.

**Question for the human.** Should the orthant graph be the union (what the code does now) or the mutual graph (what the KDoc says)?

**What the page needs afterwards.** Nothing if the KDoc is fixed; the new section on the page already describes the union. If the code becomes mutual, reword the section's "at most four edges of its own, and the points other points choose add more" and recapture `assets/graph_connectivity_examples/orthant_vs_knn.png`.
