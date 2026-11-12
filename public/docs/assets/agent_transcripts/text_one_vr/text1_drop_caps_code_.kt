import afield.graphics.scene.reading.*

/*
Authored line breaks are part of the poem. The first three lines sit beside the initial;
the fourth returns to the paragraph's left margin. Everything is one measured run, with bounded Slug atlases for its
render parts.
*/
val body = Typefaces.named("Noto Serif", 100, strict = true).withWeight(300.0)
val graph = readingDiagram {
    val paragraph = text(
        "paragraph",
        "The river carries\nsmall things toward\nthe edge of light\nand brings them home.",
        point("paragraph-point", -.15, .25),
    ) {
        font = body
        scale = .65f
        blurCapacity = .8f // reserve body-em Gaussian range before Drawing builds its caches
        dropCap(lines = 3, gapEm = .2f) {
            font = body
            weight = 600.0
        }
    }
    val aside = text("aside", "remember this", point("aside-point", .5, -.4, .03)) {
        font = body
        italic = true
        scale = .45f
    }
    connection(
        "initial-thread",
        paragraph.initial.attach(DiagramTextSite.BOUNDARY),
        aside.attach(DiagramTextSite.LEFT_MARGIN),
    ) {
        responseSeconds = 1.2f
        bow.set(0f, -.07f, .025f)
        width = .001f
        color = vec(1f, 1f, 1f, .2f)
    }
}
val progress = progressReporter("Prepare a dropped initial", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, body, "field-slug".file, diagramScore(graph, 30.0) {}) { n, total, text ->
        progress.update(n.toDouble() / total, text)
    }
} finally {
        progress.close()
    }
prop<ReadingExample>(exampleEight) = ReadingExample(
    "8 · A dropped initial",
    "An ink-fitted three-line initial, body text returning to the left margin, and an elastic connection anchored to the initial itself.",
    player,
)

controlGroup("drop-cap paragraph") {
    slider("Paragraph blur (body ems)", initialValue = 0.0, min = 0.0, max = .8) {
        graph.text("paragraph").blur = it.toFloat()
    }
}
