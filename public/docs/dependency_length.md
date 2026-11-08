# Dependency length in real texts

In a dependency parse every word hangs from one other word, its head. The length of that arc is
how far apart the two words sit: next-door neighbours are 1 apart, and a word with three words
between it and its head is 4 apart. Add up every arc in a sentence and you get its total
dependency length.

Futrell, Mahowald and Gibson measured this over parsed corpora of 37 languages and found the same
thing in every one: real sentences have shorter totals than random reorderings of the same trees,
and the gap widens as sentences get longer ([PNAS 112, 10336–10341, 2015](https://doi.org/10.1073/pnas.1502134112)).
This page repeats their measurement on five public-domain English texts. It draws a sentence
beside its reorderings, then a whole speech one sentence at a time, then all five texts at once.

The parses are retained: Stanza parsed each passage once, and the trees ship with Field, so
nothing here needs the parser. [Sentence diagrams](sentence_diagrams.md) covers drawing one
sentence in detail. [Open the five examples](offline.html?file=assets/agent_transcripts/dependency_length/metadata.edn)
to get them as editable boxes.

## One sentence and its reorderings

A random reordering keeps the tree and changes only the order. At each head, the head and its
dependents are shuffled, and then each dependent's own group is shuffled in the same way. Every
word stays next to the rest of its phrase, so no two arcs cross; only the order of the phrases
changes. Punctuation is removed first, and the arc into the root does not count.

This box takes the fifth sentence of the Gettysburg Address and draws it as Lincoln said it, three
random reorderings, and the shortest order the tree allows. The number beside each line is its
total.

```diffstatic_runner {data-lang=kotlin screenshot="assets/dependency_length/reorderings.png" capture="box" open-outputs="true" w="1080" h="1400" padding="12" trim=true embed=true name="dependency-reorderings" alt="Five rows of the same eleven words joined by arcs below them: Lincoln's order with total 22, three shuffled orders whose long arcs reach under whole phrases, with totals 35, 32 and 33, and the shortest order with total 18"}
import afield.graphics.text.sentences.*

// "It is altogether fitting and proper that we should do this", without its full stop.
val tree = SentenceTexts.load("gettysburg").sentences[4].tree()!!
val rng = java.util.Random(5)
val rows = listOf("as spoken" to tree.observed) +
    (1..3).map { "shuffled" to Linearization.freeRandom(tree, rng) } +
    listOf("shortest" to Linearization.optimal(tree))

val caption = font("Serif", 30.0)
var y = 0.0
val figure = rows.flatMap { (name, order) ->
    val d = SentenceDiagram().use(tree.sentence(order))
    d.scheme = Scheme.UD
    d.wordFont = font("Serif", 30.0)
    d.wordGap = 22.0
    d.heights = Heights.Flat(y)
    d.arcLabels = false
    d.x0 = 250.0
    val f = d.flines()
    // Keep the words and the arcs; the constituency tree is a stand-in for a reordered sentence.
    val arcs = f.copy(treeEdges = emptyList(), wordEdges = emptyList(), texts = f.texts.filter { it.kind == TextKind.WORD })
    val label = outlinedText("$name  ${tree.length(order)}", P(0.0, y), caption)
    y = d.diagram().bounds.y1 + 60.0
    arcs.everything() + label
}
display("figure", figureSvg(figure))
```

As spoken, every word sits close to its head except one: "do", the head of "that we should do
this", hangs from "fitting" across "and proper". Each shuffle puts whole phrases between words and
their heads, and the arcs that reach under them take the total past 30. The shortest order saves
four more by putting the clause first, an order no one would say.

## A whole text, sentence by sentence

`measure()` does this for every sentence of a text: the observed total, the totals of a hundred
random reorderings, and the shortest possible. The ratio of the observed total to the mean of the
random ones is the number to read: below 1 is shorter than chance.

Here are the ten sentences of the Gettysburg Address as arcs alone, one dot per word, the ratio
beside each.

```diffstatic_runner {data-lang=kotlin screenshot="assets/dependency_length/gettysburg.png" capture="box" open-outputs="true" w="1080" h="1100" padding="12" trim=true embed=true name="dependency-gettysburg" alt="Ten rows of arcs hanging below rows of dots, one dot per word, each labelled with its ratio and opening words; the ratios run from 0.24 for the last and longest sentence to 0.86"}
import afield.graphics.text.sentences.*

val text = SentenceTexts.load("gettysburg")
val ratioFont = font("Serif", 18.0)
val openingFont = font("Serif", 13.0)
var y = 0.0
val figure = text.measure().flatMap { m ->
    // The arcs start at x = 270, leaving a column for the ratio and the opening words.
    val row = m.tree.arcLines(at = P(270.0, y), spacing = 8.0, depth = 0.3) +
        outlinedText("%.2f".format(m.ratio), P(0.0, y + 6), ratioFont) +
        outlinedText(m.tree.words.take(4).joinToString(" ") + " …", P(50.0, y + 5), openingFont)
    y += m.tree.arcDepth(spacing = 8.0, depth = 0.3) + 30.0
    row
}
display("figure", figureSvg(figure))
```

Every sentence is below 1. The lowest ratio belongs to the longest sentence, the closing one: it
runs to more than eighty words, but almost all of its arcs join neighbours or near neighbours.

## Shorter than chance, more so for long sentences

All five texts pooled: one point per sentence for each measure, against the number of words in the
sentence. The random reorderings rise quickly with length; the real sentences rise more slowly and
stay closer to the shortest order the tree allows.

```diffstatic_runner {data-lang=kotlin screenshot="assets/dependency_length/growth.png" embed=true name="dependency-growth" alt="A scatter plot of total dependency length against words per sentence for 238 sentences: the free random means climb to about 1,300 at around 115 words and the fixed order means a little below them, while the sentences as written stay under 400 and the shortest orders sit just beneath those"}
import afield.graphics.text.sentences.*
import space.kscience.plotly.*
import space.kscience.plotly.models.ScatterMode

val measured = SentenceTexts.all().flatMap { it.measure() }
val words = measured.map { it.length }
val series = listOf(
    "free random reordering (mean of 100)" to measured.map { it.lengths.freeMean },
    "fixed order random reordering (mean of 100)" to measured.map { it.lengths.fixedOrderMean },
    "as written" to measured.map { it.lengths.observed.toDouble() },
    "shortest possible" to measured.map { it.lengths.optimal.toDouble() },
)
val plot = Plotly.plot {
    for ((name, values) in series) {
        scatter {
            this.name = name
            x.set(words)
            y.set(values)
            mode = ScatterMode.markers
            marker { size = 4.0 }
        }
    }
    layout {
        title = "Total dependency length, ${measured.size} sentences from five texts"
        xaxis { title = "words in the sentence" }
        yaxis { title = "total dependency length" }
    }
}.darkMode()
// darkMode() places the legend beside the plot; put it inside, over the empty upper left.
plot.layout { legend { x = 0.02; y = 0.98 } }
display(plot)
```

The fixed order baseline is the stricter control. It gives each relation type one place
relative to its head for the whole sentence, so every subject goes the same side of its verb, as
in English. Its means sit below the free ones, and the real sentences are still shorter.

## Five texts side by side

The table sums each text's totals, so long sentences weigh more than short ones. The last column
counts sentences whose own total is below the mean of their own reorderings.

```diffstatic_runner {data-lang=kotlin trim=true screenshot="assets/dependency_length/table.png" embed=true name="dependency-table" alt="A table with one row per text: as written, the totals are 0.44 to 0.62 of the free random totals, 0.52 to 0.70 of the fixed order totals and 1.34 to 1.49 times the shortest; between 83 and 100 percent of each text's sentences are shorter than chance"}
import afield.graphics.text.sentences.*

val rows = SentenceTexts.all().map { t ->
    val m = t.measure()
    val observed = m.sumOf { it.lengths.observed }.toDouble()
    "| ${t.title} | ${m.size} | %.1f | %.2f | %.2f | %.2f | %.0f%% |".format(
        m.map { it.length }.average(),
        observed / m.sumOf { it.lengths.freeMean },
        observed / m.sumOf { it.lengths.fixedOrderMean },
        observed / m.sumOf { it.lengths.optimal },
        100.0 * m.count { it.lengths.observed < it.lengths.freeMean } / m.size,
    )
}
markdown(
    """
    | text | sentences | mean words | ÷ free random | ÷ fixed order | ÷ shortest | shorter than chance |
    | --- | ---: | ---: | ---: | ---: | ---: | ---: |
    ${rows.joinToString("\n    ")}
    """.trimIndent(),
)
```

The Gettysburg Address is furthest below chance and Genesis closest, with its run of short clauses
joined by "and". The sonnets come second, inversions and all: their sentences are long, and the
sum is weighted towards long sentences. The violins show the spread per sentence, for sentences of
eight words or more, with chance as the dashed line at 1.

```diffstatic_runner {data-lang=kotlin screenshot="assets/dependency_length/ratios.png" embed=true name="dependency-ratios" alt="Five violins of the per-sentence ratio, one per text, with medians between about 0.64 and 0.72 and almost all of each below the dashed line at 1; the sonnets and Genesis reach a little above it"}
import afield.graphics.text.sentences.*

val texts = SentenceTexts.all()
display(
    "ratios",
    violin(
        *texts.map { t -> t.key to t.measure().filter { it.length >= 8 }.map { it.ratio } }.toTypedArray(),
        references = List(texts.size) { 1.0 },
        yTitle = "as written ÷ free random mean",
    ),
)
```

## How it is measured

`SentenceTexts.load(key)` reads one retained passage (`gettysburg`, `pride_and_prejudice`,
`moby_dick`, `sonnets`, `genesis`) as a `ParsedText`, whose `sentences` carry words, tags and a
Universal Dependencies tree. `ParsedSentence.tree()` removes punctuation, reattaching anything
that hung from a punctuation mark to that mark's head, and returns a `DependencyTree`.

On a tree, `length(order)` is the total for an order of its words (`order[k]` is the word at
position k; `observed` is the order as written), `isProjective(order)` says whether every subtree
is contiguous, and `lengths(samples, seed)` measures the observed order against the baselines:

| baseline | `Linearization` | what it keeps |
| --- | --- | --- |
| free random | `freeRandom` | the tree; at each head, the head and its dependents shuffled |
| fixed order random | `fixedOrder` with `randomWeights` | one weight in [−1, 1] per relation type, the head at 0, each group sorted by weight |
| fixed head position | `fixedHeadPosition` | each dependent on the side of its head where it was written, shuffled within that side |
| shortest | `optimal` | the shortest order with no crossing arcs (Gildea and Temperley, 2007) |

The first two are the paper's main baselines, the third is from its supplement, and each draws a
hundred orders per sentence. `fixedOrder` draws fresh weights for each order, as the paper does,
so the relation order is consistent within a sentence, not across a text.

The trees use Universal Dependencies, which makes the noun the head of "of the people" and the verb
the head of "that we should do this". The paper reattached prepositions and complementizers as
heads before measuring, and reports the same result without doing so. A sentence as written may
have crossing arcs (Stanza finds a few in Moby-Dick); its total is still measured, against
baselines that never do.

To measure a text of your own, add a passage file beside the retained ones, in
[`src/jvmMain/resources/afield/sentences/texts/`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/resources/afield/sentences/texts/),
and parse it with the prepared parser:

```diffstatic {data-lang=bash}
./gradlew prepareSentenceParser
optional/sentence-parser/.venv/bin/python tools/parse_sentence_texts.py my_passage
```

A passage starts with `# title:` and `# source:` lines, then paragraphs separated by blank lines.
For verse add `# sentences: punctuation`, which splits sentences at `.`, `?` and `!`; otherwise
Stanza takes the capital at the start of each line as a new sentence. Add the key to
`SentenceTexts.keys` to have `all()` include it.

## Where to go next

- [Sentence diagrams](sentence_diagrams.md): the constituency canopy, labelled arcs, decorations
  and Reed–Kellogg layout for any one of these sentences, and the parser server for new text.
- [Charts as FLines](chart.md) and Plotly: other ways to plot the measurements.
- [Starting points](agent_transcripts.md#dependency_length): the five boxes above as a document.
- Futrell, Mahowald and Gibson, [Large-scale evidence of dependency length minimization in 37
  languages](https://doi.org/10.1073/pnas.1502134112), PNAS 112(33), 2015, for the corpora,
  regressions and the other baselines.
