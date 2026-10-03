import afield.four.synth.*
import afield.stdlib.noir.audioai.*
import java.nio.file.Path
val pattern = (pat("{bd ~ sd ~, hh*8}") with gain(0.5))
val render = renderDirtWav(Path.of("public/docs/assets/audio_render_analysis/pat-dirt-study.wav"), pattern, cycles = 12, cps = 1.6, library = DirtRender.requirePinnedLibrary(), normalizePeak = 0.85f, provenance = mapOf("pattern" to "bd ~ sd ~ + hh*8", "cycles" to "12", "cps" to "1.6"))
check(render.provenance["sample_library"] == "true")
check(render.provenance["matched"] == "bd hh sd")
java.io.File("public/docs/assets/audio_render_analysis/render-provenance.json").writeText(FieldAudioClient.json.encodeToString(kotlinx.serialization.json.JsonObject(render.provenance.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) })))
println("PAT render complete: ${render.durationSeconds} seconds")
