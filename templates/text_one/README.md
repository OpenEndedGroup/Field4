# text_one — a reading space

A small CPU-driven hanging diagram: Field's 3D SIMDVerlet solver, FLines,
GPU-expanded strokes and SDF markers, and Slug analytic text coverage in both eyes.
The example passages are original placeholders. Replace them freely.

The document separates three jobs:

1. **Four example boxes** author named material and prepare distinct text runs once.
2. **Scores / example selector** controls that material over time, without rebuilding it.
3. **Drawing** uploads one CPU scene snapshot and renders it with the current matrices for both eyes.

Launch `text_one_launch`; `text_one_stop` stops Drawing before Reading space.
The five shader boxes export their `vertex` and `fragment` properties.
The renderer reads those live properties when **Reload drawing, blur and compositor shaders** is pressed.
Stop Drawing before rebuilding the graph. Re-running just the score leaves the physics,
text atlases and renderer in place.

## Writing material

```kotlin
val graph = readingDiagram {
    val peg = point("peg", -.7, .4, pinned = true)
    val baseline = point("baseline", 0, 0)
    link(peg, baseline) // physics only; a line is a separate choice

    val sentence = text("sentence", "between one breath and the next", baseline)
    val breath = sentence.word("breath")

    dot("beside-breath", breath.right.offset(x = .025f)) {
        radius = .004f
        shape = DiagramPointShape.RING
    }
    val mark = underline("breath-mark", breath, gap = .016f) {
        width = .002f
        color = vec(.85f, .43f, .12f, 1f)
    }
    dot("on-the-line", mark.at(.65f)) // full world arclength, not a control-point parameter

    arc("thread", peg.anchor, sentence.above, rise = .12f) {
        dashed(period = .03f, duty = .5f)
        arrow = .014f
    }
}
```

`point` positions, widths, dash lengths, radii, arrow size and anchor offsets are metres.
Coordinates are diagram-local, with Y up. The VR reader-facing transform applies to the
whole diagram afterwards; anchors are never tied to the user's eye pose.

A text run remains one shaped Slug outline. `word("breath", occurrence = 1)` selects
the second whole-word occurrence; `span("one breath")` selects literal text;
`characters(start, endExclusive)` uses UTF-16 indices. Selection never retypesets the word
or estimates its position from character counts. Anchors use the glyph origins of the
original complete layout. Splitting a shaped glyph cluster is rejected: select the complete
word or cluster instead. The current typography path is left-to-right, and the font must
contain all characters. This is not a claim of complete script or fallback-font support.

Spans expose `baselineLeft`, `baselineRight`, `baseline`, `left`, `right`, `center`,
`above` and `below`. Left/right sit partway up the font's ascent. Above uses 80% of ascent;
below is 2 cm below the baseline. Use `.offset(...)` to tune placement precisely.
`underline` is a line between baseline boundaries, displaced down by `gap`.

A dot may attach to a word, a physics point or any position on a path. Anchors follow the
current text scale, scored text offset and physical particle position. `path.at(.65f)`
follows the full untrimmed path: drawing the line in/out does not slide the dot along it.
Dots have their own opacity and cues, so they can outlive or precede the material they mark.

Inside the same `readingDiagram` block, arbitrary centreline geometry is straightforward:

```kotlin
path("swoop", shape = {
    val a = position(sentence.left)
    val b = position(sentence.right.offset(y = -.15f))
    FLine().moveTo(a).cubicTo(a + vec(0f, .2f, 0f), b + vec(0f, .2f, 0f), b)
}) {
    width = .0015f
}
```

The shape callback runs once per scene update, rather than once per eye. It returns a real
FLine; node `color` and `thickness` may vary along it. Recursive path anchors are diagnosed
by name. Cross-diagram anchors, duplicate names, duplicate physics links and more than four
constraints per particle are rejected rather than silently dropped.

## Scoring and transitions

```kotlin
val score = diagramScore(graph, duration = 12.0) {
    enter(graph.text("sentence"), at = 0.0, seconds = 1.0)
    drawIn(graph.path("breath-mark"), at = 1.0, seconds = 1.0)
    enter(graph.dot("on-the-line"), at = 2.0, rise = 0f)
    exitAll(at = 9.0, seconds = 2.0)
}
player.playback.score = score
```

`fadeIn`, `fadeOut`, `drawIn`, `drawOut`, `move`, `resize` and `enter` evaluate absolute
seconds with smoothstep easing. `drawOut` advances the start of the stroke. `move` adds a
metre offset; linked annotations follow their source. Resize applies to text and dots.
Each evaluation starts from a fresh pose, so scrubbing backward does not accumulate edits.
Multiple fade/scale tracks multiply, offsets add, and erasure takes the furthest start.
For an unusual envelope, `animate(item) { seconds, pose -> ... }` is an explicit intervention
on that fresh pose; keep it deterministic and free of persistent side effects.

Author selections with `group("verse-one") { ... }` inside the diagram builder. Groups
collect material created in their block and add no extra physics or draws. The score can
`fadeIn(graph.group("verse-one"), ...)`, `fadeOut(...)` or
`transition(graph.group("verse-one"), graph.group("verse-two"), at = 8.0, seconds = 2.0)`.
A transition crossfades disjoint groups and draws incoming paths in. Material can be kept
physically linked while its visible representations fade independently.

The selected player is `poem.current` in the document; the gallery routes the same renderer to it.
The score is separate from playback: `player.playback.seek(6.0)`, `.playing = false`,
`.repeat = false` and `player.restart()` are simple controls. A paused score still permits
physics to settle. Gravity and air movement both set to zero, followed by **Still proof / reset**, give a still proof; reciprocal distance constraints and layout springs agree
at rest. Writable particle `.rest` vectors provide layout targets for later re-layouts.
Score offsets affect drawing; they do not inject velocity or change the physics rest lengths.

## Physics and rendering

Every physics link is reciprocal. The solver runs at 120 Hz, with at most 12 catch-up
steps and a 100 ms elapsed-time cap for simulation. Score time remains real elapsed time.
A weak spring attracts each free particle toward its authored position. Air, gravity,
constraint gain and damping are separate controls. The examples use a few dozen particles and text runs; it does not need dust_six or task/mesh shaders.

DiagramGeometry handles multiple contours, lines, cubics and Close. Adaptive CPU centreline
samples carry per-node colours and widths. The vertex shader expands connected ribbons in
each eye, with shared miter vertices. Rounded stroke/dash/reveal ends use analytic fragment
coverage and fwidth antialiasing. Marker shapes are DISC, RING, DIAMOND and LEAF. Custom
shapes remain easy to write in the point fragment shader.

Text uses actual font outlines and Slug analytic curve coverage, with no bitmap atlas or
fixed-resolution distance field. Each distinct complete run is prepared once. A word that
crosses the near/eye plane is hidden to avoid an unstable perspective quad. The rendering
callback reads the current stereo matrices, steps physics once and uploads one snapshot,
after the target acquires its frame slot. Both eyes use that snapshot and their own current
predicted view. Only the diagram's deliberate reader-facing yaw is gently smoothed; eye
matrices never are. No TAA, delayed head pose or image-history composite is involved.

Colours are linear; drawing accumulates into a float target before blur and paint compositing.
The browser is drawn sharply above the diagram. Desktop preview samples the stereo
image, with an optional **Show both eyes side by side** control.

## Desktop and headset

The initial run is a desktop preview. For VR, connect WiVRn and start Field in a fresh JVM.
Before launching Reading space set `val headsetMode = true` in its code, then run
`text_one_launch`. Vulkan/OpenXR extension selection happens before the first renderer;
switching modes needs a fresh JVM. The VR path uses the existing high-resolution Field
browser and its keyboard/mouse forwarding and controller pane movement. Recenter is explicit.
The desktop mirror samples the same multiview image supplied to the headset.

## Five examples

- **Marked breath** — “Listen if you will / birdsong reflects off the walls”. Two complete
  runs in planes 5 cm apart; selected-word underlines, a dot beside a word, a bead on an
  underline and a labelled reflection arc. Inspired by the decoration hooks in `sentences`.
- **Constituency canopy** — “The quiet water carries the light”. The actual parser tree
  suspends word particles from a hierarchy, with restrained NP/VP labels, ring joints and
  shallow depth layers. Every tree edge is drawn; physical constraints use a bounded
  four-link stencil, with layout springs retaining the remaining branches.
- **Dependency undertow** — “Every small wave remembers the shore”. The existing nested
  arc layout provides the structure and handle offsets. Moving word anchors rebuild it in
  3D; beads and labels follow full arclength. Text remains in readable planes.
- **Sentence furniture** — “The wind carries a little weather through the room”. The
  Reed–Kellogg layout becomes a spatial mobile, with softened text slants and shallow shelf
  offsets. At 9 seconds the separate score crossfades it into a complete reading run.

- **A room for words** — mixed faces and inline styles, repeated words in different fonts,
  numeric weights, measured boxes and a cap-height ruler. Source: `typography_playground.kt`.

The example selectors reuse the same stereo renderer and prepared text resources. They
start at 6 seconds for inspection; **Restart entrances** shows the full score. The fourth
example's reading-line state is visible after its transition at 9–11.4 seconds.

The first four use Stanza parses, packaged in `/afield/sentences/examples/1.json` through `4.json`,
so reopening the document does not need the service. The source material and 2D layout
algorithms live in `src/jvmMain/kotlin/afield/graphics/text/sentences`; the example boxes rebuild their
connections around moving three-dimensional anchors. They do not turn a rendered SVG
into a flat texture. The optional `python/fieldsentences` parser server runs at `http://127.0.0.1:9500/`; POST UTF-8 sentence
text to regenerate a cache. Parser interpretations are editable source material, rather
than an assertion of a unique grammatical reading.

## Sources and recreation

The four live-code examples are in `templates/text_one/example_one.kt` through
`example_four.kt`; the separate score and selector are in `score.kt`. Core reusable types
live in `afield.graphics.scene.reading`. These are ordinary vertex/fragment modules, with
no dependency on the dust renderer.

`python templates/text_one/build_transcript.py` emits `create.json`, `launch.json` and
`stop.json` under `/tmp/text-one-transcripts`. Import them with the backdoor's `macro write`
commands, then run the create and launch macros in the intended document. The four parses are classpath resources, so no document-side file copy is needed.
The generator reads the StereoViews ABI and shader sources; it never edits metadata.edn directly.
For the public starting place, run:

```bash
uv run --group backdoor python templates/text_one/build_transcript.py --transcript dev_docs/agents/transcripts/text_one_vr.yaml
./gradlew formatBoxCode --args="dev_docs/agents/transcripts/text_one_vr.yaml"
```

Readable YAML source blocks keep Kotlin and shader properties distinct for documentation checks.

## Blur and paint

The drawing, marker and Slug modules add opacity-weighted RGB and opacity into a transparent
`R16G16B16A16_SFLOAT` two-eye target, with no depth attachment. Four separable blur passes
produce medium and broad images at half and quarter resolution. The final compositor and
the sharp browser surface follow on the same command buffer and current stereo views.

Green is a blur-control channel. Edit each item's green value to change its response; the
final image reconstructs green from red and blue. **Blur mix** starts at zero, and the
selector defaults to the medium image. `blurWeights` in box 11 defines the effective LUT.
The **Additive to weighted paint** control mixes accumulated light with opacity-normalized
colour. Alpha sums coverage from every stroke, point and glyph, rather than being cleared
to one or overwritten with display alpha. The paint threshold and knee set the crossover.

Box 10 exposes the blur shader; box 11 exposes the compositor. Drawing's reload button
reloads all shader pairs while preserving physics and text atlases. The settings also expose
the fold, grading, luminance-preserving split tones, FXAA and supersample-mask parameters.
To allocate a larger scene, stop Drawing and pass `sceneScale = 1.5f` (up to `2f`) to
`StereoDiagramRenderer`; eye output and browser resolution stay fixed. Filtering happens in
linear light before the final output transfer. There is no temporal image history.


### Resolution and stroke reveals

The OpenXR path uses the same `FieldVrWorkspace` target as `dust_six`: per-eye dimensions
come from the runtime's swapchains. The 2560 × 1600 allocation is the sharp browser
texture; 1600 × 1200 is only the desktop fallback. Reading space reports the actual
per-eye size. Restart Field after changing WiVRn resolution, or when changing
the `headsetMode` code switch, before launching the scene.

Unrevealed strokes emit no segments. The vertex shader rejects tessellation segments outside the reveal before rasterization, preventing antialiasing tails at joins from becoming stray dots. The fragment shader also rejects empty reveal
intervals and fades reveals shorter than a cap diameter, preventing a full-size cap
from popping in ahead of a line. The authored peg ring and path beads are separate
markers with their own score cues.

## Typography and text-relative geometry

The typography playground adds an inline italic word, an inline bold word, an alternate
face, identical strings in different fonts, numeric weights, word boxes, a run box and a
cap-height ruler. Each face/style combination is shaped and compiled into Slug outside
the frame loop. Physics, score transforms and headset pose do not rebuild typography.

```kotlin
val serif = Typefaces.named("Noto Serif", 100, strict = true)
val title = text("title", "quiet water", point("title-point", 0, 0)) {
    font = serif
    word("quiet").style { italic = true }
    word("water").style { bold = true }
}
rectangle("ink", title.inkBounds, padding = .008f) { width = .001f }
rectangle("no-descenders", title.bodyBounds) { dashed(.02f) }
underline("under-water", title.word("water"), gap = .015f)
dot("after-word", title.word("quiet").baselineRight.alongText(title, x = .01f))
textPath("ruler", title, shape = {
    FLine().moveTo(advance.left, capHeight).lineTo(advance.right, capHeight)
})
```

`font` accepts any `Typeface`, including `Typefaces.fromFile(...)`. A run also has `bold`,
`italic`, `weight` and `tracking`. A selected `word(...)`, `span(...)` or `characters(...)`
can override font/style through `.style { ... }`. Overlapping overrides use the last
matching style. Each style boundary starts a shaping context: kerning and ligatures remain
within that section. Styles and fonts are preparation-time choices; reprepare the player
and drawing after changing them. These are real glyph outlines, not shader dilation.

Numeric `weight` accepts 100–900, including intermediate values. AWT resolves available
faces or synthesizes weight. This is not an arbitrary OpenType variation-axis API; for an
exact specific weight, supply the named or file-backed face. Different fonts may resolve
intermediate requests to the same available face.

`frame = player.frame()` samples without advancing time or physics. `frame.measure(run)`
or `frame.measure(run.word("water"))` returns cached measurements **in font units**:

- `ink`: tight cubic-outline bounds, including italic overhangs and descenders.
- `advance`: the baseline pen interval, including selected whitespace; one baseline has zero height, multiple baselines span their baseline separation.
- `noDescenders`: ink bounds clipped at the baseline, useful for boxes sitting on it.
- `ascent`, `descent`, `leading`, `capHeight` (the ink top of H), and `xHeight` (the ink top of x).
- `baseline` and `hasInk`; whitespace has an advance but no ink.

Each span exposes `inkBounds`, `bodyBounds`, `emBounds`, `capBounds`, `xHeightBounds` and
`advanceBounds`. Each has `topLeft`, `topRight`, `bottomLeft`, `bottomRight`, edge midpoints,
`center`, and `.at(u, v)` (bottom-left is 0,0). The same properties are available on whole
runs. `run.words` enumerates Unicode word selections; `run.caret(index)` locates shaped
cluster boundaries. Indices are UTF-16, like JVM strings. Selecting through a ligature or
surrogate pair fails clearly instead of inventing a coordinate.

Whole multiline runs have union bounds; `baseline` is the first and `lastBaseline` the last. Use `run.line(index)` for individual baseline measurements. Inline font
styles currently require a single baseline; use separate styled runs for multiline text.

`textPath` authors an arbitrary FLine in font units, with x from the complete run's
baseline-left and y upward. `frame.textLine(run, fline)` applies the glyph transform to any
existing FLine, including control points. `span.local(x,y,z)` creates an anchor in font
units relative to that span's baseline-left. `anchor.alongText(run,x,y,z)` offsets in
**metres**, rotated with the run; generic `anchor.offset(...)` stays in diagram axes.
Rectangles, underlines and these anchors follow text translation, score scale and rotation.
Rectangle padding and underline gaps are metres. Nothing adds a second text-rendering pass.

## Focused document guides

The companion book is in `templates/text_one/guides/`: start with `00-start-here.md`, then
read typography, measurements, shapes/anchors, motion/scores, and recipes/maintenance.
Copy these Markdown files to the document's `guides/` alongside this README as
`01-reading-space.md`; Field discovers them through its Guides catalogue.
