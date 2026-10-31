import afield.geometry.topology.vectorfield.*

// The eagle's flow (one grid of 4-pixel cells per frame) and the film it was tracked from.
val flow = loadParticleFlow("public/docs/assets/vector_field_topology/eagle.flow.gz")
val film = videoFrames("public/docs/assets/vector_field_topology/eagle.mp4", width = 480).toList()
val cameras = CameraMotion.entries
var frame = 270
var camera = CameraMotion.KEEP
var minSpeed = 0.0

// Streamlines, separatrices and critical points of one frame's flow, over the darkened film.
fun show() {
    val topology =
        VectorFieldTopology.compute(flow.at(frame, window = 1, camera = camera), minStrength = 0.02, maxLength = 300.0)
    val lines =
        topology.streamlines(spacing = 1.5, length = 14.0, minSpeed = minSpeed) +
            topology.toFLines() +
            topology.criticalPointsFLine(6.0)
    val picture = film[frame]
    val dark = Img(picture.width, picture.height, 3, FloatArray(picture.width * picture.height * 3))
    showImgGrid(
        "frame $frame, camera ${camera.name.lowercase()}" to
            picture.overlay(dark, 0.55).drawFLines(lines.map { it * flow.cell.toDouble() }, scale = 2.0),
        columns = 1,
        cellSide = 960,
    )
}
// camera: 0 keeps the camera's motion, 1 removes its median shift, 2 its fitted affine motion.
// min speed: draw streamlines only where the flow is faster than this, in pixels per frame.
controlGroup("eagle") {
    slider("frame", min = 0.0, max = flow.frameCount - 1.0, step = 1.0, initialValue = 270.0) {
        frame = it.toInt()
        show()
    }
    slider("camera", min = 0.0, max = 2.0, step = 1.0, initialValue = 0.0) {
        camera = cameras[it.toInt()]
        show()
    }
    slider("min speed", min = 0.0, max = 3.0, step = 0.1, initialValue = 0.0) {
        minSpeed = it
        show()
    }
}
show()
