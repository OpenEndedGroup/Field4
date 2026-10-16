// Six pins around a loop. Drag any pin's control below and the loop redraws: the orange mean
// and six blue draws, all closed, all passing near every pin. Edit `start` to keep a shape.
val start = [
    vec(1.0, 0.0),
    vec(0.45, 0.8),
    vec(-0.5, 0.95),
    vec(-1.1, 0.0),
    vec(-0.4, -0.75),
    vec(0.6, -0.9),
]
val pins = start.map { vec(it.x, it.y, 0) }.toMutableList()
var lengthScale = 0.6

fun plot(line: FLine) = line.transform { vec(it.x * 200, -it.y * 200, 0) }

fun redraw() {
    // each pin's place on a parameter that runs once round the loop, 0 up to 1
    val at = DoubleArray(pins.size) { it.toDouble() / pins.size }
    // period 1: t = 0 and t = 1 are the same point, so every draw closes without a seam
    val loop = gpCurve(
        at,
        pins,
        kernel = GpKernels.periodic(period = 1.0, lengthScale = lengthScale, variance = 0.04),
        noiseStd = DoubleArray(pins.size) { 0.01 },
        dimensions = 2,
    )
    val distribution = loop.posterior(DoubleArray(201) { it / 200.0 })
    val draws =
        (0L..5L).map { seed ->
            plot(distribution.sampleLine(seed)).apply {
                color = vec(0.3, 0.65, 0.9, 0.8)
                thickness = 1.5
            }
        }
    val mean = plot(distribution.meanLine()).apply {
        color = vec(1, 0.5, 0.2, 1)
        thickness = 3.0
    }
    val dots = plot(FLine().apply { pins.forEach { moveTo(it.x, it.y) } }).apply {
        stroked = false
        pointed = true
        pointSize = 3.0
        color = vec(1, 1, 1, 1)
    }
    // a fixed frame, so the picture does not rescale as pins move
    val frame = plot(FLine().rect(-1.6, -1.4, 3.2, 2.8)).apply {
        color = vec(0.3, 0.3, 0.3, 1)
        thickness = 1.0
    }
    lines([frame] + draws + mean + dots, w = 520.0, h = 460.0, fitToSize = true)
}

// one panel of controls; running the box puts them back at `start`
controlGroup("pins", columns = 3) {
    pins.indices.forEach { i ->
        vec2Control("pin ${i + 1}", Vector2f(pins[i].x, pins[i].y), xRange = -1.5 to 1.5, yRange = -1.3 to 1.3) { v ->
                pins[i] = vec(v.x, v.y, 0)
                redraw()
            }
            .set(Vector2f(pins[i].x, pins[i].y))
    }
    slider("length scale", min = 0.1, max = 1.5, initialValue = lengthScale) {
            lengthScale = it
            redraw()
        }
        .set(lengthScale)
}
redraw()
