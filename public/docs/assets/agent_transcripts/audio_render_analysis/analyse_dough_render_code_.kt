import afield.four.synth.*
import afield.stdlib.noir.audioai.*
import java.nio.file.Path

val client = FieldAudioClient()
val source = ZawOsc(Param(110f)) * 0.35f
val voice = source.through(Phaser(
    rate = Param(0.23f), depth = Param(0.78f),
    center = Param(540f), sweep = Param(1800f), mix = Param(0.92f),
))
val render = renderDoughWav(
    Path.of(System.getProperty("java.io.tmpdir"), "field-audio-renders", "dough-phaser-study.wav"),
    voice, seconds = 18.0,
    provenance = mapOf("field_box" to "analyse_dough_render", "voice" to "ZawOsc → Phaser"),
)
display("render", "rendered ${render.path} · ${render.sampleRate} Hz mono")

client.analyzeRenderedAudio(
    render,
    requests = { region -> standardAudioAnalyses(region, requestedBy = "field:render-analysis/dough") },
    onAccepted = { named, accepted -> display("${named.label} job", fieldAudioJobStatusHtml(accepted)) },
    onProgress = { named, accepted, event -> display("${named.label} job", fieldAudioJobStatusHtml(accepted, event)) },
).thenAccept { completed ->
    display("synchronized result", completed.visualizationHtml(width = 1040, panelHeight = 230))
}.exceptionally { error ->
    display("analysis error", error.cause?.message ?: error.message ?: error.toString())
    null
}
