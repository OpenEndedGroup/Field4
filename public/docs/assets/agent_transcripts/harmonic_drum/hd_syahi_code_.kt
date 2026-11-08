import afield.audio.modal.*

// Fit a syahi of one, two and three stacked layers to 1:2:3:4:5, each starting from
// Ramakrishna & Sondhi's disc. Below, each design's density from the centre (left) to the rim.
val designs = (1..3).toList().parallelStream().map { designSyahi(it, h = 0.08) }.toList()
display(
    "designs",
    table(
        "layers" to designs.map { it.layers },
        "radii" to designs.map { d -> d.radii.sorted().joinToString(" ") { it.f2 } },
        "centre density" to designs.map { it.peak.f1 },
        "start (cents)" to designs.map { it.startFit.rms.f1 },
        "fitted (cents)" to designs.map { it.fit.rms.f1 },
        "worst (cents)" to designs.map { it.fit.worst.f1 },
    ),
)
val colours = [vec(0.35, 0.6, 1.0, 1.0), vec(1.0, 0.85, 0.3, 1.0), vec(1.0, 0.45, 0.3, 1.0)]
val profiles = designs.mapIndexed { i, d -> d.profile(w = 3.0, h = 1.0, top = 16.0).apply { color = colours[i] } }
val composite = fline {
    moveTo(0.0, 1 - 9.77 / 16)
    lineTo(1.2, 1 - 9.77 / 16)
    lineTo(1.2, 1 - 1.0 / 16)
    lineTo(3.0, 1 - 1.0 / 16)
}
    .apply { color = vec(0.6, 0.6, 0.6, 1.0) }
lines(profiles + composite, w = 520.0, h = 180.0, fitToSize = true)
