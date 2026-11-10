// A string held at both ends. "pluck" pulls it aside at "pluck at" (the orange dot) and lets go.
// What follows is a sum of the string's modes, each a whole number of half waves, ringing at whole multiples of the lowest frequency, the high ones dying
// away faster. "mode" shows one mode alone (0 shows the pluck); "length" stops the string short,
// as a finger on a fret does.
val n = 30
var at = 0.25               // where the last pluck was, as a fraction of the string
var depth = 0.8             // how far it was pulled, as a fraction of the picture's half-height
var length = 1.0
var mode = 0
var plucked = -10.0         // when the pluck happened, in seconds since launch
var time = 0.0

fun weight(k: Int) = depth * 2 / (k * k * PI * PI * at * (1 - at)) * sin(k * PI * at)
fun decay(k: Int) = 2.5 / k.toDouble().pow(0.7)
fun base() = 110 / length

// The sound: one sine per mode, its loudness set by the pluck and its own decay.
var audioPluck = -1f        // audio time of the latest pluck; NaN asks the audio thread to stamp it
val partials = (1..n).map { k ->
    SineOsc(Param { (k * base()).toFloat() }) * Param {
        if (audioPluck.isNaN()) audioPluck = time
        if (audioPluck < 0) 0f else (weight(k) * exp(-(time - audioPluck) / decay(k))).toFloat()
    }
}

// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline { rect(0.0, 0.0, 640.0, 300.0); thickness = 1.0; color = vec(1.0, 1.0, 1.0, 0.0) }
val left = 12.0
val middle = 150.0
fun stringEnd() = left + 616 * length

fun pluck(x: Double, y: Double) {
    at = ((x - left) / (stringEnd() - left)).coerceIn(0.05, 0.95)
    depth = ((middle - y) / 130).coerceIn(-1.0, 1.0)
    if (abs(depth) < 0.05) return
    plucked = time
    mode = 0
    audioPluck = Float.NaN
}

fun scenery(): List<FLine> {
    val rest = fline { moveTo(stringEnd(), middle); lineTo(628.0, middle); thickness = 1.5; color = vec(1.0, 1.0, 1.0, 0.3) }
    val pegs = fline { circle(left, middle, 7.0); circle(stringEnd(), middle, 7.0); filled = true; stroked = false; color = vec(1.0, 1.0, 1.0, 0.8) }
    return [frame, rest, pegs]
}

fun string(y: (Double) -> Double) = fline {
    moveTo(left, middle)
    for (i in 1..300) lineTo(left + (stringEnd() - left) * i / 300, middle - 130 * y(i / 300.0))
    thickness = 3.0
    color = vec(0.4, 0.9, 1.0, 1.0)
}

// Shown slowed down: the lowest mode takes two seconds a swing.
fun animate() {
    val since = time - plucked
    val y = { x: Double ->
        if (mode > 0) sin(mode * PI * x) * cos(PI * mode * time)
        else (1..n).sumOf { k -> weight(k) * sin(k * PI * x) * cos(PI * k * since) * exp(-since / decay(k)) }
    }
    val mark = fline { circle(left + (stringEnd() - left) * at, middle + 24, 6.0); filled = true; stroked = false; color = vec(1.0, 0.6, 0.2, 1.0) }
    lines(scenery() + string(y) + mark, channel = "string", w = 640.0, h = 300.0)
}

animate()
controlGroup {
    keys("pluck", letters = "p") { _, down -> if (down) pluck(left + (stringEnd() - left) * at, middle - 130 * 0.8) }
    slider("pluck at", 0.05, 0.5, step = 0.01, initialValue = 0.25) { at = it }
    slider("mode", 0.0, 6.0, step = 1.0, initialValue = 0.0) { mode = it.toInt() }
    slider("length", 0.5, 1.0, step = 0.01, initialValue = 1.0) { length = it }
}

val start = AnimationClock.nowSeconds()
loop {
    time = AnimationClock.nowSeconds() - start
    animate()
} + audio(partials.reduce { a, b -> a + b }, gain = 0.15f)
