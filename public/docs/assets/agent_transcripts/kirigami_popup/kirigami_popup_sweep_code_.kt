import afield.examples.chain.KirigamiPopUp

// Three sheets, each stretched through the same strains, every step relaxed from the last.
val strains = (0..40).map { it * 0.001 }
val thicknesses = [0.06, 0.08, 0.12]
val runs = thicknesses.map { t -> t to KirigamiPopUp.sheet(thickness = t).sweep(strains) }

val percent = strains.map { it * 100 }
val tilt =
    Chart(title = "tiles tilt once the sheet buckles", xLabel = "strain (%)", yLabel = "mean tile tilt (degrees)")
val stress = Chart(title = "and the sheet softens", xLabel = "strain (%)", yLabel = "stress / E (x 1000)")
for ((t, states) in runs) {
    val critical = KirigamiPopUp.threshold(states)
    val at4 = states.last()
    println(
        "t = $t: flat until strain %.2f%%, threshold / (t / bridge)^2 = %.4f; at 4%% mean tilt %.1f deg, max %.1f deg"
            .format(100 * critical, critical / ((t / 0.12) * (t / 0.12)), at4.meanTilt, at4.maxTilt),
    )
    tilt.line("t = $t", percent, states.map { it.meanTilt })
    tilt.vrule(100 * critical, "%.2f%%".format(100 * critical))
    stress.line("t = $t", percent, states.map { 1000 * it.stress })
}
display("tilt", tilt)
display("stress", stress)
