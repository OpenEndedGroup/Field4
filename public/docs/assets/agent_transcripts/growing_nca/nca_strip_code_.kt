import afield.media.image.util.PngEncoder

/*
Grow a flower from one cell, cut a hole in it, and let it grow back: seven snapshots side by side.
Runs on the CPU (no renderer needed); the rule is the one the GPU box below runs.
*/
val nca = GrowingNca.pretrained("flower")
val target = GrowingNca.pretrainedTarget("flower")
val scale = 4
val w = nca.size * scale
val shots = mutableListOf<IntArray>()

fun snap(label: String) {
    shots += nca.argb(scale)
    println("$label: step ${nca.steps}, error ${nca.loss(target).f4}")
}

for (s in [30, 80, 300]) {
    nca.step(s - nca.steps)
    snap("growing")
}
// erase a disc 24 cells across through the top of the flower
nca.damage(32.0, 26.0, 12.0)
snap("damaged")
for (s in [340, 400, 600]) {
    nca.step(s - nca.steps)
    snap("regrowing")
}

// lay the snapshots out in a row, with a 4-pixel grey gap between them
val gap = 4
val stripW = shots.size * (w + gap) - gap
val strip = IntArray(stripW * w) { 0xffdddddd.toInt() }
for ((k, shot) in shots.withIndex()) {
    for (y in 0 until w) {
        val at = y * stripW + k * (w + gap)
        System.arraycopy(shot, y * w, strip, at, w)
    }
}
display("main", PngEncoder.encode(strip, stripW, w))
