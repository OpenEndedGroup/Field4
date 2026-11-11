markdown(
    "guide",
    """
    ## MuScriptor · recording to an instrument-labelled score

    `analysis.transcribe_music` is one model-neutral operation with three revision-pinned worker capabilities: small, medium, and large. It retains onset, offset, MIDI pitch, instrument group, activity curves, MessagePack events, performance-timed MIDI, and optional beat-quantized MIDI.

    Run box 2 from the committed real result without a server to explore the synchronized piano roll. Replace box 3's `sourcePattern` with a recording you have the rights to transcribe, then run it for asset lookup → submit → progress → durable result → source/score audition → MIDI download. Unchanged operations are cache hits.

    The code is MIT, but the gated model weights are CC BY-NC 4.0. Accept the selected model's Hugging Face terms before starting the worker.""".trimIndent(),
    width = 1040,
)
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 430))
