import java.io.File

val json = FieldAudioClient.json
val folder = File("public/docs/assets/audio_ai_spectral_embeddings")
val preview = json.decodeFromString<AnalysisPreview>(File(folder, "result-preview.json").readText())
val frames = decodeSpectralEmbeddingMessagePack(
    File(folder, "frames.msgpack").readBytes(),
    maxFrames = 600,
    durationSeconds = 10.0,
    poolMillis = 200.0,
    hopMillis = 40.0,
)
val previews =
    [
            SpectralEmbeddingDistance.MEL_WASSERSTEIN,
            SpectralEmbeddingDistance.LOG_MEL_COSINE,
            SpectralEmbeddingDistance.MFCC_EUCLIDEAN,
        ]
        .mapIndexed { index, distance ->
            preview.withSpectralProjection(
                frames.tsne(
                    dimensions = 4,
                    distance = distance,
                    perplexity = 30.0,
                    maxIterations = 750,
                    seed = 184921L + index,
                ),
            )
        }
display("spectral spaces", spectralEmbeddingPreviewDashboardHtml(previews, width = 1040, phaseHeight = 210))
