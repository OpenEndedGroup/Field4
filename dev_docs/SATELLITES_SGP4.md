# SGP4 and the satellite catalogue: notes

Code: `src/jvmMain/kotlin/afield/simulation/orbits/` (`Sgp4.kt`, `DeepSpace.kt`, `Frames.kt`, `SatelliteCatalogue.kt`).
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

Data: CelesTrak (credit requested), Natural Earth 1:110m coastline (public domain), SGP4-VER
vectors from the AIAA 2006-6753 distribution (via python-sgp4) in `src/jvmTest/resources/afield/orbits/`.

## Second pass (2026-11-11, branch claude/sgp4-next-12adc2)

Done:

- **Deep space (SDP4).** `DeepSpace.kt` ports `dscom`/`dpper`/`dsinit`/`dspace` ("improved" mode, GSFC
  Lyddane choice on the perturbed inclination). `Sgp4Test.deepSpaceMatchesTheVerificationVectors`: 24 cases,
  508 states, worst 0.12 mm / 0.9 µm/s against `tcppver.out`. The comparison now walks `tcppver.out` block by
  block (20413 appears twice; the second block is the 3.5-year run) and checks that `propagate` returns `null`
  where the reference stops with an error (7 such stops across both branches; 33334's single t=0 row is the
  reference's "use previous data line" placeholder, i.e. it failed at epoch too).
- The resonance integrator restarts from epoch on every call instead of caching `atime`/`xli`/`xni`, so `Sgp4`
  stays immutable and thread-safe. Same numbers (the step grid is fixed); cost is ~t/720 steps per call, a few
  hundred for a year out.
- `MeanElements` for deep space adds the lunar-solar secular rates (`dnodt`, `domdt`, `dmdt`); resonance
  changes to mean motion and `dedt`/`didt` are left out. Over an hour from the catalogue epoch: median 23 km,
  p90 32 km for all 795 deep-space objects (`meanElementsTrackSgp4`).
- `SatelliteCatalogue` keeps deep-space objects by default (`includeDeepSpace = false` restores the old
  behaviour, and `skippedDeepSpace` counts what that left out). `epoch` became the latest *near-earth*
  epoch: CXO and XMM-Newton in the snapshot carry predicted epochs 1-2 days ahead, which moved the catalogue
  epoch to 13 Nov and tripled the near-earth mean-element error.
- `Sun` (Vallado algorithm 29, checked against his example 5-1), `Frames.isSunlit` (cylindrical shadow),
  `Observer.sunElevationDeg`, `Observer.visiblePasses`, `Satellite.isSunlit`. ISS over Chicago in the week
  after the snapshot: 39 passes, 11 visible.
- `passes` refines the culmination by golden-section search (it was the 30 s sample) and keeps a pass still
  open at `to` (a geostationary satellite above the horizon used to give no passes at all).

Considered and not done:

- **Frame accuracy.** TEME to Earth-fixed uses GMST with UTC for UT1 and no polar motion. |UT1-UTC| < 0.9 s
  is at most ~0.4 km of ground position at the equator, polar motion a few metres; both are far inside
  SGP4's own error (km at epoch, tens of km a week out). Adding them means shipping and refreshing an IERS
  table for no visible gain.
- **Conical shadow / penumbra.** The cylinder misplaces the shadow edge by a few seconds of a low orbit's
  pass; a visibility list rounded to the minute does not change.
- **Catalogue freshness beyond `fetch`.** `fetch` already downloads, caches for six hours and falls back.
  Re-bundling a newer snapshot only moves the date; left for whoever refreshes the resource.
- **Performance.** `positionsEcef` for the whole catalogue is one SGP4 call per object (~15,900), once per call;
  nothing in the docs calls it per frame (the GPU path uses `MeanElements`), so it was not parallelised. Whole-catalogue pass search is the slow case; it is on the page's "Where to go next".
- **Flying to a satellite, level of detail.** Box-level graphics work with nothing to verify headlessly beyond a
  native-window still; left on the page's list.
- **Brightness (magnitudes), OMM / Alpha-5 parsing, lit points on the GPU globe.** Real but separate pieces;
  each is a page "Where to go next" item with enough detail to start from.
- The first globe recapture after deep space came in was framed off-centre (globe at the left third, right half empty); an immediate recapture of the same code was centred. Likely the native-window trim keyed off a stray distant point; not root-caused.
