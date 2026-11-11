// The light a patch gives off, against what a screen can show of it. The swatch is the patch;
// the curve is the brightness the screen sends, which runs out at its brightest white.
fun draw(watts: Double) {
    // A screen channel holds 0..1 of linear light, here reached at 50 W, and encodes it as sRGB.
    fun shown(w: Double) = min(1.0, w / 50).pow(1 / 2.2)
    val curve = fline {
        moveTo(40.0, 260.0)
        for (w in 1..100) lineTo(40.0 + w * 3.6, 260.0 - 220 * shown(w.toDouble()))
        thickness = 2.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    val axes = fline {
        moveTo(40.0, 30.0)
        lineTo(40.0, 260.0)
        lineTo(400.0, 260.0)
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    val here = fline {
        moveTo(40.0 + watts * 3.6, 260.0)
        lineTo(40.0 + watts * 3.6, 260.0 - 220 * shown(watts))
        thickness = 2.0
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    val v = shown(watts)
    val swatch = fline {
        moveTo(450.0, 60.0)
        lineTo(610.0, 60.0)
        lineTo(610.0, 220.0)
        lineTo(450.0, 220.0)
        lineTo(450.0, 60.0)
        filled = true
        color = vec(v, v, v, 1.0)
    }
    lines(axes, curve, here, swatch, channel = "curve", w = 640.0, h = 280.0)
    markdown("readout", "${watts.roundToInt()} W → ${(v * 100).roundToInt()} % of white")
}

draw(10.0)
controlGroup {
    slider("power", 0.0, 100.0, step = 1.0, initialValue = 10.0) { draw(it) }
}
