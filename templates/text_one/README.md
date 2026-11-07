# text_one — a reading space

A small CPU-driven hanging diagram: Field's 3D SIMDVerlet solver, FLines,
GPU-expanded strokes and SDF markers, and Slug analytic text coverage in both eyes.
The example passages are original placeholders. Replace them freely.

The document separates three jobs:

1. **Four example boxes** author named material and prepare distinct text runs once.
2. **Scores / example selector** controls that material over time, without rebuilding it.
3. **Drawing** uploads one CPU scene snapshot and renders it with the current matrices for both eyes.

Launch `text_one_launch`; `text_one_stop` stops Drawing before Reading space.
The three shader boxes export their actual `vertex` and `fragment` properties.
The renderer reads those live properties when **Reload the three shader pairs** is pressed.
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
constraint gain and damping are separate controls. The four examples use a few dozen particles and text runs; it does not need dust_six or task/mesh shaders.

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

Colours are linear; normal alpha blending occurs once into the sRGB stereo target. The
browser is drawn sharply above the diagram. Desktop preview samples the actual stereo
image, with an optional **Show both eyes side by side** control.

## Desktop and headset

The initial run is a desktop preview. For VR, connect WiVRn and start Field in a fresh JVM.
Before launching Reading space set its `headsetMode` property to the text `true`, then run
`text_one_launch`. Vulkan/OpenXR extension selection happens before the first renderer;
switching modes needs a fresh JVM. The VR path uses the existing high-resolution Field
browser and its keyboard/mouse forwarding and controller pane movement. Recenter is explicit.
The first validation captures are desktop GPU renders; headset readability still needs a
live headset check.

## Four examples

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

The example selectors reuse the same stereo renderer and prepared text resources. They
start at 6 seconds for inspection; **Restart entrances** shows the full score. The fourth
example's reading-line state is visible after its transition at 9–11.4 seconds.

These use real Stanza parser responses, packaged in `/afield/sentences/examples/1.json` through `4.json`,
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
The generator reads the current StereoViews ABI and real shader sources; it never edits
metadata.edn directly. The current document has `text_one_create`, `text_one_launch` and
`text_one_stop` installed already.

Validation: eight focused CPU tests pass. Real desktop GPU captures cover all four examples,
the fourth example after its crossfade, shader reload and two distinct stereo eye layers.
These are saved under `files/previews/`. Headset readability and interaction remain a live
hardware check. The document is left showing example one at six seconds, paused for inspection;
use the selector or **Restart entrances** to explore.
