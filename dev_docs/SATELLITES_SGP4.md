# SGP4 and the satellite catalogue: notes

Code: `src/jvmMain/kotlin/afield/simulation/orbits/` (`Sgp4.kt`, `Frames.kt`, `SatelliteCatalogue.kt`).
Page: `public/docs/satellites.md`. Transcript: `dev_docs/agents/transcripts/satellites.yaml`.

## Measurements (2026-11-11)

- SGP4-VER near-earth (`Sgp4Test.nearEarthMatchesTheVerificationVectors`): 9 satellites
  (5, 6251, 22312, 28057, 28350, 28872, 29141, 29238, 88888), 158 states, worst position error
  7.3e-6 m, worst velocity 7.8e-4 mm/s against `tcppver.out`.
- `MeanElements` (secular Kepler + J2 rates, what the vertex shader runs) vs full SGP4, every 20th
  bundled object at +0/+30/+60 min from the catalogue epoch: median 10.1 km, p90 15.5 km.
- Bundled snapshot: 15,884 sets, 15,089 near-earth, 795 deep-space skipped. ISS above 10° over
  Chicago in the week after the epoch: 39 passes.

## Traps

- `prop<T>(name)` declares `var Box.name`; assign it (`sky = ...`) after the `prop` line. A
  `val sky = ...` beside it shadows the property and the child box sees nothing ("Can't find a
  value for inherited property 'sky'").
- `primitiveType = lines` with vertex attributes and `set(n, 0)` (non-indexed) drew nothing on
  KosmicKrisp/macOS; indexed `set(n, n / 2)` with `tri.add(i)` per vertex draws (the pattern in
  `stereo_rtt_composite`). Not root-caused.
- The first full `macro run satellites` timed out at the backdoor's default timeout while the
  satellites box's preamble compiled cold; later runs (and `--timeout 900`) completed in seconds.
- Kotlin `Double.mod` is floored; the reference code's `fmod` is truncated. `Sgp4` uses `rem` to
  match it.
- Deep space (SDP4) is not implemented; `Sgp4` throws for period >= 225 min.

Data: CelesTrak (credit requested), Natural Earth 1:110m coastline (public domain), SGP4-VER
vectors from the AIAA 2006-6753 distribution (via python-sgp4) in `src/jvmTest/resources/afield/orbits/`.
