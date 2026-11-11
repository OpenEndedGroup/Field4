import afield.graphics.scene.reading.*

// Separate words can breathe on separate clocks. The blur radius is measured in ems;
// opacity is independent, so a word can stay bright while its outline dissolves.
val serif = Typefaces.named("Noto Serif", 100, strict = true).withWeight(300.0)
val graph = readingDiagram {
    for ((i, word) in listOf("words", "becoming", "weather").withIndex()) {
        val p = point("word-$i", (i - 1) * .65f, .04f, pinned = true)
        text("blur-word-$i", word, p) {
            font = serif
            scale = .48f
            // Reserve once, before Drawing. Scrubbing blur then only samples cached masks.
            blurCapacity = 2.2f
        }
    }
}
val progress = progressReporter("Prepare words becoming weather", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, serif, "field-slug".file, diagramScore(graph, 32.0) {}) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
        progress.close()
    }
prop<ReadingExample>(exampleSix) = ReadingExample(
    "6 · Words becoming weather",
    "Slow word-local Gaussian blur: emerge from a soft cloud, hold in focus, then dissolve again. Structural lines and other words stay sharp.",
    player,
)
