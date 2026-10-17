# Flow reset, shader probes and temporal history

Baseline `242165597`. Twelve findings across four authored sources: compute barriers express
compute/graphics consumers; bilinear lookup and particle generation/respawn code name their
intermediates; reset waits report a consumed failure or a bounded timeout. The history guide
captures the camera before writing object history. Probe examples guard the final workgroup,
bind their particle count, distinguish step distance from speed and show matching statistics
producer code. Blocking reads and optional asynchronous snapshots are separate examples.

The actual editor import pass and ktfmt cover 32 Kotlin bodies/states; the GLSL formatter covers
the transcript shaders and seven Markdown states. Parsed containers, reviewed tokens, comments,
fragment boundaries, untrimmed raw strings and non-code content are checked by `verify.py`.
The four-source idiom/collection audit is clean. Both complete transcript compile routes pass.

Real GPU checks execute the authored particle kernel at counts 0, 1, 64, 65 and 100000 with padded
canaries: all valid particles update, padding stays intact, and each positive probe reports the
exact count. Zero tests dispatch with padded physical allocation, not a zero-size allocation.
The published matching statistics producer executes for 16384 particles and reports 16384 finite
samples with positive finite mean. Actual reset waits exercise completion, consumed failure and
no-consumer timeout (only the test deadline is shortened). Actual FrameHistory/MotionItems calls
verify initial, consecutive and skipped-frame validity. Two published captures are refreshed by
the real docs runner, using the corrected threebox transcript.

Evidence retains early harness errors: the statistics cleanup called a nonexistent `close`, and
the camera test imported the old matrix package. Both harnesses are corrected; all behavioral
assertions pass. Only the private test JVM was retired to free the native resources left by the
cleanup error. GPU assertions were not pointlessly repeated after that cleanup-only repair.

See `changes.json` for reviewed edits, `verification.json` for source preservation, the reusable
harnesses here, and `evidence.tar.gz` for actual verdicts, logs and executed test programs.
