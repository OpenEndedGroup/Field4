# Sentence diagrams

Reads the JSON from the shipped `fieldsentences` parser and lays out both halves of
a sentence diagram: a **constituency tree** above a row of words, **dependency arcs**
below it. Geometry only — the drawing is yours.

## Quick start

Hold a [`SentenceDiagram`](SentenceDiagram.kt), set properties, ask for output again:

```kotlin
import afield.graphics.text.sentences.*

val d = SentenceDiagram("Listen if you will birdsong reflects off the walls")

d.layerGap = 60.0
d.heights = Heights.ByTreeDepth(jitter = 20.0)

d.flines().curves          // List<FLine>, ready to draw
d.flines().all()           // curves plus labels
d.svg()                    // or a string of SVG
d.diagram()                // or the raw geometry
```

The parse is cached (it costs a round trip); the layout is not (about 0.25ms), so
re-rendering after a tweak is free and safe every frame. Animating is just assignment:

```kotlin
d.heights = Heights.Custom { i, _ -> 300.0 + 80 * sin(i * 0.7 + t) }
_self.lines.addAll(d.flines().curves)
```

`Heights` covers `ByTreeDepth`, `Flat`, `Wandering`, `Explicit` and `Custom`. For control
over x as well, set `d.placements` directly — that overrides `heights` entirely, and is the
hook to use when positions come from your own animation or physics.

Sources: `d.text` (asks the server), `d.load(json)`, `d.loadFile(path)`, `d.use(sentence)`.
`d.index` picks between sentences; `d.problems()` reports any inconsistency in the parse.

The knobs below are on the class directly (`d.laneGap`, `d.treeHandle`, …), delegating to
`d.config` so the defaults live in exactly one place. Anything not surfaced is reachable
with `d.config = d.config.copy(...)`. Setting `d.fontSize` also replaces the metrics, so
word widths and drawn text stay in step.

### Decorating

Six hooks, one per kind of thing the diagram draws. Each is handed the geometry that was
produced plus everything around it, and returns whatever extra FLines you want:

```kotlin
val deco = decorations {
    arc       { dot(it.lowest(), 2.0 + it.reach * 0.8) }        // bead, sized by reach
    connector { ring(it.pointAt(1.0), 3.5) }                    // ring where tag meets word
    text      { if (it.kind == TextKind.RELATION) box(it.bounds.expand(2.0)) else null }
    treeEdge  { if (it.child.leaf) tick(it.curve, 0.5, 8.0, it.flipY) else null }
    underline { segment(it.left, P(it.left.x, it.left.y + 6)) }
    node      { if (it.word == null) dot(it.position, 1.0) else null }
}

lines(d.flines(decorate = deco).everything())
```

Return an `FLine`, a list of them, or nothing — `null`, `Unit` and a lambda whose last
statement isn't an expression all mean "no decoration", which keeps one-liners short while
hacking.

Each `Part` carries more than the shape it decorates. `ArcPart` knows its `edge`, `label`,
`reach`, and the `WordBox` at either end (`governor` is null for the root arc); `TreeEdgePart`
has both `PlacedNode`s so you can ask whether the child is a leaf; `TextPart` gives the `Rect`
the rotated label occupies and can hand you its glyph `outline()`. Everything reaches the whole
`Diagram`, so any hook can consult the sentence, the other words, or the config.

Use `it.at(p)` rather than building points by hand, and pass `it.flipY` to helpers — that way
decorations land in the same space as the lines they decorate whichever way up you are working.

Builders provided for the usual marks: `dot`, `ring`, `segment`, `box`, `tick`.

Results land in `DiagramFLines.decorations` and are included in `curves`/`everything()`, drawn
last so they sit over everything. Nothing is spliced into the per-kind lists, so `arcs` stays
exactly the arcs.

Decorations show up in `d.svg()` as well as on screen, because both go through the same FLines.

### Text sizes

Three sizes, all living in `LayoutConfig` so nothing can disagree:

```kotlin
d.fontSize = 26.0        // words       (config.wordSize)
d.labelFontSize = 11.0   // relations   (config.labelSize)
d.tagFontSize = 9.5      // ROOT/S/NP   (config.tagSize)
d.arcLabels = false      // skip relation labels entirely
```

`labelFontSize` is a layout setting as much as a drawing one — the collision solver reserves
space using it, so the arcs move when you change it. Same for `arcLabels = false`: with the
labels gone there is nothing to keep the arcs clear of, and the arc field pulls in toward plain
nested semicircles.

These sizes flow all the way through: `flines()` builds a `TextFonts` from the config, so
`everything()` needs no arguments at all and still honours `wordFont`:

```kotlin
lines(d.flines().everything())
```

### Text



`lines()` draws **no text**. Its renderer (`afield.graphics.someSkiko.outputTo`) honours
geometry plus `color`, `thickness`, `stroked` and `filled` — there is no text-carrying
property it looks for, so a label attached to an FLine as a property is silently invisible.
`textFLine()` produces a single `moveTo` and nothing else: it passes the renderable filter
and then draws an empty path.

So text becomes **glyph outlines** — real FLines, drawn like everything else:

```kotlin
val d = SentenceDiagram("...")
d.wordFont = font("Georgia", 26.0)
lines(d.flines().everything(word = d.wordFont!!))
```

`everything()` is `curves + textOutlines()`. `textOutlines()` alone gives just the type, one
FLine per label, filled and unstroked, rotated for arc labels. They come out with real
geometry — a word is a few hundred nodes of contour.

This is also what the original PDFs contain: their text is outlined rather than set, which
is why no strings can be extracted from them.

Setting `wordFont` also switches the layout onto `Java2DMetrics`, so words are measured with
the same font that gets drawn. Worth doing before fussing over `wordGap` — the built-in
`ApproxSerifMetrics` is about 7% narrow on "birdsong" at 26pt.

For new text, prepare the optional environment with `./gradlew prepareSentenceParser`, then start the parser server, so each sentence costs milliseconds rather than a ten
second model load:

```bash
optional/sentence-parser/.venv/bin/python python/fieldsentences --serve 9500
```

## Measuring whole texts

`DependencyLength.kt` measures dependency length minimisation the way Futrell, Mahowald and
Gibson did (PNAS 112, 2015): the total of |head − dependent| over a sentence, punctuation and root
arc excluded, against random projective reorderings of the same tree.

```kotlin
val text = SentenceTexts.load("gettysburg")       // retained parse; no parser needed
for (m in text.measure()) println("${m.length} words  ratio ${m.ratio}")

val tree = text.sentences[4].tree()!!             // punctuation removed
tree.length()                                     // as written
tree.length(Linearization.freeRandom(tree, java.util.Random(1)))
tree.length(Linearization.optimal(tree))
tree.lengths(samples = 100)                       // all baselines at once
```

The retained passages and their parses live in `src/jvmMain/resources/afield/sentences/texts/`:
a `.txt` passage and the `.json` that `tools/parse_sentence_texts.py` writes from it with the
prepared parser. The JSON keeps words, tags and the UD tree only, about 1 kB a sentence.
`tree.sentence(order)` turns any order back into a `Sentence` for `SentenceDiagram`
(`d.scheme = Scheme.UD`); `tree.arcLines()` draws arcs alone for small multiples. The user page is the
"Dependency length in real texts" section of `public/docs/sentence_diagrams.md`.

## Seeing it

`Main.kt` dumps SVG, which is the fastest way to judge a config change:

```bash
java -cp <cp> afield.graphics.text.sentences.MainKt --in 3b.json --depth --out 3b.svg
```

`--flat`, `--wander SEED`, `--depth`, `--heights CSV` cover the height modes;
`--check` validates the JSON without drawing.

## How the layout works

The **words are the input**. Everything else is derived from them.

Going up: each POS tag hugs its own word at `preterminalGap` above it, while internal
nodes sit on `layerGap` depth layers. That split is what makes arbitrary word heights
work — the tag follows the word wherever it goes. Layers are then clamped so a parent is
never left below one of its children (`NodeY.LAYERED_CLAMPED`), and `treeTop` is derived
from the words so the layers always have room. `NodeY.HUG_CHILDREN` abandons layers
entirely and lets the tree follow the words.

Everything sits on one **50pt grid**: `layerGap`, `preterminalGap`, `laneGap`,
`baselineMargin` and `wordGap` all default to 50, measured off the originals (36 of 40
tree-edge curves in `3b.pdf` have `|dy|` within 0.9 of 50).

`wordGap = 50` also reproduces the originals' page width almost exactly — 5a comes out
**2006pt** wide against the original's **1994.5**, 3b **1261** against **1320.8**. That
horizontal room is most of what makes their arcs read as arcs rather than as narrow loops,
since arc depth is proportional to span.

The tree does not stop at the POS tag. A `wordEdge` carries on down to the **start** of the
word, landing on the top of the underline bracket's left tick so the tree runs continuously
into it. The bracket overhangs the word by `underlinePad` at each end, which is what keeps
the connector clear of the first letter. Tags
anchor near the word's start rather than its centre (`preterminalAnchor = 0.0`,
`preterminalOffset = 5.0`, both measured), which pulls each tag left and, because parents
are placed from their children, leans the whole tree left of where centring would put it.
Draw `wordEdges` with `treeEdges`; they use the same handle rule.

Words absent from the dependency graph still get a connector — the connector belongs to the
constituency tree, which covers every word, while the underline reflects dependency-graph
membership. The originals do this too (`of` in 3b has a connector but no underline).

Going down: each arc gets a lane, and an arc sits one lane deeper than anything whose
span it overlaps. Short arcs stay shallow, long ones dip beneath them, which is the
nesting the original diagrams show.

### Arc depth and shape

Arc depth is **proportional to span** (`ArcDepth.SPAN`), not measured down to a shared
baseline. That is what the originals do: measuring the arc control points in `3b.pdf` gives
a depth/span ratio of exactly **0.500** on 6 of 11 arcs, median 0.500. A cubic semicircle
would be 0.667, so these sit just inside one. Nesting then falls out for free, since a
containing arc always spans further than what it contains.

Arcs are then pushed down until none of them crosses a word or a relation label
(`arcAvoidText`). Without it, an arc between two shallow words hangs at their height and cuts
through anything deeper in between — which happens as soon as the words are packed close
horizontally but spread out vertically.

The solver is a greedy sweep: each arc only has to clear the words plus the labels of arcs
settled before it. It checks both the curve and where the label lands, since a label that
comes to rest on a word is just as bad. Settling in *some* fixed order is what makes it
terminate — pushing every colliding arc at once moves two overlapping labels down *together*,
so they stay overlapped and descend for ever.

Four details, each of which was a visible bug before it was a rule:

- **Longest span settles first.** A long arc is deep anyway, so its label is deep and out of a
  short arc's way, leaving the short ones near their natural depth. Shortest-first instead puts
  small labels at shallow depths and every larger arc has to dodge them — a cascade that drove
  mid-size arcs to `depth/span` of **1.89**, against 0.4 natural.
- **Push is bounded by the arc's own span** (`arcMaxPushRatio`). Without a bound, a short arc
  settled late chases a gap between deep labels and ends up at 6× its span — it stopped reading
  as an arc long before that. An arc that finds no room stays at its natural depth and is
  marked `!clear`: a slight overlap beats a grotesque shape.
- **Labels are tested as oriented rectangles**, not axis-aligned bounds. An 11pt line set at
  −65° has bounds 71 tall; testing those treats it as a far bigger obstacle than it is.
- **Restarts.** The settle order is the solver's one real degree of freedom, and longest-first
  is a decent heuristic but not a strong one — over 200 random orders, about half beat it. So
  it tries longest-first plus `arcSettleRestarts` shuffles and keeps the best, scoring
  overlaps against total depth via `arcOverlapPenalty`. Seeds are fixed, so the same input
  always gives the same drawing.

On restarts being worth it: at 12 they are a clear if modest win (on 3b, one overlap becomes
none *and* total push drops). Pushed to 40 they start buying overlap reductions with real
distortion — `push` doubling, worst `depth/span` past 1.0 — which is `arcOverlapPenalty`
asserting itself rather than the layout genuinely improving. The remaining overlaps on a dense
sentence are places where there simply is not room.

Cost: about 3.5ms on a 21-word sentence with 12 restarts, 0.4ms with none, 0.2ms with
`arcAvoidText = false`. Fine inside a 60fps budget, but if you are animating word positions
every frame, `arcSettleRestarts = 0` is where to start economising.

### Nesting

**No arc crosses an arc whose token range contains its own** (`arcNesting`). If `advcl(1,6)`
spans `nsubj(3,5)`, you see one arc sitting inside the other, never the two cutting through
each other — nesting reads as structure, a crossing reads as a mistake.

Two things are needed for it, and they were both wrong at first:

- `arcClearsWords` measures every arc from below the deepest word it **spans**, not just its
  own two ends. This is what makes nesting hold *by construction*: a container spans a
  superset, so its reference depth is at least as low and its span strictly greater, so its
  natural depth is strictly greater. With it off, an inner arc between two deep words sits
  below a container between two shallow ones and no amount of pushing can fix it, since the
  inner arc cannot rise above its own words.
- The solver then never pushes a contained arc past its container, and *lifts* one that starts
  out crossing. The lift matters: a short arc at the mouth of a long one is crossed at its
  natural depth, because the long arc has to rise to meet its own word exactly where the short
  arc bellies out. Pushing down makes that worse.

Containment is tested point by point along x, not against any single depth — two arcs sharing
an endpoint both rise to the same word there, so a ceiling taken at the endpoints reads as no
room at all and crushes the inner arc into the words.

What survives: arcs that share an endpoint graze by about 1pt right at that shared word.
That is unavoidable — they leave from the same place — and invisible. `arcNestClearance` is
the tolerance before a dip counts.

Worth knowing that the originals do *not* enforce this; arcs cross freely in 3b. It costs a
few more label overlaps than letting arcs escape downward, which is the trade being made.

`ArcDepth.LANES` is the older behaviour: lanes below a baseline clear of every word. It
guarantees arcs never cross anything, but every arc plunges to the same depth however short
its span, which reads as pendulous. Under it, lane depth is compensated for word height
(`evenLanes`) — a cubic only reaches about three quarters of the way to its control points,
so an arc's real depth is `0.75 * laneY + (y0 + y1) / 8`, and with words at very different
heights that last term dominates, sinking a shallow lane below a deeper one and stacking
labels on top of each other.

Three shapes, all with depth as above:

- `SHEARED` (default) — both handles the same length, each dropping below its *own* word, so
  the arc drapes parallel to the line between the two. The graceful option where the words
  differ in height, and it keeps vertical departures so arcs stay clear of their neighbours.
- `LEVEL` — both handles to one shared depth. The strict original; slightly lopsided across
  a big height difference.
- `PARABOLA` — a quadratic through one apex, degree-elevated. A true parabola, but arcs leave
  their words at an angle, so they travel sideways sooner and pass nearer whatever lies
  between; on a dense sentence the apexes bunch and the labels collide. `SHEARED` gets the
  drape without either problem.

Every curve is a `Cubic`, with `pointAt`/`tangentAt`/`angleAt` so you can put a label
anywhere along one. Arc labels come pre-rotated and pre-flipped so they never read upside
down.

### Tree edge handles

Both handles on a tree edge point **straight down out of the parent and straight up out of
the child** — along y, never toward each other, however far apart the two nodes are
horizontally. Handle length is `treeHandle` as a fraction of the edge's vertical drop:

```
c1 = (a.x, a.y + 0.6 * dy)
c2 = (b.x, b.y - 0.6 * dy)
```

That comes from measuring the original PDFs rather than guessing. Converting `3b.pdf` with
`pdftocairo -svg` and reading the actual Bézier control points gives, for all 40 tree
edges, a horizontal handle offset of *exactly* zero — across spans from 4pt to 362pt — and
a vertical fraction of 0.6 on 39 of the 40 (the odd one is 0.5 and looks hand-nudged).

Since two 0.6 handles reach past each other, the curve blends and flares gently through
the middle rather than meeting in a tight S. That overshoot is what gives those diagrams
their particular line quality. At 0.5 the handles just touch; below that edges straighten
toward diagonals.

`treeHandleSpread` leans handles sideways into their direction of travel. The originals use
zero; it exists in case you want the other look.

The dependency arcs already worked this way — `c1 = (from.x, laneY)`, `c2 = (to.x, laneY)`,
both vertical, which is exactly what the PDF's arc curves do (measured: `c1y == c2y`, both
handles on the endpoint's x).

## The heights are not arbitrary in the originals

Measuring `3b.pdf` suggests the 2011 word baselines are close to `depth * layerGap +
constant` — each word hanging at the depth of its own preterminal. Read off the rendered
page: "Listen" at depth 4 sits at 391 (predicted 391), "birdsong" at depth 3 at 313 (317),
"you" at depth 7 at 617 (613).

That is `Placements.byTreeDepth`, and it explains the look: because the drop per layer
equals the tree's own `layerGap`, every POS tag lands exactly on its depth layer, which
is why those diagrams feel ordered despite a very uneven row of words. Pass `jitter` to
loosen it without losing the shape.

So: `byTreeDepth` to sit near the originals, `reading` with your own height function to
go anywhere else.

## Which words get an underline

`WordBox.underlined` (from `Token.inGraph`). Collapsed Stanford Dependencies delete
prepositions and conjunctions as graph nodes, folding them into relation names — `of`
becomes part of `prep_of`. Those words have no arcs, and in the originals they have no
underline either. Draw the underline when `underlined` is true and that falls out for
free.

## Reed-Kellogg

A second layout, in [`Kellogg.kt`](Kellogg.kt), rather than a mode of the first one. The
dependency runs the other way: in `layout` the words are the input and the structure is derived
around them, whereas here the structure is the input and the words are placed *on* it, along
baselines and slants. Nothing about "a row of words" survives, so it needs its own pass.

```kotlin
val d = SentenceDiagram("Listen if you will birdsong reflects off the walls")
d.wordFont = font("Georgia", 26.0)

lines(d.kelloggFLines().everything())
d.kelloggSvg()
d.kellogg().problems()      // coverage check -- see below
```

Collapsed Stanford Dependencies is the right input because it has already folded prepositions and
conjunctions into relation names, and Reed-Kellogg wants the same folding one step further:
`Role.MERGED` writes "will push" and "step off" as single things on the baseline, the way the
tradition does. The folded-in preposition arrives in `Edge.marker` rather than as a token, which
is why `KNode` carries it — `prep_of(walls, plant)` puts *of* on the slant and *plant* on the
shelf below, which is exactly the shape R-K draws.

`roleOf` maps each relation to one of nine roles: `SUBJECT` left of the bisector, `PREDICATE`
right of it, `OBJECT` past a half-bar, `COMPLEMENT` past a backslash, `MODIFIER` on a slant,
`PREPOSITION` on a slant with a shelf, `CLAUSE` on its own baseline under a dotted riser,
`MERGED` into a neighbour's text, `DROPPED`.

### Why there is no collision solver here

Everything is built at the origin and translated into place (`Cluster`), so the measuring and the
drawing are the same code — no second copy of the geometry to keep in step. Room is reserved on
the way up, and the reservation is the one piece of real maths: a modifier is written *along* its
slant, so at angle θ it projects `cos θ · width` horizontally and carries its own subtree below
that. **How much room a child needs depends on its depth, not just its text** — Reingold-Tilford's
reserve-width recursion with slanted edges. Reserve correctly and nothing can overlap, which is a
pleasant contrast to the arc field.

### The check that matters

`KelloggDiagram.problems()` verifies **coverage**: every token in the graph must appear on the
page, either on a line of its own or folded into a neighbour. Placing words by role makes a
silently dropped word the easy failure, and it caught exactly that twice while this was being
built — first an `OBJECT` nested under a `MODIFIER`, then a subject that was itself a predication.
The fix in both cases was making attachment total: `attach` has a branch for every role, and
`hang` takes the set of children the caller already drew rather than skipping by role.

### The slants have to know their own text

`slantAngle` is a legibility setting as much as a stylistic one, since modifiers are written along
their slant — much past 45° and they stand too near vertical to read.

Three things follow from a word being *written along* the line that carries it, each of which was
a visible collision before it was a rule:

- **`Cluster.bounds` measures footprints, not anchors.** It used to union the text's anchor
  *points*, so reservation treated every word as zero-sized and only the slant's own length stood
  in for it. `KWord.footprint` is the real rotated box — the perpendicular extent matters, since a
  16pt line on a 45° slant reaches about 11pt either side of its line.
- **`KWord` carries its own `size` and `width`.** The layout was measuring slant text at 26pt
  while the renderer drew it at `0.62 ×` that: reserving one size and drawing another, the same
  bug as the arc labels. Now `modifierSize` lives in the config, the word carries what it was
  measured at, and the bridge draws each word at that size.
- **A slant starts its text below the descenders of whatever it hangs under**, and its own
  children start *past* its word. Children of a slant run parallel to it, so beginning at the
  slant's origin drives them straight through the parent's own text.

`nestAngleStep` steepens each nested level by 14°, capped at `maxSlantAngle`. Without it a child
slant anchored on its parent at the same angle is exactly *collinear* with it, and a nest reads as
one long line instead of a hierarchy.

**A slant clears the line it hangs from, not just the word.** A host's line carries on past its
word, and a child diverges from it by only `nestAngleStep`, so at distance `t` along the child the
perpendicular gap is `t · sin(between)` — the child's text has to start past `textHeight /
sin(between)` or it runs alongside its parent's line and through it. That is why `nestAngleStep` is
22° rather than something smaller: the clearance it buys is what keeps the offsets sane.

**A line is drawn after its children, long enough to reach them.** `hang` returns how far along the
host line it actually attached anything, and the slant or shelf is then drawn to at least that
length. Sizing a line in advance does not work: a child anchors at `word + gap` along its parent
while the parent was only drawn to `word + slantPad`, so with `gap` larger than `slantPad` every
child hung a few points past the end of its parent, unattached — and the clearance loop could push
it further still. `KelloggDiagram` is checked for this: every slant, shelf and riser has to touch
another line somewhere along its length.

Between them these take the text collisions on the three test sentences from 8, 4 and 5 to zero,
with no text crossing a line and no line left unattached.

Worth knowing when reading those checks: a slant leaving a baseline *beneath* a word legitimately
grazes that word's descender band, and a bisector crosses its baseline mid-line rather than at an
endpoint. Both look like collisions to a naive test — the checks count only a line through the body
of a text (above its own baseline), and probe along a line's whole length rather than its start.

### What is missing

- **Coordination forks.** R-K splits the baseline into two parallel lines joined by a dotted
  vertical carrying the conjunction. For now a `conj` renders like a preposition — conjunction on
  the slant, conjunct on a shelf. Legible, loses no words, and not the tradition.
- R-K presumes a traditional grammar, so the tail of the relation set has no canonical treatment:
  anything unrecognised becomes a slant, which is wrong but visible rather than dropped.
- It is less forgiving of a doubtful parse than arcs are. Arcs can cross and still read; a wrong
  frame here produces a confidently wrong picture.

## One renderer

There is no separate SVG renderer in this package. Field goes FLine to SVG internally
(`afield.graphics.drawToSVG`), so `svg()` builds the FLines and hands them to that. A second
renderer is a second place for every size and colour to live, and it drifted twice over exactly
that -- label size and tag size were each being set in one place and read from another.

The consequence worth knowing: `everything()` leaves lines **unstyled** by default, and Field
draws an unstyled FLine white -- which is what you want on a dark canvas. `svg()` applies
`DiagramStyle.onWhite` instead. Pass `style = DiagramStyle.onWhite` to `everything()` if you
want dark ink on screen, or build your own `DiagramStyle`; every field is nullable and null
means "leave it alone".

## Files

| | |
|---|---|
| `SentenceDiagram.kt` | the live, mutable handle — start here |
| `Sentence.kt` | the JSON model, plus `problems()` for a sanity check |
| `Geometry.kt` | `P`, `Rect`, `Cubic` — doubles, y down, no drawing dependency |
| `Layout.kt` | placements, lane assignment, the layout itself |
| `Flines.kt` | `FLine`/`Vec2` bridge and `d.flines()`; `flipY = true` if your surface is y-up |
| `Main.kt` | CLI preview, a thin wrapper over `SentenceDiagram` |
| `DependencyLength.kt` | retained texts, dependency length and its random baselines |

`SentenceDiagram` deliberately has no dependency on the graphics stack — `flines()` lives in
`Flines.kt` as an extension — so the layout side stays runnable and testable without it.

## Knobs worth turning first

`layerGap` and `preterminalGap` set the tree's density; `laneGap` and `baselineMargin`
the arc field's. `treeHandle` controls how much tree edges fan — see below. `arcLabelT`
slides relation labels along their arcs —
at 0.5 an asymmetric arc puts the label on the descending flank, tilted, as the
originals do; `arcLabelAtLowest` puts it at the bottom, horizontal.

`laneGap` defaults to 68, which keeps relation labels clear at four lanes on these
sentences; 46 is tighter but collides.

`LaneOrigin.PER_ARC` is worth knowing about and probably not worth using. It does tighten
the arc field considerably — each arc hangs just below its own two words rather than below
every word — but it gives up the one property that keeps a diagram readable: that all arcs
live in a band below everything else. An arc between two shallow words then hangs at the
height of the *deeper* words, and cuts straight through the tree. It is only safe when the
word heights are roughly level, and in that case it does the same thing as
`COMMON_BASELINE` anyway.
