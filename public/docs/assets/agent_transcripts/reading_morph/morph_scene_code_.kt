import afield.graphics.scene.reading.*

// Each line is cut into keyed pieces. A key on both sides glides; a key on one side fades where it stands.
// Keys are yours: here they name the terms of an expression, so the second `a` is a new key.
fun keyed(text: String, vararg parts: Pair<String, String>): List<DiagramKeyedSpan> {
    var from = 0
    return parts.map { (s, k) ->
        val i = text.indexOf(s, from)
        from = i + s.length
        DiagramKeyedSpan(i, i + s.length, k)
    }
}
val one = "a × (b + c)"
val two = "a × b + a × c"
val three = "a × b + c × a"
val graph = readingDiagram {
    val here = point("here", 0, .02, pinned = true)
    val caption = point("caption", 0, -.12, pinned = true)
    textMorph(
        "distribute",
        one,
        keyed(one, "a" to "a", "×" to "times", "b" to "b", "+" to "plus", "c" to "c"),
        two,
        keyed(two, "a" to "a", "×" to "times", "b" to "b", "+" to "plus", "a" to "a2", "×" to "times2", "c" to "c"),
        here.anchor,
    ) {
        scale = 1.4f
    }
    textMorph(
        "commute",
        two,
        keyed(two, "a" to "a", "×" to "times", "b" to "b", "+" to "plus", "a" to "a2", "×" to "times2", "c" to "c"),
        three,
        keyed(three, "a" to "a", "×" to "times", "b" to "b", "+" to "plus", "c" to "c", "×" to "times2", "a" to "a2"),
        here.anchor,
    ) {
        scale = 1.4f
    }
    text("why-1", "distribute: the brackets go, a second a arrives", caption) {
        scale = .5f
        color = Vector4f(1f, 1f, 1f, .6f)
    }
    text("why-2", "commute: c and a change places", caption) {
        scale = .5f
        color = Vector4f(1f, 1f, 1f, .6f)
    }
}
val distribute = graph.textMorph("distribute")
val commute = graph.textMorph("commute")
val score = diagramScore(graph, 12.0) {
    fadeIn(distribute.group, at = 0.0, seconds = 1.0)
    fadeIn(graph.text("why-1"), at = .5, seconds = 1.0)
    morph(distribute, at = 1.5, seconds = 2.0)
    fadeOut(distribute.group, at = 5.0, seconds = .01)
    fadeOut(graph.text("why-1"), at = 4.5, seconds = .8)
    fadeIn(commute.group, at = 4.99, seconds = .01)
    fadeIn(graph.text("why-2"), at = 5.0, seconds = 1.0)
    morph(commute, at = 6.0, seconds = 2.0)
    fadeOut(commute.group, at = 10.5, seconds = 1.0)
    fadeOut(graph.text("why-2"), at = 10.5, seconds = 1.0)
}
println(
    "distribute: " +
        distribute.matched +
        " matched, " +
        distribute.pieces.size +
        " pieces; commute: " +
        commute.matched +
        " matched",
)
prop<ReadingDiagramPlayer>(sumPlayer) =
    ReadingDiagramPlayer.prepare(graph, Typefaces.named("Serif", 100), "field-slug".file, score)
sumPlayer.gravity = 0f
sumPlayer.wind = 0f
