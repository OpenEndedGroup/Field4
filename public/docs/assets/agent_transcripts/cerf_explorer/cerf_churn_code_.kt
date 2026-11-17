import afield.geometry.topology.morsesmale.*
import afield.geometry.topology.tracking.*

/*
Run this one (Alt-Enter), then drag the slider. Three seed patches in Gray–Scott's spot regime, where each spot
grows, splits in two and pushes its daughters apart until the spots fill the field. 120 frames, 40 steps apart: 4,800 steps in all.
*/
var state = grayScottSeed(96, 96, patches = 3, patchSize = 10, seed = 7)
val frames = ArrayList<Img>()
val cerf = CerfDiagram()
for (frame in 0 until 120) {
    state = state.stepTimes(GRAY_SCOTT_SPOTS, 40)
    val v = state.v.gaussianBlur(1.5)
    frames.add(v)
    cerf.add(frame.toDouble(), MorseSmale.compute(v, persistence = 0.05), minPersistence = 0.05)
}

val births = cerf.events(CerfEventType.BIRTH).size
val deaths = cerf.events(CerfEventType.DEATH).size
val swaps = cerf.events(CerfEventType.SWAP).size

fun show(t: Double) {
    val diagram =
        Img.filled(480, 320, 1, 0.08f)
            .drawFLines(cerf.toFLines(480.0, 320.0, cursor = t, showSwaps = false, thickness = 1.0), scale = 2.0)
    val view =
        frames[t.toInt()].colormap("magma", 0f, 0.4f).drawFLines(cerf.fieldMarkers(t, 4.0, window = 1.0), scale = 5.0)
    showImgGrid(
        "$births births, $deaths cancellations, $swaps swaps" to diagram,
        "frame ${t.toInt()}" to view,
        columns = 2,
        cellSide = 480,
    )
}

slider("frame", min = 0.0, max = 119.0, step = 1.0, initialValue = 70.0) { show(it) }
show(70.0)
