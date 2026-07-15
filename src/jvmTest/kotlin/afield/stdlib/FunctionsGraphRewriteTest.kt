package afield.stdlib

import afield.stdlib.Functions.Easing
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FunctionsGraphRewriteTest {

    @Test
    fun opRegistryTransmutesAcrossTheFullGraphAwareFamily() = with(Functions) {
        // frequency <-> period bridging: a sine transmuted to a saw stays finite.
        val toSaw = sin(2.0).rewrite("sin => op=saw")
        assertEquals("saw", toSaw.graphNode().op)
        assertTrue(toSaw.eval(0.3).isFinite())

        // Reaches beyond the old 5-oscillator set: noise, windows, motion.
        for (target in listOf("fbm", "valueNoise", "perlinNoise", "triangle", "square", "gaussian", "tween")) {
            val changed = sin(2.0).rewrite("sin => op=$target")
            assertEquals(OpRegistry.canonical(target), changed.graphNode().op, "op= to $target")
            assertTrue(changed.eval(0.42).isFinite(), "eval after op=$target")
        }

        // Value transforms transmute into each other because they carry a `source` child;
        // a raw generator has no source, so generator => transform is an honest no-op.
        val softened = (sin(1.0).clamp(-0.5, 0.5)).rewrite("clamp => op=softClip")
        assertEquals("softClip", softened.graphNode().op)
        val noop = sin(1.0).rewrite("sin => op=clamp")
        assertEquals("sin", noop.graphNode().op)
    }

    @Test
    fun patternRulesRewriteStructurallyAndPreserveMeaning() = with(Functions) {
        // Algebraic elimination reaches a fixed point and keeps the sampled signal identical.
        val source = (sin(2.0) + 0.0) + 0.0
        val simplified = source.rewritePatterns("add(?x, 0) => ?x")
        assertTrue(simplified.stable)
        assertEquals("sin", simplified.function.graphNode().op)
        assertEquals(source.eval(0.37), simplified.function.eval(0.37), 1e-12)

        // multiply(?x, 1) => ?x
        val unit = (saw(0.25) * 1.0).rewritePatterns("multiply(?x, 1) => ?x")
        assertEquals("saw", unit.function.graphNode().op)

        // Bare-op RHS transmutes the matched node, keeping its children.
        val toCos = sin(3.0).rewritePatterns("sin => cos")
        assertEquals("cos", toCos.function.graphNode().op)
        assertEquals(cos(3.0).eval(0.2), toCos.function.eval(0.2), 1e-12)

        // Repeated metavariable requires structurally equal subtrees, then the Kotlin RHS folds them.
        val doubled = (sin(1.0) + sin(1.0)).rewriteUntilStable(
            fnRule("fold ?x + ?x", "add(?x, ?x)") { m -> m.sub("x") * 2.0 }
        )
        assertEquals(sin(1.0).eval(0.3) * 2.0, doubled.function.eval(0.3), 1e-12)
    }

    @Test
    fun seededMutationsAreDeterministic() = with(Functions) {
        val source = sin(2.0) * 0.5
        val jitter = FnMutations.jitterNumbers(0.3)
        val a = jitter.mutate(source, Random(7))
        val b = jitter.mutate(source, Random(7))
        assertEquals(a.graphSignature(), b.graphSignature())

        val substitute = FnMutations.substituteOp()
        assertEquals(
            substitute.mutate(source, Random(11)).graphSignature(),
            substitute.mutate(source, Random(11)).graphSignature()
        )
    }

    @Test
    fun evolveImprovesFitnessDeterministically() = with(Functions) {
        val target = c(0.5)
        val fitness = matchFitness(target, samples = 32)

        val run1 = evolve(c(0.0), iterations = 60, lambda = 8, rngSeed = 1L, fitness = fitness)
        val run2 = evolve(c(0.0), iterations = 60, lambda = 8, rngSeed = 1L, fitness = fitness)

        assertEquals(run1.bestScore, run2.bestScore, 0.0)
        assertEquals(run1.best.graphSignature(), run2.best.graphSignature())
        assertTrue(run1.improved, "evolution should beat the flat seed toward the target")
        assertTrue(run1.steps.isNotEmpty())
        assertTrue(run1.best.eval(0.5) in 0.2..0.8, "evolved value should approach the target")
    }

    @Test
    fun everyEasingKeepsCleanEndpointsAndStaysFinite() = with(Functions) {
        for (easing in Easing.entries) {
            val motion = tween(0.0, 1.0, 0.0..1.0, easing)
            assertEquals(0.0, motion.eval(0.0), 1e-9, "$easing start")
            assertEquals(1.0, motion.eval(1.0), 1e-9, "$easing end")
            // Overshooting families (back/elastic) still stay finite across the interval.
            for (i in 1..9) assertTrue(motion.eval(i / 10.0).isFinite(), "$easing mid")
        }
        // The classic families now resolve distinctly rather than collapsing to a default.
        assertTrue(tween(0.0, 1.0, 0.0..1.0, Easing.BOUNCE_OUT).eval(0.5) != tween(0.0, 1.0, 0.0..1.0, Easing.LINEAR).eval(0.5))
    }

    @Test
    fun editorOpVocabularyMirrorsTheRegistryInBothDirections() {
        val js = File("src/commonMain/resources/fnrewrite.js").readText()
        fun setLiteral(name: String): List<String> {
            val body = Regex("""const\s+$name\s*=\s*new\s+Set\(\[(.*?)]\)""", RegexOption.DOT_MATCHES_ALL)
                .find(js)?.groupValues?.get(1) ?: error("Could not find `$name` in fnrewrite.js")
            return Regex(""""([^"]+)"""").findAll(body).map { it.groupValues[1] }.toList()
        }

        val opWords = setLiteral("opWords")
        assertTrue(opWords.isNotEmpty())

        // Forward: the editor never names an op the registry does not know.
        val unknown = opWords.filterNot { OpRegistry.knows(it) }
        assertTrue(unknown.isEmpty(), "editor opWords not known to OpRegistry: $unknown")

        // Reverse: the editor never *omits* a transmutation target. `transmutableOps`
        // are exactly the canonical ops `op=` can rebuild a node into, i.e. what a
        // user types after `op=` and expects highlighted/autocompleted; a missing one
        // is a silent editor gap for a real feature. This is deliberately scoped to
        // transmutation targets, NOT all of `OpRegistry.knownOps`: the registry's own
        // contract is that data-carrying ops (piecewise, timeline, rbf, sequence, …)
        // are *known* but are not transmutation targets, and `fnrewrite.js` documents
        // `opWords` as a curated subset of `knownOps`. Requiring every known op would
        // fight that design and need a large allow-list; requiring every transmutable
        // op is the principled completeness boundary and needs no allow-list today
        // (every transmutation target is already surfaced). The forward check above
        // still licenses the extra highlighted-but-not-transmutable words (delay,
        // mirror, repeat, warp, timeline).
        val surfaced = opWords.mapTo(HashSet()) { it.lowercase() }
        val missingFromEditor = OpRegistry.transmutableOps.filterNot { it.lowercase() in surfaced }
        assertTrue(
            missingFromEditor.isEmpty(),
            "OpRegistry transmutation targets missing from editor opWords: $missingFromEditor"
        )

        // The core transmutable oscillators must stay highlighted in the editor.
        assertTrue(surfaced.containsAll(listOf("sin", "cos", "saw", "triangle", "square")))
    }

    @Test
    fun graphQueriesKeepOccurrencePathsWhileMetricsRecognizeSharing() = with(Functions) {
        val shared = sin(2.0)
        val graph = shared + shared

        val matches = graph.graphMatches { it.op == "sin" }
        val metrics = graph.graphMetrics { node -> if (node.op == "sin") 10.0 else 1.0 }

        assertEquals(listOf(listOf(0), listOf(1)), matches.map { it.context.path })
        assertEquals(6, metrics.uniqueNodes)
        assertEquals(11, metrics.occurrences)
        assertEquals(10, metrics.edges)
        assertEquals(15.0, metrics.cost)
    }

    @Test
    fun namedRulesProduceTraceAndReachAFixedPoint() = with(Functions) {
        val eliminateAddZero = FnGraphRule(
            name = "x + 0 -> x",
            matches = { context ->
                context.op == "add" && context.node.child("right")?.graphNode()?.numbers?.get("value") == 0.0
            },
            replace = { _, copy -> copy.graphNode().child("left") ?: copy }
        )
        val source = (sin(2.0) + 0.0) + 0.0

        val run = source.rewriteUntilStable(eliminateAddZero)

        assertTrue(run.stable)
        assertEquals(FnGraphRewriteStop.STABLE, run.stop)
        assertEquals(2, run.events.size)
        assertEquals(setOf("x + 0 -> x"), run.events.map { it.rule }.toSet())
        assertEquals("sin", run.function.graphNode().op)
        assertEquals(source.eval(0.37), run.function.eval(0.37), 1e-12)
    }

    @Test
    fun fixedPointRunnerReportsRewriteCycles() = with(Functions) {
        val sinToCos = FnGraphRule("sin -> cos", { it.op == "sin" }) { _, copy ->
            copy.rebuildAsOp("cos") ?: copy
        }
        val cosToSin = FnGraphRule("cos -> sin", { it.op == "cos" }) { _, copy ->
            copy.rebuildAsOp("sin") ?: copy
        }

        val run = sin(1.0).rewriteUntilStable(sinToCos, cosToSin, maxPasses = 8)

        assertFalse(run.stable)
        assertEquals(FnGraphRewriteStop.CYCLE, run.stop)
        assertEquals(2, run.passes)
    }

    @Test
    fun dslOperationReplacementRemainsVisibleToLaterGraphPasses() = with(Functions) {
        val changed = sin(1.0).rewrite("sin => op=cos")

        assertEquals("cos", changed.graphNode().op)
        assertTrue(changed.graphMatches { it.op == "cos" }.isNotEmpty())
        assertTrue(changed.graphMatches { it.op == "name" }.isEmpty())
    }

    @Test
    fun fnRewriteCompilerIsForgivingButNeverSilent() {
        val program = compileFnRewrite(
            """
            #fnrewrite
            saw -> op=triangle       # inline comments are human-friendly
            role=amplitude → *= 0.5  // unicode arrows work too
            sin => wobble wildly
            any => op=notAnOperation
            """.trimIndent()
        )

        assertEquals(4, program.rules.size)
        assertFalse(program.valid)
        assertTrue(program.diagnostics.any { it.severity == FnRewriteSeverity.ERROR && "Unknown action" in it.message })
        assertTrue(program.diagnostics.any { it.severity == FnRewriteSeverity.WARNING && "not a known operation" in it.message })
        assertTrue("at 4:1" in program.diagnosticText())
    }

    @Test
    fun previewReportsMatchesChangesAndUnmatchedRulesForAgents() = with(Functions) {
        val preview = (saw(0.25) * 0.5).previewRewrite(
            """
            saw => op=triangle
            role=amplitude => *= 0.25
            parent=doesNotExist => drop
            """.trimIndent()
        )

        assertTrue(preview.program.valid)
        assertTrue(preview.matchCount >= 2)
        assertTrue(preview.changeCount >= 2)
        assertEquals(listOf("parent=doesNotExist"), preview.unmatchedRules.map { it.selector })
        assertTrue("unmatched: line 3" in preview.summary())
        assertTrue("path=/" in preview.summary())
        assertEquals("multiply", preview.function.graphNode().op)
        assertTrue(preview.function.graphMatches { it.op == "triangle" }.isNotEmpty())
    }

    @Test
    fun checkedRewriteRejectsBadProgramsWhileLegacyRewriteStaysForgiving() = with(Functions) {
        val source = saw(0.25)
        val malformed = "saw => dance"

        assertFailsWith<IllegalArgumentException> { source.rewriteChecked(malformed) }
        assertEquals(source.eval(0.37), source.rewrite(malformed).eval(0.37), 1e-12)
    }

    @Test
    fun tweenAndCursorCoverCommonProceduralMotionOperations() = with(Functions) {
        val tween = tween(10.0, 20.0, 2.0..4.0, Easing.SINE_IN_OUT)
        assertEquals(10.0, tween.eval(2.0), 1e-12)
        assertEquals(15.0, tween.eval(3.0), 1e-12)
        assertEquals(20.0, tween.eval(4.0), 1e-12)
        assertEquals("tween", tween.deepCopy().graphNode().op)

        val cursor = identity(Functions.Domain.ALL).cursor(start = 3.0, step = 0.5)
        assertEquals(3.0, cursor.peek())
        assertEquals(3.0 to 3.0, cursor.nextWithX())
        cursor.skip(2)
        assertEquals(4.5, cursor.peek())
        assertEquals(3.0, cursor.reset(3.0).peek())
        assertFailsWith<IllegalArgumentException> { cursor.next(-1) }
        Unit
    }

    @Test
    fun constantDelayTranslatesTheDomainSoTheSignalRidesWithIt() = with(Functions) {
        // A constant delay slides the whole signal forward; its finite domain must slide too,
        // otherwise a graph browser (or any domain-driven sampler) plots the wrong window.
        val f = (sin(4.0) over 0..0.5).delay(2.0)
        assertEquals(2.0, f.domain.min, 1e-9)
        assertEquals(2.5, f.domain.max, 1e-9)

        // The delayed signal is live across 2.0..2.5 and out-of-domain before it.
        assertTrue(f.evalSafe(2.25, outside = Double.NaN).isFinite())
        assertTrue(f.evalSafe(0.25, outside = Double.NaN).isNaN())

        // graphTrace samples the shifted window, so the x-axis matches the domain.
        val trace = f.graphTrace(samples = 64)
        assertEquals(2.0, trace.xs.first(), 1e-9)
        assertEquals(2.5, trace.xs.last(), 1e-9)

        // A signal-valued (non-constant) shift has no single offset, so the domain is intersected,
        // not translated — the ramp's 0..1 domain survives unchanged.
        val varying = (sin(4.0) over 0..1.0).shift(ramp(0.0, 0.5))
        assertEquals(0.0, varying.domain.min, 1e-9)
        assertEquals(1.0, varying.domain.max, 1e-9)
    }

    @Test
    fun graphBrowserKeepsLocalAndContextualEffectOnTheRootTimeBase() = with(Functions) {
        val source = sin(1.0).shift(0.125) * 0.5
        val trace = source.graphTrace(sampleDomain = Functions.Domain.DEFAULT, samples = 64)
        val root = trace.nodes.first { it.path.isEmpty() }
        val sine = trace.nodes.first { it.op == "sin" }

        assertEquals(64, trace.xs.size)
        assertEquals(64, trace.final.size)
        assertEquals(trace.final.toList(), root.effect.toList())
        assertTrue(sine.effect.indices.any { index ->
            sine.effect[index].isFinite() && sine.local[index].isFinite() &&
                kotlin.math.abs(sine.effect[index] - sine.local[index]) > 1e-4
        })

        val html = source.graphBrowseHtml(samples = 64, maxNodes = 16)
        assertTrue("data-mode=&quot;effect&quot;" in html)
        assertTrue("data-mode=&quot;cascade&quot;" in html)
        assertTrue("data-mode=&quot;sensitivity&quot;" in html)
        assertTrue("the function&#39;s domain" in html)
        assertFalse("gradient" in html.lowercase())
    }

    @Test
    fun cascadeChainAndSensitivitySweepsRideTheRootTimeBase() = with(Functions) {
        val source = (sin(1.0).shift(0.125) * 0.5).withName("gesture")
        val trace = source.graphTrace(sampleDomain = Functions.Domain.DEFAULT, samples = 64, sensitivitySteps = 7)

        // The cascade view walks a node's ancestor prefixes; every prefix must be a present occurrence.
        val leaf = trace.nodes.maxBy { it.path.size }
        val present = trace.nodes.mapTo(HashSet()) { it.path }
        for (depth in 0..leaf.path.size) assertTrue(leaf.path.take(depth) in present, "prefix ${leaf.path.take(depth)}")

        // A numeric leaf carries a value sweep, each swept curve on the same 64-sample axis.
        val numeric = trace.nodes.first { it.numeric }
        assertEquals(7, numeric.sensitivity.size)
        assertTrue(numeric.sensitivity.all { it.samples.size == 64 })
        assertTrue(numeric.sensitivity.map { it.value }.distinct().size > 1)
    }

    @Test
    fun rewriteTraceGivesSteppableBeforeAfterLanes() = with(Functions) {
        val source = (saw(0.2) * 0.5).withName("gesture")
        val spec = """
            saw => op=triangle
            **/[role=amplitude] => *= 0.5
        """.trimIndent()
        val trace = source.graphRewriteTrace(spec, sampleDomain = Functions.Domain.DEFAULT, samples = 48)

        assertEquals(2, trace.stages.size)
        assertTrue(trace.stages.all { it.before.size == 48 && it.after.size == 48 && it.delta.size == 48 })
        assertTrue(trace.stages.first().changed)
        // Cumulative: the last stage's after is the reported final.
        assertEquals(trace.stages.last().after.toList(), trace.finalSamples.toList())

        val html = trace.rewriteBrowseHtml()
        assertTrue("before" in html && "after" in html && "delta" in html)
        assertTrue("stepRange" in html)
    }
}
