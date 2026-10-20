// Reactions: these run when a person draws (or `hand` stands in for one). Stop the box to remove them.
notation.onStroke { println("stroke: ${it.points.size} points from ${it.start} to ${it.end}") }
notation.onArrow { println("arrow: from '${it.fromBox?.name}' to '${it.toBox?.name}'") }
notation.onText { println("text: '${it.text}'") }
notation.onChange { println("change: ${it.added.size} added, ${it.updated.size} updated, ${it.removed.size} removed") }
println("listening")
