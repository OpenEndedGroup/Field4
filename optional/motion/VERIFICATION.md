# Motion integration evidence — 20 October 2026

CLoSD has completed unmodified upstream inference and real physical intervention through the
Field process protocol. ARDY has also completed unmodified generation, browser playback and its real Kotlin GPU smoke.
This report separates actual simulator results from the unfinished proposal experiments.

## Environment and provenance

CachyOS Linux x86-64, kernel 7.1.3, glibc 2.43, RTX 4090, NVIDIA driver 610.43.02. Existing unrelated
GPU processes occupied about 20.4 GB during the short trials and browser checks; they were left running.
The user subsequently provided a free GPU window for final verification. Python environments are
separate. Source, checkpoint, DistilBERT and Isaac archive pins are in `upstreams.json`.

CLoSD: Python 3.8.20, Torch 2.2.2+cu121, Isaac Gym Preview 4. The stock dependency recipe resolves
with the build-only `pip` declaration for chumpy in `environments/closd-uv.toml`. The SDK imports
and gymtorch C++ extension builds. A selective runtime asset download avoids training sets and
optimizer state (about 4.6 GB rather than upstream's 19.3 GB snapshot). The unchanged upstream
headless text-to-motion task completed one episode: reward 166.95657348632812, 299 steps.

ARDY: Python 3.11.15, Torch 2.14.1+cu130, Core27/20 Hz/horizon40. The exact gated
Meta-Llama-3-8B-Instruct access check returns 200. The unchanged upstream CPU encoder service and
`scripts/generate.py` produced five seconds at seed 0 / ten denoising steps with postprocessing
disabled. The unchanged browser viewer rendered the actual skinned character and playback controls.
The first walking sample travelled only 0.209 m; successful inference is not useful locomotion by
itself. Three Llama shards currently use temporary `/dev/shm` storage because disk space is low;
move this cache to persistent storage for use after reboot. Both source checkouts remain unchanged.

## Real physics and native integration checks

* Isaac Gym free 1 kg cube, zero gravity, dt=1/120 s: a 1 N force gives delta velocity
  0.00833333377 m/s. A 0.1 m lever gives delta angular velocity 0.125 rad/s, matching torque/inertia.
  The following unforced step has zero additional acceleration: force lifetime is one step.
* An offset-COM variant gives the expected 0.0625 rad/s angular increment, confirming COM torque
  and that rigid-body state position is body origin while linear velocity is COM velocity.
* The ARDY Kotlin GPU smoke validates its 27-joint skeleton, timed root path, sparse LeftFoot target, unsupported physical-operation rejection, reset and 20-frame recording replay.
* The actual Kotlin GPU smoke opens CLoSD asynchronously, validates its 24-joint runtime skeleton,
  receives position-only reference and executed poses, schedules a prompt, advances, tethers,
  releases and applies one impulse. Thirty frames round-trip through recording exactly.
* An independent Kotlin/Python interoperability smoke writes 81 frames in Kotlin, rechunks in
  Python and compares all returned pose floats. Neither model is needed for this check.
* Eleven focused Kotlin tests cover basis/reflection conversion, quaternion interpolation, topology/hash,
  reset boundaries, bounded queues, event ordering, lifecycle and point-force math. Seven Python
  recording tests cover corruption, incomplete writes, ordering and unavailable reference rotations.
* All 33 first-run setup tests pass. Its existing stale wordmark source path had to be repaired.

The adapter leaves the stock planner, policy and executed-prefix feedback intact. It interposes
on the player run loop and immediately before Gym simulate; upstream source files are unmodified.
Upstream force submissions are accumulated with tether/impulse forces. Each planner event reports
an executed-prefix residual; all recorded trials below report 0.0 m. Physics submission time is
labelled separately from actual GPU physics duration. CLoSD reference rotations are unavailable;
the recording does not fabricate them.

## Short paired CLoSD trials

Each clip is 6 seconds / 180 executed frames at 30 Hz. Seed 0, 1 and 2 were matched across baseline,
weak tether, stronger capped tether, breakable tether and torso impulse. Additional exploratory
seed-0 clips cover a wrist, moving anchor, informed prompt, stronger stiffness and smaller timestep.
The tether engages at 2 seconds with an anchor at the current attachment point and releases at
4 seconds unless already broken. Ramping, force caps and actual per-step forces are recorded.
No trial here terminated/reset or produced a non-finite pose. Failed early adapter samples remain
in the development directory with incomplete markers; they are not included as completed trials.

| Seed-0 mode | Mean tracking error (m) | Peak tether force (N) | Break events |
| --- | ---: | ---: | ---: |
| Baseline | 0.0438 | 0.0 | 0 |
| Weak foot | 0.0436 | 40.0 | 0 |
| Capped foot | 0.0469 | 150.0 | 0 |
| Breakable foot | 0.0438 | 54.6 | 1 |
| Informed prompt | 0.0412 | 59.4 | 0 |
| Wrist | 0.0453 | 27.0 | 0 |
| Moving anchor | 0.0437 | 39.3 | 0 |
| Stronger foot | 0.0570 | 312.4 | 0 |
| Stiff foot | 0.0565 | 769.1 | 0 |
| Stiff foot, half dt | 0.0660 | 800.0 | 0 |

Typical warmed 0.1-second advance calls took about 41–44 ms median and 44–49 ms p95 on this occupied
GPU. Cold worker startup was about 9.7–16.4 seconds. These are advance/startup measurements,
**not command-to-visible latency or sustained live-display guarantees**. Raw summaries live in
`evidence/closd-trials/`; the public replay clip preserves full poses, metadata and command events.

Seed 1's breakable tether did not break: its force stayed below the configured threshold. Seeds 0
and 2 did break. This is a valid negative result, not an omitted failure. Offline review of actual
baseline/weak/capped poses shows recognizable locomotion and modest constraint-dependent changes;
it does not establish believable strain, surprise or recovery. Stronger tethers change locomotion
more clearly. Halving physics dt while preserving 30 Hz control changes the stiff-tether trajectory
substantially; **timestep convergence is unproven**. Finite state and absence of resets alone do
not validate numerical stability or general recovery.

## ARDY vocabulary, paths and contradiction

The vocabulary run retained all 65 six-second clips: five seeds for each of walk, jog, run, jump
in place, jump forward, stealth, crouch/creep, sideways roll, somersault, recoil, step backwards,
raise hands and freeze. All completed without non-finite state. The full public ZIP includes poses,
metadata, summaries and a blank artistic score sheet. Bone-length residuals are below a micrometre
for these FK-derived poses; this does not validate contacts or natural motion.

At four denoising steps, walking displacement ranges from 0.05 to 2.38 m; running from 4.96 to
6.84 m. Jump-in-place pelvis height spans roughly 0.75–1.26 m, varying by seed. Forward-jump prompts
do not establish a successful jump. Sideways-roll joint positions reach 0.122 m below nominal floor.
Ground-motion/rolling and convincing surprise are not certified capabilities. Offline review shows
crouched/inverted transitions for somersault conditioning, but no complete artistic rating has been
assigned to every seed.

Some locomotion body poses become nearly static in later windows while the root keeps travelling.
An additional unchanged upstream four-step `generate.py` running trial reproduces the late-window
rotation decay. The integration did not hide or correct it. This is a significant current artistic
limitation, alongside skating/penetration.

A six-second, 0.6 m/s requested root route produces about 3.70 m displacement. Under the same root
plan, repeated positional LeftFoot targets over 2–4 seconds give 0.189 m mean / 0.484 m maximum raw
anchor error. Adding global orientation gives 0.200 / 0.647 m. Targets are then cleared. No output
joint is overwritten and postprocessing is disabled. The pinned model requires root-2D conditions
alongside global joint positions, so these are explicit root-plan-plus-foot experiments. There is
no silent root-free pin, native weight or physical force. Prompt transitions walk→run→stop also
complete; their recognizability still needs artistic assessment.

Cached-window generation in these short tests is about 25–52 ms after the first 157–166 ms window;
first CPU prompt encoding was about 1.28 s and cached embedding loads below 1 ms. These are worker
measurements, not presentation latency. The 65-clip evaluator reports about 931 MB peak Torch
allocation; process/context and total GPU memory are separate measurements. The warmed loopback
JSON echo benchmark, including encode/decode in both directions, is about 0.84–0.88 ms median and
1.09–1.17 ms p95 for actual three/four-frame payloads (about 16.5–16.8 KB). It excludes inference and
display. This supports retaining bounded JSON for the initial single-character live prototype;
recordings already use binary float chunks.

## Live operation and display

The ten-minute Field run completed 600.002 s with 12,004 recorded poses, 29 programmatic prompt/path
edit cycles and zero queued commands at completion. Worker GPU allocation stayed 1364–1376 MiB in
external driver samples; its history remained bounded at 160 frames. The default eight-frame live
buffer evicted 11,996 old frames as designed; these evictions are not measured display underruns.
The same existing Three viewer was visually inspected during the session. Full recordings remain
in the development results directory; `evidence/ardy-field-soak.json` and the driver trace preserve
summary/provenance. `examples/soak_ardy.kt` reproduces the bounded automated run.

Both final live scenes passed actual browser PointerEvent interactions. The test waits for the
existing Three viewer to draw a newer command revision and report capture readiness, then passes
another animation-frame boundary. ARDY's eight prompt/path/restriction edits measured 315.3 ms
median and 826.2 ms p95; CLoSD's six attach/update/move/release/impulse/reset edits measured
233.1 ms median and 888 ms p95. Percentiles use nearest rank. Raw samples and runnable browser
probes are in `evidence/*-browser-latency.json` and `tests/browser_live_*.js`. These small samples
measure browser input to a revised rendered snapshot, not perceptual recognition of the requested
action, native Vulkan presentation or physical screen latency. The revised skeletons and controls
were visually inspected. The public replay/probe documentation captures were generated by Field's
docs runner and visually inspected as well.

Final opted-in Kotlin runs passed against the latest workers: ARDY 7.843 s, CLoSD 19.065 s and
the 81-frame Kotlin/Python replay interoperability check 1.716 s (`evidence/kotlin-gpu-smokes.json`). These are whole smoke durations,
not inference latencies. Worker capabilities now include adapter/worker SHA-256 and Python/Torch
versions, in addition to source/checkpoint pins, for new recordings.

The complete docs HTML build passes its asset audit and local-reference checks: all 3,324 local
references resolve; the single pre-existing theme-generated 404 skip-link exception is explicitly
accepted by the docs checker. The six motion starting points and generated Kotlin API reference
are included. This verifies the local package; it is not a claim of website deployment.

## Kotlin authoring, following and movies

The authoring extensions use the existing session: suspending `awaitMotion`, `prompt`, timed
`route`, named physical `body` handles, kinematic `joint` handles and wall-paced `playMotion`.
Tether handles support tuning, moving anchors, sustained breaks and release. Observations come
from actual applied/physics/reset events; a retired connection is not reported as still attached.
Replay can now include those events as well as poses. End-of-recording playback clamps instead
of waiting forever, and closed replay sessions disown themselves from their Field RunScope.

The new opted-in `motion.score.gpu` check passed in 20.137 s: actual CLoSD tether/tune/move/release,
reset, reuse of an attachment ID with an immediate queued release, and one-shot impulse. The
first invocation hit the tool's 20-second default deadline; the explicit 240-second invocation
completed. `evidence/kotlin-score-gpu.json` retains the passing result. The original eleven motion
tests plus four authoring tests pass; eight existing Three camera/document-scope tests also pass.
Seven Python recording tests and the Node camera-link/turntable/follow tests pass.

The browser test edited the viewer camera using the existing inspector and observed continued
following while eye-minus-orbit-target stayed equal to floating-point precision. It saw 109
rendered frames, enabled follow and a translated target. The live instrument test clicked Resist
and Release, observed physical tether telemetry and the released state, and paused afterward.
The screenshot visibly confirms preset values 8000 N/m, 120 N s/m and a 2500 N cap. The browser
probes are reproducible in `tests/browser_follow.js` and `tests/browser_instrument.js`; raw results
are in `evidence/polish-browser.json`.

The stronger two-second tether trial has 0.040 m mean / 0.130 m maximum raw anchor error and
1079 N peak submitted force. A longer early-attachment trial has 0.042 m mean / 0.266 m maximum
error while active, reaches 2214 N, then terminates and resets. Both outcomes are retained;
the latter is not recovery. See `evidence/closd-resistance*.summary.json`.

The initial ARDY route movie was withdrawn as a positive demonstration: its nearly static body
slides along the root path. A fresh ten-step trial travels 3.551 m in six seconds, with more
root-relative articulation than the four-step run, but still slides. Both raw recordings are
retained and labelled as failures in the public guide; no pose correction was applied. The new
trial metrics are in `evidence/ardy-path-10steps.summary.json`.

The interactive ARDY session tells a different story from the failing walking-route takes.
A read-only audit of its closed chunks found sustained skipping. Three contiguous six-second
excerpts retain their original timestamps, frame indices and episode IDs, with source-chunk
SHA256 provenance and command context; the source worker/session was not stopped or edited.
The public skipping replay uses session seconds 102–108, with no reset inside the excerpt.
A fresh seed-0, four-step trial of “A person skips forward.” also sustains articulation for all
six seconds and travels 4.654 m. Its late two-second mean root-relative joint speed is 0.974 m/s,
versus 0.112 m/s in the four-step walking-route trial. These are measurements of movement, not
recognizability scores. See `evidence/ardy-skip-fresh.summary.json`. Prompt/history-dependent
failures must not be presented as a general ARDY failure.

The user-facing guide now uses an editable Kotlin/Field renderer for ARDY vocabulary, CLoSD
baseline/weak/strong tether and four/ten-step route comparisons. The strong comparison lane uses
the actual 8000 N/m, 120 N s/m, 2500 N capped recording, replacing the weaker old “trap” trial.
The two-joint coordinate probe remains a contract test and starting point, outside the main guide.
The Field renderer was launched and visually inspected through the real browser; its three
complete bodies and session clock are present. No external Pillow renderer supplies the new movies.

A first live-score capture failed when the host's global OOM killer terminated Chromium; the
kernel log identifies the task's headless browser as the victim. Spare task-owned Playwright
browsers/drivers and idle task Gradle daemons were closed. Later captures also hit the host’s
full-zram OOM condition, including a serial capture with no other verification running. The
resource repair removed 1.1 GiB of owned regenerable old docs staging and moved one 5 GB
encoder shard from tmpfs to disk, with matching original/copy SHA256 and a transparent cache
symlink. Swap headroom rose to 9.8 GiB; no global VM settings or unrelated jobs were changed. Neither original
interactive Field nor its workers were stopped. Captures use 15 fps; the resistance/release movie is 20 fps. A subsequent attempt to capture a
warmed live score was rejected by the movie motion guard (only 3 of 119 adjacent frame pairs
changed materially). It is not published. The final score movie instead replays an actual Kotlin
execution from its recording; all public movie fences now work without either optional backend.

The published score was executed in Field with recording attached to the exact example source.
Its manifest stores the example's SHA256; the check matches `examples/tether_score.kt`. It has
210 frames at 30 Hz, attaches at 2.0000001 s, releases at 4.0000002 s, reaches 1079.45 N and has
0.039995 m mean raw tether stretch. It has no termination or reset. The longer 2/3/3-second
variant did terminate/reset at 5.667 s after release; it remains in the workspace as
`kotlin-movement-score.f4motion`, with `evidence/score-three-second-hold.summary.json`. The starter
score uses the two-second hold. `evidence/kotlin-movement-score.summary.json` audits the published
source and command timings. Movie generation replays these recorded poses and events in Field;
it does not ask a simulator to reproduce the take.

Browser follow uses an exponential half-life, preserves orbit/pan/zoom, supports Y/Z up, and
treats changed epochs as discontinuities. Its mathematical tests cover cadence, visibility,
disable and reset behavior. Frame-synchronous documentation captures are demonstrations, not
measurements of real-time camera response; the browser filter uses browser time. Native Vulkan
following is not claimed.

All seven published movies were captured from real Kotlin/Field scenes and visually inspected.
`field-movies.provenance.json` records dimensions, frame counts, FPS, hashes and source recordings:
resistance/release (9 s at 20 fps), Kotlin score (7 s at 15 fps), follow-camera demonstration
(9 s at 15 fps), live-session ARDY skipping (6 s at 15 fps), and the ARDY vocabulary, CLoSD
baseline/weak/strong and four/ten-step route comparisons (6 s each at 15 fps). The two single-body
close-ups use explicit viewer padding so raised hands and heads remain visible. The final two
captures passed with zero failures after the cache/storage repair. The pose-review code is in
`examples/recorded_results.kt`, `examples/recorded_score.kt` and `examples/ardy_demo_replay.kt`.

Final Tailscale browser verification loaded all seven MP4s with HTTP 200 and decoded video
metadata (`readyState=4`), played the score, and found no raw fold markers or Kotlin fences.
The recorded ARDY Field URL advanced from 1 to 98 rendered frames over 2.5 seconds, with Follow
enabled and capture readiness true. Raw results are in `evidence/final-docs-browser.json` and
`evidence/final-viewer-browser.json`. The final full HTML/asset/link build passes: 3326 local
references and 39739 fragments resolve; only the repository's known 404 theme anchor is excluded.

The CPU text encoder was restored after the heavy checks, with its existing cache and offline
model loading. A new uncached prompt returned a finite 1×4096 embedding in 3.26 seconds; the
result is in `evidence/encoder-restored-check.json`. The original and polished live demos both
use its loopback service at port 9550. No StreamDiffusion run was added.

## Remaining experiments

StreamDiffusion coexistence is deferred at the user's request. Its initial worker attempt was
killed by the host's global OOM killer before producing output; no coexistence success is claimed.
The native Vulkan renderer itself launched, but a motion/native-renderer performance comparison
was not completed. Full timestep convergence, high-stiffness stability,
fall/recovery observation windows, stance-versus-swing paired attachment trials and systematic
route failure boundaries remain unfinished. The native adapters and four starting scenes are
experimental tools, not resolution of every proposed research question. No retraining, simulator
port, mesh retargeting, face/finger claims or ARDY-to-CLoSD coupling has been introduced.

The machine's original checkout has unrelated `src/personal` compilation failures against the
current public package layout. Live Field checks use an isolated checkout containing the same
public changes, without modifying private source. Core Kotlin compilation/tests pass in the main
development checkout. Publication/landing state is reported separately from these local results.
