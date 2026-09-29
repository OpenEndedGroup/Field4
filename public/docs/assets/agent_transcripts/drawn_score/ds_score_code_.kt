// The score, drawn from code into the document's drawing: a frame named "score", a piano
// roll, a glissando and a held note. Running it again replaces these marks; anything you
// draw by hand in the frame is left alone and is read with them.
notation { drawnScore() }
println("${notation.mine.size} marks drawn")
