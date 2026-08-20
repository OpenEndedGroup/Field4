package afield

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.reflect.full.createType

/*
FIELD_TEST
id: kotlin.shared-types.smoke
runner: jvm-main
entrypoint: afield.SharedTypesSmokeTest
tags: [smoke, kotlin, repl, exported]
timeoutSec: 420
expect:
  exitCode: 0
  stdoutContains:
    - "Shared types smoke passed"
*/
/**
 * End-to-end smoke for [SharedTypes]: publishing user-declared Kotlin types that every box can name.
 *
 * The assertions cover the whole arc the feature exists for:
 *
 *  - [testPublishProducesLoadableClasses] — publish compiles and lands on the shared loader.
 *  - [testPublishedTypeIsExportable] — the payoff for `@exported`. A published type passes
 *    [ExportedTypeNames], so it crosses between boxes with its real type where a box-declared class
 *    can only ever cross as `Any?`. This is the join between the two halves of the work.
 *  - [testFreshEngineGetsFullFidelity] — named arguments, `copy`, destructuring and the data-class
 *    `toString` all survive, which only happens because these are real compiled classes.
 *
 * [measureRepublish] is a measurement rather than an assertion, in the style of
 * `SharedTypesClasspathSpike`: it reports whether a *second* publish of the same class name actually
 * takes effect. It may well not — `gen-1` was added to the classloader before `gen-2`, and URL
 * search order is first-wins — and if so that is a real constraint on the feature, not a test bug.
 */
object SharedTypesSmokeTest {

    private const val SOURCE_V1 = """
package shared

data class Foo(val a: Double, val b: Double)
"""

    private const val SOURCE_V2 = """
package shared

data class Foo(val a: Double, val b: Double) {
    val sum: Double get() = a + b
}
"""

    private lateinit var workspace: File

    fun runTests(): Boolean {
        return try {
            println("Running shared types smoke...")

            workspace = Files.createTempDirectory("shared-types-smoke").toFile()

            // Mirror what KotlinInterface2's init would build, so publish targets the loader that
            // every engine in this process derives its compile classpath from.
            KotlinInterface2.sharedClassLoader = URLClassLoader(
                arrayOf(),
                Thread.currentThread().contextClassLoader
            )

            val first = testPublishProducesLoadableClasses()
            testPublishedTypeIsExportable()
            testFreshEngineGetsFullFidelity()
            measureRepublish(first)

            println("✅ Shared types smoke passed")
            true
        } catch (t: Throwable) {
            println("❌ Shared types smoke failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "Shared types smoke failed" }
    }

    /** refreshOthers=false throughout: there is no document session in a bare jvm-main test. */
    private fun publish(source: String) =
        SharedTypes.publish(source, workspace, except = null, refreshOthers = false)

    private fun testPublishProducesLoadableClasses(): SharedTypes.Publication {
        val published = publish(SOURCE_V1)
        require(published.ok) { "publish failed: ${published.log}" }
        require(published.classFiles.any { it == "Foo.class" }) {
            "expected Foo.class, got ${published.classFiles}"
        }
        require(published.dir?.let { File(it, "shared/Foo.class").exists() } == true) {
            "Foo.class is not at the package-qualified path in ${published.dir}"
        }

        // Runtime half: the shared loader reaches it, so every box's snippet loader will too.
        val loaded = KotlinInterface2.sharedClassLoader!!.loadClass("shared.Foo")
        require(loaded.name == "shared.Foo") { "unexpected class: ${loaded.name}" }
        println("  published gen-${published.generation} -> ${published.classFiles}, loadable as ${loaded.name}")
        return published
    }

    /**
     * The join with Phase 0. A published type is nameable, so `@exported` carries it with its real
     * type — which is precisely what a box-declared class cannot do.
     */
    private fun testPublishedTypeIsExportable() {
        val loader = KotlinInterface2.sharedClassLoader!!
        val fooType = loader.loadClass("shared.Foo").kotlin.createType()
        val rendered = ExportedTypeNames.render(fooType, loader)
        require(rendered == "shared.Foo") {
            "a published type must be nameable for @exported, got <$rendered>"
        }
        println("  @exported would carry it as: $rendered")

        // And a List of it, to show the argument survives rather than being erased to a star.
        val listOfFoo = List::class.createType(
            listOf(kotlin.reflect.KTypeProjection.invariant(fooType))
        )
        val renderedList = ExportedTypeNames.render(listOfFoo, loader)
        require(renderedList == "kotlin.collections.List<shared.Foo>") {
            "expected List<shared.Foo>, got <$renderedList>"
        }
        println("  ...and List<shared.Foo> as: $renderedList")
    }

    /** The niceties: named arguments, copy, destructuring, data-class toString. */
    private fun testFreshEngineGetsFullFidelity() {
        val ki = KotlinInterface2(filePrefix = "shared-types-smoke-1")

        val basic = eval(ki, "shared.Foo(a = 1.0, b = 2.0).toString()")
        require(basic.contains("Foo(a=1.0, b=2.0)")) { "named args / toString failed: $basic" }

        val copied = eval(ki, "shared.Foo(a = 1.0, b = 2.0).copy(b = 5.0).toString()")
        require(copied.contains("Foo(a=1.0, b=5.0)")) { "copy with named arg failed: $copied" }

        val destructured = eval(ki, "val (x, y) = shared.Foo(3.0, 4.0); x + y")
        require(destructured.contains("7")) { "destructuring failed: $destructured" }

        println("  fresh engine: named args, copy and destructuring all work")
    }

    /**
     * MEASUREMENT, not an assertion. Publishing `shared.Foo` a second time writes `gen-2`, but
     * `gen-1` is already ahead of it on the classloader and on the compile classpath, and URL search
     * order is first-wins. If the new member is invisible, re-publishing a *changed* type needs a
     * new shared classloader — i.e. a document-wide restart, not a per-box one.
     */
    private fun measureRepublish(first: SharedTypes.Publication) {
        val second = publish(SOURCE_V2)
        require(second.ok) { "second publish failed to compile: ${second.log}" }
        require(second.generation > first.generation) {
            "generation did not advance: ${first.generation} -> ${second.generation}"
        }
        println("  re-published as gen-${second.generation} (gen-${first.generation} still on the loader)")
        require(second.shadowed == listOf("shared.Foo")) {
            "a re-publish must report what it shadows rather than fail silently, got ${second.shadowed}"
        }
        println("  publish reported the shadowing: ${second.shadowed}")

        val ki = KotlinInterface2(filePrefix = "shared-types-smoke-2")
        val outcome = try {
            eval(ki, "shared.Foo(1.0, 2.0).sum")
        } catch (t: Throwable) {
            "threw ${t::class.simpleName}: ${t.message}"
        }
        val visible = outcome.contains("3")
        println("  probe: new member visible after re-publish = $visible ($outcome)")
        println(
            if (visible) {
                "VERDICT: re-publish takes effect — generation directories are enough."
            } else {
                "VERDICT: re-publish is SHADOWED by the earlier generation — changing an existing " +
                    "shared type needs a new shared classloader (document-wide restart), not just a " +
                    "new generation directory."
            }
        )
    }

    private fun eval(ki: KotlinInterface2, code: String): String =
        runBlocking { withTimeout(180_000) { ki.exec(code) } }?.toString() ?: "<null>"
}
