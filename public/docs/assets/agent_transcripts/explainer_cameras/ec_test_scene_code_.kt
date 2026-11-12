import afield.simulation.optics.*

// The scene every camera in the talk photographs. Drag to look around it.
three(channel = "scene", height = 400, width = 640) {
    mesh(OpticsScene.testScene().mesh(), wireframe = false)
    background("#d8dde3")
}
