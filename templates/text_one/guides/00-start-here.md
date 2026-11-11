# A reading space: start here

`text_one` is a small, spatial reading instrument. Words hang from a CPU physics graph;
FLines describe its drawing; Slug renders the actual font outlines in both eyes. The
score controls how this material appears, moves and disappears.

The layers are deliberately separate:

| Layer | Owns | Edit when |
| --- | --- | --- |
| Example boxes | Particles, constraints, words, fonts, paths and markers | The composition or typography changes |
| Scores | Opacity fades, offsets and text/marker scale | The phrasing or timing changes |
| Drawing | Current eye views, placement, GPU thickening, Slug and compositing | The rendering or reading distance changes |

Choose an example in **Scores / example selector**. **Show complete diagram** is useful
for inspecting the composition; **Restart entrances** restores its animated introduction.
The score-time slider is seekable. Pausing the score does not stop physics or headset poses.

Global looks can be stored, restored and blended in **23 · Global looks / keyframes**.
See [Global keyframes](16-global-keyframes.md) for the saved values and code API.

## Read these next

- [Spatial audio](08-spatial-audio.md): a world-space WAV, headset pose, and WiVRn output.
- [Word blur](07-word-blur.md): slow, independent Gaussian entrances and exits.
- [Typography and style](02-typography-and-style.md): fonts, bold, italics, numeric weights and inline selections.
- [Measurements](03-measurements.md): ink, advances, words, baselines, cap height, x-height and descenders.
- [Drawing in text coordinates](04-shapes-and-anchors.md): rectangles, rules, dots and arbitrary FLines that remain attached to text.
- [Motion and scores](05-motion-and-scores.md): control entrances and exits independently of graph construction.
- [Recipes and maintenance](06-recipes-and-maintenance.md): experiments, module map and a safe edit/reload routine.
- [Full reading-space guide](01-reading-space.md): the original walkthrough, controls, compositor and implementation notes.

The fifth example, **A room for words**, is the typography test bench. It combines styled
words in one run, the same word in three different faces, a numeric weight row, word boxes,
a run box, a baseline attachment and a cap-height ruler. The other four examples retain the
sentence-diagram compositions.

## Two coordinate systems

The diagram uses **metres, Y up**. Particles, line thickness, marker radius, rectangle
padding and underline gaps are in metres. Shallow Z differences give the mobile depth.

Prepared typography uses **font units, Y up**, with the origin at the run's baseline-left.
`player.textScale` converts font units to metres, then each text item's scale and the score's
scale are applied. `textPath`, `textLine` and the measured anchors use exactly that transform.

The final world placement and eye projections happen afterward. Text remains planar;
its supporting graph and the reading space can occupy three dimensions.

## Desktop and headset

Set `val headsetMode = true` in Reading space **before creating any renderer in a fresh
Field JVM**. Connect WiVRn, then launch the document. Changing this switch or WiVRn's
resolution requires restarting Field. The mode is an explicit line of code, not a custom
property that might disappear across a restart.

Headset eye dimensions come from OpenXR, as in `dust_six`. The 2560 × 1600 allocation
belongs to the sharp browser surface. The 1600 × 1200 allocation is only the desktop fallback.
The resolution output reports the actual per-eye dimensions.

Both eyes use the current predicted pose. The float scene, blur cascade and final green-channel
compositor run in the same frame and command buffer. The browser is drawn afterward and
stays sharp. There is no previous-frame image or temporal antialiasing in this chain.

## Version 1

The white-ink composition, smaller grammatical labels and Noto Serif Light treatment are
checkpointed in source commit `fc33fd4b2`. A complete document archive is retained at
`../.checkpoints/text_one-v1-20261107.tar.gz`, relative to this document's directory.
Extract that archive into a separate workspace to revisit it without replacing this document.

- [Elastic connections](10-elastic-connections.md): multi-node threads, sliding word anchors, length and curvature forces.

- [Drop caps](11-drop-caps.md): opening initials spanning authored lines, with measured anchors.

- [Individual reading planes](12-individual-reading-planes.md): independent reader-facing text, endpoint dots and the source passage.

## The current passage and movement

All nine examples now quote only the supplied passage, retained verbatim in
`files/source-passage.txt` in this document. Authored line breaks change layout, not wording.
The parser-based examples use retained real CPU Stanza analyses of three sentences from it.
Their curves remain; grammatical annotation labels are omitted from the reading space.
All line entrances and exits fade full curves, with small dots at both ends of arcs. The dots precede their curves by about 0.7 seconds.

**20 · Move through the reading space** uses the left controller stick for smooth movement:
forward/back follows the horizontal direction of your gaze; left/right strafes. Movement
has a radial dead zone and analog speed, initially 0.65 metres/second. The right controller
retains browser scrolling, clicking and panel manipulation. **Recenter poem** brings the
composition back in front of you. Physical head motion still uses the current OpenXR pose.

## WiVRn body tracking and OpenXR validation

The reading-space startup disables **OpenXR** validation before creating the renderer.
The installed core API layer does not register spaces created by `XR_MNDX_xdev_space`,
the vendor extension used for WiVRn’s body trackers. It consequently rejects those spaces
in `xrLocateSpace`. `Options.validateOpenXR = false` avoids that failure. Vulkan validation
has a separate switch and remains available. Changing instance layers requires a fresh Field JVM.

The live Quest setup reports hips, chest, lower arms, lower legs and feet. Together with the
head and controller endpoints these provide 11 body interaction capsules. Tracked finger
capsules are additional and require valid hand-joint poses; controller mode does not invent them.

- [Body contact and debug nodes](09-body-and-hand-interaction.md): wafting, actual SIMD contacts and force lines.
- [Text frames](13-text-frames.md): shared border/connection standoff and additive light.

- [Two readings](15-two-readings.md): the combined staggered fade/blur, frosted surrounds, elastic link and body-interaction sequence in example 9.

[Mechanical text holders](17-mechanical-text-holders.md) introduces examples 10–13: eight corner extensions and two-ended frosted frusta.
