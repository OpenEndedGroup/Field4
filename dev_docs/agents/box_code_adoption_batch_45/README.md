# Beginner graphics, live speed and quoted-code formatting

Four frozen findings addressed: `b07fd9b678a8-01`, `dd2f4283e7fa-01`, `231a6405af15-01`, `1e41294cbe22-03`.

The beginner triangle uses moduleHere and a named Mesh builder. It retains the existing box-derived module slot: actual rehearsal exposed that a newly chosen key leaves the original keyed drawing alive. The final version replaces the existing drawing on relaunch. Shader property links remain discoverable, and matching template/docs shader copies have concise clip-space intent comments and actual GLSL formatting.

The moving drawing exposes phaseStep next to petals, with deliberate declaration/reset pairs for Alt+Enter. Its comment explains that speed advances per Runloop iteration. The bookmarks fixture uses a flat dark background with identical extents and contrast.

## Behavioral proof

- Actual matching native renderer: before/after pixels identical, two more relaunches identical, exactly one module/mesh with three vertices/indices. Editing a vertex changes actual native pixels. Renderer stopped afterward; that JVM is now CPU-only.
- Actual drawing loop: assignment changes increments from .045 to .09, edited value survives full relaunch, previous loop stops advancing, Stop freezes phase and lifecycle census confirms cleanup.
- Actual browser DOM: bookmarks remains 520×300, padding 12px and text rgb(238,238,238); backgroundImage changes from gradient to none.
- Actual SavingContext refreshes both templates' inline fallbacks, retaining IDs, graph and non-source properties; every fallback matches its sidecar.
- Genuine docs-hook bookmarks capture regenerated in captures.log. Genuine 18-second first-look video regenerated separately in captures-video.log (ui_drives=1, failures=0); early/late frames visually inspected, showing five/eight petals and live output. The Linux NVIDIA triangle figure is retained under its normal capture profile.

## Mechanical coverage and formatter repair

Actual import/ktfmt covers ten Kotlin bodies; GLSL formatting covers six bodies. Token/container, raw-string and execution-boundary checks pass. Collection literals audited; no conversions needed in these bodies. Scoped checkBoxCodeCompiles/checkBoxIdioms pass; actual native/live runs above cover fixtures outside the compile guard's selected transcript scope.

The formatter now decodes complete quoted/plain YAML code properties and expands them to literal blocks before formatting. Metadata/comments remain intact. Six regression tests pass, including GLSL, idempotence, Unicode code-point offsets and preserving aliases/flow collections. Aliases/flow collections are explicitly skipped with diagnostics, and folded scalars remain outside supported coverage. This is additional tooling work, not an extra closed finding or a claim of complete corpus formatting.

Rehearsal scripts, reviewed changes and evidence.tar.gz preserve real verdicts and initial diagnostic failures. A first named-slot attempt and harness quoting/protocol fixes are retained. The archive excludes managed-server bulk logs and duplicate published media. Full sweep remains in progress: 304/405 addressed, 101 open.
