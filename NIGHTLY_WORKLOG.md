# Nightly worklog — state-estimation worked example (2026-07-21)

Kind C: documentation / explainer with runnable, captured evidence.

## Situation found (premise partly stale)
The mission premise ("pf.kt essentially undocumented, only a passing mention in
interpolants_examples.md") is out of date on the latest committed tree. A full
two-example state-estimation section (UKF Mackey-Glass + UKFParticleFilter)
already lives — awkwardly — in `public/docs/interpolants_examples.md`, with real
committed captures in `public/docs/assets/pf_examples/`. That page is really about
interpolants/keyframes/correlation; estimation is grafted on.

## Plan
1. Create a proper standalone `public/docs/state_estimation_examples.md` that
   documents the real `pf.kt` API surface (UKFOptions knobs, predict/update cycle,
   Matrix/diag, randomGaussian, UnscentedKalmanFilter, UKFParticleFilter,
   RougheningOptions).
2. Reuse the two PROVEN example code blocks verbatim (they already have committed
   real captures) + add a NEW third example: a 2-D constant-velocity tracker that
   infers unmeasured velocity from noisy position (the classic Kalman selling point).
3. Capture ALL images FRESH into `public/docs/assets/state_estimation_examples/`
   via the real docs harness (FIELD_DOCS_RUNNER_MODE=always
   `python3 tools/build_zensical_docs.py --page state_estimation_examples --skip-build`).
   Eyeball each PNG with Read.
4. Remove the estimation section from interpolants_examples.md (leaving a pointer),
   fix its title/intro, drop the now-orphaned assets/pf_examples.
5. Nav: rename interpolants nav entry, add "State Estimation Examples" to
   public/zensical.yml. Cross-link from timeseries_examples.md.
6. Commit in small steps.

## What changed
- NEW `public/docs/state_estimation_examples.md` — standalone tutorial: what the
  UKF is / when to use it, the `UKFOptions` knobs (alpha/beta/kappa/jitter as a
  table, from the real defaults), the predict/update cycle, `Matrix`/`diag`,
  `randomGaussian()`, `UnscentedKalmanFilter`, `UKFParticleFilter` +
  `RougheningOptions`. Three runnable boxes:
  1. UKF Mackey-Glass (reused verbatim from the previously-proven example).
  2. NEW: 2-D constant-velocity tracker recovering an unmeasured velocity from
     noisy position only.
  3. UKFParticleFilter beta-hypothesis tracking (reused verbatim).
- NEW captures under `public/docs/assets/state_estimation_examples/`:
  `ukf_mackey_glass.png`, `ukf_velocity_tracker.png`, `ukf_particle_filter.png`.
- `public/docs/interpolants_examples.md` — removed the grafted-on Estimation
  section (lines 329–672), retitled ("interpolants & keyframes"), reworded the
  intro/outro, added cross-links to the new page.
- `public/zensical.yml` — renamed the interpolants nav entry, added
  "State Estimation Examples".
- `public/docs/timeseries_examples.md` — added a cross-link to the new page.
- Removed now-orphaned `public/docs/assets/pf_examples/*` (git rm).

## Verification status
- Capture harness: `FIELD_DOCS_RUNNER_MODE=always python3
  tools/build_zensical_docs.py --page state_estimation_examples --skip-build`.
- First run hit a cold-start websocket flake ("no close frame received or sent")
  on capture #1 and the server auto-restarted. A clean re-run produced
  **regenerated=3 (screenshots=3), server_restarts=0, elapsed 21s** — every box
  executed through the real Field engine.
- **Eyeballed all three PNGs** with the Read tool. All show sensible traces:
  Mackey-Glass estimate tracks the hidden x through measurement noise; the
  velocity panel recovers the true sine velocity from position alone (noisier, as
  expected for a derived quantity, but clearly tracking); the particle filter
  catches the beta regime-jump at ~t=1055 with the weighted estimate + sd envelope
  following true beta and the particle cloud clumping/jumping. Not black/flat/garbled.
- `tools/check_doc_assets.py`: all three referenced assets present; no
  missing/dangling references. No remaining `pf_examples` references.

## Risks
- The reused Mackey-Glass and particle-filter code is identical to previously
  committed, captured examples; the only new code (velocity tracker) was executed
  by the harness and eyeballed. Low risk.
- Did NOT run a full docs build (`zensical build`) — only the capture
  preprocessing for the one page. Nav/link wiring is by inspection against the
  existing pattern in zensical.yml.

## Recommendation
Merge. Clean, self-contained docs addition with real captured evidence; improves
structure by giving state estimation a proper home and de-cluttering the
interpolants page.
