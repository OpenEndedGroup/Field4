// Two strokes, drawn from code into the document's drawing: a circle named "from" and a
// heart named "to". Running it again replaces them.
notation { morphShapes() }
// To morph two strokes you drew yourself (Alt+D, P), delete the line above and use:
//   val mine = notation.strokes.filter { it.handDrawn }.takeLast(2)
//   notation.edit(mine[0]) { name = "from" }
//   notation.edit(mine[1]) { name = "to" }
println(notation.strokes.map { "${it.label}: ${it.points.size} points" })
