package afield

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.dependencies
import kotlin.script.experimental.jvm.JvmDependency

/*
FIELD_TEST
id: kotlin.shared-types-classpath.spike
runner: jvm-main
entrypoint: afield.SharedTypesClasspathSpike
tags: [spike, kotlin, repl, exported]
timeoutSec: 420
expect:
  exitCode: 0
  stdoutContains:
    - "Shared-types classpath spike complete"
*/
/**
 * Measures whether a *shared declarations directory* can carry user-defined types between boxes.
 *
 * Background: each box has its own [KotlinInterface2], so a type declared in one box's snippet is
 * invisible to every other box — see [ExportedTypeNames]. The proposed fix is to compile such types
 * as ordinary Kotlin into a directory hung off the process-wide shared classloader, which every
 * engine derives its compile classpath from. Runtime identity is free that way (snippet loaders are
 * children of the shared loader). The open question is compile-time visibility, because
 * `dependenciesFromClassloader` *snapshots* the classpath into a `JvmDependency(files)` rather than
 * holding a live view of the loader.
 *
 * Hence the one thing worth measuring, [probeA]: a directory that was on the classpath snapshot but
 * *empty* at the time — does a class file appearing in it later become resolvable to an engine that
 * already exists? If yes, publishing a shared type is just writing a file. If no, publishing has to
 * rebuild the consuming engines, which is a much bigger hammer and changes the feature's cost.
 *
 * [probeB] is the invariant rather than the measurement: a *fresh* engine must see the directory,
 * since that is the fallback the whole design rests on. [probeC] checks the payoff actually
 * survives the trip — named arguments and a `data class` `toString`, which only work if real Kotlin
 * metadata made it across, not merely a loadable JVM class.
 *
 * This is a spike: it fails on B or C, and *reports* A either way.
 */
object SharedTypesClasspathSpike {

    private const val SHARED_SOURCE = """
package shared

data class Foo(val a: Double, val b: Double)
"""

    /** What we ask each engine to evaluate. Named arguments require real parameter metadata. */
    private const val PROBE_SNIPPET = """shared.Foo(a = 1.0, b = 2.0).toString()"""

    private var probeAResolved = false

    fun runTests(): Boolean {
        return try {
            println("Running shared-types classpath spike...")

            val root = Files.createTempDirectory("shared-types-spike").toFile()
            val sharedDir = File(root, "classes")
            // Must exist before any engine is built: the classpath extractor drops entries that are
            // neither an existing directory nor a jar, so a not-yet-created dir is silently lost.
            check(sharedDir.mkdirs()) { "could not create $sharedDir" }

            // Stand in for what the real feature would do at startup: put the shared declarations
            // directory on the process-wide loader before the first engine snapshots its classpath.
            KotlinInterface2.sharedClassLoader = URLClassLoader(
                arrayOf(sharedDir.toURI().toURL()),
                Thread.currentThread().contextClassLoader
            )

            val engineA = KotlinInterface2(filePrefix = "shared-types-A")
            sanityCheck(engineA)
            assertOnClasspath(engineA, sharedDir)

            // The directory was empty for all of the above. Only now does the class appear.
            compileSharedTypes(root, sharedDir)

            probeA(engineA)
            probeB(sharedDir)

            println("Shared-types classpath spike complete")
            println(
                if (probeAResolved) {
                    "VERDICT: late-arriving classes ARE visible to an existing engine — " +
                        "publishing a shared type is a file write."
                } else {
                    "VERDICT: late-arriving classes are NOT visible to an existing engine — " +
                        "publishing a shared type must rebuild the consuming engines."
                }
            )
            true
        } catch (t: Throwable) {
            println("❌ shared-types classpath spike failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "shared-types classpath spike failed" }
    }

    private fun sanityCheck(ki: KotlinInterface2) {
        val result = runBlocking { withTimeout(180_000) { ki.exec("40 + 2") } }
        require(result.toString().contains("42")) { "engine is not evaluating at all, got: $result" }
        println("  engine evaluates ordinary snippets")
    }

    /** Proves the plumbing before testing the interesting part. */
    private fun assertOnClasspath(ki: KotlinInterface2, sharedDir: File) {
        val classpath = ki.compilerConfig[ScriptCompilationConfiguration.dependencies]
            .orEmpty()
            .filterIsInstance<JvmDependency>()
            .flatMap { it.classpath }
        val present = classpath.any { it.canonicalFile == sharedDir.canonicalFile }
        require(present) {
            "shared dir never reached the compile classpath (${classpath.size} entries) — " +
                "the rest of this spike would be measuring nothing"
        }
        println("  shared dir is on the compile classpath (${classpath.size} entries total)")
    }

    /** Compiles the shared declarations as ordinary Kotlin, via the same path the feature uses. */
    private fun compileSharedTypes(root: File, outDir: File) {
        val (produced, log) = SharedTypes.compileTo(SHARED_SOURCE, outDir, moduleName = "shared-types-spike")
        require(produced.isNotEmpty()) { "compiling shared types failed:\n$log" }
        println("  compiled shared.Foo -> ${produced.joinToString { it.name }}")
    }

    /**
     * THE MEASUREMENT. The engine predates the class file entirely. Resolving it means the compiler
     * re-scans classpath directories per compilation rather than trusting the snapshot.
     */
    private fun probeA(ki: KotlinInterface2) {
        val outcome = try {
            val result = runBlocking { withTimeout(180_000) { ki.exec(PROBE_SNIPPET) } }
            result?.toString()
        } catch (t: Throwable) {
            println("  probe A threw: ${t::class.simpleName}: ${t.message}")
            null
        }
        probeAResolved = outcome != null && outcome.contains("Foo(a=1.0, b=2.0)")
        println("  probe A (pre-existing engine, late class): resolved=$probeAResolved outcome=$outcome")
    }

    /** The invariant, plus the payoff check. A fresh engine must see the directory. */
    private fun probeB(sharedDir: File) {
        val ki = KotlinInterface2(filePrefix = "shared-types-B")
        assertOnClasspath(ki, sharedDir)
        val rendered = runBlocking { withTimeout(180_000) { ki.exec(PROBE_SNIPPET) } }?.toString()
        require(rendered != null && rendered.contains("Foo(a=1.0, b=2.0)")) {
            "probe B: a fresh engine could not use shared.Foo — the shared-directory approach does " +
                "not work at all. Got: $rendered"
        }
        println("  probe B (fresh engine): $rendered")
        probeC(ki)
    }

    /**
     * The payoff. `copy` with a named argument and a data-class `toString` only work if Kotlin
     * metadata crossed, not merely a loadable JVM class.
     */
    private fun probeC(ki: KotlinInterface2) {
        val copied = runBlocking {
            withTimeout(180_000) { ki.exec("shared.Foo(a = 1.0, b = 2.0).copy(b = 5.0).toString()") }
        }?.toString()
        require(copied != null && copied.contains("Foo(a=1.0, b=5.0)")) {
            "probe C: data-class metadata did not survive (copy/named args). Got: $copied"
        }
        println("  probe C (copy + named args + data toString): $copied")

        val destructured = runBlocking {
            withTimeout(180_000) { ki.exec("val (x, y) = shared.Foo(3.0, 4.0); x + y") }
        }?.toString()
        require(destructured != null && destructured.contains("7")) {
            "probe C: destructuring failed, componentN not visible. Got: $destructured"
        }
        println("  probe C (destructuring): $destructured")
    }
}
