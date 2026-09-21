# Backlog: completion and truthful captures (2026-09-21)

## Scope and landing history

The preceding five-fix batch is landed on main through `afa7712a7`, including
integration of concurrent Marf work. This supersedes the pending landing status in
`BACKLOG_NEXT_FIVE_2026_09_20.md`. The live checkout's uncommitted diff was identical
before and after that fast-forward; nothing was pushed.

This follow-up fixes autocomplete receiver truncation, rejects screenshots without
settled populated output, and corrects the initial stereo browser panel orientation.
Published capture assets are unchanged.

## Changes and focused evidence

- Autocomplete: raise the receiver index budget to 4,000 while retaining the separate
  800-item package enumeration budget; propagate successful backend diagnostics and
  make the end-to-end probe reject truncation. Applied the three-file repair from
  `c0d9c409e342e421efc96be73d30522ddfb68d29` without importing its unrelated branch.
  A real K2 standalone backend on port 17893 passed all 46 AA checks plus fallback
  checks. `zip(other: CharSequence)` appears among 435 candidates with no truncation.
  Log: `/tmp/field4-backlog-aa-live.log`.
- Screenshot readiness: reveal disclosures before measuring, require populated
  visible content rather than an empty shell, and fail when readiness or layout
  stability never arrives. Measure disclosure/inline content, not Field's deliberately
  zero-width overflowing output shell. Preserve explicit handle-only captures.
  Nine Chromium tests exercise the actual production preparation scripts. Four of
  the initial six cases failed before the repair. A real Field capture of populated
  HTML passed; an empty target failed with no image written.
  Log: `/tmp/field4-capture-readiness-live.log`; positive artifact:
  `build/capture-readiness-live.png`.
- Native browser captures: replayed all four authored `puppet_browser.md` fences in
  `always` mode against a real macOS shared-texture renderer, redirecting output to
  `build/puppet-captures/`. Field UI, YouTube desktop, stereo, and kaleidoscope all
  produced visible content and were visually inspected. The old black-frame problem
  did not reproduce; this is current-path evidence, not a claimed black-frame root
  cause repair. Logs: `/tmp/field4-puppet-capture-first.log` and
  `/tmp/field4-puppet-captures-rest.log`.
- Visual review exposed horizontally mirrored stereo content. Orient the initial
  panel toward the viewer instead of reversing shader UVs, preserving agreement
  between displayed content and raycast input. Two of three geometry tests failed
  before the fix; all three now pass, checking both eyes. Replayed the actual stereo
  fence after repair and inspected readable text in both eyes. Log:
  `/tmp/field4-puppet-stereo-repaired.log`; before/after artifacts:
  `build/puppet-captures/youtube-stereo-before.png` and `youtube-stereo.png`.

Combined focused gate: all 12 new tests passed; log
`/tmp/field4-capture-final-targeted.log`.

## Certification and boundaries

Full combined-tree certification passed on `f2d1586aa`, integrating main
`6c9351ff7` with the fixes in `18c6a5a72`:

- `./gradlew --no-daemon certify`: **BUILD SUCCESSFUL in 30m 22s**;
  `/tmp/field4-completion-captures-certify.log`.
- JVM: 4,121 total, 4,117 passed, four skipped, zero failures/errors.
- Python tooling: 609 total, 608 passed, one skipped.
- Citation/documentation contracts: 106 tests passed in the immediately preceding
  combined-tree merge checks; certification reused that up-to-date result.
- Expected-failure register: zero registered, zero observed. Generated-reference,
  transcript, completion-fixture, source-partition, and documentation checks passed.
- The tracked-file write guard passed; the isolated checkout stayed clean.
- The JS browser-test task was skipped and JS tests reported no source; these are
  not a claim of full browser UI regression coverage. The actual Chromium fixtures,
  live Field output capture, and four native captures above provide targeted evidence.

The final evidence-only commit does not change runtime source. Main was clean at
the final pre-landing check. All three owned verification ports (17893, 9581, 9583)
were free after their sessions were stopped. Nothing was pushed.

The native evidence covers Apple M1 Max/macOS, not Windows, Linux, or an OpenXR
headset/controller session. Temporary logs and build artifacts are local evidence,
not tracked publication assets. Disk-budget work and the remaining Marf transcript
registration are outside this batch.
