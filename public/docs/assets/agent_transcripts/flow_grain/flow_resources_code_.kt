import afield.simulation.flow.*

fun grid() = defaultBuffer {
    size = 14400.vec4s
    zeroInit()
}
    .buffer
propDown<FrameBufferGroup>(flowA)
flowA = grid()
propDown<FrameBufferGroup>(flowB)
flowB = grid()
propDown<FrameBufferGroup>(advected)
advected = grid()
propDown<FrameBufferGroup>(divergence)
divergence = grid()
propDown<FrameBufferGroup>(pressureA)
pressureA = grid()
propDown<FrameBufferGroup>(pressureB)
pressureB = grid()
propDown<FrameBufferGroup>(particleA)
particleA = defaultBuffer {
    size = 32000.vec4s
    zeroInit()
}
        .buffer
propDown<FrameBufferGroup>(particleB)
particleB = defaultBuffer {
    size = 32000.vec4s
    zeroInit()
}
        .buffer
propDown<FrameBufferGroup>(tileHeaders)
tileHeaders = defaultBuffer {
    perFrame = true
    size = 25.vec4s
}
        .buffer
propDown<FrameBufferGroup>(cameraHistory)
cameraHistory = defaultBuffer {
    perFrame = true
    size = 17.vec4s
}
        .buffer
propDown<FrameBufferGroup>(itemHistory)
itemHistory = defaultBuffer {
    perFrame = true
    size = 3.vec4s
}
        .buffer
propDown<SimulationReset>(resetFlow)
resetFlow = SimulationReset()
propDown<EffectiveParameter>(force)
force = EffectiveParameter("Force", 2.5, 0.0, 12.0, "world/s²", "Vortex impulse")
propDown<EffectiveParameter>(grainCell)
grainCell = EffectiveParameter("Grain cell", .018, .006, .12, "world", "Fixed world noise scale")
propDown<EffectiveParameter>(strokeTime)
strokeTime = EffectiveParameter("Stroke time", .15, .01, .6, "s", "Noise integration interval")
propDown<afield.math.linalg.Vec4>(flowLook)
flowLook = vec(.018, .15, .45, 0) // Grain cell, integration seconds, gain, debug view.
propDown<afield.math.linalg.Vec4>(flowClock)
flowClock = vec(0, 1.0 / 60, 0, 0) // Time, fixed step, reset generation, reserved.
propDown<afield.math.linalg.Vec4>(flowEmitter)
flowEmitter = vec(0, 0, 2.5, 0) // World position and force strength.
propDown<ComputeTarget>(flowSimulation)
flowSimulation = ComputeTarget(renderer)
propDown<java.util.concurrent.atomic.AtomicLong>(flowStep)
flowStep = java.util.concurrent.atomic.AtomicLong()
propDown<java.util.concurrent.atomic.AtomicBoolean>(travel)
travel = java.util.concurrent.atomic.AtomicBoolean(false) // Move across tile boundaries and exercise zoom history.
propDown<EffectiveParameter>(flowWidth)
flowWidth =
    EffectiveParameter("View half-height", 8.0, 3.0, 10.0, "world") // Visible vertical half-height in world units.

// A color surface and a correspondence attachment. The latter stores previous UV,
// validity, and coverage; it is a sampling coordinate, not a velocity in pixels/s.
propDown<RenderToTexture>(flowSurface)
flowSurface = RenderToTexture {
    width = 960
    height = 720
    colorAttachments = 2
    colorFormats =
        [org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT, org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT]
    useDepth = false
    clearColor = vec(0, 0, 0, 0)
    debugName = "flow-correspondence"
}
val simulation = flowSimulation
val surface = flowSurface
val targets = renderer.renderList.preTargets
targets["flow-simulation"] = simulation
targets["flow-surface"] = surface
loop {}
    .end {
        if (targets["flow-simulation"] === simulation) targets.remove("flow-simulation")
        if (targets["flow-surface"] === surface) targets.remove("flow-surface")
    }
