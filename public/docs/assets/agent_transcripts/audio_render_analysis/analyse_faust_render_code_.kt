import afield.four.synth.*
import afield.stdlib.noir.audioai.*
import java.nio.file.Path

val client = FieldAudioClient()
val faustSource = """
    import("stdfaust.lib");
    sweep = 240 + 170 * os.osc(0.11);
    voice = os.sawtooth(sweep) : fi.lowpass(3, 900 + 650 * os.osc(0.07));
    process = voice * 0.18 <: _,_;
""".trimIndent()

renderFaustWavAsync(
    Path.of(System.getProperty("java.io.tmpdir"), "field-audio-renders", "faust-sweep-study.wav"),
    name = "faust-sweep-study", source = faustSource, seconds = 18.0,
    provenance = mapOf("field_box" to "analyse_faust_render"),
).thenCompose { render ->
    display("render", "rendered ${render.path} · ${render.sampleRate} Hz stereo")
    client.analyzeRenderedAudio(
        render,
        requests = { region -> standardAudioAnalyses(region, requestedBy = "field:render-analysis/faust") },
        onAccepted = { named, accepted -> display("${named.label} job", fieldAudioJobStatusHtml(accepted)) },
        onProgress = { named, accepted, event -> display("${named.label} job", fieldAudioJobStatusHtml(accepted, event)) },
    )
}.thenAccept { completed ->
    display("synchronized result", completed.visualizationHtml(width = 1040, panelHeight = 230))
}.exceptionally { error ->
    display("analysis error", error.cause?.message ?: error.message ?: error.toString())
    null
}
