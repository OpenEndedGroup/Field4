"""Persistent Whisper worker behind AI4 push-to-talk.

Field (`afield.ai.models.VoiceTranscriber`) starts this once and keeps it alive, so the model loads a
single time. The protocol is one JSON object per line in each direction:

    -> {"id": 1, "pcm": "/tmp/clip.f32", "sampleRate": 16000, "prompt": "fromContext, Renderer"}
    <- {"id": 1, "ok": true, "text": "...", "millis": 412, "backend": "mlx-whisper"}

`pcm` is raw little-endian float32 mono at 16 kHz, which both backends take as an array, so no
ffmpeg is needed. `prompt` is Whisper's initial prompt: the identifiers the conversation is using,
which is what turns "from context" into `fromContext`.

Backends, first that imports wins: `mlx-whisper` (Apple silicon GPU), then `faster-whisper` (CUDA,
else CPU). Install with `uv sync --inexact --group voice`. The model is `large-v3-turbo` on a GPU and
`small.en` on a CPU, unless `FIELD_VOICE_MODEL` names another.

On startup the worker prints `{"ready": true, ...}` or `{"ready": false, "error": ...}`.
"""

from __future__ import annotations

import json
import os
import sys
import time


def _emit(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


def _load_backend():
    model = os.environ.get("FIELD_VOICE_MODEL", "").strip()
    errors = []
    try:
        import mlx_whisper  # type: ignore

        repo = model or "mlx-community/whisper-large-v3-turbo"

        def run(audio, prompt):
            result = mlx_whisper.transcribe(
                audio,
                path_or_hf_repo=repo,
                language="en",
                initial_prompt=prompt or None,
                condition_on_previous_text=False,
                verbose=None,
            )
            return result.get("text", "")

        return "mlx-whisper", repo, run, ""
    except Exception as e:  # ImportError, or a broken install
        errors.append(f"mlx-whisper: {e}")
    try:
        from faster_whisper import WhisperModel  # type: ignore
    except Exception as e:
        errors.append(f"faster-whisper: {e}")
    else:
        return _faster_whisper(WhisperModel, model)
    raise RuntimeError(
        "no Whisper backend is installed (" + "; ".join(errors) + "). "
        "Run `uv sync --inexact --group voice` in the venv Field uses."
    )


def _cuda_devices():
    try:
        import ctranslate2  # type: ignore

        return ctranslate2.get_cuda_device_count()
    except Exception:
        return 0


def _faster_whisper(WhisperModel, model):
    """CUDA runs large-v3-turbo fast enough for push-to-talk; a CPU does not, so without a GPU the
    default drops to small.en. When CUDA is present but its runtime libraries are not (CUDA 12 and
    cuDNN 9), the GPU attempt fails at load or on the first clip, so it is tried with a warm-up and
    the worker falls back to the CPU with a note instead of failing outright."""
    import numpy as np

    def make(name, device, compute):
        whisper = WhisperModel(name, device=device, compute_type=compute)

        def run(audio, prompt):
            segments, _info = whisper.transcribe(
                audio,
                language="en",
                initial_prompt=prompt or None,
                condition_on_previous_text=False,
                vad_filter=True,
            )
            return "".join(s.text for s in segments)

        run(np.zeros(16000, dtype=np.float32), "")
        return run

    note = ""
    if _cuda_devices() > 0:
        name = model or "large-v3-turbo"
        try:
            return "faster-whisper (cuda)", name, make(name, "cuda", "float16"), ""
        except Exception as e:
            note = (f"CUDA was found but could not run Whisper ({e}); using the CPU. "
                    "Install the CUDA 12 and cuDNN 9 runtimes to use the GPU.")
    name = model or "small.en"
    return "faster-whisper (cpu)", name, make(name, "cpu", "int8"), note


def main():
    try:
        import numpy as np

        backend, model, run, note = _load_backend()
        # Warm up so the first real clip doesn't pay for the model download and graph build.
        run(np.zeros(16000, dtype=np.float32), "")
    except Exception as e:
        _emit({"ready": False, "error": str(e)})
        return 1
    _emit({"ready": True, "backend": backend, "model": model, "note": note})

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        request_id = None
        try:
            request = json.loads(line)
            request_id = request.get("id")
            audio = np.fromfile(request["pcm"], dtype="<f4")
            rate = int(request.get("sampleRate", 16000))
            if rate != 16000:
                # Linear resample; the browser is asked for 16 kHz, so this is the rare path.
                n = int(round(len(audio) * 16000 / rate))
                audio = np.interp(np.linspace(0, len(audio) - 1, n), np.arange(len(audio)), audio).astype(np.float32)
            started = time.perf_counter()
            text = run(audio, request.get("prompt", "")).strip()
            _emit({
                "id": request_id,
                "ok": True,
                "text": text,
                "millis": int((time.perf_counter() - started) * 1000),
                "backend": backend,
            })
        except Exception as e:
            _emit({"id": request_id, "ok": False, "error": str(e)})
    return 0


if __name__ == "__main__":
    sys.exit(main())
