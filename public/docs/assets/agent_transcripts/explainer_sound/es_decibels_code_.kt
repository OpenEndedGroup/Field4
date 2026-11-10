// Sound pressure level: 20 × log10 of the pressure swing over 20 micropascals, the quietest a
// young ear hears at 1 kHz. Drag "pressure" (a logarithmic slider, in pascals) and read the level.
fun level(pa: Double) = 20 * log10(pa / 20e-6)
lateinit var bar: MeterHandle

fun show(pa: Double) {
    val db = level(pa)
    markdown("readout", "**${"%.3g".format(pa)} Pa → ${db.f1} dB**\n\n0 dB: hearing just begins · 60 dB: talking, a metre away · 120 dB: it starts to hurt")
    if (::bar.isInitialized) bar.set(db)
}

show(0.02)
controlGroup {
    slider("pressure", 0.00002, 200.0, step = 0.00001, initialValue = 0.02, law = ControlLaw.LOGARITHMIC) { show(it) }
    bar = meter("decibels", 0.0, 140.0, initialValue = level(0.02))
}
