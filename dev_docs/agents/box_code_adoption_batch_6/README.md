# Transcript comments and collection style

Baseline `c8c2a4913`. [Reviewed changes](comment_changes.json), [source diff](sources.diff.gz),
[gate logs and regression results](evidence.tar.gz). Twenty saved findings addressed.

Twenty-two comment edits replace maintenance history with current intent and correct geometric,
rendering, timing and measurement explanations: flatness/skew, chart clipping/ticks/picking,
slider laws, closed reliefs, vertical half-height, jump-flood rounds, four preference dials,
nonfinite visualization, fresh Kratos caches, interpolation cadence, simplified native fallback,
MARF stage boundaries, synth/Dirt box names, fixed decimal places, frame-based shader time,
heightfield samples and spectrogram orientation. Operations and descriptions stay intact;
prose mismatches are filed in the accompanying handoff.

Twenty-four eligible listOf calls became collection literals using a Kotlin-token-aware pass over
explicit paths. Strings, comments, explicit generic calls and spread arguments are preserved.
All selected Kotlin/GLSL properties received the real import/ktfmt/editor formatting pass.
Authored fragment markers and independent live reset assignments are preserved.

## Verification and concrete repairs

The first selected compile/replay found two problems introduced by the mechanical step:

- A literal appended to a generic list left `+` ambiguous in gp_tune. Appending the two concrete
  FLine values separately retains the same order and resolves the overload.
- JVM intro analysis removed three math imports needed by the visionOS Kotlin/JS head. The shared
  formatter now recognizes explicit Kotlin/JS head markers with the existing profile resolver
  and preserves their imports while still running ktfmt. It makes no JVM-intro assumptions for
  those programs. Unmarked guest-language context remains a separate concern.

After repair, **all 17 selected top-level transcripts passed** the actual classification-driven
compile/replay guard, including the Kotlin/JS head compiler. Executable CPU transcripts ran;
window/graphics and service transcripts followed their compile-only classification. The nested
photo_plaque capture transcript is outside that guard's selection: its comment/layout edit did
not change the native calculation, and no new photo capture is claimed.

**All 10 BoxImportsTest tests passed**, including a regression that keeps the visionOS math import
and checks formatting idempotence. An initial test-run repository-write guard caught an import
edit made during the run; its failed log is retained, and the stable-tree rerun passed.
The explicit 18-file collection-literal/idiom audit is clean. Compiler logs include deliberately
failed shader snippets taught by the original examples; the final guard verdict is successful.

The private Field used for earlier batches was stopped before these source/runtime changes.
Generated transcript copies remain the lander's responsibility.
