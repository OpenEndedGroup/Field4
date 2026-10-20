import java.nio.file.Files
import java.nio.file.Path

// Supply a WAV with at least 12 seconds of source audio for this request.
val sourcePath = Path.of(
    requireNotNull(System.getenv("FIELD_AUDIO_UNDERFIT_SOURCE_WAV")) {
        "Set FIELD_AUDIO_UNDERFIT_SOURCE_WAV to your source WAV (at least 12 seconds)."
    },
)
require(Files.isRegularFile(sourcePath)) { "Source WAV not found: $sourcePath" }
val loraRoot = Path.of(
    requireNotNull(System.getenv("FIELD_AUDIO_UNDERFIT_LORA_ROOT")) {
        "Set FIELD_AUDIO_UNDERFIT_LORA_ROOT to the worker's checkpoint directory."
    },
)
val checkpoint = UnderfitLoraCheckpoint.fromFile(
    loraRoot.resolve("mancini-experiment-in-terror/step-1000.safetensors"),
    workerRoot = loraRoot,
    strength = 0.85,
    intervalMax = 0.92,
)
val client = FieldAudioClient()
val source = client.importWav(sourcePath).get()
val region = AssetRegion(source.id, startSeconds = 0.0, durationSeconds = 12.0)
client
    .submit(
        underfitCandidates(
            source = region,
            prompt = "mancini-eit, suspenseful nocturnal orchestral jazz, flute, vibraphone and muted brass",
            negativePrompt = "vocals, speech, harsh distortion",
            durationSeconds = 30.0,
            checkpoints = [checkpoint],
            model = UnderfitModel.SMALL_MUSIC_BASE,
            controls = UnderfitGenerationControls(
                steps = 50,
                cfg = 1.5,
                initNoiseLevel = 0.65,
                durationPaddingSeconds = 2.0,
                chunkedDecode = true,
            ),
            seeds = 1962L..1962L,
            requestedBy = "field:audio-ai-underfit-mancini",
        ),
    )
    .thenCompose(client::awaitCandidates)
    .thenAccept { display("Mancini LoRA", candidateBoardHtml(it)) }
