package afield.lang.fieldml.tests

import afield.document.graph.Box
import afield.lang.fieldml.FieldMLCompiler
import afield.lang.fieldml.FieldMLRuntimePrelude
import afield.lang.fieldml.core.Evaluator
import afield.lang.fieldml.core.FieldMLValue
import afield.lang.fieldml.core.RuntimeEnv
import afield.lang.fieldml.core.StepFuel
import afield.lang.fieldml.core.evalFieldMLSuspending
import afield.lang.fieldml.core.renderFieldMLValue
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.jvm.internal.CoroutineStackFrame
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps FieldML's two evaluator walks — `evalFieldML` and `evalFieldMLSuspending` in
 * `core/Evaluator.kt` — in step, and pins how much a suspending loop costs per turn.
 *
 * Node-kind coverage is the compiler's job: both walks are exhaustive `when` *expressions* over the
 * sealed `CoreExpr`, so a new node kind handled by one walk and not the other does not compile.
 * What the compiler cannot see is a case handled *differently*; [everyProgramInTheCorpusAgreesOnBothWalks]
 * runs one corpus through both and compares values and failure messages.
 */
class FieldMLWalkParityTest {
    private val corpus = listOf(
        "1 + 2 * 3",
        "-(2.5) * 2.0",
        "\"a\" ++ \"b\"",
        "(1, \"x\", true)",
        "[1, 2, 3] |> map (fun x -> x * x)",
        "let sum n = if n == 0 then 0 else n + sum (n - 1) in sum 10",
        "let fact n = if n <= 1 then 1 else n * fact (n - 1) in fact 10",
        "let go i acc = if i == 0 then acc else go (i - 1) (acc + i) in go 1000 0",
        "type Shape = Dot | Sq Int | Rect Int Int\nlet area s = match s with | Dot -> 0 | Sq n -> n * n | Rect w h -> w * h in [area Dot, area (Sq 3), area (Rect 2 5)]",
        "let r = { a = 1, b = \"two\" } in (r.a, r.b)",
        "let boom u = match u with | 1 -> true in false && boom 0",
        "let boom u = match u with | 1 -> true in true || boom 0",
        "match [1, 2, 3] with | h :: t -> (h, t) | _ -> (0, [])",
        "match (1, \"x\") with | (1, s) -> s | _ -> \"no\"",
        "let x = 1 in let x = x + 1 in x",
        "let compose f g x = f (g x) in compose (fun a -> a + 1) (fun b -> b * 2) 5",
        "vec3 1.0 2.0 3.0 + vec3 1.0 1.0 1.0",
        "(vec2 3.0 4.0).y",
        "let deep n = if n == 0 then 0 else 1 + deep (n - 1) in deep 2000",
        "match 3 with | 1 -> \"one\"",
        "let r = { a = 1 } in r.a + (hostNull |> hostAsInt)",
    )

    private val box = Box("fieldml-walk-parity-test")

    @Test
    fun everyProgramInTheCorpusAgreesOnBothWalks() {
        val uncompiled = mutableListOf<String>()
        for (source in corpus) {
            val core = FieldMLCompiler.compile(source).core ?: run { uncompiled += source; null } ?: continue
            val sync = runCatching { renderFieldMLValue(Evaluator(prelude()).evaluate(core)) }
            var suspending: Result<String>? = null
            suspend { renderFieldMLValue(evalFieldMLSuspending(core, scope())) }
                .startCoroutine(Continuation(EmptyCoroutineContext) { suspending = it })
            assertEquals(outcome(sync), outcome(suspending!!), "walks disagree on: $source")
        }
        assertEquals(emptyList<String>(), uncompiled, "corpus programs that no longer compile")
    }

    /** A host whose `park` suspends until [step] resumes it, recording how deep the caller chain is. */
    class Parker {
        var pending: Continuation<Int>? = null
        val depths = mutableListOf<Int>()
        var finished = false

        suspend fun park(): Int = suspendCoroutine { continuation ->
            var depth = 0
            var frame: CoroutineStackFrame? = (continuation as? CoroutineStackFrame)?.callerFrame
            // Capped: an unbounded walk per turn would make a regression quadratic (the uncapped
            // baseline measurement took 32 minutes for 100k turns).
            while (frame != null && depth < 10_000) {
                depth++
                frame = frame.callerFrame
            }
            depths += depth
            pending = continuation
        }

        fun start(block: suspend () -> Unit) {
            block.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow(); finished = true })
        }
    }

    @Test
    fun anEndlessHostAwaitLoopHoldsAConstantContinuationChain() {
        // `go` calls itself in tail position after every wait. Before 2026-11-03 each turn left one
        // more chain of suspended evaluator frames behind (~13 frames per turn, so ~1.3M live
        // continuation objects after 100k turns); now tail calls loop inside the walk and the
        // chain the host holds is the same length at turn 100,000 as at turn 2.
        val parker = Parker()
        box.setPropertyRaw("parker", parker, false)
        val source = """let p = thisBox |> hostCall "getPropertyRaw" ("parker", hostNull)
p |> hostCall1 "start" (fun u ->
  let go i =
    let _ = hostAwait "park" () p in
    if i < 100000 then go (i + 1) else ()
  in go 0)"""
        val result = FieldMLCompiler.compile(source)
        assertTrue(result.successful, result.diagnostics.joinToString("\n") { it.message })
        Evaluator(prelude()).evaluate(result.core!!)
        while (!parker.finished) parker.pending!!.resume(0)
        assertEquals(100001, parker.depths.size)
        println("FieldMLWalkParityTest continuation depth: turn 1=${parker.depths[1]}, turn 1000=${parker.depths[1000]}, turn 100000=${parker.depths[100000]}")
        assertEquals(parker.depths[1], parker.depths[100000], "continuation chain grew across turns")
    }

    @Test
    fun bothWalksRunOutOfStepsAtTheSameStep() {
        // A runaway tail loop no longer overflows the stack, so it would run forever: the step limit
        // turns it into an error, with the same message on either walk.
        val forever = compile("let spin n = spin (n + 1) in spin 0")
        val (syncForever, suspendingForever) = bothWalks(forever, 100_000)
        assertTrue("step limit reached: 100000" in syncForever, syncForever)
        assertEquals(syncForever, suspendingForever)

        // And they count steps identically: find the exact budget a program needs on the
        // synchronous walk, then check the suspending walk succeeds with it and fails with one less.
        val fact = compile("let fact n = if n <= 1 then 1 else n * fact (n - 1) in fact 10")
        val needed = (1L..100_000L).first { !bothWalks(fact, it).first.startsWith("failure") }
        assertEquals(bothWalks(fact, needed).first, bothWalks(fact, needed).second)
        assertEquals("value: 3628800", bothWalks(fact, needed).second)
        assertEquals(bothWalks(fact, needed - 1).first, bothWalks(fact, needed - 1).second)
        assertTrue(bothWalks(fact, needed - 1).second.startsWith("failure"))
        println("FieldMLWalkParityTest: fact 10 takes $needed steps on both walks")
    }

    private fun compile(source: String) =
        FieldMLCompiler.compile(source).also { assertTrue(it.successful, source) }.core!!

    private fun bothWalks(core: afield.lang.fieldml.core.CoreExpr, limit: Long): Pair<String, String> {
        val sync = runCatching { renderFieldMLValue(Evaluator(prelude(), stepLimit = limit).evaluate(core)) }
        var suspending: Result<String>? = null
        val env = RuntimeEnv(fuel = StepFuel(limit)).also { e -> prelude().forEach { (k, v) -> e.define(k, v) } }
        suspend { renderFieldMLValue(evalFieldMLSuspending(core, env)) }
            .startCoroutine(Continuation(EmptyCoroutineContext) { suspending = it })
        return outcome(sync) to outcome(suspending!!)
    }

    private fun prelude() = FieldMLRuntimePrelude.values(box = box)

    private fun scope(): RuntimeEnv = RuntimeEnv().also { env -> prelude().forEach { (k, v) -> env.define(k, v) } }

    private fun outcome(result: Result<String>): String =
        result.fold({ "value: $it" }, { "failure: ${it::class.simpleName}: ${it.message}" })
}
