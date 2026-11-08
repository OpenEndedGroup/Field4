import afield.audio.modal.*

// A unit drum loaded like Ramakrishna & Sondhi's tabla: density ×9.77 inside radius 0.4.
// Its nine lowest mode families, drawn in Raman's five columns, one per harmonic.
val bare = polarDrumMesh(0.05) { 1.0 }
val bareModes = bare.modes(15)
val modes = bare.withDensity(syahi(0.4, 3.125 * 3.125)).modes(15)
val labels = modes.circularLabels()
val pictures = ArrayList<FLine>()
val perTone = IntArray(6)
Raman.families.forEach { (n, m, tone) ->
    val k = labels.indexOfFirst { it.diameters == n && it.circles == m }
    val dx = (tone - 1) * 2.3f
    val dy = perTone[tone]++ * 2.3f
    pictures += modes.modeMesh(k).map { it.copy().apply { transform { v -> v.add(dx, dy, 0f) } } }
    pictures +=
        modes.nodalLines(k).copy().apply {
            transform { v -> v.add(dx, dy, 0f) }
            color = vec(1.0, 1.0, 1.0, 1.0)
            thickness = 1.5
        }
    pictures +=
        fline { circle(dx.toDouble(), dy.toDouble(), 0.4) }
            .apply {
                color = vec(0.1, 0.1, 0.1, 1.0)
                thickness = 1.0
            }
}
val families = table(
    "mode" to Raman.families.map { (n, m, _) -> "($n,$m)" },
    "tone" to Raman.families.map { it.third },
    "bare" to bareModes.ramanFamilies().toList(),
    "loaded" to modes.ramanFamilies().toList(),
    "exact" to Raman.compositeExact.toList(),
    "measured" to Raman.measured.toList(),
)
listOf("bare", "loaded", "exact", "measured").forEach { c -> families.format(c) { (it as Double).f3 } }
display("families", families)
lines(pictures, w = 520.0, h = 330.0, fitToSize = true)
println("bare drum: ${bareModes.ramanHarmonicity()}")
println("loaded drum: ${modes.ramanHarmonicity()}")
