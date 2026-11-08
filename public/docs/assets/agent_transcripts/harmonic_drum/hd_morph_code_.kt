import afield.audio.modal.*

// From the bare drum to Raman's harmonic drum and back, every 4 s, as a live voice.
val bare = polarDrumMesh(0.07) { 1.0 }.modes(15)
val composite = loadedDisk(Raman.compositeRadius, Raman.compositeMassRatio, h = 0.07).modes(15)
val fitted = designSyahi(1, h = 0.08).modes
val drums = [bare, composite, fitted]
val names = ["f₂", "f₄", "f₆", "f₇", "f₉", "f₁₁", "f₁₅"]
val picks = [1, 3, 5, 6, 8, 10, 14]
val spectra = table(
    "drum" to ["bare", "composite", "one layer"],
    *names.mapIndexed { c, name -> name to drums.map { it.ratios()[picks[c]] } }.toTypedArray(),
)
names.forEach { n -> spectra.format(n) { (it as Double).f3 } }
display("spectra", spectra)
ModalDrum(bare, fundamental = 220.0, every = 0.7, autoX = 0.62, autoY = 0.15, morph = drums, morphPeriod = 4.0)
