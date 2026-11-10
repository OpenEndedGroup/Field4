import afield.media.image.util.PngEncoder

// A flat box of molecules. They fly straight, bounce off one another and off the walls.
// "length" moves the right wall; the plot counts hits on it per unit of wall per second.
val imageW = 640
val imageH = 300
val n = 240
val r = 3.0
val x = DoubleArray(n) { 30 + Math.random() * 580 }
val y = DoubleArray(n) { 20 + Math.random() * 260 }
val vx = DoubleArray(n)
val vy = DoubleArray(n)
n { i -> val a = Math.random() * 2 * PI; vx[i] = 2 * cos(a); vy[i] = 2 * sin(a) }

var speed = 1.0
var length = 1.0
var follow = false
val trail = mutableListOf<Pair<Double, Double>>()
val flashes = mutableListOf<DoubleArray>()   // x, y, age
val hitsPerFrame = mutableListOf<Double>()
val rate = mutableListOf<Double>()

val px = IntArray(imageW * imageH)
fun dot(cx: Double, cy: Double, radius: Double, argb: Int) {
    for (j in (cy - radius).toInt()..(cy + radius).toInt()) for (i in (cx - radius).toInt()..(cx + radius).toInt()) {
        if (i in 0 until imageW && j in 0 until imageH && (i - cx) * (i - cx) + (j - cy) * (j - cy) <= radius * radius) px[j * imageW + i] = argb
    }
}

// The picture, then the plot, then the controls, in that order down the output.
display("box", PngEncoder.encode(px, imageW, imageH))
fun plot() = showSignals("hits on the right wall" to rate.takeLast(120), channel = "rate", width = 640, height = 200, yTitle = "pressure")
plot()
controlGroup {
    slider("speed", 0.0, 2.0, step = 0.01, initialValue = 1.0) { speed = it }
    slider("length", 0.3, 1.0, step = 0.01, initialValue = 1.0) { length = it }
    checkbox("follow one") { follow = it; trail.clear() }
}

var frameCount = 0
// The simulation steps sixty times a second of animation time, however often frames arrive.
var clock = AnimationClock.nowSeconds()
val left = 20.0
val top = 10.0
val bottom = 290.0
fun right() = left + 600 * length

fun step() {
    val right = right()
    var hits = 0
    val steps = 2
    repeat(steps) {
        n { i ->
            x[i] += vx[i] * speed / steps
            y[i] += vy[i] * speed / steps
            if (x[i] < left + r) { x[i] = left + r; vx[i] = abs(vx[i]); flashes += doubleArrayOf(left, y[i], 0.0) }
            if (x[i] > right - r) {
                // A molecule the moving wall swept up is pushed back in, but that is not a hit.
                val swept = x[i] - vx[i] * speed / steps > right - r
                x[i] = right - r
                vx[i] = -abs(vx[i])
                if (!swept) { hits++; flashes += doubleArrayOf(right, y[i], 0.0) }
            }
            if (y[i] < top + r) { y[i] = top + r; vy[i] = abs(vy[i]); flashes += doubleArrayOf(x[i], top, 0.0) }
            if (y[i] > bottom - r) { y[i] = bottom - r; vy[i] = -abs(vy[i]); flashes += doubleArrayOf(x[i], bottom, 0.0) }
        }
        // Equal masses colliding head on swap the part of their velocities along the line between them.
        for (i in 0 until n) for (j in i + 1 until n) {
            val dx = x[j] - x[i]
            val dy = y[j] - y[i]
            val d2 = dx * dx + dy * dy
            if (d2 < 4 * r * r && d2 > 1e-9) {
                val d = sqrt(d2)
                val nx = dx / d
                val ny = dy / d
                val approach = (vx[i] - vx[j]) * nx + (vy[i] - vy[j]) * ny
                if (approach > 0) {
                    vx[i] -= approach * nx; vy[i] -= approach * ny
                    vx[j] += approach * nx; vy[j] += approach * ny
                }
            }
        }
    }
    // A moving wall heats or cools the gas; hold the average speed steady so only the squeeze shows.
    val mean = (0 until n).sumOf { sqrt(vx[it] * vx[it] + vy[it] * vy[it]) } / n
    n { i -> vx[i] *= 2 / mean; vy[i] *= 2 / mean }

    if (speed > 0) {
        hitsPerFrame += hits / speed
        if (hitsPerFrame.size > 240) hitsPerFrame.removeAt(0)
    }
    if (follow) { trail += x[0] to y[0]; if (trail.size > 1500) trail.removeAt(0) }

}

loop {
    val now = AnimationClock.nowSeconds()
    var caught = 0
    while (clock < now && caught < 6) { clock += 1 / 60.0; step(); caught++ }
    if (clock < now) clock = now
    val right = right()
    px.fill(0xff050505.toInt())
    for (j in top.toInt()..bottom.toInt()) for (i in left.toInt()..right.toInt()) px[j * imageW + i] = 0xff101418.toInt()
    trail.forEach { (tx, ty) -> dot(tx, ty, 0.8, 0xffff9933.toInt()) }
    n { i -> dot(x[i], y[i], r, if (follow && i == 0) 0xffff9933.toInt() else 0xff66e6ff.toInt()) }
    flashes.forEach { f ->
        val glow = (255 * (1 - f[2] / 8)).toInt().coerceIn(0, 255)
        dot(f[0], f[1], 4.0, (0xff shl 24) or (glow shl 16) or (glow shl 8) or glow)
        f[2] += 1.0
    }
    flashes.removeAll { it[2] > 8 }
    display("box", PngEncoder.encode(px, imageW, imageH))
    // A picture this size is a large message; about twenty a second is what the page keeps up with.
    delay(50)

    frameCount++
    if (frameCount % 5 == 0 && hitsPerFrame.isNotEmpty()) {
        // Hits per frame of time on the right wall, divided by the wall's height: pressure, in our units.
        rate += 1000 * hitsPerFrame.average() / (bottom - top)
        plot()
    }
}
