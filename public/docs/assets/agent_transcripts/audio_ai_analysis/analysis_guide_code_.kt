markdown(
    "guide",
    """
    ## Beat and pitch analysis

    **librosa** supplies the onset envelope, multi-scale tempograms, beat grid and monophonic pYIN track. **Basic Pitch** supplies polyphonic note events, activations, pitch bends and MIDI. Essentia is not loaded by this worker.

    Run box 2 for real cached beat and pitch previews of Nancy from 18–42 seconds. Run box 3 for the complete live path: filename lookup → source window → three submitted jobs → WebSocket progress → durable results → synchronized analysis board. The source player and every analysis lane share hover, zoom and playback time; click any plot to seek the recording. Re-running unchanged requests is a cache hit.""".trimIndent(),
    width = 1040,
)
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 430))
