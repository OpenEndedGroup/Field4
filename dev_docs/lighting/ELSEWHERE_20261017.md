# Linux/NVIDIA handoff verification, 2026-10-17

Verified on an RTX 4090, NVIDIA 610.43.02, Linux, with a connected display. The
originating annotation store is absent; outcomes are recorded in the tracked
handoffs, including the October 15 duplicates. Their IDs can be closed in that
store after landing.

## Reconstruction and OpenDlssNr

- `304cbbae`: `ReconstructionInputs` accepts motion direction and units. NGX
  receives the corresponding sign and input-size scales. A real 44-frame DLAA
  comparison measured mean absolute error 0.0000186 for tagged UV motion against
  pixel-motion history, versus 0.017175 for the deliberately untagged UV control.
- `69d0c231`: the source-owning `evaluate` overload records its RTT before NGX
  and refuses a source independently registered in `preTargets`. The GPU check
  observed exactly 44 source recordings for 44 frames and rejected a late duplicate.
- `c40cc5b7`, `ce738dca`: studies use typed inherited shader properties and belong
  to their box run. Renderer teardown also retires studies and standalone passes.
  The native-model smoke replaced a live study before closing its old run, retained
  the replacement's module/target, and closed both study and standalone pass before
  device destruction. The October 11 study-example handoff was verified too.

The real `open_dlss_nr` page runner regenerated all three native captures with the
model active. Original PNGs were inspected. The editable SR and RR plumbing checks
each passed 20 commands: attachment assertions, bypass on/off, history cut, rerun,
and normal Stop. Fresh SR/RR renders were inspected; server logs had zero Vulkan
validation errors. See [plumbing results](results/neural-tools/plumbing.json) and
[motion measurements](results/elsewhere-20261017/motion.json).

Focused JVM tests, KDoc attachment, saved-demo consistency and box idioms passed.
After regenerating codebase references and Starting Points, the strict HTML build
passed all 3,301 local references and every authored fragment across 1,715 pages.
The sole grandfathered missing fragment is the theme's 404 skip link. This final
build retained 1,016 media assets with no missing asset and started no GPU server.

The optional NGX adapter now uses ABI 5. Rebuild it with
`bash tools/lighting/build_ngx.sh` using the pinned DLSS SDK. An older adapter is
rejected before its evaluation ABI can be used.

## Path tracing

`2cbd888d` was already implemented by upstream `336d59c8d`: the emitter table
weights area by factor times texture mean. Two CPU regressions cover weighted
selection, black texels, and black factors. The real GPU audit now uses different
texture means with reciprocal factors in its emissive enclosures. On both compute
and ray-query backends, the textured furnace mean was 0.99811 versus 0.99810 for
the independent environment sampler, and emitter on/off composition agreed. The
lamp-box convergence and variance checks also passed: RMSE improvement was about
34.8 times; residuals decayed with reference budget rather than remaining as bias.

`18779541` had an incorrect premise, corrected upstream by `a20fbcfa1`:
`Camera.right` already uses `camera.up`; `right × forward` gives the same
orthonormal up as raster. The camera GPU check now verifies an actual 180-degree
roll in readback pixels and projection of a non-perpendicular declared up, on both
backends. No shipped camera or page recapture was needed for this clarification.
See the [hardware/stage report](results/elsewhere-20261017/pathtrace.json).

## Reproduction

Use the NVIDIA ICD and an authenticated display session. Optional native NR and
NGX assets must be built; these checks exercised the real libraries and model.

```bash
./gradlew jvmTest --tests afield.media.reconstruction.ReconstructionTest \
  --tests afield.graphics.scene.fabricated.PathTraceInstrumentTest \
  --tests afield.graphics.scene.fabricated.PathTraceEmitterWeightTest
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'run-jvm-main afield.media.reconstruction.ReconstructionMotionGpuSmoke'
uv run --project . --group backdoor python tools/run_field_tests.py \
  --id four.open-dlss-nr.teardown
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'run-jvm-main afield.graphics.scene.fabricated.RayTracingGpuAudit --arg=--stage=camera --arg=--stage=emitters --arg=--require-ray-query --timeout 900'
FIELD_DOCS_CAPTURE_PROFILES=linux-nvidia FIELD_DOCS_RUNNER_MODE=always \
  uv run --project . --group docs python tools/build_zensical_docs.py --page open_dlss_nr
```

For the multi-session plumbing check, keep a fresh managed server alive with
`tools/field_session.py serve --port 9577 --with-playwright`, then run
`uv run --group backdoor python tools/lighting/check_plumbing.py --port 9577 --server-log <managed-server.log>`.
It writes captures only after running the real graphs. The first RR attempt ran
out of VRAM while another workload occupied 22 GiB; the fresh-server run after
that workload exited passed both graphs.
