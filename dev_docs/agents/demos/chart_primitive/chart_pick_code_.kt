// Bar and point marks set `onClick`, `hoverColor` and `label`, which is exactly what
// `Box.lines` looks for when it decides between the interactive SVG renderer and Skia's
// (someSkiko.kt:37). So hovering and clicking work on a chart nobody configured —
// by construction rather than by wiring.
//
// Hover a bar: it lights up. Click one: this handler runs, here, in this box.
val c = Chart(title = "hover a bar, then click one", yLabel = "gates")
    .bars("cost", listOf("xor", "maj3", "adder", "mux", "parity"), listOf(6, 11, 27, 4, 19))

c.onPick = { pick ->
    println("clicked ${pick.category} = ${pick.y} (index ${pick.index})")
    println("  the hit itself: ${pick.hit.kind} at ${pick.hit.position}")
}

showChart(c)

// Set `pickable = false` for a figure whose points are scenery rather than data: it is
// one registered callback and one <g> per datum, which a ten-thousand-point scatter
// does not want.
println("marks: " + c.marks)
