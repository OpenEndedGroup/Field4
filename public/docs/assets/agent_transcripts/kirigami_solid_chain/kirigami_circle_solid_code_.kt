import afield.examples.chain.KirigamiSolidChain
import afield.geometry.conformal.KirigamiInverseDesign

watchdog = 300 // the solve takes about half a minute the first time; then it is cached
val design = cached("kirigami-circle-8") { KirigamiInverseDesign(n = 8).solve() }
println("design residual %.1e after %d iterations".format(design.residual, design.iterations))

// Tiles here are as small as a third of a unit and as sharp as 0.4 rad, so the slit is narrower.
fun report(gap: Double): List<String> {
    val (shut, open) = KirigamiSolidChain.measureDesign(design, gap = gap)
    return listOf(
        "gap $gap: closed genus ${shut.genus}, opened genus ${open.genus} (hinges - tiles + 1 = ${shut.predictedGenus})",
        "  ${shut.parts} part closed, ${open.parts} opened, watertight ${shut.watertight && open.watertight}",
        "  volume %.4f closed, %.4f opened; opened hull / closed hull %.3f"
            .format(shut.volume, open.volume, open.hullArea / shut.hullArea),
    )
}
val lines = report(0.01) + report(0.04)
lines.forEach { println(it) }
display("numbers", "<pre>${lines.joinToString("\n")}</pre>")

val (shut, open) = KirigamiSolidChain.measureDesign(design, gap = 0.01)
fun view(m: KirigamiSolidChain.Measured) = imgFromBytes(
    m.mesh.renderPng(MeshView(yawDegrees = 25.0, pitchDegrees = 40.0, distance = 2.7, width = 620, height = 520)),
)
showImgGrid(
    "cut pattern, closed" to view(shut),
    "opened onto the circle" to view(open),
    columns = 2,
    cellSide = 420,
)
