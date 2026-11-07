import afield.audio.modal.*

// Nine drums from a circle to a five-lobed flower. The voice glides through them and back
// every 4 s, striking itself as it goes, so every mode's pitch bends with the outline.
val shapes = (0..8).map { i -> polarDrumMesh(0.07) { t -> 1 + 0.05 * i * cos(5 * t) }.modes(12) }
val outlines = shapes.flatMapIndexed { i, m ->
    m.modeMesh(0).map { it.copy().apply { transform { v -> v.add(i * 2.8f, 0f, 0f) } } }
}
lines(outlines, w = 620.0, h = 100.0, fitToSize = true)
// One row per shape; each mode's column is heated on its own, so you can watch every
// overtone fall as the lobes deepen. Mode 1 is the reference and stays plain.
val modeNames = ["f₁", "f₂", "f₃", "f₄", "f₅", "f₆"]
val ratios = table(
    "a" to shapes.indices.map { 0.05 * it },
    *modeNames.mapIndexed { k, name -> name to shapes.map { it.ratios()[k] } }.toTypedArray(),
).apply { title = "f / f₁ of modes 1–6" }
    .format("a") { (it as Double).f2 }
modeNames.forEach { name -> ratios.format(name) { (it as Double).f3 } }
ratios.heat(*modeNames.drop(1).toTypedArray())
display("ratios", ratios)
ModalDrum(shapes[0], fundamental = 110.0, every = 0.6, autoX = 0.3, autoY = 0.1, morph = shapes, morphPeriod = 4.0)
