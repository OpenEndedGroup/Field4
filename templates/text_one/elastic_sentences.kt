import afield.graphics.scene.reading.*
import afield.graphics.text.*

// Fixed landmarks and sliding constraints share one small connection helper. Only the
// interior nodes have inertia: the two visible endpoints always stay on the measured text.
val face = Typefaces.named("Noto Serif", 100, strict = true).withWeight(300.0)
val graph = readingDiagram {
    val water = text("water", "the river remembers\nsmall things", point("water-point", -.62, .22)) {
        font = face
        scale = .55f
    }
    val light = text("light", "a changing light", point("light-point", .65, .28, .035)) {
        font = face
        scale = .55f
    }
    val breath = text("breath", "between breaths", point("breath-point", .15, -.45, -.02)) {
        font = face
        scale = .55f
    }
    val thread = connection("river-thread",
        water.word("river").attach(DiagramTextSite.UNDERLINE),
        light.word("light").attach(DiagramTextSite.BOUNDARY), interiorNodes = 8) {
        responseSeconds = 1.0f
        dampingRatio = .8f
        lengthStiffness = 35f
        curvatureStiffness = 20f
        bow.set(0f, -.14f, .04f)
        tension = .15f
        width = .0015f
        color = vec(1f, 1f, 1f, .3f)
    }
    connection("margin-thread", water.attach(DiagramTextSite.RIGHT_MARGIN),
        breath.attach(DiagramTextSite.BOUNDARY), interiorNodes = 6) {
        responseSeconds = 1.7f
        dampingRatio = 1f
        bow.set(.05f, -.1f, 0f)
        width = .001f
        color = vec(1f, 1f, 1f, .2f)
        dashed(.025f)
    }
    // Connections are physics descriptors; their .path is the usual scoreable/drawable FLine.
    dot("thread-bead", thread.path.at(.5f)) {
        radius = .003f
        color = vec(1f, 1f, 1f, .35f)
    }
}
val preparation = progressReporter("Prepare elastic sentences", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, face, "field-slug".file, diagramScore(graph, 36.0) {}) { n, total, text ->
        preparation.update(n.toDouble() / total, text)
    }
} finally {
        preparation.close()
    }
prop<ReadingExample>(exampleSeven) = ReadingExample(
    "7 · Elastic sentences",
    "Slowly following SIMD threads: sliding word boundaries and multiline margins, with independent length, curvature and spline controls.",
    player,
)
controlGroup("elastic threads") {
    slider("Response (seconds)", initialValue = 1.0, min = .1, max = 6.0) {
        graph.connections.forEach { c -> c.responseSeconds = it.toFloat() }
    }
    slider("Length stiffness", initialValue = 35.0, min = 0.0, max = 300.0) {
        graph.connections.forEach { c -> c.lengthStiffness = it.toFloat() }
    }
    checkbox("Minimize length", initialValue = false) {
        graph.connections.forEach { c -> c.lengthMode = if (it) DiagramConnectionLength.MINIMIZE else DiagramConnectionLength.STABILIZE }
    }
    slider("Rest length multiplier", initialValue = 1.0, min = 0.0, max = 3.0) {
        graph.connections.forEach { c -> c.lengthScale = it.toFloat() }
    }
    slider("Curvature minimization", initialValue = 20.0, min = 0.0, max = 300.0) {
        graph.connections.forEach { c -> c.curvatureStiffness = it.toFloat() }
    }
    slider("TCB tension", initialValue = .15, min = -1.0, max = 1.0) {
        graph.connections.forEach { c -> c.tension = it.toFloat() }
    }
    slider("TCB continuity", initialValue = 0.0, min = -1.0, max = 1.0) {
        graph.connections.forEach { c -> c.continuity = it.toFloat() }
    }
    slider("TCB bias", initialValue = 0.0, min = -1.0, max = 1.0) {
        graph.connections.forEach { c -> c.bias = it.toFloat() }
    }
}
