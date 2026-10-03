"""Model files for the mediapipe detectors: fetched once, checked, and cached outside the repo.

Nothing here is committed. The first detector that needs a model downloads it from Google's
versioned model bucket into the cache directory and checks it against a pinned SHA-256, so a
truncated download or a silently changed upstream file fails loudly instead of loading garbage.

The cache is ``$FIELDCV_MODEL_DIR`` if set, otherwise ``~/.afield/models/fieldcv``. To work offline,
copy the files there by hand; a file that is already present and matches its hash is never fetched.
"""

from __future__ import annotations

import hashlib
import os
import urllib.request
from dataclasses import dataclass
from pathlib import Path

_BUCKET = "https://storage.googleapis.com/mediapipe-models"


@dataclass(frozen=True)
class Model:
    filename: str
    url: str
    sha256: str


MODELS: dict[str, Model] = {
    "hand": Model(
        "hand_landmarker.task",
        f"{_BUCKET}/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task",
        "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1",
    ),
    "face": Model(
        "face_landmarker.task",
        f"{_BUCKET}/face_landmarker/face_landmarker/float16/1/face_landmarker.task",
        "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff",
    ),
    "pose_heavy": Model(
        "pose_landmarker_heavy.task",
        f"{_BUCKET}/pose_landmarker/pose_landmarker_heavy/float16/1/pose_landmarker_heavy.task",
        "64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b",
    ),
    "pose_lite": Model(
        "pose_landmarker_lite.task",
        f"{_BUCKET}/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task",
        "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a",
    ),
    "face_detector": Model(
        "blaze_face_short_range.tflite",
        f"{_BUCKET}/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite",
        "b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f",
    ),
}


def model_dir() -> Path:
    configured = os.environ.get("FIELDCV_MODEL_DIR")
    return Path(configured).expanduser() if configured else Path.home() / ".afield" / "models" / "fieldcv"


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def model_path(key: str) -> str:
    """The local path of model ``key`` (one of :data:`MODELS`), downloading it if it is missing."""
    try:
        model = MODELS[key]
    except KeyError:
        raise KeyError(f"no model called {key!r}; known models are {', '.join(sorted(MODELS))}") from None

    path = model_dir() / model.filename
    if path.exists():
        if _sha256(path) == model.sha256:
            return str(path)
        raise RuntimeError(
            f"{path} does not match the pinned SHA-256 for {key!r}; delete it to re-download"
        )

    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_suffix(path.suffix + ".partial")
    urllib.request.urlretrieve(model.url, partial)
    actual = _sha256(partial)
    if actual != model.sha256:
        partial.unlink(missing_ok=True)
        raise RuntimeError(f"downloaded {model.url} has SHA-256 {actual}, expected {model.sha256}")
    partial.replace(path)
    return str(path)
