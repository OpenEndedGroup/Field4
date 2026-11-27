import afield.examples.chain.KirigamiPopUp
import afield.graphics.scene.fabricated.PathTraceEnvironment
import afield.graphics.scene.fabricated.PathTraceMaterial
import afield.graphics.scene.fabricated.PathTraceScene
import afield.graphics.scene.fabricated.PathTracingRenderer
import afield.graphics.scene.fabricated.importTriMesh
import afield.graphics.vulkan.util.Camera
import java.io.File

prop<PathTracingRenderer>(tracer)

val sheet = KirigamiPopUp.sheet(thickness = 0.06)

// Move to a strain in steps of at most 0.25%, each relaxed from the last, so the sheet stays on one branch.
fun stretchTo(target: Double): KirigamiPopUp.State {
    var state = sheet.stretch(sheet.strain)
    while (abs(target - sheet.strain) > 1e-9) {
        state = sheet.stretch(sheet.strain + (target - sheet.strain).coerceIn(-0.0025, 0.0025))
    }
    return state
}

val environment =
    PathTraceEnvironment.fromFile(File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath)

// The tracer's scene is a snapshot, so every new pose is a new tracer under the same name.
fun trace(): PathTracingRenderer {
    val posed = sheet.mesh()
    val scene = PathTraceScene()
    val even = scene.material(PathTraceMaterial.plastic(0.95f, 0.42f, 0.10f, 0.3f))
    val odd = scene.material(PathTraceMaterial.plastic(0.10f, 0.38f, 0.85f, 0.3f))
    val ligament = scene.material(PathTraceMaterial.plastic(0.93f, 0.91f, 0.86f, 0.45f))
    val floor = scene.material(PathTraceMaterial.plastic(0.55f, 0.56f, 0.58f, 0.6f))
    scene.importTriMesh(
        posed.mesh,
        { t ->
            when (posed.parts[t]) {
                KirigamiPopUp.Part.EVEN_TILE -> even
                KirigamiPopUp.Part.ODD_TILE -> odd
                KirigamiPopUp.Part.LIGAMENT -> ligament
            }
        },
    )
    val reach = 30f
    val y = -0.9f
    scene.quad(
        Vector3f(-reach, y, -reach),
        Vector3f(reach, y, -reach),
        Vector3f(reach, y, reach),
        Vector3f(-reach, y, reach),
        floor,
    )
    val camera = Camera(renderer).also {
        it.at.set(8.0f, 8.5f, 11.5f)
        it.target.set(0f, -0.6f, 0.4f)
        it.p.fov = 0.62
    }
    return PathTracingRenderer(renderer, scene, environment, 1200, 736, camera, name = "kirigami-popup")
        .install()
        .also {
            it.samplesPerFrame = 4
            it.maxBounces = 6
            it.exposure = 1.1f
            it.environmentRotation = 2.1f
            it.indirectClamp = 10f
            it.blitToScreen()
        }
}

fun report(state: KirigamiPopUp.State) = markdown(
    "strain **%.2f%%**: mean tile tilt %.1f°, largest %.1f°, stress %.2e E"
        .format(100 * state.strain, state.meanTilt, state.maxTilt, state.stress),
)

// Launching stretches the sheet from flat to 6%, tracing each pose; then it holds. The slider moves it anywhere.
val ramp = (0..24).map { it * 0.0025 }.toMutableList()
var wanted = ramp.last()
slider("stretch (%)", min = 0.0, max = 6.0, step = 0.25, initialValue = 6.0) {
    ramp.clear()
    wanted = it / 100
}

var state = stretchTo(ramp.removeAt(0))
tracer = trace()
report(state)
var converged = false
frames {
    while (true) {
        val next = when {
            ramp.isNotEmpty() && tracer.samples >= 32 -> ramp.removeAt(0)
            ramp.isEmpty() && abs(wanted - sheet.strain) > 1e-9 -> wanted
            else -> null
        }
        if (next != null) {
            state = stretchTo(next)
            tracer = trace()
            report(state)
            converged = false
        }
        if (!converged && ramp.isEmpty() && tracer.samples >= 600) {
            converged = true
            println("popped sheet traced at strain %.2f%%: %d samples".format(100 * sheet.strain, tracer.samples))
        }
        frame()
    }
}
