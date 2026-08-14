import afield.four.synth.*
import afield.stdlib.noir.audioai.*
import java.nio.file.Path

val client = FieldAudioClient()
val pattern = (pat("{60 64 67 71, bd ~ sd ~, hh*8}")
    with gain(sine().range(0.22, 0.72))
    with pan("0.2 0.8"))
val render = renderDirtWav(
    Path.of(System.getProperty("java.io.tmpdir"), "field-audio-renders", "pat-dirt-study.wav"),
    pattern, cycles = 12, cps = 1.6,
    provenance = mapOf("field_box" to "analyse_pat_render", "pattern" to "pitched chord + drums"),
)
display("render", "rendered ${render.path} · ${"%.2f".format(render.durationSeconds)} s")

client.analyzeRenderedAudio(
    render,
    requests = { region -> standardAudioAnalyses(region, requestedBy = "field:render-analysis/pat") },
    onAccepted = { named, accepted ->
        display("${named.label} job", fieldAudioJobStatusHtml(accepted))
    },
    onProgress = { named, accepted, event ->
        display("${named.label} job", fieldAudioJobStatusHtml(accepted, event))
    },
    onProgressError = { named, error -> display("${named.label} stream", error.message ?: error.toString()) },
).thenAccept { completed ->
    display("synchronized result", completed.visualizationHtml(width = 1040, panelHeight = 230))
}.exceptionally { error ->
    display("analysis error", error.cause?.message ?: error.message ?: error.toString())
    null
}
