import afield.geometry.topology.morsesmale.*
import afield.geometry.topology.tracking.*

// The leaf print's dark spots (minima of its brightness), followed as the image is blurred more and
// more. A feature that survives a large blur is large; one that dies at the first is fine detail.
val print = imgFromFile("public/docs/assets/vision/fline-viewer.jpg", 3).crop(78, 262, 128, 236).scaleBy(2.0)
val sigmas = [1.0, 2.0, 3.0, 4.0, 6.0, 8.0, 11.0, 16.0]
val space =
    ScaleSpace.track(print.luma(), sigmas, persistence = 0.02, maxDistance = 14.0, types = setOf(CriticalType.MINIMUM))

// Each feature that survives to σ 3 or more, as a circle where it starts with radius twice the largest
// σ it reached (a blob of scale σ is about 2σ across), and its path through scale.
val lasting = space.tracker.tracks.filter { space.scaleOf(it) >= 3.0 }
val circles = lasting.map { t ->
    val (x, y) = t.samples.first().second.let { it.x to it.y }
    FLine().circle(x, y, 2 * space.scaleOf(t)).also {
        it.properties["color"] = FeatureTracker.colourOf(t.id)
        it.properties["thickness"] = 1.5
    }
}
val dark = Img(print.width, print.height, 3, FloatArray(print.width * print.height * 3))
showImgGrid(
    "photograph" to print,
    "by scale" to
        print
            .overlay(dark, 0.55)
            .drawFLines(
                circles +
                    lasting.map { t ->
                        t.toFLine().also {
                            it.properties["color"] = FeatureTracker.colourOf(t.id)
                            it.properties["thickness"] = 1.5
                        }
                    },
            ),
    columns = 2,
    cellSide = 320,
)
