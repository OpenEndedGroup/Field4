import afield.media.image.util.PngEncoder

// A plate 60 cm across set in a wall on the left, pushing air back and forth: red is pressure above
// the usual, blue below. When its waves are longer than the plate the sound spreads every way; when
// they are shorter, the edges of the plate interfere and the sound is aimed ahead. The strip below
// is the pressure along the plate's axis. The picture is four metres across, the bar is one metre;
// time runs slowed, half a cycle a second, whatever the frequency.
val imageW = 640
val imageH = 300
val fieldH = 228               // the pressure field; below it, a strip plotting the axis
val metre = 160.0
val cell = 2                   // the field is solved on a 2 × 2 pixel grid and drawn up
val gw = imageW / cell
val gh = fieldH / cell
val px = IntArray(imageW * imageH)
var hz = 400.0
var phase = 0.0
// Each grid point's swing as a complex amplitude (re, im), worked out once per frequency.
val re = FloatArray(gw * gh)
val im = FloatArray(gw * gh)

fun solve() {
    val k = 2 * PI * hz / 343
    val sources = 16
    for (j in 0 until gh) for (i in 0 until gw) {
        var a = 0.0
        var b = 0.0
        for (s in 0 until sources) {
            val sy = fieldH / 2.0 + metre * 0.6 * ((s + 0.5) / sources - 0.5)
            val r = sqrt((cell * i + 2.0).pow(2) + (cell * j - sy).pow(2)) / metre
            a += cos(k * r) / sqrt(r + 0.05)
            b += sin(k * r) / sqrt(r + 0.05)
        }
        re[j * gw + i] = (a / sources).toFloat()
        im[j * gw + i] = (b / sources).toFloat()
    }
}

fun paint() {
    val c = cos(2 * PI * phase)
    val s = sin(2 * PI * phase)
    px.fill(0xff000000.toInt())
    for (j in 0 until fieldH) for (i in 0 until imageW) {
        val n = (j / cell) * gw + i / cell
        val p = tanh(0.9 * (re[n] * c + im[n] * s))
        val red = (255 * p.coerceAtLeast(0.0)).toInt()
        val blue = (255 * (-p).coerceAtLeast(0.0)).toInt()
        px[j * imageW + i] = (0xff shl 24) or (red shl 16) or (((red + blue) / 4) shl 8) or blue
    }
    for (j in fieldH / 2 - 48..fieldH / 2 + 48) for (i in 0..3) px[j * imageW + i] = -1
    for (j in fieldH - 8..fieldH - 6) for (i in 470 until 630) px[j * imageW + i] = -1
    // The strip: the pressure along the plate's axis, now, in cyan about a faint middle line.
    val mid = fieldH + 36
    for (i in 0 until imageW) px[mid * imageW + i] = 0xff444444.toInt()
    var last = mid
    for (i in 0 until imageW) {
        val n = (fieldH / 2 / cell) * gw + i / cell
        val y = (mid - 30 * tanh(0.9 * (re[n] * c + im[n] * s))).toInt()
        for (j in minOf(last, y) - 1..maxOf(last, y) + 1) px[j * imageW + i] = 0xff66e6ff.toInt()
        last = y
    }
    display("field", PngEncoder.encode(px, imageW, imageH))
}

solve()
paint()
controlGroup {
    slider("frequency", 150.0, 3000.0, step = 1.0, initialValue = 400.0, law = ControlLaw.LOGARITHMIC) { hz = it; solve() }
}

var clock = AnimationClock.nowSeconds()
loop {
    val now = AnimationClock.nowSeconds()
    phase += 0.5 * (now - clock)
    clock = now
    paint()
    // A picture this size is a large message; about twelve a second is what the page keeps up with.
    delay(80)
}
