# A Word Cut as a Stencil

Cut a word out of a sheet and some of the letters fall apart: the middle of an O drops out, and so
do both middles of a B. A stencil needs a bridge across each of those holes. This page finds them
with three parts of Field that know nothing about each other. [Typography](typography_examples.md)
supplies the letter outlines, [Manifold](manifold.md) cuts them out of a plate and splits the
result into pieces, and a [Datalog](datalog.md) program decides which letters lost a piece. The
bridges then go into exactly those letters.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_stencil_bridges/bridged.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="bridged:" embed=true hidden=true timeout="300" padding="12" name="chain-stencil-bridges-hero" alt="Two grey plates with BRIDGE cut through them. In the upper plate the insides of B, R and D are drawn in red as separate pieces. The lower plate has a thin vertical bar through the middle of B, R and D, and no red pieces are left. A table beneath lists the bars cut and the piece counts and plate areas before and after" macro-file="../../dev_docs/agents/transcripts/stencil_bridges.yaml" capture-box="sb_bridge"}
// Built and run from dev_docs/agents/transcripts/stencil_bridges.yaml.
```

The example has three boxes, each a child of the one before: the first cuts the word, the second
judges it, and the third adds the bridges and counts again.

## Cutting the word

`layoutText` turns a string into placed glyphs, and `toFLines` gives each glyph's outline as an
`FLine`, holes wound the opposite way to outer rings. `Section.ofFLines` reads that winding, so the
counters stay open when Manifold fills the letters. The plate is the word's bounds grown by a
rounded margin, and `decompose` splits whatever is left after the subtraction into separate pieces:

```diffstatic {data-lang=kotlin}
stencilWord = layoutText("BRIDGE", typeface("SansSerif", 120.0))

val (lo, hi) = Section.ofFLines(stencilWord.toFLines(), sampleDistance = 0.5).bounds
stencilBlank = Section.square((hi.x - lo.x).toDouble(), (hi.y - lo.y).toDouble())
    .translated(lo.x.toDouble(), lo.y.toDouble())
    .offset(14.0, JoinType.ROUND)

val letters = Section.ofFLines(stencilWord.toFLines(), sampleDistance = 0.5)
val pieces = (stencilBlank - letters).decompose()
```

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_stencil_bridges/cut.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="cut:" embed=true hidden=true timeout="300" padding="12" name="chain-stencil-bridges-cut" alt="A three-line table: the word BRIDGE in 6 glyphs, 4 counters counted from the font, and 5 pieces left when the letters are subtracted from the plate" macro-file="../../dev_docs/agents/transcripts/stencil_bridges.yaml" capture-box="sb_cut"}
// Built and run from dev_docs/agents/transcripts/stencil_bridges.yaml.
```

The two counts come from different places. The font's count, `holeCount`, adds up the rings each
glyph winds as a hole. Manifold's count comes from the Boolean. The plate itself is one piece and
every counter is one more, so the two agree when the outlines and the subtraction are both right.

The box publishes the pieces and the glyphs as plain rows, an id followed by numbers, so the next
box doesn't need Manifold or the font to read them:

```diffstatic {data-lang=kotlin}
stencilGlyphs = stencilWord.glyphs.mapIndexed { g, p ->
    [g, p.text, p.origin.x.toDouble(), p.origin.x.toDouble() + p.glyph.advance]
}
stencilPieces = pieces.mapIndexed { i, s ->
    val (a, b) = s.bounds
    [i, (a.x + b.x) / 2.0, (a.y + b.y) / 2.0, s.area]
}
```

## Which letters fall apart

The second box hands both lists to `Datalog` as `piece` and `glyph` facts. The plate is the piece
with the largest area, and every other piece is an island. An island belongs to the glyph whose
advance contains its centre:

```diffstatic {data-lang=kotlin}
"""
#datalog
biggest(M) :- M = max A : { piece(_, _, _, A) }.
plate(P) :- piece(P, _, _, A), biggest(A).
island(P, X) :- piece(P, X, _, _), !plate(P).

falls(G, P) :- island(P, X), glyph(G, _, X0, X1), X > X0, X < X1.
lost(G, C, N) :- glyph(G, C, _, _), N = count : { falls(G, P) }, N > 0.
needs(G) :- falls(G, _).
safe(C) :- glyph(G, C, _, _), !needs(G).
"""
```

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_stencil_bridges/judged.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="judged:" embed=true hidden=true timeout="300" padding="12" name="chain-stencil-bridges-judged" alt="A three-line table: 4 islands; the letters that lose one, B with 2, R with 1 and D with 1; the letters that are safe, I, G and E" macro-file="../../dev_docs/agents/transcripts/stencil_bridges.yaml" capture-box="sb_judge"}
// Built and run from dev_docs/agents/transcripts/stencil_bridges.yaml.
```

B loses two pieces, R and D one each. I, G and E have no counters, so cutting them leaves the
plate whole. The box publishes the indices of the three letters that need bridges as
`needsBridge`.

Testing the centre against the glyph's advance is enough for a word set on one line. It would need
a vertical test as well for text on several lines.

## Bridging only those letters

The third box cuts a vertical bar, 5 units wide, down the middle of each listed letter. It takes
the bars out of the letters before the letters come out of the plate, so the bars stay behind as
plate:

```diffstatic {data-lang=kotlin}
val bars = Section.union(needsBridge.map { g ->
    val p = stencilWord.glyphs[g]
    val mid = p.origin.x + p.glyph.advance / 2.0
    Section.square(5.0, (hi.y - lo.y).toDouble() + 2.0)
        .translated(mid - 2.5, lo.y.toDouble() - 1.0)
})
val before = (stencilBlank - letters).decompose()
val after = (stencilBlank - (letters - bars)).decompose()
```

The figure at the top of the page is this box's output: the plate before bridging, with its
islands in red, and after, in one piece. Each bar crosses every counter in its letter, so one bar
is enough even for the two counters of B.

## Where each piece is documented

* [Typography as geometry](typography_examples.md) for `layoutText`, `toFLines` and `holeCount`.
* [Manifold: 2D sections and offsets](manifold.md#2d-sections-and-offsets) for `Section`,
  `offset` and the Booleans.
* [Datalog in a box](datalog.md) for facts, rules, aggregates and negation.
* [Agent transcripts](agent_transcripts.md#stencil_bridges) to replay the three boxes into a
  document.
