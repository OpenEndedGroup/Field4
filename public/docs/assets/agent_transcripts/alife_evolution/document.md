# Field Document: alife_evolution

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. 3 — MAP-Elites: illuminate, do not optimise

<!-- field-box id="map_elites" index="0" -->

### Narrative

From `code`

Illuminate the rule space instead of optimising it.

MAP-Elites keeps the best rule found in each cell of a grid over the BEHAVIOUR space -- here
(density, activity), the same two numbers caEdgeOfChaosObjective already reports as its
Evaluation.behaviour. What comes out is not one answer but a map of "the best rule that behaves
like THIS", which is the contact sheet below.

This is the figure worth looking at. Top left is sparse and nearly dead; bottom right is dense
and noisy; and somewhere in the middle band are the rules that hold structure while still moving.

```edn [field-box id="map_elites" role="properties"]
{"id" "map_elites" "index" 0 "name" "3 — MAP-Elites: illuminate, do not optimise" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 — MAP-Elites: illuminate, do not optimise" "_tombstone" false "frame" #afield/rect [60.0 360.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "map_elites_code_.kt" "lines" 55 "characters" 2867 "sha256" "0b5bd8f6f39f72f13451ecf45696aaab77fd70da0aa60fa837b590ca26d1c7a4"}]}
```

```kotlin [field-property box="map_elites" name="code" path="map_elites_code_.kt"]
// Illuminate the rule space instead of optimising it.
//
// MAP-Elites keeps the best rule found in each cell of a grid over the BEHAVIOUR space -- here
// (density, activity), the same two numbers caEdgeOfChaosObjective already reports as its
// Evaluation.behaviour. What comes out is not one answer but a map of "the best rule that behaves
// like THIS", which is the contact sheet below.
//
// This is the figure worth looking at. Top left is sparse and nearly dead; bottom right is dense
// and noisy; and somewhere in the middle band are the rules that hold structure while still moving.

val ops = LifeRuleGenome(radius = 2)
val soups = listOf(randomSoup(48, 48, density = 0.35, seed = 303L))
val objective = caEdgeOfChaosObjective(soups, warmup = 20, scored = 25)
val bins = listOf(6, 6)
val axes = listOf(0.0..1.0, 0.0..0.6)
val budget = 600

val archive = PopulationSearch.mapElites(ops, objective, budget, axes, bins, seed = 31L, initial = 40)
println(archive)
println("off-grid (clamped to an edge cell): ${archive.offGridCandidates} of $budget")
println()

// The control arm for a quality-diversity claim is the same budget of independent draws, binned
// identically and reduced to the best genome per cell. Comparing against a single best random rule
// would be the wrong control, because MAP-Elites is not trying to find one good rule.
val controlCells = HashMap<List<Int>, Double>()
val rng = kotlin.random.Random(31L)
repeat(budget) {
    val e = objective.evaluate(ops.random(rng))
    val cell = axes.indices.map { d ->
        (((e.behaviour[d] - axes[d].start) / (axes[d].endInclusive - axes[d].start)) * bins[d])
            .toInt().coerceIn(0, bins[d] - 1)
    }
    val incumbent = controlCells[cell]
    if (incumbent == null || e.fitness > incumbent) controlCells[cell] = e.fitness
}
val controlQd = controlCells.values.sum()
println("random-sampled archive: ${controlCells.size}/${archive.cells} cells, QD %.3f".format(controlQd))
println("illuminated archive:    ${archive.elites.size}/${archive.cells} cells, QD %.3f".format(archive.qdScore))
println()
println("Quality, not coverage, is the claim. Random sampling spreads over a descriptor space for")
println("free -- that is what random means -- while MAP-Elites reaches a new cell only when a")
println("mutation moves a descriptor across a bin boundary. What illumination buys is the quality")
println("held in each cell it does occupy.")

val panels = archive.elites.entries
    .sortedWith(compareBy({ it.key[0] }, { it.key[1] }))
    .map { (cell, elite) ->
        "%d%d %.2f".format(cell[0], cell[1], elite.fitness) to
            caRun(soups[0], elite.genome, 20, 25).finalGrid.toImg(2).upscaleNearest(3)
    }
showImg(
    montage(panels, columns = 6, cellSide = 150),
    caption = "one panel per occupied cell; the label is (density bin)(activity bin) and the score"
)
```

## 2. 4 — The engine is shared: FunctionEvolve too

<!-- field-box id="shared_core" index="1" -->

### Narrative

From `code`

The engine is SHARED, and that is the point of the whole exercise.

Field had two population searches before tonight and they shared no code: afield.morpho's
(GraalJS, not stdlib) and FunctionEvolve's, over Fn graphs. Neither was parametric.

FunctionEvolve.evolve no longer contains a search. Its body is
PopulationSearch.evolve(mu = 1, lambda = lambda, crossoverRate = 0, immigrantRate = 0) over an
FnGenomeOps -- the same engine the three ALife boxes run on. The random stream is IDENTICAL to
the hand-rolled version's, because the core skips the parent draw when the population holds one
individual and skips the crossover and immigrant draws when their rates are exactly zero. Each
of those is right on its own terms; together they make mu=1 a plain (1+lambda) climb.

The proof is the FIELD_TEST functions.evolve.worked-example, whose committed numbers were
captured BEFORE the refactor and reproduce bit for bit after it:

initialScore=-0.8420503456220446   bestScore=-0.15676727177844857
acceptedMutations=12  evaluations=961

Below is that same search, running here.

```edn [field-box id="shared_core" role="properties"]
{"id" "shared_core" "index" 1 "name" "4 — The engine is shared: FunctionEvolve too" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 — The engine is shared: FunctionEvolve too" "_tombstone" false "frame" #afield/rect [60.0 510.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "shared_core_code_.kt" "lines" 50 "characters" 2380 "sha256" "2ca1b47188430d56dc93af5287f4bb605acd50ee5df318ae76103fccf80c8a07"}]}
```

```kotlin [field-property box="shared_core" name="code" path="shared_core_code_.kt"]
// The engine is SHARED, and that is the point of the whole exercise.
//
// Field had two population searches before tonight and they shared no code: afield.morpho's
// (GraalJS, not stdlib) and FunctionEvolve's, over Fn graphs. Neither was parametric.
//
// FunctionEvolve.evolve no longer contains a search. Its body is
// PopulationSearch.evolve(mu = 1, lambda = lambda, crossoverRate = 0, immigrantRate = 0) over an
// FnGenomeOps -- the same engine the three ALife boxes run on. The random stream is IDENTICAL to
// the hand-rolled version's, because the core skips the parent draw when the population holds one
// individual and skips the crossover and immigrant draws when their rates are exactly zero. Each
// of those is right on its own terms; together they make mu=1 a plain (1+lambda) climb.
//
// The proof is the FIELD_TEST functions.evolve.worked-example, whose committed numbers were
// captured BEFORE the refactor and reproduce bit for bit after it:
//
//     initialScore=-0.8420503456220446   bestScore=-0.15676727177844857
//     acceptedMutations=12  evaluations=961
//
// Below is that same search, running here.

val target = with(Functions) { sin(frequency = 1.0, amplitude = 0.5) }
val seed = with(Functions) { cos(frequency = 1.0, amplitude = 1.0) }

val run = evolve(
    seed = seed,
    iterations = 120,
    lambda = 8,
    rngSeed = 3L,
    fitness = matchFitness(target, samples = 160, complexityWeight = 0.01)
)

println("initialScore=${run.initialScore}")
println("bestScore=${run.bestScore}")
println("acceptedMutations=${run.steps.size}  evaluations=${run.evaluations}")
println()
println("the accepted improvements, which are now read off the generic SearchRun.improvements:")
run.steps.forEach { println("  gen %3d  %-14s %.6f".format(it.iteration, it.mutation, it.score)) }
println()
println("evolved: ${run.best}")
println()
println("One core, four genomes: an Fn graph here, and a LeniaRule, a life-like rule table and an")
println("L-system grammar in alife_evolve.kt. A fifth costs a GenomeOps and an Objective.")

// The seed, the target and what the search found, on one time base.
val xs = (0..255).map { -1.0 + 2.0 * it / 255.0 }
showSignals(
    "target" to xs.map { target.evalSafe(it, outside = 0.0) },
    "seed" to xs.map { seed.evalSafe(it, outside = 0.0) },
    "evolved" to xs.map { run.best.evalSafe(it, outside = 0.0) }
)
```

## 3. 2 — The same engine, a life-like rule table

<!-- field-box id="evolve_ca" index="2" -->

### Narrative

From `code`

The same engine, a completely different genome.

A life-like rule is a TABLE: which neighbour counts cause a birth, which cause survival. At a
radius-2 neighbourhood that is 24 birth bits and 25 survival bits, a fixed-length bit string, and
therefore a genome two parents can be recombined across. Field had exactly one other two-parent
crossover before this (afield.morpho.MorphoGenerator); LifeRuleGenome.crossover is uniform
crossover over the rule table and it is real recombination, not a graft.

Radius 2 rather than the classic Moore 8 is a deliberate choice and it is the most useful thing
in this box. At radius 1 the space is 2^17 rules, and a few hundred INDEPENDENT draws land close
to the optimum on their own -- the first version of this demo measured evolution winning by 3%
for exactly that reason. A control arm that nearly wins is telling you the problem is easy, not
that the search is good. At radius 2 the space is 2^49 and the margin is 27%.

```edn [field-box id="evolve_ca" role="properties"]
{"id" "evolve_ca" "index" 2 "name" "2 — The same engine, a life-like rule table" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 — The same engine, a life-like rule table" "_tombstone" false "frame" #afield/rect [60.0 210.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "evolve_ca_code_.kt" "lines" 73 "characters" 3962 "sha256" "c0418b3b6d2cd06a8cab5b16ae7ef515d4aabd77bf78b290dfd27390c244afc2"}]}
```

```kotlin [field-property box="evolve_ca" name="code" path="evolve_ca_code_.kt"]
// The same engine, a completely different genome.
//
// A life-like rule is a TABLE: which neighbour counts cause a birth, which cause survival. At a
// radius-2 neighbourhood that is 24 birth bits and 25 survival bits, a fixed-length bit string, and
// therefore a genome two parents can be recombined across. Field had exactly one other two-parent
// crossover before this (afield.morpho.MorphoGenerator); LifeRuleGenome.crossover is uniform
// crossover over the rule table and it is real recombination, not a graft.
//
// Radius 2 rather than the classic Moore 8 is a deliberate choice and it is the most useful thing
// in this box. At radius 1 the space is 2^17 rules, and a few hundred INDEPENDENT draws land close
// to the optimum on their own -- the first version of this demo measured evolution winning by 3%
// for exactly that reason. A control arm that nearly wins is telling you the problem is easy, not
// that the search is good. At radius 2 the space is 2^49 and the margin is 27%.

val ops = LifeRuleGenome(radius = 2)
val soups = listOf(
    randomSoup(48, 48, density = 0.35, seed = 101L),
    randomSoup(48, 48, density = 0.12, seed = 202L)
)

// "Alive but not saturated, and still changing" -- activity times a Gaussian band on density.
// Each factor alone is trivially gamed. A rule that fills the grid and stays filled scores 1 on
// density and 0 on activity; a rule that flickers every cell scores ~1 on activity and ~0 on the
// band; an extinct rule scores the floor. Only the product is large for a rule that keeps a
// moderate population AND keeps rearranging it, which is what everybody means by an interesting
// rule and what nobody can state by looking.
val objective = caEdgeOfChaosObjective(soups, warmup = 20, scored = 30)
val budget = 400

val control = PopulationSearch.random(ops, objective, budget, seed = 7L, label = "random")

// LEXICASE, because this objective genuinely has a case structure: one case per soup. A rule that
// holds together on one initial condition and collapses on the other is exactly what a
// tournament's average hides, and lexicase never averages.
val evolved = PopulationSearch.evolve(
    ops, objective, budget, seed = 7L,
    mu = 8, lambda = 16, selection = Selection.LEXICASE,
    crossoverRate = 0.3, immigrantRate = 0.1, label = "evolve"
)

println(control.summary())
println(evolved.summary())
println()
println("margin %.4f  (%.1f%% above the control)".format(
    evolved.bestFitness - control.bestFitness,
    100.0 * (evolved.bestFitness / control.bestFitness - 1.0)
))
println("recombined ${evolved.recombined} children by uniform crossover over the rule table")
println()
println("evolved rule ${lifeRuleSignature(evolved.best!!.genome)}")
println("control rule ${lifeRuleSignature(control.best!!.genome)}")
println()
println("The first record holders score BELOW the control: the control is the best of 400")
println("independent draws, so a population starts under it and has to climb past it.")
println()
println("Try Selection.TOURNAMENT above and compare. Try LifeRuleGenome() with no radius argument")
println("and watch the margin collapse -- that is the easy problem, and it is worth seeing.")

val panels = mutableListOf<Pair<String, Img>>()
panels += "rnd %.3f".format(control.bestFitness) to
    caRun(soups[0], control.best!!.genome, 20, 30).finalGrid.toImg(2).upscaleNearest(3)
val picks = evolved.improvements.indices.toList().let { idx ->
    if (idx.size <= 5) idx else (1..5).map { (it * idx.size / 5) - 1 }
}
for (i in picks) {
    val imp = evolved.improvements[i]
    panels += "g%d %.3f".format(imp.generation, imp.fitness) to
        caRun(soups[0], imp.genome, 20, 30).finalGrid.toImg(2).upscaleNearest(3)
}
showImg(
    montage(panels, columns = 3, cellSide = 200),
    caption = "the control's best, then each successive record holder, all on the same soup -- the\n        early ones score BELOW the control, which is what overtaking looks like"
)
```

## 4. 1 — Evolution against chance, on a Lenia rule

<!-- field-box id="evolve_lenia" index="3" -->

### Narrative

From `code`

Field could always MEASURE artificial life. It could not search it.

alife.kt is 816 lines of morphology statistics -- polarization, blobShapes,
correlationLength, massRadiusDimension, stateEntropyBits, centroidDrift -- and until now not one
of them was ever used as a fitness. This box uses one.

The question: is there a Lenia rule whose seed stays alive, stays in ONE piece, and TRAVELS?
That is what a soliton is, it is the one thing continuous Lenia is famous for, and every term of
the score below is read straight off leniaRun, which already classifies a run into
DEATH / BOUNDED / SATURATION and already reports a wrapped centroid drift.

```edn [field-box id="evolve_lenia" role="properties"]
{"id" "evolve_lenia" "index" 3 "name" "1 — Evolution against chance, on a Lenia rule" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 — Evolution against chance, on a Lenia rule" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "evolve_lenia_code_.kt" "lines" 67 "characters" 3537 "sha256" "03ecde2a8ca3b3c8d834221b1b589a9097afdc2f1845598cc6423666dc75e894"}]}
```

```kotlin [field-property box="evolve_lenia" name="code" path="evolve_lenia_code_.kt"]
// Field could always MEASURE artificial life. It could not search it.
//
// alife.kt is 816 lines of morphology statistics -- polarization, blobShapes,
// correlationLength, massRadiusDimension, stateEntropyBits, centroidDrift -- and until now not one
// of them was ever used as a fitness. This box uses one.
//
// The question: is there a Lenia rule whose seed stays alive, stays in ONE piece, and TRAVELS?
// That is what a soliton is, it is the one thing continuous Lenia is famous for, and every term of
// the score below is read straight off leniaRun, which already classifies a run into
// DEATH / BOUNDED / SATURATION and already reports a wrapped centroid drift.

val ops = LeniaGenome(radius = 6, dt = 0.1, shells = 3)
val objective = leniaSolitonObjective(width = 48, height = 48, steps = 90, initialSeed = 5L, driftScale = 8.0)
val budget = 220

// PopulationSearch.random is a CONTROL ARM, not a fourth driver. It draws from exactly the
// distribution `evolve` mutates within, at the same budget, under the same seed. Without it a
// result here would say nothing at all: a generator good enough to stumble onto a decent rule
// occasionally looks exactly like a successful search.
val control = PopulationSearch.random(ops, objective, budget, seed = 13L, label = "random")
val evolved = PopulationSearch.evolve(
    ops, objective, budget, seed = 13L,
    mu = 6, lambda = 12, crossoverRate = 0.3, immigrantRate = 0.08, label = "evolve"
)

println(control.summary())
println(evolved.summary())
println()
println("margin %.4f  (%.1f%% above the control)".format(
    evolved.bestFitness - control.bestFitness,
    100.0 * (evolved.bestFitness / control.bestFitness - 1.0)
))
println()

val best = evolved.best!!.genome
val run = leniaRun(leniaSeed(48, 48, best, seed = 5L), best, steps = 90, sampleEvery = 8)
println("evolved rule: mu=%.4f sigma=%.4f".format(best.mu, best.sigma))
println("  $run")
println("  phase is MEASURED, not asserted: leniaRun classifies it from the mean field value.")
println()
println("`improvements` carries the genome of every generation whose leader improved. That is a")
println("chain of RECORD HOLDERS, not a parent-child lineage -- with mu=6 and crossover on, the")
println("odds that these six are literally descended from one another are near zero.")

// The lineage, left to right. The control arm's best is the first panel: that is the thing
// evolution has to beat, in the same picture, at the same budget.
val panels = mutableListOf<Pair<String, Img>>()
val cRule = control.best!!.genome
panels += "rnd %.2f".format(control.bestFitness) to
    leniaRun(leniaSeed(48, 48, cRule, seed = 5L), cRule, steps = 90, sampleEvery = 8).field.upscaleNearest(3)
val picks = evolved.improvements.indices.toList().let { idx ->
    if (idx.size <= 5) idx else (1..5).map { (it * idx.size / 5) - 1 }
}
for (i in picks) {
    val imp = evolved.improvements[i]
    panels += "g%d %.2f".format(imp.generation, imp.fitness) to
        leniaRun(leniaSeed(48, 48, imp.genome, seed = 5L), imp.genome, steps = 90, sampleEvery = 8)
            .field.upscaleNearest(3)
}

// A FIXED 0..1 display range. A saturated Lenia field is constant at 1, and an auto-ranged colour
// map draws a constant field black -- i.e. identical to death. This is the one family where the
// range policy changes the answer rather than tidying it.
showImg(
    montage(panels, columns = 3, cellSide = 200, displayRange = 0f..1f),
    caption = "left to right: the random control's best, then each successive record holder"
)
```

