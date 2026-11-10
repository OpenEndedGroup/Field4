import afield.media.image.util.PngEncoder

// A sphere swelling and shrinking, seen in cross-section: red is pressure above the usual, blue below.
// The picture is four metres across, and the bar at the bottom is one metre. Time runs slowed down,
// half a cycle a second whatever the frequency, so only the spacing of the rings changes with it.
val imageW = 640
val imageH = 300
val px = IntArray(imageW * imageH)
var hz = 343.0
var phase = 0.0

fun paint() {
    val wavelength = 160 * 343 / hz          // pixels: 160 to the metre
    val cx = 160.0
    val cy = 150.0
    val r0 = 18 + 4 * sin(2 * PI * phase)
    for (j in 0 until imageH) for (i in 0 until imageW) {
        val r = sqrt((i - cx) * (i - cx) + (j - cy) * (j - cy))
        px[j * imageW + i] = if (r < r0) 0xff9aa4ad.toInt() else {
            // The swing falls with distance; in a flat picture of a sphere, as one over the distance.
            val p = (60 / r).coerceAtMost(1.0) * sin(2 * PI * ((r - r0) / wavelength - phase))
            val red = (255 * p.coerceAtLeast(0.0)).toInt()
            val blue = (255 * (-p).coerceAtLeast(0.0)).toInt()
            (0xff shl 24) or (red shl 16) or (((red + blue) / 4) shl 8) or blue
        }
    }
    for (j in 284..287) for (i in 20..180) px[j * imageW + i] = -1
    display("field", PngEncoder.encode(px, imageW, imageH))
}

paint()
controlGroup {
    slider("frequency", 86.0, 1372.0, step = 1.0, initialValue = 343.0, law = ControlLaw.LOGARITHMIC) { hz = it }
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
