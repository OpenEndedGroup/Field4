markdown(
    "guide",
    """
    ## Experiment in Terror · Underfit LoRA

    This request uses 165 twelve-second chunks from the complete Henry Mancini album as training material. The pinned rank-16 DoRA checkpoint is applied to Stable Audio 3's small-music base model, with a 12-second source window extended to a 30-second candidate.

    Set `FIELD_AUDIO_UNDERFIT_SOURCE_WAV` to your source WAV (at least 12 seconds), set `FIELD_AUDIO_UNDERFIT_LORA_ROOT`, and start the Underfit worker before running box 2. The worker must have the staged base model and the checkpoint beneath that root. The request retains its model revisions, checkpoint digest, seed, and sampling controls in the durable candidate provenance.""".trimIndent(),
    width = 1040,
)
