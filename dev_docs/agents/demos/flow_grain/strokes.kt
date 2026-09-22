import afield.four.modules.fromContextWithNameOrNull

// Dependencies are explicit graph exports; resources stay in their parent box.
val particleA = fromContextWithNameOrNull<FrameBufferGroup>("particleA")!!
val particleB = fromContextWithNameOrNull<FrameBufferGroup>("particleB")!!
val cameraHistory = fromContextWithNameOrNull<FrameBufferGroup>("cameraHistory")!!
val flowStep = fromContextWithNameOrNull<java.util.concurrent.atomic.AtomicLong>("flowStep")!!

var module = moduleHere { depthTest = false
    depthWrite = false
    primitiveType = triangles }
val mesh = Mesh()
module += "velocity strokes" to mesh
mesh.particles = { if (flowStep.get() % 2L == 0L) particleB else particleA }
mesh.history = { cameraHistory }
val strokeLook = vec(.18, .6, 1, 0) // Stroke seconds, half-width pixels, gain, reserved.
mesh.look = { strokeLook }
val strokeViewport = vec(0, 0, 0, 0)
mesh.viewport = { strokeViewport.set(window.width.toFloat(), window.height.toFloat(), 0f, 0f) }
mesh.resize(16000 * 6)
renderer += "flow-strokes" to module
loop { true }.end { if (renderer.renderList.modules["flow-strokes"] === module) renderer.renderList.modules.remove("flow-strokes") }
