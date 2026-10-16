# Exact landing and immutable structural solve requests

Baseline `1e55af539`. Eight saved findings addressed: both saved-demo confluence findings,
all three confluence transcript findings, and the three structural-playground findings.

## Confluence

Completed cells stop physics. Landing interpolates from one frozen snapshot, snaps to its target
on the final frame and clears velocity. The report checks actual local positions and typed,
port-labelled edges. The WL/BFS layout is explicitly a heuristic; equal coordinate sets alone
cannot establish equal drawings. Animation cadence and landing controls are named at setup.
Stale renderer-free clock history is removed from the loaded code.

The real twenty-cell run reached the independent lambda evaluator's answer for every cell.
All twenty landed exactly and remained unchanged through ten more physics/landing calls.
Positioned graphs match across schedulers in two rows (`succ two`, `3^3`); the other three
honestly report false. A real Three.js capture was inspected. A separate renderer-free Frames
box reached five ticks and stopped with a passing lifecycle census. Prose handoff `ee3f6373`
records the description/tour claims that must be qualified.

## Structural playground

Queued requests now snapshot arm, mesh size, order, load and epoch. Compare orders copies one
snapshot. Futures retain that request; geometry/load edits and cancellation invalidate stale
publication, including edits while a worker opens. Every accepted result advances a publication
counter, so replacing a result under the same order redraws. Physical input changes clear old
stress output. A late opening session is closed on completion after Stop; an adopted session is
closed in the same finally scope. Pending solves remain tracked until settled, avoiding overlap.

The actual request/isCurrent functions pass executable checks for load/geometry/mesh changes,
cancellation, epoch changes, order comparisons and display-only exaggeration. Both edited
transcripts pass the compile/replay guard (the structural example is compile-only).
**No real Kratos solve or worker-opening/Stop race was exercised:** the optional local environment
is absent and the existing Podman machine is stopped. This is a specific verification limit,
not a claim of end-to-end worker acceptance. The native solve algorithms are unchanged.

Import analysis, ktfmt, collection literals, fragment preservation, native metadata refresh and
sidecar/mirror synchronization apply to the edited sources. Private Field/browser port 9700 was
used; the user's Electron session is separate. Exact inputs, verdicts and failed harness attempts
are retained in [evidence](evidence.tar.gz); [source diff](sources.diff.gz).
