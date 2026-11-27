import afield.examples.chain.KirigamiSolidChain
import afield.geometry.conformal.KirigamiInverseDesign

val n = 8
val narrowGap = 0.01
val wideGap = 0.04
watchdog = 300 // The first inverse solve takes about half a minute.
val design = cached("kirigami-circle-$n") { KirigamiInverseDesign(n = n).solve() }

// Sharp corners retreat further as the gap widens, so a hinge can miss its tile.
val gaps: List<Double> = [narrowGap, wideGap]
val measurements = gaps.map { KirigamiSolidChain.measureDesign(design, gap = it) }
val rows =
    gaps.zip(measurements).joinToString("\n") { (gap, pair) ->
        val (closed, opened) = pair
        "| $gap | ${closed.genus} / ${opened.genus} | ${closed.predictedGenus} | " +
            "${closed.parts} / ${opened.parts} | ${closed.watertight && opened.watertight} | " +
            "${closed.volume.f4} / ${opened.volume.f4} | ${(opened.hullArea / closed.hullArea).f3} |"
    }
markdown(
    "numbers",
    """
    Design residual ${"%.1e".format(design.residual)} after ${design.iterations} iterations.

    | Half slit width | Genus (closed / open) | Predicted | Parts (closed / open) | Both watertight | Volume (closed / open) | Hull area ratio |
    | --- | --- | --- | --- | --- | --- | --- |""".trimIndent() + "\n" + rows,
)

val (shut, open) = measurements.first()
fun view(m: KirigamiSolidChain.Measured) = imgFromBytes(
    m.mesh.renderPng(MeshView(yawDegrees = 25.0, pitchDegrees = 40.0, distance = 2.7, width = 620, height = 520)),
)
showImgGrid(
    "cut pattern, closed" to view(shut),
    "opened onto the circle" to view(open),
    columns = 2,
    cellSide = 420,
)
