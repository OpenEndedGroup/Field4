# Yield held-trigger loops and cancel inside long sampling batches

Two frozen findings addressed: `d9ced5632cf1-01`, `6291dfc415aa-01`.

Both drawing after-states retain a frame() inside the held-trigger loop. This lets input updates and Stop proceed while drawing, preserving the intended position offset. The long build checks cancellation every 65,536 samples as well as at batch boundaries; random seed, sample count and per-batch progress remain unchanged.

Actual StudyBuilds worker verification starts the authored billion-sample inner batch, cancels after entry, and waits for the worker's finally latch (not merely a CANCELLED status). Worker exits in 7ms, under the two-second limit. Independent reduced before/after jobs complete 200,000 samples with identical recorded facts: estimate 3.1472 and identical error.

Both authored Phased after-states run on the real Runloop with controlled controller input held for ten frames, then released. Both yield seven points, the Runloop advances beyond twenty ticks, and both Stop commands succeed. A line-map fixture supplies the VR-parent dependency; this checks scheduling/input responsiveness, not VR hardware or rendering. No capture is created or replaced.

Actual import/ktfmt checks nine Kotlin states across both pages. Reviewed token/container, raw-string and fragment-marker checks pass. Collection literals are preserved; no conversions needed. Initial fixture constructor/counter/dependency mistakes are retained as diagnostics and corrected before the successful verdicts. Completed build proof was reused while refining the loop fixture.

Full guards are delegated to the lander. behavior.json contains actual output and explicit coverage limits; evidence.tar.gz retains successful and diagnostic verdicts. Checkpoint: 317/405 addressed, 88 open; full corpus mechanical coverage remains unfinished.
