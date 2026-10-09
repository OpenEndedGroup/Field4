# First-contact delivery evidence

## Local behavior checked (2026-10-04)

- `POST /api/starting-points/open?name=first-steps` created an editable one-box document.
  Launched its actual stored code, edited five to eight, stopped, waited for autosave and reopened.
  The saved sidecar and fresh document snapshot both contained `var petals = 8`.
- The same endpoint opened `first-graphics` with two boxes. Launched the renderer, waited for native
  frames, launched its child triangle, captured and visually inspected the Vulkan window, then
  stopped child and parent. This was the local Linux desktop, not a cross-platform GPU qualification.
- The `first_steps.md` UI capture ran the shipped drawing through the real Monaco editor, typed the
  edit, relaunched with keyboard input, checked the resulting value in the actual output DOM, and
  stopped. The capture is reproducible through its docs fence and macro. Both early five-petal and
  final eight-petal frames were visually inspected. No computed output was substituted.
- README GIF is a scaled conversion of the recording at its original playback speed.
  The docs MP4 remains the runner's original.
- The first drawing now renders 24 layered paths with a travelling wave and changing twist.
  The real editor capture was regenerated and inspected at five and eight petals; the beginner
  edit, inline value and launch/stop gestures are unchanged.
- The launch recording demonstrates only Alt+Up. Its capture checks the actual key overlay reads
  `Alt+↑` (or `⌥↑` on macOS); four Node tests cover modifier ordering, duplicate mouse events,
  typing and macOS chords (`node --test tools/test_keycast.cjs`).
- `FirstContactStartingPointTest` passed (two tests): copied examples preserve code/sidecars and
  existing edits, and the recording's setup code matches the shipped beginner example.
- Portable PowerShell checked the new SHA-256 helper against the standard `abc` fixture.
  This is a helper check, not a substitute for executing the Windows launcher on Windows.

## Cross-platform and human evidence

See [FIRST_CONTACT_TRIALS.md](FIRST_CONTACT_TRIALS.md). That ledger distinguishes fresh hosted
source-build results from the still-needed unaided trials. Download bytes were not measured by
those initial automated runs; neither disk growth nor cache sizes are presented as traffic.

## Windows launch repairs (2026-10-05)

- The third hosted attempt failed writing `PatAlgebraKt$|over|$1.class`: Windows forbids `|`
  in filenames. Function-reference adapters now live under portable property names while the
  Kotlin operator spellings stay available. `PatAlgebraTest` checks actual compiler output
  filenames as well as the pattern algebra.
- Native Gradle output made PowerShell's `Build-Field` return an array of log lines and `false`,
  which evaluated as true. Routing that output to `Out-Host` preserves a single Boolean result.
  The native-process regression rejects the old launcher and passes with the fix, including
  under Windows PowerShell 5.1 in the hosted workflow.
- The next hosted attempt compiled Kotlin but exposed a missing ARPACK DLL in `prepareSmileNative`.
  The pinned Windows jar contains `libarpack-2.dll`; the include pattern now accepts versioned
  DLLs. Replaying the production extraction task with the actual Windows OpenBLAS/ARPACK jars
  produced both libraries and their byte-identical `openblas.dll` / `arpack.dll` aliases.

## Hosted verification after repairs (2026-10-05)

- [Run 37281461833](https://github.com/OpenEndedGroup/Field4/actions/runs/37281461833):
  Windows Server 2025 x64 passed in 1,453.0 seconds; macOS 14.8.9 arm64 passed in 1,151.2 seconds.
  The Windows PowerShell 5.1 build-result regression also passed.
- [Run 37284801723](https://github.com/OpenEndedGroup/Field4/actions/runs/37284801723):
  Ubuntu x86-64 passed in 879.1 seconds with a 4 GB compiler heap, one worker and parallel
  execution disabled. This CI profile followed two runner shutdowns whose cause remains unproven.
- All three checked real source startup, checkout/workspace identity, welcome/editor/browser
  assets, starter-document copying and default cross-site API refusal. Desktop/GPU behavior and
  unaided human trials remain outside these checks; network download bytes were not measured.
- Local `PatAlgebraTest` passed all 18 tests, including actual compiler-output filename checks.
  The low-memory full JVM compile passed. The HTML docs build passed with all 2,152 distinct
  local references resolving; generated pattern-reference links were refreshed after the Kotlin edit.
