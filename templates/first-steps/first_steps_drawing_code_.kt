// Launch: Alt+Up. Stop: Alt+Down. On a Mac, Alt is Option.
var petals = 5 // Change 5 to 8, then launch again.
println(petals) // Field shows this value beside the code.
var phase = 0.0

loop {
    phase += 0.025
    val curve = fline {
        for (i in 0..360) {
            val angle = i * Math.PI / 180.0
            val radius = 100.0 + 35.0 * Math.sin(petals * angle + phase)
            val x = 160.0 + radius * Math.cos(angle)
            val y = 160.0 + radius * Math.sin(angle)
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        thickness = 2.5
        color = vec(0.3, 0.85, 1.0, 1.0)
    }
    lines(curve, w = 320.0, h = 320.0)
    true
}

// Field autosaves edits. Stop, close this tab, then reopen your copy
// from Welcome: your edited code is there; launch it to animate again.
