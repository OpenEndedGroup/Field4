import afield.audio.modal.*

// Mixes of the loaded drum's (0,2) and (2,1) modes, which share one pitch: the nodal lines
// Raman saw in sand, from a circle to two parallel lines to two diameters.
val modes = polarDrumMesh(0.04) { 1.0 }.withDensity(syahi(0.4, 3.125 * 3.125)).modes(10)
val labels = modes.circularLabels()
val ring = labels.indexOf(CircularLabel(0, 2))
val cross = labels.indexOf(CircularLabel(2, 1))
val mixes = [0.0, 0.6, 1.0, 1.25, 2.5, 1000.0]
val out = ArrayList<FLine>()
mixes.forEachIndexed { i, w ->
    val dx = i * 2.3f
    out +=
        fline { circle(dx.toDouble(), 0.0, 1.0) }
            .apply {
                color = vec(0.5, 0.5, 0.5, 1.0)
                thickness = 1.0
            }
    out +=
        modes.mesh.zeroLines(modes.superpose(ring, cross, w)).apply {
            transform { v -> v.add(dx, 0f, 0f) }
            color = vec(1.0, 1.0, 1.0, 1.0)
            thickness = 2.0
        }
}
lines(out, w = 520.0, h = 110.0, fitToSize = true)
