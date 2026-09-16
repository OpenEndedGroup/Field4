# Binocular gaze intersection: what is pinned, and what is not

Displaced from `public/docs/openxr_eye_intersection.md` on 2026-09-16 by the documentation voice
pass. The page had carried a census of these tests plus a note to itself about which check still
needed a headset; a reader arriving to place a marker from gaze does not act on either.

## What the tests pin

`src/jvmTest/kotlin/afield/four/targets/BinocularGazeIntersectionTest.kt`, five cases:

| test | what it establishes |
| --- | --- |
| `convergentRaysRecoverOffAxisTargetsAndIgnoreDirectionMagnitude` | the midpoint solution on rays that really cross, independent of direction-vector length |
| `skewRaysUseClosestPointsAndRejectLargeGap` | closest-point fallback, and the gap threshold rejecting instead of inventing a depth |
| `parallelDivergentFarAndInvalidRaysNeverPlaceMarker` | the angle, distance and validity rejections |
| `htcPosesAlreadyInLocalSpaceNeedNoHeadTransformAndInvalidateOnLoss` | that HTC poses arrive in LOCAL space, so applying the headset pose again is the bug, and that tracking loss invalidates |
| `worldTransformCommutesWithTriangulationAndCancelsInEyeView` | `W * midpointLocal` equals triangulating in world, and that `inverse(W)` cancels in both eye view paths |

`OpenXREyeNativeCallTest.kt` and `OpenXREyeTrackingTest.kt` beside them exercise native call layouts
and lifecycle through synthetic runtime callbacks. Between them these establish the **coordinate
conversion and binding contracts**.
They say nothing about headset calibration, and nothing about whether a real eye tracker's samples
are good.

`DocumentedApiEvaluationTest` classifies this page's boxes `COMPILE_ONLY`: the target requires a
live runtime, so CI compiles the two boxes and does not run them.

## Open

- **The physical coordinate check has never been run on an HTC-capable headset.** The four-step
  procedure under `## Headset check` on the page — held marker staying anchored under head motion,
  `worldFromLocal` changes moving WORLD but not LOCAL, live rays vanishing on focus or validity
  loss — has only been exercised against the desktop Vulkan reference scene on macOS, which has no
  simulated eyes. This is stated on the page as a limitation, which is where it belongs; settling it
  needs hardware, not an edit.
- **The scene's own numbers are transcribed, not asserted.** The grey cubes' world centres
  `(0, 0, -1)`, `(0, 0, -2)`, `(0, 0, -4)` and the conversion `(+0.35, +0.10, -0.20)` with a
  30-degree yaw are written into the page and into the transcript, and `0.35` appears in neither
  `BinocularGazeIntersection.kt` nor its test — those use their own fixtures. So the page's scene
  arithmetic has no guard and would drift silently if the starting point changed. Asserting the
  starting point's conversion against the page would be the cheap fix.
