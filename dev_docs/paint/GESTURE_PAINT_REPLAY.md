# A recorded gesture, replayed as a brush movement

Backlog N1012-1: pull one gesture all the way through the painting system. Record it on an XY pad,
edit its keyframes, replay it through `TimedGesture` on a `PaintCanvas`, and give an agent the
same operation. This is a small adapter over existing parts, not a new system. Its question was
whether gesture recording composes with painting. It does. What the recording drops is listed
below.

Code:

- `src/jvmMain/kotlin/afield/paint/gestureReplay.kt` has three `PaintCanvas.gesture(...)`
  overloads and `penPose`.
- `src/jvmMain/kotlin/afield/visualizers.kt` holds `gesture(pen = true)`, `PenSample`,
  `GestureHandle.penSamples`, `penLane` and `setTake(List<PenSample>)`.
- `src/commonMain/resources/arrayControls.js` and `controllers.js` record the pen.
- `src/jvmMain/kotlin/afield/paint/GestureReplayMain.kt` is the agent entry point.
- `tools/field_mcp.py` has the `field_paint_gesture` tool.

Test: `src/jvmTest/kotlin/afield/paint/GesturePaintReplayTest.kt`. It needs the paint library
(`./gradlew buildPaintNative`) and skips without it. The lander has no dylib, so run it locally.
Demo: `dev_docs/agents/transcripts/gesture_paint_replay.yaml`.

## The chain

```
pad (gesture, pen = true) --Shift-drag--> take: (t, x, y, pressure, tiltX, tiltY, twist) x n
   --bakeGesture / penLane--> keyframe lanes x, y, pressure (tiltX, tiltY, twist)   [edit here]
   --PaintCanvas.gesture(x, y, pressureLane = ...)--> TimedGesture --stroke(g, dynamicBrush)--> pixels
```

`PaintCanvas.gesture(pad)` skips the lanes and paints the take itself.
`gesture(samples: List<PenSample>, xRange, yRange, into, ...)` is the core. The other two
overloads reduce to it.

- **Space.** The pad's square is laid over `into`, a rectangle in the canvas's FLine coordinates.
  By default that is the largest square in the middle 80% of the canvas. The pad is y-up and the
  canvas is y-down, so the mapping flips y.
- **Time.** The take's own clock is kept, scaled only by `timeScale`. Speed in mm/s therefore
  depends on how many canvas mm the pad covers: a 1.6 s stroke across a 160 mm `into` moves at
  about 100 mm/s.
- **Pressure.** Pen pressure (0..1) is multiplied by `pressureScale`, 1.2 by default. A mouse
  reports 0.5 while a button is down, so a mouse take presses 0.6, the canvas's usual press. A
  take with no pressure (`NaN`, from a pad without `pen`) presses `pressure` throughout.
- **Tilt.** `penPose(tiltX, tiltY)` gives tilt = atan(hypot(tan tiltX, tan tiltY)) and
  azimuth = atan2(tan tiltY, tan tiltX). Screen-down and canvas-down agree, so no flip is needed.
  A take whose tilt never leaves 0 (a mouse) leans `tilt` (30°) toward its heading, the way
  `PaintCanvas.gesture(line)` does.
- **Twist.** A recorded twist becomes `Sample.roll`. Without one, the roll is heading + 90°, so a
  flat is held across its path.
- **Editing the path keeps the pen.** `GestureHandle.setTake(x, y)` on a pen pad samples the old
  take's pressure, tilt and twist at each new time. Moving the path therefore leaves what the hand
  did in place.

## What the XY recording drops

A `gesture(...)` pad without `pen` records (t, x, y) only. Against what `TimedGesture.Sample`
can carry:

| Dimension | XY take | `pen = true` take | Baked lanes | Filled in on replay |
| --- | --- | --- | --- | --- |
| position | kept | kept | `x`, `y` (RDP, hard tolerance) | — |
| time / speed / pauses | kept (`performance.now()` per pointermove) | kept | key times; between keys, linear | — |
| pressure | **dropped** | `PointerEvent.pressure` | `penLane("pressure")` | constant `pressure` (0.6) |
| tilt (lean from upright) | **dropped** | `tiltX`/`tiltY` → `penPose` | `penLane("tiltX"/"tiltY")` | constant `tilt` (30°) |
| azimuth (which way it leans) | **dropped** | from `tiltX`/`tiltY` | as above | the path's heading (`lean`) |
| twist / roll | **dropped** | `PointerEvent.twist` | `penLane("twist")` | heading + 90° |
| lift between movements (`Sample.piece`) | **dropped**: a take is one stroke | dropped | — | one piece |
| world arc (`Sample.s`) | n/a | n/a | — | NaN (canvas length) |
| hover / approach before contact | dropped (only pointerdown..up is recorded) | dropped | — | — |
| sample rate | pointermove rate (~60–120 Hz, coalesced events discarded) | same | — | the brush substeps at 1 ms |

Still dropped with `pen = true`:

- **Coalesced events.** `getCoalescedEvents()` is not read, so a 240 Hz pen arrives at the
  display's event rate.
- **Lifts.** A take is one pointerdown-to-pointerup stroke, so there are no separate movements in
  one take.
- **Pad clamp.** Positions are clamped to the pad, so a stroke that leaves it flattens along its
  edge.
- **The 4096-sample cap.** That is 34 s at 120 Hz.
- **Pressure above 1.** `PointerEvent` stops at 1. `pressureScale` is the only way to reach the
  "harder still" range above 1.
- **altitudeAngle/azimuthAngle.** Safari's alternative to tiltX/tiltY is not read. Its tilt
  carries the same information.

Box 2 of the demo paints one take four ways. Its capture is
`assets/gesture_paint_replay/demo_dimensions.png`, from the demo run on 2026-10-13. Left to right:

1. Position and time only. This is what a mouse records.
2. Adding the pen's pressure (1 → 0.05) thins the end.
3. Adding the tilt (10° → 60°) narrows and streaks the second half.
4. Dropping the time, so the same path is drawn at an even pace instead of slow-then-flicked.

The fourth differs from the third only slightly with this 32-hair filbert. The differences are
real but modest at this brush size. Pressure and tilt change more than timing does here.

## Evidence

- `GesturePaintReplayTest.anEditedTakePaintsWhereTheEditMovedIt` records a seeded 189-sample pen
  wave (1.6 s), bakes it to 14 keys at 0.01, drags the y lane down by 0.6 pad units, keys the
  pressure flat, and replays both versions through a 32-hair dynamic brush. Ink in the band where
  the stroke was: recorded 652k, edited 114k. In the band it was moved to: recorded 113k, edited
  650k. The capture is `assets/gesture_paint_replay/recorded_vs_edited.png`.
- `aPensPressurePressesTheHairsWhereThePenPressed` compares hair-seconds of contact in the start,
  middle and end column bands. The pen pressure (0.05→1→0.05) gives [5.8, 14.4, 7.5]. One light
  pressure (0.35) gives [7.2, 10.8, 9.6]. The capture is `pen_pressure_vs_light.png`. The visible
  difference is texture and carry more than width: pressed, the trough is laid as a solid band;
  light, it breaks into dry brush.
- `theTakesTimingPressureAndTiltReachTheGesture` checks the timing, the y flip, pressure × 1.2,
  and the pen's lean replacing the heading lean.
- The agent path, run live through MCP `field_paint_gesture` against a Field on 9611, baked
  127 samples to 12 keys (max error 0.0098). The agent then lifted the middle third of the y
  lane by 0.4, halved the pressure lane and replayed. The capture is
  `agent_bake_then_edit.png`: the edited stroke has a raised middle and a lighter tail, with
  corners where the linear keys meet.

## Agent use

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'run-jvm-main afield.paint.GestureReplayMain --arg /abs/request.json'
```

The request is a `GestureReplayRequest` (JSON) with these fields:

- `out`, the PNG path.
- `samples`, rows `[t,x,y]` or `[t,x,y,pressure,tiltX,tiltY,twist]`, or `lanes`
  `{x,y,pressure?,tiltX?,tiltY?,twist?}` in the keyframe editor's `{time,value,type}` form.
- `bake` (a tolerance) with `lanesOut`, to bake the samples first and write the lanes for
  editing.
- Brush and canvas fields.

The run prints a JSON report and writes nothing outside the given paths. The MCP tool
`field_paint_gesture` takes the same fields, writes under `.field-backdoor/mcp-paint-gesture/`,
and returns the report and the PNG. It needs a reachable Field, but no open document.

## Not settled here

Replaying a stroke from lanes is a read of another box's keyframes. If a painting box reads a
pad box's lanes through `prop`/`fromContext`, that read is recorded as a dependency
(`dev_docs/PROPERTY_DEPENDENCIES.md`). This branch does not decide when such dependencies are
forgotten, because the rule for stale ones is open. The demo sidesteps the question by keeping
pad, lanes and painting in one box. A second box that paints another box's lanes is the case that
rule will have to answer.
