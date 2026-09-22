import afield.four.modules.fromContextWithNameOrNull
import afield.four.flow.TileAtlasLayout
import afield.four.targets.*
import afield.stdlib.TileResidency
import kotlin.math.*

// Dependencies are explicit graph exports; resources stay in their parent box.
val tileHeaders = fromContextWithNameOrNull<FrameBufferGroup>("tileHeaders")!!
val cameraHistory = fromContextWithNameOrNull<FrameBufferGroup>("cameraHistory")!!
val itemHistory = fromContextWithNameOrNull<FrameBufferGroup>("itemHistory")!!
val flowLook = fromContextWithNameOrNull<afield.stdlib.Vec4>("flowLook")!!
val flowClock = fromContextWithNameOrNull<afield.stdlib.Vec4>("flowClock")!!
val flowEmitter = fromContextWithNameOrNull<afield.stdlib.Vec4>("flowEmitter")!!
val force = fromContextWithNameOrNull<afield.stdlib.EffectiveParameter>("force")!!
val grainCell = fromContextWithNameOrNull<afield.stdlib.EffectiveParameter>("grainCell")!!
val strokeTime = fromContextWithNameOrNull<afield.stdlib.EffectiveParameter>("strokeTime")!!
val flowWidth = fromContextWithNameOrNull<afield.stdlib.EffectiveParameter>("flowWidth")!!
val resetFlow = fromContextWithNameOrNull<afield.SimulationReset>("resetFlow")!!
val flowStep = fromContextWithNameOrNull<java.util.concurrent.atomic.AtomicLong>("flowStep")!!
val travel = fromContextWithNameOrNull<java.util.concurrent.atomic.AtomicBoolean>("travel")!!

val pool = TileResidency(5, 5, 4f)
val layout = TileAtlasLayout(pool, 24)
val camera = FrameHistory()
val items = MotionItems()
val pose = MotionPose()
val itemData = FloatArray(12)
val matrix = afield.stdlib.linalg.Matrix4f()
val key = "flow-frame-inputs"
var ticks = 0L
var resetEpoch = 1
var lastRendered = Long.MIN_VALUE
var report = 0L

// This hook runs once per rendered frame, before compute/draw. No timer or
// swapchain-image index advances the simulation or camera history.
frames {
    val budget = frameProfile("Flow inputs", targetFps = 60.0, allocationKiB = 128.0)
    val upload: (Renderer.GeometryPhaseScope) -> Unit = {
        val serial = renderer.frameCounter
        if (serial != lastRendered) {
            lastRendered = serial
            budget.beginFrame()
            budget.measure("Reset and residency") {
                resetFlow.atFrame(serial) {
                    ticks = 0L
                    resetEpoch++
                    pool.invalidate()
                    camera.invalidate()
                    items.clear()
                }
                val time = ticks / 60.0
                val pan = if (travel.get()) sin(time * .12) * 28.0 else 0.0
                pool.moveTo(pan, 0.0)
                tileHeaders.mapAndWrite_ints { put(layout.updateHeaders()) }
                val height = flowWidth.effective * if (travel.get()) 1.0 + .18 * sin(time * .25) else 1.0
                val width = height * window.width / window.height.toDouble()
                matrix.set(
                    (1.0/width).toFloat(), 0f, 0f, 0f,
                    0f, (1.0/height).toFloat(), 0f, 0f,
                    0f, 0f, -1f, 0f,
                    (-pan/width).toFloat(), 0f, 0f, 1f
                )
                camera.capture(serial, matrix, 960, 720, resetFlow.epoch)
                cameraHistory.mapAndWrite { put(camera.packed) }
                flowClock.set(time.toFloat(), 1f/60, resetEpoch.toFloat(), 0f)
                flowEmitter.set((pan + cos(time*.7)*2).toFloat(), sin(time*.9).toFloat(), force.effective.toFloat(), 0f)
                flowLook.x = grainCell.effective.toFloat()
                flowLook.y = strokeTime.effective.toFloat()
            }
            budget.measure("Object correspondence") {
                pose.x = flowEmitter.x
                pose.y = flowEmitter.y
                pose.angle = flowClock.x
                items.begin(serial)
                itemData[0] = pose.x
                itemData[1] = pose.y
                itemData[2] = pose.angle
                itemData[3] = 1f
                items.write("emitter", pose, itemData, 4, camera.available)
                itemHistory.mapAndWrite { put(itemData) }
            }
            flowStep.set(ticks++)
        }
    }
    renderer.geometryProducers[key] = upload
    try {
        while (frame()) {
            if (renderer.frameCounter - report >= 120) {
                report = renderer.frameCounter
                println("render=$report step=$ticks tiles=${pool.capacity} relocations=${pool.relocations} reset=${resetFlow.completed} history=${camera.available}")
            }
        }
    } finally {
        if (renderer.geometryProducers[key] === upload) renderer.geometryProducers.remove(key)
        budget.close()
    }
}
