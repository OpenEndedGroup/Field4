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
- README GIF is a scaled conversion of the 14-second recording at its original playback speed.
  The docs MP4 remains the runner's original.
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
