markdown(
    "guide",
    """
    ## Corpus microscope

    Run box 2 for the complete path: resolve Nancy and Candlestick by filename; submit and await multi-scale segmentation and grouped descriptor jobs; query a six-second Nancy window; then audition the nearest windows and automatic source-to-neighbour splices.

    Every descriptor is an immutable NPZ asset. Retrieval weights remain visible and editable: harmony, pitch, texture, rhythm and dynamics. Players from different files share relative time, so hover, zoom, seek and the playback cursor line up without pretending that their file timestamps are the same.""".trimIndent(),
    width = 1040,
)
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 410))
