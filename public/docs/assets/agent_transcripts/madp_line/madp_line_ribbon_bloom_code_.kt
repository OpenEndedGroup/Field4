// Ribbon Bloom: draw long swoops and loops. The stroke becomes a filled, breathing ribbon with
// a bright spine. Run this box to take the pen; it makes no sound.
eachStroke(trail = 0.08) {
    draw { t ->
        val base = resample(points, 72)
        val upper = ArrayList<Vec2>()
        val lower = ArrayList<Vec2>()
        for (i in base.indices) {
            val p = base[i]
            val n = normalAt(base, i)
            val billow = tri01(t * 0.45 + i * 0.07 + driftNoise(i * 0.21, t, 0.2) * 0.6)
            val spread = (8 + envelope(i, base.size) * 30) * (0.45 + billow * 0.78) + heldNoise(i * 0.31, t, 6.0) * 5
            upper += vec(p.x + n.x * spread, p.y + n.y * spread)
            lower += vec(p.x - n.x * spread * 0.8, p.y - n.y * spread * 0.8)
        }
        val hue = t * 50
        // Up one side and back down the other. (`+` on two point lists adds them point by point.)
        val around = ArrayList(upper)
        around.addAll(lower.asReversed())
        val outline = smooth(around)
        val fill = outline.copy()
        fill.filled = true
        fill.stroked = false
        fill.color = cssHsla(hue + 35, 0.92, 0.54, 0.18)
        outline.color = cssHsla(hue + 80 + base.size * 2, 0.95, 0.6, 0.72)

        val spine = smooth(base)
        spine.color = rgba(255, 255, 255, 0.35)
        spine.overshoot = 10.0
        [fill, outline, spine]
    }
}
