import afield.examples.chain.KirigamiSolidChain

// gap: half a slit's width. bridge: how far each hinge bar runs along its cut.
val n = 8
val gap = 0.04
val bridge = 0.12
val plate = KirigamiSolidChain.measure(n, angle = 0.0, thickness = 0.1, gap = gap, bridge = bridge)

val report = listOf(
    "${plate.tiles} tiles, ${plate.hinges} hinges",
    "genus ${plate.genus}, and hinges - tiles + 1 = ${plate.predictedGenus}",
    "${plate.parts} part, watertight=${plate.watertight}",
    "volume %.6f = area %.6f x thickness 0.1".format(plate.volume, plate.sectionArea),
)
report.forEach { println(it) }
display("numbers", "<pre>${report.joinToString("\n")}</pre>")

// A three-quarter view from above; the CPU rasteriser needs no Window.
showImgGrid(
    "the cut plate, 0.1 thick" to
        imgFromBytes(
            plate.mesh.renderPng(
                MeshView(yawDegrees = 25.0, pitchDegrees = 40.0, distance = 2.7, width = 900, height = 600),
            ),
        ),
    columns = 1,
    cellSide = 600,
)
