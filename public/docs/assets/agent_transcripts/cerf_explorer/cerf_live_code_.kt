import afield.geometry.topology.morsesmale.*
import afield.geometry.topology.tracking.*

/*
Launch this one (Alt-R). A Gray–Scott run in its wandering-worms regime, seeded with three squares.
Each frame advances it 20 steps, adds the frame's critical points to the Cerf diagram, and draws
the diagram so far beside the field, with births (orange) and cancellations (violet) on both.
*/
var state = grayScottSeed(96, 96, patches = 3, patchSize = 10, seed = 7)
val cerf = CerfDiagram()

loop {
    state = state.stepTimes(GRAY_SCOTT_WORMS, 20)
    val v = state.v.gaussianBlur(1.0)
    cerf.add(frameNumber.toDouble(), MorseSmale.compute(v, persistence = 0.05), minPersistence = 0.05)
    val now = frameNumber.toDouble()
    showImgGrid(
        "births ${cerf.events(CerfEventType.BIRTH).size}, cancellations ${cerf.events(CerfEventType.DEATH).size}" to
            Img.filled(320, 220, 1, 0.08f)
                .drawFLines(cerf.toFLines(320.0, 220.0, cursor = now, showSwaps = false), scale = 2.0),
        "frame $frameNumber" to
            v.colormap("magma", 0f, 0.4f).drawFLines(cerf.fieldMarkers(now, 5.0, window = 2.0), scale = 4.5),
        columns = 2,
        cellSide = 440,
    )
}
