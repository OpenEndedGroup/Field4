// Matrix rain. The drops are ordinary Kotlin values declared above charGrid { }, so they last as long
// as the run (the demo keeps them in `state`). Each tick clears the grid and redraws every drop.
class Drop(var y: Int, var len: Int)

val drops = mutableListOf<Drop>()
// The sixteen digits and letters ring, high and bright; the nine symbols are silent.
val sounds = [
    "/dough/play/note/72/duration/0.06/s/sine/fm/1/fmd/0.03/lpf/3000/release/0.03/verb/0.4/verbdecay/0.6/pan/0.3",
    "/dough/play/note/74/duration/0.06/s/sine/fm/1/fmd/0.03/lpf/3200/release/0.03/verb/0.4/pan/0.35",
    "/dough/play/note/76/duration/0.06/s/sine/fm/1.5/fmd/0.03/lpf/3400/release/0.03/verb/0.4/pan/0.4",
    "/dough/play/note/77/duration/0.06/s/tri/lpf/2800/release/0.03/verb/0.5/pan/0.45",
    "/dough/play/note/79/duration/0.06/s/tri/lpf/3000/release/0.03/verb/0.5/pan/0.5",
    "/dough/play/note/81/duration/0.06/s/tri/lpf/3200/release/0.03/verb/0.5/pan/0.55",
    "/dough/play/note/83/duration/0.05/s/sine/lpf/3500/release/0.02/verb/0.3/pan/0.6",
    "/dough/play/note/84/duration/0.05/s/sine/lpf/3700/release/0.02/verb/0.3/pan/0.65",
    "/dough/play/note/86/duration/0.05/s/sine/lpf/4000/release/0.02/verb/0.3/pan/0.7",
    "/dough/play/note/88/duration/0.04/s/sine/lpf/4500/release/0.02/delay/0.2/delaytime/0.08",
    "/dough/play/note/89/duration/0.04/s/sine/lpf/4500/release/0.02/delay/0.2/delaytime/0.06",
    "/dough/play/note/91/duration/0.04/s/sine/lpf/5000/release/0.02/delay/0.2/delaytime/0.05",
    "/dough/play/note/93/duration/0.04/s/sine/lpf/5000/release/0.02/delay/0.15",
    "/dough/play/note/95/duration/0.04/s/sine/lpf/5500/release/0.02/delay/0.15",
    "/dough/play/note/96/duration/0.04/s/sine/lpf/5500/release/0.02/delay/0.15",
    "/dough/play/note/98/duration/0.04/s/sine/lpf/6000/release/0.02/delay/0.1",
]

charGrid(glyphs = "0123456789ABCDEF:.*+=@#$%", sounds = sounds) {
    if (drops.isEmpty()) repeat(COLS) { drops += Drop(-rand(25), 5 + rand(14)) }
    clearGrid()
    for (x in 0 until COLS) {
        val d = drops[x]
        for (i in 0 until d.len) {
            val y = d.y - i
            if (y in 0 until ROWS)
                SET(x, y, rand(glyphs.length), if (i == 0) "#fff" else "hsl(120,80%,${max(15, 65 - i * 4)}%)")
        }
        d.y++
        if (d.y - d.len > ROWS) {
            d.y = -rand(15)
            d.len = 5 + rand(14)
        }
    }
}
