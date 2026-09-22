import afield.SimulationReset
import afield.stdlib.EffectiveParameter
import afield.stdlib.TileResidency
import afield.four.flow.*
import afield.four.targets.*

val render: Renderer = fromContext()
fun grid() = defaultBuffer {
    size = 14400.vec4s
    zeroInit()
}.buffer
@exported var flowA = grid()
@exported var flowB = grid()
@exported var advected = grid()
@exported var divergence = grid()
@exported var pressureA = grid()
@exported var pressureB = grid()
@exported var particleA = defaultBuffer {
    size = 32000.vec4s
    zeroInit()
}.buffer
@exported var particleB = defaultBuffer {
    size = 32000.vec4s
    zeroInit()
}.buffer
@exported var tileHeaders = defaultBuffer {
    perFrame = true
    size = 25.vec4s
}.buffer
@exported var cameraHistory = defaultBuffer {
    perFrame = true
    size = 17.vec4s
}.buffer
@exported var itemHistory = defaultBuffer {
    perFrame = true
    size = 3.vec4s
}.buffer
@exported var resetFlow = SimulationReset()
@exported var force = EffectiveParameter("Force", 2.5, 0.0, 12.0, "world/s²", "Vortex impulse")
@exported var grainCell = EffectiveParameter("Grain cell", .018, .006, .12, "world", "Fixed world noise scale")
@exported var strokeTime = EffectiveParameter("Stroke time", .15, .01, .6, "s", "Noise integration interval")
@exported var flowLook = vec(.018, .15, .45, 0) // Grain cell, integration seconds, gain, debug view.
@exported var flowClock = vec(0, 1.0/60, 0, 0) // Time, fixed step, reset generation, reserved.
@exported var flowEmitter = vec(0, 0, 2.5, 0) // World position and force strength.
@exported var flowSimulation = ComputeTarget(render)
@exported var flowStep = java.util.concurrent.atomic.AtomicLong()
@exported var travel = java.util.concurrent.atomic.AtomicBoolean(false) // Move across tile boundaries and exercise zoom history.
@exported var flowWidth = EffectiveParameter("View half-height", 8.0, 3.0, 10.0, "world") // Visible vertical half-width in world units.

// A color surface and a correspondence attachment. The latter stores previous UV,
// validity, and coverage; it is a sampling coordinate, not a velocity in pixels/s.
@exported var flowSurface = RenderToTexture {
    width = 960
    height = 720
    colorAttachments = 2
    colorFormats = listOf(org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT, org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT)
    useDepth = false
    clearFirst = true
    clearColor = vec(0, 0, 0, 0)
    debugName = "flow-correspondence"
}
val targets = render.renderList.preTargets
targets["flow-simulation"] = flowSimulation
targets["flow-surface"] = flowSurface
loop { true }.end {
    if (targets["flow-simulation"] === flowSimulation) targets.remove("flow-simulation")
    if (targets["flow-surface"] === flowSurface) targets.remove("flow-surface")
}
