# Two readings: the combined example

Choose **9 · Two readings** in box **6 · Scores / example selector**. It starts from
the beginning and plays a 36-second cycle. Box **22 · Two readings / composed sequence**
owns the graph and score; its **Replay this sequence** and **Hold both readings** buttons
restart the clock or pause it at 16 seconds. Pausing the score leaves body interaction
and physics running.

Two excerpts from the supplied passage occupy independent, slightly angled planes:

> My words found a few last handholds of their own once my body collapsed.

> A whole school of ideas swam before me, but I could hook only one: don’t believe what you see.

Only line breaks are changed. The second reading begins five seconds later and sits
52 cm behind the first. Their silhouettes overlap enough to catch the later text through
the near surround. Each surround is the padded union of its run's line rectangles.
The same exposed boundary anchors an elastic thread, with a small dot at each end.

| Element | Entrance | Exit |
| --- | --- | --- |
| Near text | fade 0–5s; blur resolves 0–8s | fade 22–28s; blur dissolves 22–29s |
| Near surround | fade 0.7–5.7s | fade 24–29s |
| Far text | fade 5–10.5s; blur resolves 5–14s | fade 27–33s; blur dissolves 26–34s |
| Far surround | fade 6–11s | fade 28–34s |
| Endpoint dots | fade 6.4–7.4s | fade 26–31s |
| Elastic thread | fade 7.1–10s | fade 25–31s |

All lines fade as complete curves. No reveal/draw-in tracks are used. Both runs are
fully readable from 14 to 22 seconds, and everything has faded away by 34 seconds.
The last two seconds let the room breathe before repeating.

## How the pieces fit

The word-local Gaussian caches handle the text's blur; the frame-local clipped RTTs
handle the frosting behind each surround. These are independent effects. The frames
and text share the current physics positions and authored orientations, while both
eyes always use the current predicted headset pose.

Each text run hangs on an **unpinned SIMD particle**. Box **21 · Body interaction /
diagnostics** attaches its normal body-force callback to this player as well as the
other gallery players. Move close with the left stick, then waft a run with your hand
or body. Its frame follows the run; the thread endpoints slide along the exposed
frame boundaries while its eight intermediate nodes catch up. After the force stops,
layout springs return the text toward its resting position.

The authoring defaults are spring 3, damping 3, zero gravity and zero air movement.
The gallery's physics controls can override these. Head-based reorientation is off
for these two authored planes; physical head movement changes only the view.

Box **12** retains the shared frost opacity, radius, brightness, black level and blur-space
gamma controls. Box 21 retains body enable/strength/reach controls and the skeleton,
SIMD-node and applied-force diagnostics. The browser remains a separate sharp overlay.

## Editing and replaying

Change text, spacing, depth, frame standoff, connection dynamics and cue timings in
`two_readings.kt` (box 22). The named IDs make the score read against the graph directly.
Box 6 preserves this example's score when rescoring the other gallery examples.

Re-run box 22 and then box 6 after changing the graph or score. If the text or reserved
blur range changed, rebuild Drawing and its body/navigation children so the immutable
Slug atlases, blur caches and body-node capacity include the new material. Changes to
Field's Kotlin renderer API itself require a fresh Field JVM.

The frosted panels use independent replacement corrections, as in guide 14. They do
not recursively blur other frost surfaces. This example is intended to explore the
sparse, layered result with live motion and body interaction.
