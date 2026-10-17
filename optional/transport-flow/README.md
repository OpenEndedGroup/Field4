# Stipple transport / transport-space video

An experimental offline PyTorch facility and a portable Field CPU consumer. One
small SiLU network learns a velocity in **transport time**, optionally conditioned
on a distinct **movie time**. All training and inference use normalized coordinates
with y pointing down. An image's longest dimension spans `[-1,1]`; aspect ratio is
preserved. The default canonical source is the entire `[-1,1]²` rectangle.

From the repository root:

```sh
uv run --project optional/transport-flow python optional/transport-flow/run.py demo --movie
uv run --project optional/transport-flow python -m unittest discover -s optional/transport-flow -v
```

The optional project has its own lockfile/environment; the root agent/docs project
does not install Torch. CPU works with `--device cpu`; CUDA is selected when available.
MP4 generation and video input require `ffmpeg` (`ffprobe` also for video input).
On a storage-constrained checkout, set `UV_CACHE_DIR` and `UV_PROJECT_ENVIRONMENT`
to a volume with sufficient space before running uv; the default Linux Torch
distribution includes several GB of CUDA dependencies.

Train from an image, ordered images, a short video or point arrays:

```sh
uv run --project optional/transport-flow python optional/transport-flow/run.py train \
  --images drawing.png --sigma .02 --coupling bsp --out build/my-stipple
uv run --project optional/transport-flow python optional/transport-flow/run.py train \
  --images frame-000.png frame-001.png frame-002.png --temporal-strength .002 --movie
uv run --project optional/transport-flow python optional/transport-flow/run.py train \
  --video clip.mp4 --target-frames 16 --count 4096 --movie
uv run --project optional/transport-flow python optional/transport-flow/run.py train \
  --points points.npy --source-half-extent 1.2 .8
```

Point arrays are `(N,2)` or `(frames,N,2)`, already in your chosen normalized frame.
The library accepts frames with different sample counts. Image sampling is weighted
by darkness (or `--density brightness`), with `--gamma` altering that scalar mass.
It samples with replacement and subpixel jitter: it is stochastic stippling, not
blue noise. Target `--sigma` is additional isotropic Gaussian training jitter,
measured in normalized coordinates. It is not clipped to the image rectangle.
No image mass (e.g. an all-white image in darkness mode) is an explicit error.

## Controls and outputs

`run.py --help` lists count, sigma, source extent, random/BSP coupling, network
width/depth, training steps/batch, integration steps, temporal regularization and
finite-difference spacing, source random/grid arrangement, playback reverse and
overshoot. Library queries accept arbitrary transport/movie times, including
negative values and values beyond one. Grid particle counts that are perfect
squares cover a complete rectangular lattice; other counts omit the final cells.

Each training job writes `.pt` (reload/fine-tune), `.json` (portable Field model),
`.png` (particles, lattice, trajectories, velocity) and `measurements.json`.
Temporal jobs also write a sampled velocity `.npz`, and `--movie` writes four
slices through `(transport_time,movie_time)`: full formation, half formation,
diagonal traversal and fixed-movie formation/retreat. Colors belong to canonical
particles; they are not recomputed from current position.

The synthetic demo also saves a `source-density.png` poster and, with `--movie`, the original
`source-density.mp4` at four frames per second for comparison with learned transport.

```python
from transport_flow import Options, source, stipple, train, TransportFlow

frames = [stipple("a.png", 4096), stipple("b.png", 4096)]
model = train(frames, Options(sigma=.03, coupling="bsp"), device="cuda")
canonical = source(10000, seed=12)  # retain this once for the entire movie
xy = model.transport(canonical, transport_time=.6, movie_time=.35, steps=32)
v = model.velocity(xy, transport_time=.6, movie_time=.35)
paths = model.trajectories(canonical[:100], movie_time=.35)
model.save("sheet.pt")
model = TransportFlow.load("sheet.pt", device="cpu")
model.export("sheet.json")
model.bake("velocities.npz", resolution=64)
```

`transport`/`velocity` batch large point sets; `trajectories` stores every step
and should use fewer particles when memory matters. Midpoint is the default;
Euler and RK4 are available in Python. To reverse a transported point set, use
`start_time=1, transport_time=0` at the same movie condition. To play the movie,
evaluate **canonical coordinates anew** at each movie condition. Advecting last
frame's output in movie time is a different operation.

## Coupling and regularization

Random pairing implements the elementary linear conditional-flow-matching loss.
BSP recursively sorts each minibatch into equal-mass spatial halves and pairs the
leaves, preserving both marginals. It is approximate spatial coupling, not an
optimal-transport solver. It often reduces travel and turning, but can introduce
axis-dependent correspondences. There is no frame-to-frame target assignment.

One frame is sampled per training minibatch, at its discrete movie condition.
Intermediate movie conditions are never interpolated target supervision. Optional
regularization penalizes a finite-difference approximation to `∂v/∂movie_time`
at shared spatial coordinates and transport times. It can suppress useful motion
and blur reconstruction. Shared conditioning alone guarantees neither good
intermediate densities nor physically meaningful correspondence. Density matching,
transport trajectory turning and movie trajectory stability are separate metrics.

The side experiment `demo --unsafe-weights` compares interpolation between a
static C checkpoint and an S model fine-tuned from it, versus an independently
initialized S model. It is explicitly unsafe and separate from movie conditioning.
Checkpoint blending checks architecture/domain compatibility, not neuron alignment.

## Portable format and Field

`field.transport-flow.v1` JSON carries `temporal`, `widths`, `sourceHalfExtent`,
`frameTimes`, `coordinates="normalized-y-down"`, `activation="silu"` and flat
`weights`. Inputs are raw `(x,y,t)` or `(x,y,t,tau)`, without Fourier encoding or
hidden centering/scaling. Each layer stores its `(outputs,inputs)` row-major
weights then its output biases. Every hidden layer uses `x/(1+exp(-x))`; the
output is linear. `training` records options but is not required for inference.

Field's `loadStippleTransport` consumes this JSON without Torch. Use
[examples/stipple_sheet.kt](examples/stipple_sheet.kt) in a Field box for live
transport/movie sliders and a deformed lattice. The supplied model lives in
`public/docs/assets/stipple_transport/two-images.json`; see the published
[stipple transport page](../../public/docs/stipple_transport.md) for captured results.
CPU inference is for moderate geometry counts. Native GPU MLP evaluation is deferred.

Baked NPZ stores `velocity[movie_index,transport_index,y_index,x_index,xy]`,
plus axes `x`, `y`, `transport_times`, `movie_times`. It contains **velocity**, not
deformed coordinates. Consumers must interpolate samples and integrate with movie
time fixed, handle out-of-bounds paths and choose a lattice/time resolution that
resolves their field. This is an export bridge; no Field texture loader is claimed.

The small cross-framework oracle can be explicitly regenerated with:

```sh
uv run --project optional/transport-flow python optional/transport-flow/write_parity_fixture.py \
  src/jvmTest/resources/transport-flow-parity.json
./gradlew jvmTest --tests afield.geometry.spatial.StippleTransportTest --tests afield.ai.learning.FlowMatchingTest
```
