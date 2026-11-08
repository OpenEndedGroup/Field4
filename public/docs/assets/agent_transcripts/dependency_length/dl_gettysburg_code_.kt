import afield.graphics.text.sentences.*

val text = SentenceTexts.load("gettysburg")
val ratioFont = font("Serif", 18.0)
val openingFont = font("Serif", 13.0)
var y = 0.0
val figure = text.measure().flatMap { m ->
    // The arcs start at x = 270, leaving a column for the ratio and the opening words.
    val row = m.tree.arcLines(at = P(270.0, y), spacing = 8.0, depth = 0.3) +
        outlinedText(m.ratio.f2, P(0.0, y + 6), ratioFont) +
        outlinedText(m.tree.words.take(4).joinToString(" ") + " …", P(50.0, y + 5), openingFont)
    y += m.tree.arcDepth(spacing = 8.0, depth = 0.3) + 30.0
    row
}
display("figure", figureSvg(figure))
