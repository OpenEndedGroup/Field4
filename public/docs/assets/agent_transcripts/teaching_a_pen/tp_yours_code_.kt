import afield.ai.learning.pen.*

// Any function that draws an FLine in order is a corpus. This one draws spirals that wobble;
// PenCorpus.write resamples each one the way a pen reads a line and saves it for fieldpen.
fun spiral(seed: Long): FLine {
    val r = kotlin.random.Random(seed)
    val turns = 3 + r.nextDouble() * 3
    val wobble = r.nextDouble() * 4
    return fline {
        breakNext()
        for (i in 0..(turns * 80).toInt()) {
            val t = i / 80.0 * 2 * PI
            val radius = 4 + t * 3 + sin(t * 7) * wobble
            lineTo(cos(t) * radius, sin(t) * radius)
        }
    }
}
val drawings = (0 until 300).map { spiral(it.toLong()) }
val points = PenCorpus.write("spirals".file.path, drawings)
print("$points points in ${drawings.size} drawings -> ${"spirals".file.path}")
lines(sideBySide(drawings.take(6).map { [it] }, gap = 20.0), w = 520.0, h = 140.0, fitToSize = true)
