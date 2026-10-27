import afield.ai.learning.pen.*

// The "rounder" pen is the handwriting pen fine-tuned on the curliest eighth of 2400 of its own
// scribbles, chosen by curliness() instead of by hand. Same seeds, both pens: white is the
// original, gold the one that learned the taste. The numbers are mean curliness over 40 each.
val rounder = PenModel.bundled("rounder")
fun scribble(model: StrokeModel, seed: Long) = PenLine(model = model, seed = seed).also { it.grow(160) }.drawn
val before = (0 until 6).map { scribble(PenModel.distill, it.toLong()).apply { color = vec(0.85, 0.9, 1.0, 1.0) } }
val after = (0 until 6).map { scribble(rounder, it.toLong()).apply { color = vec(1.0, 0.8, 0.35, 1.0) } }
lines(stacked(listOf(sideBySide(before.map { listOf(it) }, gap = 25.0), sideBySide(after.map { listOf(it) }, gap = 25.0)), gap = 30.0),
    w = 520.0, h = 260.0, fitToSize = true)
val c0 = (0 until 40).map { curliness(scribble(PenModel.distill, 100L + it)) }.average()
val c1 = (0 until 40).map { curliness(scribble(rounder, 100L + it)) }.average()
print("curliness: handwriting ${"%.3f".format(c0)}, rounder ${"%.3f".format(c1)}")
