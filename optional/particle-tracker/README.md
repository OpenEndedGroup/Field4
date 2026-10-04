# Optional video particle tracker

Bidirectional pretrained Torchvision RAFT-large supplies dense flow to a C++17 particle engine.
Kotlin launches cached jobs, reads `PVTRACK1` tracks without Python, scrubs trajectories and renders
sampled-color particles. Nothing here is installed or downloaded by Field startup, compilation or
ordinary tests. See [the public walkthrough](../../public/docs/particle_video.md).

Prepare an isolated Python environment explicitly (Python 3.10–3.12, preferably 3.11):

```sh
uv sync --project optional/particle-tracker --python 3.11 --frozen
```

The lock pins Torch 2.6.0, Torchvision 0.21.0 and NumPy 2.2.6. Linux x86-64 uses the CUDA 12.4 wheel
index; allow several GB for a fresh environment. Apple Silicon macOS resolves the matching PyPI packages; these pins have no Intel Mac wheels. The
launcher supports explicit `cpu`, `cuda[:index]` and `mps` devices; the full example has been validated on Linux/RTX 4090 CUDA, plus a short Linux CPU inference smoke.
Mac/MPS execution is unverified. Windows use requires WSL: the optional cache locks and process cleanup use POSIX.
Reading and drawing exported files use ordinary JVM APIs and require none of those neural tools.

Alternatively, set `FIELD_PARTICLE_TRACKER_PYTHON` to an existing Python executable with matching
CUDA-enabled Torch/Torchvision. This was the validation path on the development machine and avoids
duplicating several GB of packages. FFmpeg, FFprobe and `c++` must be on PATH. The C++ engine is
compiled into the shared cache on first use (OpenMP on Linux; serial on macOS), not into this tree.
Set `CXX` to select a compiler executable. No OpenCV dependency is needed.

The equivalent command-line launcher is:

```sh
optional/particle-tracker/.venv/bin/python optional/particle-tracker/cached_track.py \
  --input movie.mp4 --options '{"particles":24000,"color_limit":1.0}'
```

`width=0` means native video resolution. The complete defaults are in `cached_track.DEFAULTS` and
match `ParticleTrackingOptions`. Use `--offline` after the pretrained checkpoint is cached, or
`--force` to recompute. The approximately 21 MB RAFT-large checkpoint is downloaded on first use into
Torch's normal model cache; it is not included in Git. Downloads obey `TORCH_HOME`.

The job cache defaults to `~/.afield/particle-tracker`. Successful runs have `tracks.pvt`,
`tracks.pvt.run.json`, `run.log` and a checksummed `manifest.json`. Keys include video SHA-256, every
tracking/inference option, source digests, compiler/flags, FFmpeg, Python/Torch/Torchvision versions,
device identity and checkpoint SHA-256. Source paths are provenance, not part of the content key.
Changing a file in place invalidates its request. Identical concurrent requests share a file lock.
Failed/cancelled staging directories are cleaned; existing successful entries survive failed forced
runs. Corrupt cached tracks/metadata are recomputed. Cache size is not capped: dense runs can be
large, so remove old run directories when finished. The 24,000-particle/300-frame validation run is
approximately 575 MB; the Kotlin reader memory-maps it and decodes only queried frames.

Kotlin can override tools, interpreter and cache explicitly in `particleTracker(...)` or through
`FIELD_PARTICLE_TRACKER_TOOLS`, `FIELD_PARTICLE_TRACKER_PYTHON`, `FIELD_PARTICLE_TRACKER_CACHE` and the
corresponding `afield.particleTracker.{tools,python,cache}` system properties.

Validation:

```sh
python -m unittest discover -s optional/particle-tracker -v
make -C optional/particle-tracker/engine test
./gradlew jvmTest --tests afield.stdlib.ParticleVideoTest
```

The explicit `afield.examples.video.ParticleTrackerSmoke` JVM entrypoint takes `VIDEO OUTPUT_DIR`.
Run it through `tools/field_backdoor.py run-jvm-main` with the selected Python environment. It
executes real CUDA inference, repeats the request offline, checks Python/Kotlin record and lifecycle
parity, exports Kotlin-rendered media, and cancels another real job. It is intentionally outside the
automatic portable smoke catalog, because it needs the prepared optional environment and a GPU.
`tools/export_particle_video_docs.py` publishes those verified media and a compressed representative
subset of the same tracks for the portable Starting point.

## Provenance

The engine and `neural_track.py`/`pvt.py` were recovered from source-writing commands embedded in
[the user's shared tracker conversation](https://chatgpt.com/share/6ac2b442-dda4-83eb-a7c6-3923754dab9b).
The original sandbox ZIP was unavailable as a public URL. `engine/SOURCE.json` records the imported
snapshot hashes. Field adds the cache launcher and Kotlin integration; the original tracking
algorithm is retained. Torch/Torchvision and pretrained weights retain their respective upstream
terms; weights and Python environments are not vendored.
