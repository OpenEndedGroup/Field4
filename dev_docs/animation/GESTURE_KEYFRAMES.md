# Gesture takes <-> keyframe lanes

Code: `src/jvmMain/kotlin/afield/animation/gesture/gestureKeyframes.kt`, `GestureHandle` in `afield/visualizers.kt`.
Test: `src/jvmTest/kotlin/afield/animation/gesture/GestureKeyframesTest.kt`. User docs: `public/docs/keyframe_editor.md#from-a-performed-gesture`.
Demo: `dev_docs/agents/transcripts/gesture_to_keyframes.yaml` (box `gk_bake`).

## Method
Joint Ramer–Douglas–Peucker over the (t, x, y) samples, error measured at equal time (how a lane is
evaluated), worst axis. Keys are `linear` and a subset of the samples; take and lanes are both
piecewise linear with lane breakpoints among the sample times, so the max error over all t equals
the max at samples: the tolerance is a hard bound. Both lanes share key times (linked lanes); first
key t=0, last t=duration. Reverse (`lanesToTake`) samples evaluators at 60 Hz plus every key time.

## Numbers (seeded 240-sample hand-drawn eight, 2 s; fit measured on a 16x dense resample)
| tol | keys | max | mean |
|---|---|---|---|
| 0.002 | 187 | 0.0020 | 0.0003 |
| 0.005 | 77 | 0.0050 | 0.0017 |
| 0.01 | 35 | 0.0096 | 0.0031 |
| 0.02 | 28 | 0.0185 | 0.0049 |
| 0.05 | 16 | 0.0338 | 0.0162 |
| 0.1 | 14 | 0.0980 | 0.0251 |

Round trip lanes -> take: error vs lanes ~3e-8 (float storage); vs original take = bake error.
Eased (bezierFree, flat handles) lanes -> 181-sample take: max 0.0002, mean 0.00006; rebake at 0.01 -> 21 keys.
Demo box (different seed/noise): 30 keys of 240, max 0.0162, mean 0.0040.

## Traps
- A `bezier*` key with no `handles` evaluates as a straight line on the JVM (default handles at 0.3
  along the chord); the editor derives smooth ones. Give handles to test curvature.
- `ControlValueHandle.set` does not call the gesture's `replace`, so `setTake` updates the handle's
  take itself as well as the control.
