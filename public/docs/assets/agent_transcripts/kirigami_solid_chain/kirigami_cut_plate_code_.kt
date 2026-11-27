import afield.examples.chain.KirigamiSolidChain

val n = 8
val thickness = 0.1
val gap = 0.04 // Half the slit width.
val bridge = 0.12 // Hinge bar length along the cut.
val plate = KirigamiSolidChain.measure(n, angle = 0.0, thickness = thickness, gap = gap, bridge = bridge)

markdown(
    "numbers",
    """
    - ${plate.tiles} tiles, ${plate.hinges} hinges
    - Genus ${plate.genus}, predicted by hinges − tiles + 1 = ${plate.predictedGenus}
    - Parts: ${plate.parts}, watertight: ${plate.watertight}
    - Volume ${plate.volume.f6} = area ${plate.sectionArea.f6} × thickness $thickness""".trimIndent(),
)

showImgGrid(
    "the cut plate, $thickness thick" to
        imgFromBytes(
            plate.mesh.renderPng(
                MeshView(yawDegrees = 25.0, pitchDegrees = 40.0, distance = 2.7, width = 900, height = 600),
            ),
        ),
    columns = 1,
    cellSide = 600,
)
