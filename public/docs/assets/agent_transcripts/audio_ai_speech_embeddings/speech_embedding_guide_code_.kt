markdown(
    "guide",
    """
    ## Speech-reactive learned spaces

    **emotion2vec** emphasizes vocal affect, prosody and texture. **WavLM Base+** supplies a broader self-supervised speech representation; layer 6 is a useful acoustic/phonetic compromise. Both produce 50 Hz frame vectors, explicit WebRTC voice activity, energy, novelty, and a compact binary MessagePack artifact.

    Run box 2 to inspect the synchronized visualizer without a worker. Run box 3 for the complete local path: find or import `~/Downloads/near_drowning.wav` → submit two durable jobs → watch progress → retrieve cached results → pool 500 ms of acoustic evidence every 40 ms (25 Hz) → run fixed-seed cosine t-SNE in Field through Smile → play the recording through axes 0×1 and 2×3. Hover a path to inspect its time; click it to seek the audio.""".trimIndent(),
    width = 1040,
)
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 430))
