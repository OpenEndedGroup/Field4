# Local voice input for AI4

AI4 push-to-talk sends recorded audio to a local Python Whisper worker. Install its optional
packages from the Field checkout with `uv sync --inexact --group voice`. `--inexact` retains
other groups already installed in the environment. Select that interpreter in Field's Python
configuration, or set `FIELD_VOICE_PYTHON` to its executable before launching Field.

Apple silicon uses `mlx-whisper`; other platforms use `faster-whisper`, trying CUDA and falling
back to CPU if it cannot execute there. The worker's GPU route needs CUDA 12 and cuDNN 9 runtimes.
It reports its selected backend and a fallback note; finding a GPU alone is not proof that
inference works. No FFmpeg executable is needed: the worker receives float32 audio samples.

Weights download on the first use. Defaults are `large-v3-turbo` on GPU and `small.en` on CPU;
`FIELD_VOICE_MODEL` selects another model. Allow disk space and time for its download and warm-up.
The worker emits a `ready` result only after running a warm-up transcription. A package install
or first-run selection does not perform that inference check.

Allow microphone access when the app asks, then try a short clip in AI4 and check its returned
transcript/error. Speech recognition runs locally; using its text in an AI conversation sends
that text to the configured provider. See [Field's trust page](../../public/docs/privacy.md).

Implementation: [whisper_worker.py](whisper_worker.py), launched by
`src/jvmMain/kotlin/afield/ai/core/VoiceTranscriber.kt`.
