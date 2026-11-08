import afield.media.image.util.PngEncoder

/*
The rule trained on the drawn fish, on the CPU: grow it from one cell, cut a disc out of its back,
and let it grow back, as the flower does above.
*/
val nca = GrowingNca.pretrained("fish")
val target = GrowingNca.pretrainedTarget("fish")
val scale = 4
val w = nca.size * scale
val shots = mutableListOf<IntArray>()

fun snap(label: String) {
    shots += nca.argb(scale)
    println("$label: step ${nca.steps}, error ${nca.loss(target).f4}")
}

for (s in [30, 100, 300]) {
    nca.step(s - nca.steps)
    snap("growing")
}
// erase a disc 20 cells across through the fish's back
nca.damage(34.0, 26.0, 10.0)
snap("damaged")
for (s in [340, 400, 600]) {
    nca.step(s - nca.steps)
    snap("regrowing")
}

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
