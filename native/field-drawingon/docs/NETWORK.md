# Drawing On curve network

Paste [NetworkWorkout.kt](../NetworkWorkout.kt) into a Field box and run it. The fixed 2D fixture
shows measured strokes, color-coded retrace groups, and fitted edges with graph vertices. Try
varying each tolerance and watch the short partition, the two wall faces, the open doorway and the
arch. This is a geometry workout with known input, so the same parameter changes can be tried
without a device.

Use `pad.capture(session).networkAt()` to fit a take received by Field. The same method works on
a capture opened with `readDrawingOn(path)`. Pass a capture time to scrub the graph. It uses
completed visible strokes and respects the same layer overrides as ink replay. Undo, redo and
late corrections are reconstructed from capture history. The graph's nodes have drawing-plane XY
coordinates. Edges have vertex IDs, source stroke IDs, a user layer and an FLine. The clusters
give the original input to each fitted curve. Use `toFLines(showNodes = true)` for a Field preview
or `pad.publish(graph.toFLines(), title = "Clean drawing")` for the iPad reference overlay.

Paste [LiveNetwork.kt](../LiveNetwork.kt) into a Field box and set the iPad target to `network`.
This example follows the most recent live session and refreshes on pen lifts, corrections, undo
and layer changes. It publishes to the target's single reference layer. Drawing On's
`onEvent` runs on Field's Runloop. Benchmark representative takes before increasing the update
rate: this prototype fits a snapshot of visible strokes per call. Explicit lines and circles keep
their tool geometry. Endpoint-to-interior joins split the host curve. Interior crossings remain
independent. The graph can change when stroke evidence is removed or a junction changes.

The in-process baseline groups close coextensive freehand retraces using arc-length samples and
fits lines or arcs using Field's low-segment fitter, with a polyline fallback. It does not infer
wall thickness, attach lines to circles, merge partial overlaps, or infer depth. Tolerances are
in drawing units. The oldest member anchors retrace membership, and a cluster can change if
that member is undone.

Call `graph.strokeStripScap()` to export the freehand groups as an upstream
[StrokeStrip](https://github.com/davepagurek/StrokeStrip) input file. Each candidate cluster gets
one ID. Coordinates have a shared origin shift to the freehand bounds and pressure is set to
zero. StrokeStrip fits pre-clustered strokes and its reference executable requires a licensed
Gurobi install. Run it separately to compare its fitted SVG to Field's baseline. The workout
and the Field backend both run without Gurobi.

The quick fit uses Pencil timestamps and screen positions to detect sustained slow, curved
movement. If a stroke contains such a passage, it keeps the measured shape as a smooth FLine
and skips arc fitting and retrace merging for that stroke. A straight slow stroke can still
be straightened. Captures without per-sample timing use the usual geometric fit.

The quick fit prefers an arc when a shallow bend has consistent curvature. Short sideways
ticks or hooks at either end can mark the endpoints of a line, arc, or circle. The fit can
omit those marks when the rest of the stroke fits the shape. The capture keeps every sample.

The live example also shows **Plan geometry** after a pause. It consolidates straight continuations,
checks for a supported pair of perpendicular directions per layer, and uses Field's Ceres solver
to align those edges together. Curved and off-axis edges anchor their shared nodes. The fit rejects
large moves and substantial narrowing of short gaps between disconnected tips. Isolated repeated
hatch strokes and layers named **Annotation**, **Markup**, or **Hatch** are shown dimmed in Field
and omitted from the plan sent to the iPad. The quick fit stays available in the **lines** panel.
On the iPad, Plan cleanup replaces the quick fit in the target's reference layer.
