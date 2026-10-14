package afield

import kotlin.script.experimental.api.ResultValue

/*
FIELD_TEST
id: core.repl-rerun-compile-cache.smoke
runner: jvm-main
entrypoint: afield.ReplRerunCompileCacheSmokeTest
tags: [smoke, core, kotlin, repl, regression]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "ReplRerunCompileCache tests passed"
*/
/**
 * [RerunCompileCache]: a rerun of an unchanged snippet is evaluated again without compiling, its
 * body really runs, and every change that would make a recompile bind differently compiles.
 *
 * The launch path runs the regathered preamble and then the box code through
 * [KotlinInterface2.exec] on every rerun; this drives the same calls on one engine.
 */
object ReplRerunCompileCacheSmokeTest {

    /** Incremented by snippet bodies, so a test can see that a re-evaluated body ran. */
    @JvmField
    var bodyRuns = 0

    private const val COUNT = "afield.ReplRerunCompileCacheSmokeTest.bodyRuns++"

    @JvmStatic
    fun main(args: Array<String>) {
        KotlinEngineConfig.rerunCompileCache = true
        hitReevaluatesWithoutCompiling()
        editedCodeRecompiles()
        changedPreambleRecompilesTheCodeThatReadsIt()
        expansionChangeRecompiles()
        snippetEvaluatedLastIsRecompiled()
        otherPropertyIsADifferentEntry()
        newEngineStartsCold()
        disposedEngineDoesNotReuse()
        switchedOffAlwaysCompiles()
        println("ReplRerunCompileCache tests passed")
    }

    private fun ResultValue?.v(): Any? = (this as? ResultValue.Value)?.value

    private fun engine() = KotlinInterface2()

    private fun KotlinInterface2.run(s: String, property: String = "code"): Any? {
        sourceProperty = property
        var failure: String? = null
        val r = execAndWait(s, { failure = "compile $it" }, { failure = "eval $it" }, { failure = "exception $it" }, emptyList())
        check(failure == null) { "`$s` failed: $failure" }
        return r.v()
    }

    private fun hitReevaluatesWithoutCompiling() {
        val ki = engine()
        val preamble = "val base = 10\nfun scale(x: Int) = x * base"
        val code = "$COUNT; scale(bodyCount())".replace("bodyCount()", "afield.ReplRerunCompileCacheSmokeTest.bodyRuns")
        val before = bodyRuns
        ki.run(preamble, "preamble")
        val first = ki.run(code)
        val compilesAfterFirst = ki.compileCount
        val results = (1..5).map { ki.run(preamble, "preamble"); ki.run(code) }
        check(ki.compileCount == compilesAfterFirst) { "reruns compiled: ${ki.compileCount} vs $compilesAfterFirst" }
        check(bodyRuns == before + 6) { "re-evaluated bodies did not all run: ${bodyRuns - before} of 6" }
        check(results == (1..5).map { (before + 1 + it) * 10 }) { "rerun results $results (first $first)" }
        check(ki.rerunCache.hits == 10) { "expected 10 hits, got ${ki.rerunCache.hits}" }
        println("  hit: 5 reruns of preamble+code, 0 compiles, bodies ran, results $results")
    }

    private fun editedCodeRecompiles() {
        val ki = engine()
        ki.run("val base = 3", "preamble")
        check(ki.run("base + 1") == 4)
        ki.run("val base = 3", "preamble")
        val n = ki.compileCount
        check(ki.run("base + 2") == 5) { "edited code did not show its new behaviour" }
        check(ki.compileCount == n + 1) { "edited code was not compiled" }
        println("  edit: new source compiled and shows new behaviour")
    }

    private fun changedPreambleRecompilesTheCodeThatReadsIt() {
        val ki = engine()
        ki.run("val q = 5", "preamble")
        check(ki.run("q * 2") == 10)
        ki.run("val q = 6", "preamble") // an ancestor changed its declaration
        val n = ki.compileCount
        val r = ki.run("q * 2")
        check(r == 12) { "unchanged code bound the old preamble: $r (re-evaluating would say 10)" }
        check(ki.compileCount == n + 1) { "code reading a redeclared name was not recompiled" }
        check(ki.rerunCache.lastMissReason.contains("redeclared")) { ki.rerunCache.lastMissReason }
        println("  preamble: redeclared 'q' recompiles the code that reads it -> $r")
    }

    private fun expansionChangeRecompiles() {
        // The key is the macro-EXPANDED text: a macro expanding differently (here, the `__line__`
        // substitution a fragment gets) is a different source, however alike the raw text is.
        val ki = engine()
        ki.run("val a = 1", "preamble")
        check(ki.run("a + 0 // line 0") == 1)
        ki.run("val a = 1", "preamble")
        val n = ki.compileCount
        check(ki.run("a + 0 // line 1") == 1)
        check(ki.compileCount == n + 1) { "different expansion reused a snippet" }
        println("  expansion: a different expansion compiles")
    }

    private fun snippetEvaluatedLastIsRecompiled() {
        val ki = engine()
        val before = bodyRuns
        ki.run(COUNT)
        val n = ki.compileCount
        ki.run(COUNT)
        check(bodyRuns == before + 2) { "body did not run twice: ${bodyRuns - before}" }
        check(ki.compileCount == n + 1) { "the snippet evaluated last was re-evaluated (its body would not run)" }
        println("  last-evaluated: same snippet twice in a row compiles, body runs twice")
    }

    private fun otherPropertyIsADifferentEntry() {
        val ki = engine()
        ki.run("1 + 1", "code")
        ki.run("2", "code")
        val n = ki.compileCount
        ki.run("1 + 1", "other")
        check(ki.compileCount == n + 1) { "same text under another property reused a snippet" }
        println("  property: another source property compiles")
    }

    private fun newEngineStartsCold() {
        val a = engine()
        a.run("val z = 1", "preamble"); a.run("z"); a.run("val z = 1", "preamble"); a.run("z")
        check(a.compileCount == 2)
        val b = engine() // a reset box gets a new engine
        b.run("val z = 1", "preamble"); b.run("z")
        check(b.compileCount == 2) { "a new engine reused another engine's snippets" }
        println("  reset: a new engine compiles everything")
    }

    private fun disposedEngineDoesNotReuse() {
        val ki = engine()
        ki.run("val w = 1", "preamble"); ki.run("w"); ki.run("val w = 1", "preamble")
        ki.dispose()
        var compileError = false
        ki.execAndWait("w", { compileError = true }, {}, {}, emptyList())
        check(compileError) { "a disposed engine evaluated a cached snippet" }
        println("  dispose: a disposed engine refuses instead of reusing")
    }

    private fun switchedOffAlwaysCompiles() {
        KotlinEngineConfig.rerunCompileCache = false
        try {
            val ki = engine()
            repeat(3) { ki.run("val y = 2", "preamble"); ki.run("y") }
            check(ki.compileCount == 6) { "switched off but compiled ${ki.compileCount}" }
        } finally {
            KotlinEngineConfig.rerunCompileCache = true
        }
        println("  off: -Dafield.kotlin.rerunCompileCache=false compiles every time")
    }
}
