# Recipes, edits and maintenance

## Small experiments worth trying

**A measured word room.** Put one sentence on one particle. Box a word using `bodyBounds`,
add a cap-height ruler, and put a dot at its baseline-right. Rotate the run or score its scale.
Everything should retain its relationship without manually updating coordinates.

**A typographic triptych.** Draw the same word in regular serif, italic serif and bold sans.
Use separate text items and compare their ink boxes and advances. The fifth example does this;
it also checks that repeated strings do not accidentally share the wrong Slug buffer.

**A sentence that changes furniture.** Draw the structural diagram first, then crossfade its
group into a whole reading line. Keep the graph construction separate from the transition.
Use **Sentence furniture** as a starting point.

**A weight study.** Prepare several identical words with weights from 300 to 700. Compare
actual outlines rather than assuming every numeric step changes the face. For exact weights,
load the intended font files. Arbitrary variable-font axes are a separate future extension.

**A baseline instrument.** Work almost entirely in `textPath`: brackets, short rules,
rectangles and cubic gestures. Use `advance` for pen spacing, `ink` for visible contact,
`bodyBounds` for a baseline-sitting room, and `capHeight`/`xHeight` for horizontal registers.

## Safe edit/reload routine

For shader edits, use **Reload Shader** (Alt+R) in the edited shader box. Each pass owns
its `moduleHere` module and generated accessors. Medium and broad blur each have separate
horizontal/vertical boxes, so an ordinary reload reaches exactly that pass. The active vertex shader must keep the reveal-segment rejection that removes stray dots.

For score edits, re-run Scores. That changes phrasing without preparing fonts or replacing physics.
Use the current example and score controls afterward; re-scoring may seek the players to the
example's inspection time.

For a new string, font, style, tracking setting or graph topology:

1. Stop Drawing.
2. Re-run the affected example box to prepare layouts and Slug data.
3. Re-run Scores so the gallery references the new players.
4. Re-run Drawing so its GPU atlas buffers match those prepared players.

For headset mode or WiVRn resolution, save the look and restart Field in a fresh JVM before
launching Reading space. The mode is the explicit `val headsetMode` code switch. The runtime
supplies headset eye dimensions; desktop fallback and browser texture sizes are independent.

## Troubleshooting by layer

| Symptom | Check |
| --- | --- |
| Dot anticipates an unrevealed curve | Stroke vertex reveal rejection; distinguish an authored marker with its own cue |
| A word box looks too narrow or too wide | Ink versus advance bounds; italic overhang; selected whitespace |
| Decoration stops rotating with text | Use a measured anchor, `textPath` or `alongText`, rather than diagram-axis offsets |
| “Same” word gets the wrong font | Reprepare player and renderer; GPU buffers are keyed by prepared shape, not string alone |
| Weight slider/code changes seem quantized | AWT may choose the same available face; use an exact named/file-backed font |
| A faint stroke looks surprisingly opaque | Opacity, exposure and accumulated overlapping light |
| Font or topology change is invisible | The old player/gallery/renderer still owns prepared data; follow the reload sequence |
| A requested character/word boundary is rejected | It splits a shaped glyph cluster, is out of range, or does not match the literal text |

## Source map for people and agents

The durable templates live under `templates/text_one/` in the Field4 checkout. The four
composition sources are `example_one.kt` through `example_four.kt`; the fifth is
`typography_playground.kt`. `score.kt` supplies independent scores and the selector;
`context.kt` creates the renderer and browser workspace; `drawing.kt` exposes rendering controls.
`build_transcript.py` builds the public and local operation transcripts from those sources.

Core code lives in `src/jvmMain/kotlin/afield/graphics/scene/reading/`:

- `DiagramDsl.kt`: scene items, font/style selections, graph and anchor vocabulary.
- `DiagramTypography.kt`: style resolution, preparation keys and inline shaping sections.
- `DiagramTextGeometry.kt`: cached measurements, cubic extrema and text-relative FLine helpers.
- `ReadingDiagramPlayer.kt`: prepared runs, physics, score sampling and live frame resolution.
- `ReadingGallery.kt`: switches prepared scenes and keeps different fonts distinct.
- `DiagramGeometry.kt`: CPU centrelines/markers and packed GPU input.
- `StereoDiagramRenderer.kt`: shared upload, per-eye thickening, Slug buffers and world placement.
- `DiagramCompositor.kt`: float sharp image, blur cascade, green selection and additive light.

The shared `Typeface` and `Typefaces` APIs are in `afield/graphics/text/typography.kt`.
Reading shaders are in `src/jvmMain/resources/afield/reading/`.

The prepared layout, rendered outline and measurement transform must agree. A shape cache key
must distinguish text, font, inline style sections and tracking. Renderer Slug data must be keyed
by prepared shape rather than the visible string. Font compilation and GPU allocation belong
outside the frame loop. Measurements use cached CPU data, never GPU readback.

## Verification

The focused suites are:

```bash
./gradlew jvmTest --tests 'afield.graphics.scene.reading.*' --tests 'afield.graphics.text.TypographyTest'
```

They exercise font/style identity, Unicode boundaries, multiline baselines and carets,
whitespace, descenders, analytic cubic bounds, physics and score transforms. CPU tests use an
existing native atlas fixture only where rendering is not under test. GPU verification prepares
and renders the actual fonts through Slug; it must not substitute a bitmap or fake a capture.

Public documentation is `public/docs/sentence_diagrams.md`. Its native capture is driven by
`dev_docs/agents/transcripts/docs_capture/text_one_native.yaml`. Captured images come from the
real docs runner; the main public starting-point transcript is `text_one_vr.yaml`.

Keep edits in the live document as well as the durable template when appropriate. Preserve the
user's current score, compositor values and unrelated document changes before rebuilding or
restarting. The version-1 document archive described in the index is a recovery copy, not a
reason to overwrite the working document.
