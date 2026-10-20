// A box that cuts itself to somewhere else in the score: on the way in, and five times on the
// way across, it jumps near the playhead and leaves a fading ghost of where it was. The box
// really moves on the canvas; its frame is part of the instrument.
class Ghost(val x: Double, val y: Double, val w: Double, val h: Double, var life: Double = 1.0)

val ghosts = mutableListOf<Ghost>()
var jumps = 0
var flash = 0.0

score {
    fun jump() {
        val was = box.get()
        ghosts += Ghost(was.x + was.w / 2, was.y + was.h / 2, was.w, was.h)
        if (ghosts.size > 8) ghosts.removeAt(0)

        val w = 120 + rand(0.0, scoreWidth * 0.08)
        val h = 90 + rand(0.0, scoreHeight * 0.12)
        val anchor = playheadX + rand(-scoreWidth * 0.12, scoreWidth * 0.12)
        box.set(
            x = clamp(anchor - w * (0.2 + rand(0.0, 0.5)), scoreLeft, scoreRight - w),
            y = clamp(rand(scoreTop + 10, scoreBottom - h - 10), scoreTop, scoreBottom - h),
            w = w,
            h = h,
        )
        flash = 1.0
        jumps++
        sound("/dough/play/note/${55 + (jumps % 5) * 2}/duration/0.06/s/pulse/pw/0.22/gain/0.13/lpf/${700 + (jumps % 7) * 220}/release/0.04/pan/0.18", key = "jump", minInterval = 0.03)
    }

    if (justEntered) jump()
    if (onSubdivision(5, "jump-grid")) jump()

    ghosts.forEach { it.life *= 0.84 }
    ghosts.removeAll { it.life < 0.06 }
    for (g in ghosts) {
        val p = fromScore(g.x, g.y)
        draw.rect(p.x - g.w * scaleX / 2, p.y - g.h * scaleY / 2, g.w * scaleX, g.h * scaleY, stroke = "rgba(255,255,255,${g.life * 0.28})")
    }

    flash *= 0.82
    val live = box.get()
    val c = fromScore(live.x + live.w / 2, live.y + live.h / 2)
    val ww = live.w * scaleX
    val hh = live.h * scaleY
    draw.rect(c.x - ww / 2, c.y - hh / 2, ww, hh, stroke = "rgba(255, 171, 125, 0.92)", width = 2 + thisRMS * 2.5)
    draw.line(c.x - ww / 2, c.y - hh / 2, c.x + ww / 2, c.y + hh / 2, stroke = "rgba(255, 171, 125, 0.48)", width = 1.2)
    draw.line(c.x + ww / 2, c.y - hh / 2, c.x - ww / 2, c.y + hh / 2, stroke = "rgba(255, 171, 125, 0.48)", width = 1.2)
    draw.circle(c, 8 + flash * 28 + thisRMS * 18, fill = "rgba(255,255,255,${0.05 + flash * 0.14})")
}
