# Field Document: interaction_nets_lambda

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. normalise a term

<!-- field-box id="lambda_normalise" index="0" -->

### Narrative

From `code`

Type a λ-term, get its normal form. `LambdaTerms.normalise` is the whole pipeline in one call:
parse the text, compile it to an interaction net, reduce the net until nothing fires, read a term
back out. Nothing here needs a GPU, a network or any setup.

```edn [field-box id="lambda_normalise" role="properties"]
{"id" "lambda_normalise" "index" 0 "name" "normalise a term" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "normalise a term" "_tombstone" false "frame" #afield/rect [60.0 60.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lambda_normalise_code_.kt" "lines" 52 "characters" 3203 "sha256" "fd541f2541f252b52edd740b7502ec8407cc1443cde9d6786904bf57c317c9f6"}]}
```

```kotlin [field-property box="lambda_normalise" name="code" path="lambda_normalise_code_.kt"]
// Type a λ-term, get its normal form. `LambdaTerms.normalise` is the whole pipeline in one call:
// parse the text, compile it to an interaction net, reduce the net until nothing fires, read a term
// back out. Nothing here needs a GPU, a network or any setup.

val run = LambdaTerms.normalise("(\\m n f x. m f (n f x)) (\\f x. f x) (\\f x. f (f x))")
println("add one two  =  ${run.printed}")
println("${run.steps} reactions, ${run.fans} fans compiled, ${run.remainingNodes} agents left standing")
println("oracle agrees: " + LambdaTerms.alphaEquivalent(run.result, LambdaTerms.referenceNormalOrder(run.term)))

// That last line is the only reason to trust the one above it. `referenceNormalOrder` is an ordinary
// capture-avoiding substitution evaluator with no nets and no sharing anywhere in it — it exists in
// order to disagree with the net, and the readback is compared to it with `alphaEquivalent`, not with
// `==`, because a readback invents its own binder names.

println()
println("term                                       normal form                   steps  fans  agents")
listOf(
    "\\x. x",
    "(\\x y z. x z (y z)) (\\x y. x) (\\x y. x)",
    "(\\n f x. f (n f x)) (\\f x. f (f x))",
    "(\\m n f. m (n f)) (\\f x. f (f x)) (\\f x. f (f (f x)))",
    "(\\f x. f (f (f x))) (\\f x. f (f x))",
    "(\\n f x. n (\\g h. h (g f)) (\\u. x) (\\u. u)) (\\f x. f (f (f x)))",
    "(\\x. x x) (\\y. ((\\x. x) (\\x. x)) y)",
    "(\\x y. x) (\\x. x) ((\\m n f. m (n f)) (\\f x. f (f x)) (\\f x. f (f (f x))))"
).forEach { source ->
    val each = LambdaTerms.normalise(source)
    val ok = LambdaTerms.alphaEquivalent(each.result, LambdaTerms.referenceNormalOrder(each.term))
    println(
        source.take(40).padEnd(42) + each.printed.take(28).padEnd(30) +
            "%5d %5d %7d".format(each.steps, each.fans, each.remainingNodes) + (if (ok) "" else "   ORACLE DISAGREES")
    )
}

// Read the `agents` column against the `normal form` column on the exponentiation row — `three two`,
// which is 2^3. The net is left standing at 10 agents; the term it reads back as needs 11 nodes (one
// ROOT, two LAM, eight APP). The net is SMALLER than its own answer, because it stayed shared: the
// unfolding happened in the readback, not in the reduction. A fan in a normal form is not a node of
// the term, it is a bracket on the path through it, which is what `Lambda.FAN_LABEL` is for.
//
// Do not read that as a general claim. `mul two three` finishes at 10 agents against a 9-node term
// and `mul three three` comes out even. Which way it goes depends on the term.

println()
println("what the compiler builds, for `\\f. \\y. f (f y)`:")
val compiled = LambdaTerms.compile(LambdaTerms.parse("\\f. \\y. f (f y)"))
println("  " + compiled.net.nodes.groupingBy { it.type.name }.eachCount().toSortedMap())
println("  ${compiled.fans} fan(s) for the variable used twice, ${compiled.erasers} eraser(s) for unused binders")

// Try it: put a term of your own in the list. `\x. x x` applied to itself diverges, and the net will
// too — `normalise` takes a `maxSteps` and `run.finished` is false when it ran out, which is all
// a reducer can report about a term that has no normal form.
```

## 2. the corpus against the oracle

<!-- field-box id="lambda_oracle" index="1" -->

### Narrative

From `code`

The whole corpus against the oracle, and then against four schedulers. This is the box that says
whether any of the rest is true: `readback(reduce(compile(t)))` against an ordinary
capture-avoiding normal-order evaluator, for every named term, printed rather than summarised.

```edn [field-box id="lambda_oracle" role="properties"]
{"id" "lambda_oracle" "index" 1 "name" "the corpus against the oracle" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the corpus against the oracle" "_tombstone" false "frame" #afield/rect [60.0 2060.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lambda_oracle_code_.kt" "lines" 62 "characters" 3098 "sha256" "d6c39ae65df7d9e8ce8a9eb6caf12e2bfbf2c67f732044821ab7062d762f0f8d"}]}
```

```kotlin [field-property box="lambda_oracle" name="code" path="lambda_oracle_code_.kt"]
// The whole corpus against the oracle, and then against four schedulers. This is the box that says
// whether any of the rest is true: `readback(reduce(compile(t)))` against an ordinary
// capture-avoiding normal-order evaluator, for every named term, printed rather than summarised.

val corpus = LambdaTerms.corpus()
val orders = listOf(
    ActivationOrder.FIRST_FOUND,
    ActivationOrder.LEFTMOST_BY_ID,
    ActivationOrder.RIGHTMOST_BY_ID,
    ActivationOrder.HIGHEST_PAIR_ENERGY
)
println("${corpus.size} named closed terms, ${orders.size} schedulers")
println()

println("                        steps  fans  agents  normal form")
var netTotal = 0
val disagreed = mutableListOf<String>()
val notConfluent = mutableListOf<String>()

corpus.forEach { (name, source) ->
    val term = LambdaTerms.parse(source)
    val oracleKey = LambdaTerms.deBruijnKey(LambdaTerms.referenceNormalOrder(term))
    val keys = orders.associateWith { LambdaTerms.deBruijnKey(LambdaTerms.normalise(term, order = it).result) }
    val run = LambdaTerms.normalise(term)
    netTotal += run.steps
    if (keys.getValue(ActivationOrder.FIRST_FOUND) != oracleKey) disagreed += name
    if (keys.values.toSet().size != 1) notConfluent += name
    println(
        name.padEnd(22) + "%6d %5d %7d".format(run.steps, run.fans, run.remainingNodes) +
            "  " + run.printed.take(46)
    )
}

println()
println(
    "agreed with the oracle : ${corpus.size - disagreed.size} of ${corpus.size}" +
        if (disagreed.isEmpty()) "" else "   DISAGREED: $disagreed"
)
println(
    "confluent across ${orders.size} schedulers: ${corpus.size - notConfluent.size} of ${corpus.size}" +
        if (notConfluent.isEmpty()) "" else "   NOT CONFLUENT: $notConfluent"
)
println("net reactions          : $netTotal over ${corpus.size} terms at FIRST_FOUND")

// Two things that number is not. It is not comparable to a β-step count — a net reaction is any rule
// firing, β or duplication or erasure or a fan meeting, so the two are different units. And it is not
// the word "optimal": there are no brackets and no croissants here, so the sharing is correct but
// nothing bounds how many times a fan may be copied.

println()
println("and the pieces on their own:")
println("  parse -> format round-trips exactly: " +
    corpus.values.all { LambdaTerms.parse(LambdaTerms.format(LambdaTerms.parse(it))) == LambdaTerms.parse(it) })
println("  every corpus term is closed: " +
    corpus.values.all { LambdaTerms.freeVariablesOf(LambdaTerms.parse(it)).isEmpty() })
println("  de Bruijn key of Church two: " + LambdaTerms.deBruijnKey(LambdaTerms.parse("\\f x. f (f x)")))
println("  substitution avoids capture: " +
    LambdaTerms.format(LambdaTerms.substitute(LambdaTerms.parse("\\y. x y"), "x", LambdaTerms.parse("y"))))

// Try it: add a term to `LambdaTerms.corpus()` in `src/jvmMain/kotlin/afield/stdlib/lambdaterms.kt`.
// `stdlib.lambda-terms.smoke` asserts the corpus by the SET OF ITS NAMES, so a new entry fails that
// test until you name it there too — which is the point. A corpus that can shrink is not a corpus.
```

## 3. one reduction, panel per stage

<!-- field-box id="lambda_reduction" index="2" -->

### Narrative

From `code`

One reduction, one panel per stage. `(\x. x x) (\y. y)` is the smallest term that uses the whole
vocabulary, and the four reactions it takes are the two that did not exist before 2026-09-18:
`dup-lam` on an abstraction whose binder is wired to its own body, and `fan-fan` annihilating the
two halves of that duplication.

```edn [field-box id="lambda_reduction" role="properties"]
{"id" "lambda_reduction" "index" 2 "name" "one reduction, panel per stage" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "one reduction, panel per stage" "_tombstone" false "frame" #afield/rect [60.0 1060.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lambda_reduction_code_.kt" "lines" 58 "characters" 2602 "sha256" "cf58678b15f64b3638e6de59941763edd3fe4d854964390343e4304a42015cd7"}]}
```

```kotlin [field-property box="lambda_reduction" name="code" path="lambda_reduction_code_.kt"]
// One reduction, one panel per stage. `(\x. x x) (\y. y)` is the smallest term that uses the whole
// vocabulary, and the four reactions it takes are the two that did not exist before 2026-09-18:
// `dup-lam` on an abstraction whose binder is wired to its own body, and `fan-fan` annihilating the
// two halves of that duplication.

import afield.Rect
import afield.graphics.FLine

val source = "(\\x. x x) (\\y. y)"
val compiled = LambdaTerms.compile(LambdaTerms.parse(source))
// relaxIterations = 8 rather than the headless default of 0: this one IS the picture.
val reducer = Reducer(Lambda.rules(), relaxIterations = 8)

compiled.net.relax(60)
val stages = mutableListOf<Triple<String, List<FLine>, String>>()
stages += Triple("compiled", compiled.net.toFLines(), compiled.net.summaryHtml("compiled", maxNodes = 3))

while (stages.size < 8) {
    val detail = reducer.stepDetailed(compiled.net) ?: break
    compiled.net.relax(24)
    stages += Triple(
        "${stages.size}. ${detail.ruleName}",
        compiled.net.toFLines(),
        compiled.net.summaryHtml("${stages.size}. ${detail.ruleName}", maxNodes = 3)
    )
}

val readback = LambdaTerms.readback(compiled)
val oracle = LambdaTerms.referenceNormalOrder(LambdaTerms.parse(source))
println("$source  ->  ${LambdaTerms.format(readback)}")
println("oracle: ${LambdaTerms.format(oracle)}   agree: ${LambdaTerms.alphaEquivalent(readback, oracle)}")
println("stages: " + stages.joinToString(" | ") { it.first })

// Each panel is fitted to its own frame, so the scales are NOT comparable across panels. The agent
// counts on the cards are what say how big each net was.
val panelWidth = 196.0
val gutter = 10.0
val panels = stages.mapIndexed { index, stage ->
    val frame = Rect(14.0 + index * (panelWidth + gutter), 18.0, panelWidth, 250.0)
    listOf(frame.toFLine(filled = true, color = vec(0.07, 0.11, 0.16, 0.5))) +
        stage.second.fitInto(frame, padding = 22.0)
}.flatten()

showInteractionOverview(
    graphicLines = panels,
    cards = stages.map { it.third },
    channel = "reduction",
    graphicTitle = "$source, one panel per stage",
    graphicWidth = 14.0 + stages.size * (panelWidth + gutter),
    graphicHeight = 286.0
)

// Read the cards left to right and the agent count goes 6, 4, 6, 4, 2. It goes UP at `dup-lam`,
// which is the point: duplication is work, and it is work the net does explicitly rather than by
// copying a subtree behind your back.
//
// Try it: change `source` to `(\f. \y. f (f y)) ((\x. x) (\x. x))` and run again — a redex shared
// under a binder, reduced once for both of its uses.
```

