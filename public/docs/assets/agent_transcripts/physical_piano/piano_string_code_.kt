// One of C3's strings, struck at velocity 0.8, drawn at five moments after the hammer
// leaves it. The kink travelling along it is the hammer's blow.
val times = [0.0, 0.0005, 0.001, 0.002, 0.004]
val plots =
    times.joinToString("") { t ->
        pianoPlotSvg(
            pianoStringShape(48, 0.8, t, points = 300),
            "C3 string, %.1f ms after contact".format(t * 1000),
            height = 110,
        )
    }
display("shape", plots)
displayOpen("shape")
