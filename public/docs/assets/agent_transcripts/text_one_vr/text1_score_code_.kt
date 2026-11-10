import afield.graphics.scene.reading.*

// All eight scores are separate from graph construction and native typography preparation.
// Re-run this box to rescore the existing players; their physics and Slug buffers are retained.
val allExamples =
    listOf(exampleOne, exampleTwo, exampleThree, exampleFour, exampleFive, exampleSix, exampleSeven, exampleEight)
for ((index, example) in allExamples.withIndex()) {
    val graph = example.player.diagram
    example.player.playback.score = diagramScore(
        graph,
        duration =
            if (index == 7) 30.0
            else if (index == 6) 36.0 else if (index == 5) 32.0 else if (index == 3) 24.0 else 20.0,
    ) {
            if (index == 7) {
                for (text in graph.texts) fadeIn(text, at = 0.0, seconds = 4.0)
                for (path in graph.paths) drawIn(path, at = 1.0, seconds = 5.0)
                move(graph.text("aside"), vec(0f, 0f, 0f), vec(-.1f, .08f, 0f), at = 6.0, seconds = 14.0)
                exitAll(at = 26.0, seconds = 4.0)
            } else if (index == 6) {
                for (text in graph.texts) fadeIn(text, at = 0.0, seconds = 2.0)
                for (path in graph.paths) drawIn(path, at = .5, seconds = 3.0)
                move(graph.text("light"), vec(0f, 0f, 0f), vec(.12f, -.3f, .08f), at = 4.0, seconds = 12.0)
                move(graph.text("light"), vec(0f, 0f, 0f), vec(-.12f, .3f, -.08f), at = 18.0, seconds = 12.0)
            } else if (index == 5) {
                for ((i, word) in graph.texts.withIndex()) {
                    blurIn(word, at = i * .7, seconds = 7.0, radius = .9f)
                    fadeIn(word, at = i * .7, seconds = 3.5)
                    blurOut(word, at = 18.0 + i * .7, seconds = 8.0, radius = 1.2f)
                    fadeOut(word, at = 24.0 + i * .7, seconds = 5.0)
                }
            } else if (index == 3) {
                val furniture = graph.group("furniture")
                val reading = graph.group("reading")
                fadeIn(furniture, at = 0.0, seconds = 1.0)
                for ((i, path) in furniture.items.filterIsInstance<DiagramPath>().withIndex()) drawIn(
                    path,
                    at = .15 + i * .06,
                    seconds = 1.2,
                )
                transition(furniture, reading, at = 9.0, seconds = 2.4)
                exitAll(at = 20.0, seconds = 2.5)
            } else {
                for ((i, text) in graph.texts.withIndex()) enter(text, at = i * .16, seconds = 1.0)
                for ((i, path) in graph.paths.withIndex()) drawIn(path, at = .2 + i * .10, seconds = 1.2)
                for ((i, dot) in graph.dots.withIndex()) enter(dot, at = .8 + i * .08, rise = 0f)
                exitAll(at = 17.0, seconds = 2.5)
            }
        }
    example.player.playback.seek(6.0)
}
// Keep the gallery through a re-score, replacing it only when graph construction
// produced different players. once/prop are the normal persistent property path.
prop<ReadingGallery>(poem)
once.poem { ReadingGallery(allExamples) }
if (poem.examples.map { it.player } != allExamples.map { it.player }) poem = ReadingGallery(allExamples)

fun describe() {
    val e = poem.examples[poem.selected]
    display("selected example", "<div style='padding:12px;width:440px'><b>${e.title}</b><p>${e.description}</p></div>")
}
controlGroup("reading spaces") {
    for ((i, e) in allExamples.withIndex()) button(e.title) { down ->
        if (down) {
            poem.select(i)
            poem.current.playback.seek(6.0)
            describe()
        }
    }
}
controlGroup("score") {
    checkbox("Play score", initialValue = true) { poem.current.playback.playing = it }
    checkbox("Repeat score", initialValue = true) { poem.current.playback.repeat = it }
    slider("Score time (seconds)", initialValue = 6.0, min = 0.0, max = 36.0) {
        poem.current.playback.playing = false
        poem.current.playback.seek(it.toDouble())
    }
    button("Restart entrances") { down ->
        if (down) {
            poem.current.restart()
            poem.current.playback.playing = true
        }
    }
    button("Show complete diagram") { down ->
        if (down) {
            poem.current.playback.playing = false
            poem.current.playback.seek(6.0)
        }
    }
}
controlGroup("gentle physics") {
    slider("Layout spring", initialValue = 28.0, min = 0.0, max = 100.0) {
        allExamples.forEach { e -> e.player.spring = it.toFloat() }
    }
    slider("Link stiffness", initialValue = 18.0, min = 0.0, max = 80.0) {
        allExamples.forEach { e -> e.player.constraintStrength = it.toFloat() }
    }
    slider("Gravity", initialValue = -.025, min = -.3, max = .1) {
        allExamples.forEach { e -> e.player.gravity = it.toFloat() }
    }
    slider("Air movement", initialValue = .012, min = 0.0, max = .3) {
        allExamples.forEach { e -> e.player.wind = it.toFloat() }
    }
    button("Still proof / reset") { down ->
        if (down) {
            poem.current.gravity = 0f
            poem.current.wind = 0f
            poem.current.resetPositions()
            poem.current.playback.playing = false
            poem.current.playback.seek(6.0)
        }
    }
}
describe()
