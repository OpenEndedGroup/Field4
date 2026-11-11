markdown(
    "guide",
    """
    ## Short-time spectral spaces

    One lightweight `analysis.embed_spectral` job records a 25 Hz trajectory as normalized mel mass, absolute log-mel, MFCC, energy, centroid, flatness, rolloff, and flux. Field owns temporal pooling, distances, MDS/t-SNE, and audition.

    **Mel Wasserstein** measures how far spectral mass moves along a perceptual frequency axis. **Log-mel** retains detailed band shape. **MFCC** gives a compact broad spectral envelope. Run box 2 from the committed real result without any service; run box 3 for import → submit → monitor → await → decode → three Smile t-SNE fits → synchronized playback.""".trimIndent(),
    width = 1040,
)
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 430))
