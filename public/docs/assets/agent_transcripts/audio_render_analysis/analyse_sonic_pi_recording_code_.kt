import afield.four.synth.*
import afield.stdlib.noir.audioai.*
import java.nio.file.Path

val client = FieldAudioClient()
val ruby = """
    use_bpm 92
    live_loop :analysis_drums do
      sample :bd_haus, amp: 0.55
      sleep 0.5
      sample :elec_blip2, rate: [0.7, 1.0, 1.4].tick, amp: 0.28
      sleep 0.5
    end
    live_loop :analysis_tone do
      use_synth :hollow
      play (scale :e3, :minor_pentatonic).tick, release: 0.7, amp: 0.35
      sleep 1
    end
""".trimIndent()

recordSonicPiWav(
    sonicPi(),
    Path.of(System.getProperty("java.io.tmpdir"), "field-audio-renders", "sonic-pi-study.wav"),
    ruby, seconds = 18.0, workspace = "field_audio_analysis",
    provenance = mapOf("field_box" to "analyse_sonic_pi_recording", "bpm" to "92"),
).thenCompose { render ->
    display("render", "recorded ${render.path}")
    client.analyzeRenderedAudio(
        render,
        requests = { region -> standardAudioAnalyses(region, requestedBy = "field:render-analysis/sonic-pi") },
        onAccepted = { named, accepted -> display("${named.label} job", fieldAudioJobStatusHtml(accepted)) },
        onProgress = { named, accepted, event -> display("${named.label} job", fieldAudioJobStatusHtml(accepted, event)) },
    )
}.thenAccept { completed ->
    display("synchronized result", completed.visualizationHtml(width = 1040, panelHeight = 230))
}.exceptionally { error ->
    display("analysis error", error.cause?.message ?: error.message ?: error.toString())
    null
}
