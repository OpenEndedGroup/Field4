import afield.geometry.spectral.FieldSample
import afield.geometry.spectral.PlaneWaveField
import afield.geometry.spectral.discoverPlaneWaves
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

// Change the source shape or wave budget and launch again.
val shape = "torus" // sphere, torus, or concavities
val budget = 20
val bounds = boundsAround(radius = 1.7)
val source = when (shape) {
    "sphere" -> sdfSphere(0.85)
    "torus" -> sdfTorus(0.82, 0.33)
    else ->
        sdfSubtract(
            sdfUnion(sdfTorus(0.86, 0.35), sdfSphere(0.28, vec(0, 0.82, 0))),
            sdfSphere(0.48, vec(0.75, 0.36, 0.14)),
        )
}
val original = marchingCubes(source, bounds, resolution = 36)
val target = PlaneWaveField.meshTarget(original, truncation = 0.37)

go {
    val result = runInterruptible(Dispatchers.Default) {
        val random = Random(944)
        fun point() = vec(random.nextDouble(-1.7, 1.7), random.nextDouble(-1.7, 1.7), random.nextDouble(-1.7, 1.7))
        val uniform = List(600) { point() }
        val nearSurface =
            List(300) { i ->
                val vertex = random.nextInt(original.vertexCount)
                val offset = if (i % 2 == 0) 0.07f else -0.07f
                original.position(vertex).fma(offset, original.normal(vertex)!!)
            }
        val samples =
            uniform.map { FieldSample(it, target(it)) } +
                nearSurface.map { FieldSample(it, target(it), weight = 2.0) }
        val heldOut = List(300) { point() }.map { FieldSample(it, target(it)) }
        // All three methods use the same wave budget. Only the directional and adaptive fits
        // use the irregular weighted training samples. Every method uses the same held-out set.
        val fft = PlaneWaveField.fitCartesian(bounds, intArrayOf(16, 16, 16), budget, target)
        val fundamental = 2 * PI / bounds.size.x
        val shells = doubleArrayOf(fundamental, fundamental * 1.75, fundamental * 2.75, fundamental * 4.0)
        val fixed = PlaneWaveField.fitDirectional(
            samples,
            PlaneWaveField.frequencyShells(shells, (budget + 3) / 4).take(budget),
        )
        val adaptive = discoverPlaneWaves(
            samples,
            PlaneWaveField.frequencyShells(shells, 18),
            budget,
            refinementRadius = 0.22,
            refinementIterations = 9,
        ) { fitted ->
            display("progress", "Refined ${fitted.waves.size} / $budget plane waves")
        }
        ["Cartesian FFT" to fft, "Fixed shells" to fixed, "Adaptive discovery" to adaptive].map { (name, field) ->
            val rms = sqrt(
                heldOut
                    .map { sample ->
                        val error = field.evaluate(sample.point) - sample.value
                        error * error
                    }
                    .average(),
            )
            Triple(name, rms, field.extractSurface(bounds, resolution = 28))
        }
    }
    three(name = "wave-fitting", width = 840, height = 660) {
        background("#080808")
        helpers(false)
        columns(2)
        val camera = ThreeCameraSpec(yawDegrees = 31.0, pitchDegrees = 47.0)
        val framing = boundsAround(radius = 1.43)
        panel(title = "Source · $shape", camera = camera)
        fitTo(framing.min, framing.max, panel = 0)
        mesh(original, wireframe = false, id = "source")
        for ((index, fit) in result.withIndex()) {
            val (name, rms, reconstructed) = fit
            val topology = reconstructed.topology()
            val genus = topology.genus?.let { " · genus $it" } ?: ""
            panel(title = name, caption = "$budget waves · held-out RMS ${rms.f3}$genus", camera = camera)
            fitTo(framing.min, framing.max, panel = index + 1)
            if (!reconstructed.isEmpty) mesh(reconstructed, wireframe = false, id = "fit-$index")
        }
    }
    display("progress", "Fitted all three representations")
    displayOpen("main")
}
