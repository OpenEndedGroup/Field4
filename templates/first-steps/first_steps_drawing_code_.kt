// Launch: Alt+Up. Stop: Alt+Down. On a Mac, Alt is Option.
var petals = 5
petals = 5 // Change this 5 to 8, then press Alt+Enter on this line while the drawing runs.
println(petals) // Field shows this value beside the code.
var phase = 0.0

loop {
    phase += 0.045
    // Each curve is a little further into the wave: together they make a ribbon.
    val ribbon =
        (0..23).map { layer ->
            val depth = layer / 23.0
            fline {
                breakNext()
                for (i in 0..180) {
                    val angle = i * Math.PI / 90.0
                    val wave = Math.sin(petals * angle + phase - 4.0 * depth)
                    val radius = 35.0 + 90.0 * depth + 18.0 * wave
                    val twist = angle + 0.4 * Math.sin(phase + 2.0 * depth)
                    val x = 160.0 + radius * Math.cos(twist)
                    val y = 160.0 + radius * Math.sin(twist)
                    lineTo(x, y)
                }
                thickness = 0.8
                color = vec(0.55 + 0.4 * depth, 0.85 + 0.15 * depth, 1.0, 0.8)
            }
        }
    lines(ribbon, w = 320.0, h = 320.0)
}

// Field autosaves edits. Stop, close this tab, then reopen your copy
// from Welcome: your edited code is there; launch it to animate again.
