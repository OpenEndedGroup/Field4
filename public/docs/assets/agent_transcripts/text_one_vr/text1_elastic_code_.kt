import afield.graphics.scene.reading.*
import afield.graphics.text.*

/*
Verbatim excerpts from texts/source-passage.txt; line breaks are the only layout edits.
Fixed landmarks and sliding constraints share one small connection helper. Only the
interior nodes have inertia: the two visible endpoints always stay on the measured text.
*/
val face = Typefaces.named("Noto Serif", 100, strict = true).withWeight(300.0)
val readingFrames = mutableListOf<DiagramTextFrame>()
val graph = readingDiagram {
    val door = text("door", "When the door closed\nbehind me", point("door-point", -.62, .22)) {
        font = face
        scale = .55f
    }
    val eyes = text("eyes", "my eyes were going\nto be useless in the dark", point("eyes-point", .65, .28, .35)) {
        font = face
        scale = .55f
    }
    val hands = text("hands", "hands lifting—feeling around\nin front of me.", point("hands-point", .15, -.45, -.3)) {
        font = face
        scale = .55f
    }
    val nonexistence = text("nonexistence", "nonexistence.", point("nonexistence-point", -.8, -.38, .65)) {
        font = Typefaces.firstAvailable("Noto Serif Thin", "Noto Serif Light", "Noto Serif", size = 100)
        scale = .62f
    }
    val sipping = text("sipping", "sipping.", point("sipping-point", 1.4, .05, -.7)) {
        font = face
        italic = true
        scale = .58f
    }
    val taut = text("taut", "this sentence,\npulled taut, goes nowhere:", point("taut-point", -.15, .65, -.5)) {
        font = face
        scale = .52f
    }
    val frames =
        listOf(door, eyes, hands, nonexistence, sipping, taut).associateWith { run ->
            textFrame("${run.id}-frame", run, standoff = .025f, mode = DiagramTextFrameMode.LINE_UNION) {
                    width = .0008f
                    color = vec(1f, 1f, 1f, .22f)
                }
                .also { readingFrames.add(it) }
        }
    val thread = connection(
        "door-thread",
        frames.getValue(door).attach(),
        frames.getValue(eyes).attach(),
        interiorNodes = 8,
    ) {
        responseSeconds = 1.0f
        dampingRatio = .8f
        lengthStiffness = 35f
        curvatureStiffness = 20f
        bow.set(0f, -.14f, .04f)
        tension = .15f
        width = .0015f
        color = vec(1f, 1f, 1f, .3f)
    }
    val marginThread = connection(
        "margin-thread",
        frames.getValue(door).attach(DiagramTextSite.RIGHT_MARGIN),
        frames.getValue(hands).attach(),
        interiorNodes = 6,
    ) {
        responseSeconds = 1.7f
        dampingRatio = 1f
        bow.set(.05f, -.1f, 0f)
        width = .001f
        color = vec(1f, 1f, 1f, .2f)
        dashed(.025f)
    }
    val silenceThread = connection(
        "nonexistence-thread",
        frames.getValue(hands).attach(),
        frames.getValue(nonexistence).attach(),
    ) {
        responseSeconds = 1.4f
        bow.set(0f, -.08f, .06f)
        width = .001f
        color = vec(1f, 1f, 1f, .25f)
    }
    val elsewhereThread = connection(
        "sipping-thread",
        frames.getValue(eyes).attach(DiagramTextSite.RIGHT_MARGIN),
        frames.getValue(sipping).attach(),
    ) {
        responseSeconds = 1.1f
        bow.set(.05f, 0f, .04f)
        width = .001f
        color = vec(1f, 1f, 1f, .25f)
    }
    val tideThread = connection(
        "taut-thread",
        frames.getValue(door).attach(),
        frames.getValue(taut).attach(),
    ) {
        responseSeconds = 1.8f
        bow.set(0f, .1f, -.06f)
        width = .001f
        color = vec(1f, 1f, 1f, .25f)
    }
    // Endpoints use the same live path anchors, including sliding attachments.
    for (line in listOf(thread, marginThread, silenceThread, elsewhereThread, tideThread)) endpointDots(line.path)
}
// Each text plane turns about its own physics point. The stage itself stays fixed in the room.
graph.texts.forEach { it.faceReader = true }
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
    "Six independently facing text planes in a shallow volume, endpoint dots and fading elastic links. The passage’s nonexistence is lighter; sipping is italic.",
    player,
)
controlGroup("elastic threads") {
    slider("Response", unit = "s", initialValue = 1.0, min = .1, max = 6.0) {
        graph.connections.forEach { c -> c.responseSeconds = it.toFloat() }
    }
    slider("Length stiffness", initialValue = 35.0, min = 0.0, max = 300.0) {
        graph.connections.forEach { c -> c.lengthStiffness = it.toFloat() }
    }
    checkbox("Minimize length", initialValue = false) {
        graph.connections.forEach { c ->
            c.lengthMode = if (it) DiagramConnectionLength.MINIMIZE else DiagramConnectionLength.STABILIZE
        }
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

controlGroup("individual text planes") {
    checkbox("Each run faces the reader", initialValue = true) {
        graph.texts.forEach { run -> run.faceReader = it }
    }
    slider("Facing half-life", unit = "s", initialValue = .7, min = 0.0, max = 3.0) {
        graph.texts.forEach { run -> run.facingHalfLife = it.toFloat() }
    }
}

controlGroup("text frames") {
    checkbox("Per-line rectangle union", initialValue = true) { enabled ->
        readingFrames.forEach { frame ->
            frame.mode = if (enabled) DiagramTextFrameMode.LINE_UNION else DiagramTextFrameMode.BOUNDS
        }
    }
    checkbox("Merge small steps", initialValue = true) { enabled ->
        readingFrames.forEach { frame -> frame.mergeSmallSteps = enabled }
    }
    slider("Frame standoff", unit = "m", initialValue = .025, min = 0.0, max = .15) {
        readingFrames.forEach { frame -> frame.standoff = it.toFloat() }
    }
    slider("Frame opacity", initialValue = .22, min = 0.0, max = 1.0) {
        readingFrames.forEach { frame -> frame.path.color.w = it.toFloat() }
    }
    slider("Frame width", unit = "mm", initialValue = .8, min = .1, max = 3.0) {
        readingFrames.forEach { frame -> frame.path.width = it.toFloat() * .001f }
    }
}
