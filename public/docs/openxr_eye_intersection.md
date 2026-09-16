# Place a world-space marker from binocular gaze

The `openxr_eye_intersection` starting point draws a small white wire cube where the two
HTC eye rays come closest. It follows the editable `Mesh` and shader pattern in
`canonicalGraphics`, with direct OpenXR eye rendering as in `helloXR`. The renderer box
is the scene box's parent. Launch the renderer first, wait for its window/headset views,
then launch the scene. On macOS it shows a desktop reference scene without simulated eyes.

[Open the starting point](/offline.html?file=/field4/assets/agent_transcripts/openxr_eye_intersection/metadata.edn)

Both extensions remain optional. `XR_HTC_eye_tracker` supplies the two origins and
orientations needed for binocular depth. `XR_EXT_eye_gaze_interaction` supplies a single
combined gaze pose, drawn as a dashed grey ray; it cannot by itself supply an intersection
depth. Enable eye tracking and calibration in the headset runtime. The status panel shows
which interfaces exist, per-eye validity, the HTC sample time, and why a marker is hidden.

## Coordinate contract

OpenXR eye poses returned by these bindings are already located in the target's **LOCAL**
reference space, in metres. Each orientation rotates eye-forward **(0, 0, -1)** into that
same space. Applying the headset pose again would move and rotate the rays twice.

`BinocularGazeIntersection.update(xr.htcEyeState())` solves in LOCAL space. Real measured
rays often do not cross exactly: it finds their closest points and uses the midpoint,
provided both points lie forward and the gap is small. Defaults reject angles below
0.25 degrees, distances outside 0.05–8 metres, and gaps above 0.03 metres. These editable
thresholds avoid unstable far-away or divergent intersections; no smoothing hides the raw result.

The scene puts its application world somewhere other than LOCAL, so the conversion is never the
identity: translation `(+0.35, +0.10, -0.20)` metres and a 30-degree yaw. With `W = worldFromLocal`:

```diffstatic {data-lang=text}
markerWorld = W * midpointLocal
clip = eyeProjection * eyeViewLocal * inverse(W) * modelWorld * vertex
```

The inverse in the view path is essential. Both gaze data and eye views refer to LOCAL;
the marker and reference geometry refer to application world. The shader uses `p * m`
with `m = eyeViewLocal * inverse(W) * modelWorld`. The scene reads the current tracking
sample before drawing the left eye and uses that same result for both rendered eyes.

## Headset check

1. Look at the grey reference cubes, whose world centres are `(0, 0, -1)`, `(0, 0, -2)`
   and `(0, 0, -4)` metres. Cyan and orange are the HTC left and right rays; the white
   marker should approach the fixation point. A magenta segment shows the accepted gap.
2. Check **Hold the last valid marker in world space**, then translate and rotate your
   head while looking elsewhere. The reported WORLD position must stay constant and the
   cube must remain anchored in the scene. With hold off, invalid tracking hides the cube.
3. Change `worldFromLocal` and relaunch the scene. WORLD coordinates should change while
   the LOCAL result and its physical location continue to agree with the new view conversion.
4. Lose focus or eye validity: live rays and the live marker must disappear. An explicitly
   held marker remains. An EXT-only runtime must show its ray without inventing a depth.

This hold assumes the same LOCAL reference-space origin. Restart the scene after a runtime
recenter; this starter does not compensate saved placements for reference-space resets.
Vergence depth becomes noisy at distance, so a rejected sample is a useful diagnostic.

## On the desktop

The desktop preview has no simulated eyes, so `NO_TRACKING` is the right answer there and the
binocular marker stays hidden; the grey reference cubes and the EXT dashed ray are what you can
check. **Nobody has run the four-step check above on an HTC-capable headset.** Tests cover the
coordinate conversion and the native bindings, and `dev_docs/OPENXR_EYE_INTERSECTION_AGREEMENT.md`
says which; a real eye tracker's calibration is not among them.

## The saved document

The working document is `eyeRayIntersection` in the local AField workspace. Its
`launch-eye-rays` macro launches the renderer and scene in order. The repository transcript
`dev_docs/agents/transcripts/openxr_eye_intersection.yaml` recreates the two-box graph in a
new document; the generated starting point ships the same Kotlin and shaders.
