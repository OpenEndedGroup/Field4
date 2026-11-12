# Elastic connections between words

The **Elastic sentences** playground connects an underline, a word boundary and a multiline
margin. Intermediate nodes carry inertia; endpoints remain kinematic constraints on the text.
The existing SIMD Verlet engine integrates all thread nodes in one separate batch at 120 Hz.
It does not add particles or neighbour slots to the word graph, or pull words around by reaction.

```kotlin
val graph = readingDiagram {
    val a = text("a", "the river remembers\nsmall things", point("pa", -.6, .2))
    val b = text("b", "a changing light", point("pb", .65, .25, .03))
    val thread = connection(
        "thread",
        a.word("river").attach(DiagramTextSite.UNDERLINE),
        b.word("light").attach(DiagramTextSite.BOUNDARY),
        interiorNodes = 8,
    ) {
        responseSeconds = 1.0f
        dampingRatio = .8f
        layoutStrength = 1f
        lengthMode = DiagramConnectionLength.STABILIZE
        lengthStiffness = 35f
        lengthScale = 1f
        curvatureStiffness = 20f
        bow.set(0f, -.14f, .04f)
        tension = .15f
        continuity = 0f
        bias = 0f
        width = .0015f
        color = vec(1f, 1f, 1f, .3f)
    }
    dot("bead", thread.path.at(.5f)) { radius = .003f }
}
```

A connection has a normal **`.path`**: pass it to `drawIn`, `drawOut`, `fadeIn`, `dashed`,
path-relative dots and any other stroke/score operation. `graph.connection("thread")` retrieves
the live dynamics. `thread.nodes(player.frame())` returns copies for inspection or debug dots.
Node count is construction-time (1–64 interior nodes); other controls are live. Rebuild the
player when the graph or node count changes. A score change alone retains the node histories.

## Endpoint choices

Both a `DiagramText` run and a `DiagramTextSpan` support `attach(site, bounds, underlineGap)`.
Words, literal spans and `line(index)` retain their coordinates inside the entire shaped run.

| Site | Constraint |
| --- | --- |
| `CENTER`, `LEFT`, `RIGHT` | Fixed centre or side-centre of the selected bounds |
| `CLOSEST` | Nearest point in the selected per-line rectangles, including their interiors |
| `BOUNDARY` | Nearest point on a per-line rectangle's perimeter |
| `UNDERLINE` | Nearest point along a selected line's advance, below its baseline |
| `LEFT_MARGIN`, `RIGHT_MARGIN` | Nearest point on each selected line's left/right edge |

The default bounds are `INK`. Choose `EM`, `NO_DESCENDERS`, `CAPS`, `X_HEIGHT` or `ADVANCE`
when those suit the typography. Underline uses the line advance and baseline regardless of
bounds; its default gap is .014 metres. Other sites lie exactly on/in the selected rectangles,
without extra padding. These are measured text boxes, not projections onto individual glyph
outlines. A multiline margin follows each line's own width instead of the longest line's box.
A sliding endpoint may switch to another line when it becomes closer.

Projection is in diagram space after text rotation, font scaling, physics and score offsets.
Each endpoint slides toward its adjacent interior node. Before the first physics step, the
opposite text endpoint seeds that direction. Fixed anchors also work directly:
`connection("fixed", a.right, b.word("light").below)`; use `.attachment()` to mix an existing
anchor with a sliding attachment. Carets, particles and path anchors work this way too.

## Three independent kinds of stiffness

**Layout following** attracts interior nodes to interpolated endpoint positions plus
`4t(1-t) * bow`. `responseSeconds` is an approximate settling time (.05–60 seconds);
`dampingRatio` controls ringing (0–4; about 1 is critical for the layout spring alone).
`layoutStrength = 0f` disables this attraction, leaving length/curvature forces and damping.
Bow is metres in diagram coordinates; its endpoint contribution is always zero.

**Length** is controlled by `lengthStiffness` (0–1000). `STABILIZE` attracts each segment to
its endpoint-layout rest distance. `restLength = 1.2f` chooses a fixed total length in metres;
segment shares follow the target layout, and `lengthScale` (0–10) multiplies either kind of
rest length. `MINIMIZE` sets segment rest lengths to zero and contracts excess arc length.
With separated pinned endpoints it cannot make the total length zero: it tends toward a taut
line. These are soft forces, not a hard inextensibility guarantee.

**Curvature** is `curvatureStiffness` (0–1000), independent of length. It applies the negative
gradient of `.5 * sum(lengthSquared(p[i-1] - 2*p[i] + p[i+1]))`, with endpoints fixed. It uses
neighbours out to two steps, straightening bends rather than merely shortening segments.
This is discrete curvature in node-index space, not an arclength-normalized rod model. Changing
node count changes its effective stiffness. Both forces compete with layout following; a
strong curvature term can flatten an authored bow. Zero disables the corresponding force.

**TCB** `tension`, `continuity` and `bias` (-1–1) shape the rendered FLine through the current
nodes. They do not push particles. Zero uses Field's Catmull–Rom-like TCB defaults; tension 1
flattens tangents. GPU thickening, dashes, arrows and arclength trimming remain the usual
FLine rendering path. Curves can overshoot between nodes at loose TCB settings.

## Timing and attachment guarantees

Physics follows the player's fixed steps, independently of score play/pause. Pausing a score
therefore leaves the threads free to settle. Reads of a `DiagramFrame`, and `update(0.0)`, do
not integrate thread state. Both visible endpoints resolve afresh even on a zero-step frame,
so a moved/scaled word cannot detach from a lagging intermediate node. A score `move` on the
connection's path bends its target interior with the same zero-at-endpoints envelope; it does
not translate endpoints off their words. Opacity and draw-in/out still apply normally.

`restart()` and `resetPositions()` reset thread motion with the word graph. A new player has
its own thread history even when it shares the same diagram descriptor. Score seeking moves
current text targets but retains intermediate history until a physics reset; use a reset for
a clean cut. Very fast target jumps can create a temporary long first/last segment by design.

For tomorrow: select **7 · Elastic sentences**, let the score run, then compare Length
stiffness, Minimize length, Curvature minimization and TCB tension separately. To test pure
thread mechanics, set `layoutStrength = 0f` in the connection block and disable word wind/gravity.
