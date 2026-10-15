import afield.document.workspace.path
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

val client = LorisClient()
val chunkStart = 6.0
val chunkDuration = 3.0
client.analyzeWavAsync("%docs/assets/subharmonic_sound/notld-barbara-source.wav".path,
    options = LorisOptions(resolutionHz = 35.0, windowWidthHz = 80.0,
        frequencyDriftHz = 50.0, amplitudeFloorDb = -55.0),
    onProgress = { display("status", it) },
).thenCompose { analyzed ->
    val model = analyzed.model
    val changed = model.copy(partials = model.partials.map { partial ->
        partial.copy(points = partial.points.map { point ->
            val u = (point.timeSeconds - chunkStart) / chunkDuration
            if (u <= 0.0 || u >= 1.0) point else {
                val envelope = sin(PI * u).pow(2)
                val octaves = 1.5 * envelope * sin(2 * PI * 4 * u)
                val shutter = .25 + .75 * (.5 + .5 * sin(2 * PI * 12 * chunkDuration * u))
                point.copy(
                    frequencyHz = (point.frequencyHz * 2.0.pow(octaves)).coerceAtMost(model.sampleRate * .49),
                    amplitude = point.amplitude * (1 - envelope + envelope * shutter),
                    bandwidth = point.bandwidth * (1 - envelope) + .85 * envelope,
                )
            }
        })
    })
    display("status", "Resynthesizing the whirlpool…")
    client.synthesizeAsync(changed)
}.thenAccept { edited ->
    display("edited tracks and sound", edited.visualizationHtml(
        LorisViewOptions(height = 400, syncGroup = "my-whirlpool")))
    display("provenance", edited.provenance)
}.exceptionally { error ->
    display("error", error.cause?.message ?: error.message ?: error.toString())
    null
}
