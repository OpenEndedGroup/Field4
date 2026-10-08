import afield.stdlib.loris.LorisClient
import afield.stdlib.loris.LorisEnvelope
import afield.stdlib.loris.LorisOptions
import afield.stdlib.loris.LorisReference
import afield.stdlib.loris.LorisViewOptions
import afield.stdlib.loris.crop
import afield.stdlib.loris.dropUnlabeled
import afield.stdlib.loris.labelCounts
import afield.stdlib.loris.loudestTimeSeconds
import afield.stdlib.loris.visualizationHtml
import afield.stdlib.path

val client = LorisClient()
client.analyzeWavAsync(
    "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path,
    options = LorisOptions(resolutionHz = 35.0, windowWidthHz = 80.0,
        frequencyDriftHz = 50.0, amplitudeFloorDb = -55.0),
    onProgress = { display("status", it) },
).thenAccept { analyzed ->
    val film = analyzed.model
    val a = client.distill(film.crop(1.6, 3.6), LorisReference.Estimated(150.0, 450.0)).model
    val b = client.distill(film.crop(11.5, 13.5), LorisReference.Estimated(60.0, 200.0)).model
    display("labels", "A ${a.labelCounts()}  B ${b.labelCounts()}")
    val end = a.durationSeconds
    val aligned = client.dilate(b, listOf(0.0, b.loudestTimeSeconds(), end),
        listOf(0.0, a.loudestTimeSeconds(), end), durationSeconds = end).model
    val morphed = client.morph(a.dropUnlabeled(), aligned.dropUnlabeled(), LorisEnvelope.ramp(0.4, 1.6))
    display("morph", morphed.visualizationHtml(LorisViewOptions(maximumHz = 5000.0, syncGroup = "my-morph")))
}.exceptionally { error ->
    display("error", error.cause?.message ?: error.message ?: error.toString())
    null
}
