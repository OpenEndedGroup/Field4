import afield.document.workspace.path
import java.util.concurrent.CompletableFuture

val client = LorisClient()
val path = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
val passes = listOf(
    "fine time" to LorisOptions(resolutionHz = 80.0, windowWidthHz = 160.0, hopSeconds = .003),
    "fine frequency" to LorisOptions(resolutionHz = 20.0, windowWidthHz = 40.0, hopSeconds = .005),
).map { (name, settings) ->
    client.analyzeWavAsync(path, durationSeconds = 6.0, options = settings,
        onProgress = { display("$name status", it) },
    ).thenAccept { result ->
        display(name, result.visualizationHtml(LorisViewOptions(label = name,
            height = 280, syncGroup = "loris-resolution-comparison")))
        display("$name provenance", result.provenance)
    }
}
CompletableFuture.allOf(*passes.toTypedArray()).exceptionally { error ->
    display("error", error.cause?.message ?: error.message ?: error.toString())
    null
}
