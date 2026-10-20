# ARDY and CLoSD setup

Install either backend to generate movement locally from Field. ARDY and CLoSD use separate
Python environments and worker processes. Neither is needed to open Field or replay a recording.

For the controls, Kotlin examples and movies, start with
[Generative Mocap — ARDY and CLoSD](../../public/docs/motion_recordings.md).
The [recorded ARDY demo](../../public/docs/agent_transcripts.md#motion_ardy_demo_replay) and
[CLoSD resistance/release](../../public/docs/agent_transcripts.md#motion_resistance_replay)
run without installing a model. Use this guide when you are ready to run a live backend.

## Install ARDY

Choose a development directory outside Field. Each network gets its own source checkout and
environment. From Field's root, fetch and inspect the pinned ARDY revision:

```bash
python3 optional/motion/prepare.py fetch ardy --directory /path/to/mocap
uv venv --python 3.11 /path/to/mocap/environments/ardy
python3 optional/motion/prepare.py probe ardy --directory /path/to/mocap --network
```

The probe exits 2 when a prerequisite is missing; `prerequisitesReady` never means inference was
validated. It reports source pins, interpreter/import status, GPU occupancy and free disk space.
`--network` checks the cached Hugging Face credential without printing it or downloading weights.
The probe does not install packages or accept licenses.

ARDY needs access to [Meta-Llama-3-8B-Instruct](https://huggingface.co/meta-llama/Meta-Llama-3-8B-Instruct).
Request access using the Hugging Face account used by the local token. Its
[upstream setup](https://github.com/nv-tlabs/ardy/tree/e706e27acc88070918308a10922b8f7174d0825e#setup)
installs a matching CUDA build of Torch before `ardy[demo]`. Reproduce
`scripts/generate.py` with `--model core --seed 0 --no-postprocess`, then play its NPZ with
`scripts/visualize.py`. Record failures as well as outputs. The selected Core model has 27 joints,
a 20 Hz sample rate and a 40-frame generation window; its exact source/checkpoint revisions are in
[upstreams.json](upstreams.json). Do not download all Llama repository formats: the text encoder
uses LLM2Vec/PEFT and resolves the necessary base-model files itself. Check available disk first.

Install and start ARDY after resolving its gated-model access:

```bash
MOTION_DIR=/path/to/mocap
uv pip install --python "$MOTION_DIR/environments/ardy/bin/python" -e "$MOTION_DIR/upstream/ardy[demo]"
# Needs about 16.4 GB for the encoder/adapters plus 765 MB for Core; use persistent storage.
ENCODER_CACHE=/path/to/a/large/huggingface-cache
"$MOTION_DIR/environments/ardy/bin/python" optional/motion/assets.py --backend ardy --encoder-cache "$ENCODER_CACHE"
python3 optional/motion/encoder.py --home "$MOTION_DIR" --cache "$ENCODER_CACHE"
# In another terminal, from Field's checkout:
FIELD_MOTION_HOME="$MOTION_DIR" ./field.sh
```

`encoder.py` runs the unchanged upstream encoder on CPU and binds loopback only. Both the
construction-time environment setting and upstream `--device cpu` are necessary. Returned files
use upstream's allowed temporary directory. Tested package snapshots are in `environments/*-tested.txt`;
keep separate environments and capture your resolved packages. The encoder and selected weights
need substantial RAM/disk space; native driver/platform compatibility still needs a local smoke.
On this development machine, three Llama shards currently use temporary `/dev/shm` storage because
the main disk lacks room. That cache must move to persistent storage for availability after reboot.
Cached prompt embeddings are small `.npy` files in `$MOTION_DIR/embeddings`, keyed by encoder
family/revision and prompt. They survive encoder shutdown. A new uncached prompt needs the service;
Field inference never falls back to silently loading Llama into its JVM or motion worker's GPU.

Open [paths](../../public/docs/agent_transcripts.md#motion_ardy_path),
[intentions](../../public/docs/agent_transcripts.md#motion_ardy_intentions), or
[foot restriction](../../public/docs/agent_transcripts.md#motion_ardy_foot).
`ARDY.openAsync()` returns the shared session facade. Its four-frame token commits are 0.2 s at
20 Hz; `advance` rejects partial tokens. A 40-frame generated future remains editable and is not
silently queued for display. Prompt/path/target changes discard uncommitted future, preserve
committed poses and regenerate from bounded history (at most 160 frames for this checkpoint).
Global joint position conditions require a root plan covering the target interval in this pinned
representation. Root paths use XZ metres and optional heading; target quaternions are global xyzw.
No postprocessing, body-joint overwrite, native soft weights or physical attachments are advertised.

To reproduce short trials and the retained vocabulary:

```bash
"$MOTION_DIR/environments/ardy/bin/python" optional/motion/ardy_trial.py --home "$MOTION_DIR" --mode foot --output /fresh/path/ardy-foot.f4motion
"$MOTION_DIR/environments/ardy/bin/python" optional/motion/evaluate_ardy.py --home "$MOTION_DIR" --output /fresh/path/vocabulary
```

The [verification report](VERIFICATION.md) includes the raw constraint residuals, weak walking
samples, ground penetration and late-window degradation. Completing generation is not an action
recognition score. `import_ardy.py` converts verified Core27 NPZ exports without pickle; SOMA
reduced-to-expanded export needs a separate explicit mapping and is not inferred here.

Prepare CLoSD separately:

```bash
python3 optional/motion/prepare.py fetch closd --directory /path/to/mocap
uv venv --python 3.8 /path/to/mocap/environments/closd
ISAAC_GYM_ROOT=/path/to/isaacgym python3 optional/motion/prepare.py probe closd --directory /path/to/mocap
```

Obtain [Isaac Gym Preview 4](https://developer.nvidia.com/isaac-gym) separately through NVIDIA
Developer. CLoSD uses this legacy simulator. Isaac Sim and Isaac Lab are different environments.
Follow the pinned [CLoSD setup](https://github.com/GuyTevet/CLoSD/tree/d8950bdb78312fcd2844f336b3fc079e7368c87f#getting-started)
in the Python 3.8 environment, including the SDK's `python` package and spaCy English model.
`environments/closd-uv.toml` supplies the undeclared `pip` build dependency needed by `chumpy==0.70`
when resolving with uv. The full environment and unmodified simulation have run on CachyOS/RTX 4090. It does not change CLoSD's package pins.
Import Isaac Gym before Torch. Start with one environment and an unmodified upstream task.

A reproducible CLoSD installation, after extracting the separately licensed SDK:

```bash
MOTION_DIR=/path/to/mocap
ISAAC_GYM_ROOT=/path/to/isaacgym
uv pip compile --config-file optional/motion/environments/closd-uv.toml --python "$MOTION_DIR/environments/closd/bin/python" "$MOTION_DIR/upstream/CLoSD/requirement.txt" --output-file "$MOTION_DIR/closd-resolved.txt"
uv pip install --python "$MOTION_DIR/environments/closd/bin/python" --config-file optional/motion/environments/closd-uv.toml --no-build-isolation-package pytorch3d -r "$MOTION_DIR/closd-resolved.txt"
uv pip install --python "$MOTION_DIR/environments/closd/bin/python" -c "$MOTION_DIR/closd-resolved.txt" -e "$ISAAC_GYM_ROOT/python"
# About 4.7 GB runtime assets, excluding upstream training data and optimizer state.
"$MOTION_DIR/environments/closd/bin/python" optional/motion/assets.py
FIELD_MOTION_HOME="$MOTION_DIR" ./field.sh
```

Capture the resolved package list for your working host. For direct Python invocations, Isaac
Gym needs the real Python 3.8 interpreter's `lib` directory on `LD_LIBRARY_PATH`; Field's launcher
sets it automatically. Native workers run with `HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1`:
`assets.py` pins the cache reference requested by stock CLoSD. It never downloads the SDK or
changes upstream code.

Open [recorded CLoSD](../../public/docs/agent_transcripts.md#motion_closd_recording) with no model
installed, or [live CLoSD](../../public/docs/agent_transcripts.md#motion_closd_live) after setup.
The live scene records each performance and exposes body names, attachment coefficients,
break/release, torso impulse, prompt changes and manual reset. Its worker follows stock auto-reset
and logs every reset; a reset is never a recovery demonstration.

First-run setup (`./field.sh --setup`) lists ARDY and CLoSD under Workers and models on
Linux/NVIDIA. Selecting them shows these separate recipes; it does not download SDKs, models or
start services. Ordinary Field startup has no motion-backend dependency.

## Kotlin recording and playback

Load an existing recording and sample it at a session time:

```kotlin
import afield.animation.mocap.*
import java.nio.file.Path

val recording = MotionRecording(Path.of("performance.f4motion"))
val clip = recording.clip() // Explicitly loads all frames; frames() streams long recordings.
val snapshot = clip.sample(1.5)
val bodyLines = snapshot.frame.executed.bones(clip.skeleton)
```

`bodyLines` is an ordinary 3D FLine. Use your Field camera and drawing module. `MotionSkeleton`
contains runtime joint names, parent indices, rest translations/rotations, source coordinates and
a cross-language SHA-256 hash. `sceneSkeleton()` returns Field's existing glTF hierarchy.
`joint(name)` and `body(name)` resolve distinct mappings; a display-joint index never selects a
force-application body.

`FLine.motionRoute(startTime, duration, count, headings, distanceFraction)` uses the existing
arc-length cursor. Its monotone distance envelope supplies timing separately from geometry;
headings are radians from +Z towards +X. This samples a requested route; it does not generate
motion or guarantee that a model can follow it.

`MotionSession` runs a `MotionBackend` on one daemon thread, owns it through Field's existing
RunScope, and exposes prompt/path/joint-target/physical-intervention methods with capability
checks. The replay backend supports `advance` and rejects generation/intervention operations. `CLoSD.openAsync()` creates the native process session after validating its pinned source and runtime skeleton. CLoSD advertises prompt, attachment/update/detach, impulse, reset and advance; root plans and kinematic joint targets are rejected.

`advance(seconds)` returns a future. Read `buffer.sample(time)` on the display thread; do not wait
there. The default live buffer holds at most eight frames and .4 seconds, drops old frames and
counts drops. It holds the last available pose with explicit lag, uses Field's shortest-arc
quaternion interpolation and holds across episode boundaries. Offline `MotionClip` keeps the
complete recording. Path/target edits coalesce before the next ordered event; attach/detach,
impulse, reset and advance operations retain order. A full command queue rejects submission.

Use `record(directory, configuration)` to retain session frames and requested/applied events.
Include seed, dependency/commit and checkpoint revisions in configuration. Call
`markVisible(frame)` **when the renderer actually displays that frame** to measure command-to-visible
delay; the supplied asynchronous Three viewer scenes do not claim presentation timing from their draw-call submission. In particular, receiving a frame does not count as visibility. `subscribe` callbacks run on the worker
thread, return closeable registrations, and are released with the session.

`JointTarget` records kinematic conditioning. `MotionAttachment` records a physical point tether
with stiffness, damping, engagement ramp, force cap and optional sustained break criteria.
`pointTether` calculates point velocity, capped force, COM torque and mechanical power; it does
not apply forces or validate a simulator. Both centred and offset-COM Isaac Gym GPU probes verify those semantics and one-step force lifetime. Real humanoid trials verify capped tethering, release, breaking and an impulse; this does not establish arbitrary recovery.

## Portable recordings and checks

The [recording format](protocol.md) uses JSON metadata/events and chunked float32 poses. No
pickle/object payload is accepted. Replay reads the stored poses and does not rerun a model.
The Python reader needs only the standard library:

```bash
PYTHONPATH=optional/motion/python python3 -m field_motion.cli performance.f4motion --benchmark
python3 -m unittest discover -s optional/motion/tests -v
./gradlew jvmTest --tests afield.animation.mocap.MotionTest
uv run --project . --group backdoor python tools/run_field_tests.py --id motion.recording.interop
```

The benchmark measures serialization only. It makes no inference, transport or GPU-latency claim.
The interop smoke writes a Kotlin recording, reads/rechunks it in Python, and compares every
returned pose in Kotlin. Consequential unit checks cover coordinates/reflections, skeleton
topology/hash, normalized quaternions, reset interpolation, bounded buffers/queues, event order,
corrupt recordings, body mapping and point-force torque/velocity.

## Compose a performance

The authoring extensions stay on the existing `MotionSession`: `prompt`, `route`, named `body`
and `joint` handles, and `playMotion` from a Field `frames` coroutine. `awaitMotion()` suspends
without blocking the runloop. See [the Kotlin score](examples/tether_score.kt) and
[the live instrument](examples/live_closd.kt). A body tether returns a handle with `tune`, `moveTo`,
`release`, a scheduling acknowledgement and actual `observation` telemetry. Runtime body-local
points use Field's existing quaternion hierarchy. Reset/break events retire the observed connection;
a retired handle's release is harmless. Reattach before tuning it again.

`MotionSession.replay(recording)` replays stored interventions and reset events as well as poses;
no model is run. `playMotion` rounds a requested duration to complete model/control steps and clamps
recorded playback at its end. The existing `Three` viewer can follow a point with a browser-side
exponential filter: `followCamera(point, halfLifeSeconds=.4, epoch=episode.toString())`.
`showMotion(..., follow=true)` supplies the executed root and episode automatically. Eye and orbit
target translate together, preserving orbit/pan/zoom; horizontal tracking suppresses jump bob.

The live instrument exposes Drag (150/15/150) and Resist (8000/120/2500) presets in stiffness N/m,
damping N s/m and maximum force N. These are compliant capped springs, not fixed joints. The
stronger seed-0 two-second trial has 4 cm mean / 13 cm maximum raw anchor error. A longer trial
attached from 0.1 s has 4.2 cm mean error while active, then the controller terminates and resets;
this is a useful failure, not recovery. `trial.py --attach-time .1 --release-time 8 --duration 9`
reproduces that timing; stiffness, damping and cap remain explicit options. Its summary includes
raw mean/max/final tether residuals. The interactive scene uses a 60-second episode limit with
stock early termination still enabled; every reset releases attachments and is visible in telemetry.

Playable Field movies and the new replay/score starting points are on the
[public motion page](../../public/docs/motion_recordings.md).
