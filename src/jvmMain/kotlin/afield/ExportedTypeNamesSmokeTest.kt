package afield

import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.createType
import kotlin.reflect.typeOf

/*
FIELD_TEST
id: kotlin.exported-type-names.smoke
runner: jvm-main
entrypoint: afield.ExportedTypeNamesSmokeTest
tags: [smoke, kotlin, repl, exported]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "ExportedTypeNames smoke passed"
*/
/**
 * Smoke for [ExportedTypeNames], which decides the type text `@exported` splices into another box's
 * preamble.
 *
 * Two properties are load-bearing and pull in opposite directions, so both are asserted here:
 *
 *  - **Precision.** Library and `afield.` types must survive with their generic arguments intact.
 *    The whole point of the change is that they used to be flattened to `Any?` by a prefix
 *    whitelist that no real type matched.
 *  - **Safety.** A type the consuming box cannot name must degrade to `Any?`, never be emitted
 *    hopefully. A preamble line that fails to compile poisons every later compile in that box, so
 *    a false accept is far more expensive than a false reject.
 *
 * [testUnnameableArgumentIsErasedNotEmitted] is the regression that motivated the rewrite: the old
 * implementation accepted any name starting with `kotlin.` and returned it *whole*, so a
 * `List<SomeBoxLocalClass>` was emitted verbatim with an unresolvable argument inside it.
 *
 * Pure renderer assertions — no REPL, no engine, so this stays fast. Runnable-object test style,
 * matching [WitherSmokeTest].
 */
object ExportedTypeNamesSmokeTest {

    /** The loader every box's engine actually derives its classpath from, in this process. */
    private val appLoader: ClassLoader = ExportedTypeNamesSmokeTest::class.java.classLoader

    fun runTests(): Boolean {
        return try {
            println("Running ExportedTypeNames smoke...")
            testBuiltinsKeepTheirNames()
            testNullabilityIsPreserved()
            testGenericArgumentsSurvive()
            testUserVisibleClassIsNameable()
            testUnnameableArgumentIsErasedNotEmitted()
            testAnonymousClassIsOpaque()
            testNonPublicClassIsOpaque()
            testUnreachableFromLoaderIsOpaque()
            testFunctionTypes()
            println("✅ ExportedTypeNames smoke passed")
            true
        } catch (t: Throwable) {
            println("❌ ExportedTypeNames smoke failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "ExportedTypeNames smoke failed" }
    }

    private fun expect(expected: String, actual: String, what: String) {
        require(expected == actual) { "$what: expected <$expected>, got <$actual>" }
        println("  ok  $what -> $actual")
    }

    private fun testBuiltinsKeepTheirNames() {
        expect("kotlin.Int", ExportedTypeNames.render(typeOf<Int>(), appLoader), "Int")
        expect("kotlin.Double", ExportedTypeNames.render(typeOf<Double>(), appLoader), "Double")
        expect("kotlin.String", ExportedTypeNames.render(typeOf<String>(), appLoader), "String")
        expect("kotlin.Unit", ExportedTypeNames.render(typeOf<Unit>(), appLoader), "Unit")
    }

    private fun testNullabilityIsPreserved() {
        expect("kotlin.String?", ExportedTypeNames.render(typeOf<String?>(), appLoader), "String?")
        // The boxed erasure of a nullable primitive must not change the Kotlin spelling.
        expect("kotlin.Int?", ExportedTypeNames.render(typeOf<Int?>(), appLoader), "Int?")
    }

    /** The headline improvement: nested generics of nameable types keep every argument. */
    private fun testGenericArgumentsSurvive() {
        expect(
            "kotlin.collections.List<kotlin.Double>",
            ExportedTypeNames.render(typeOf<List<Double>>(), appLoader),
            "List<Double>"
        )
        expect(
            "kotlin.collections.Map<kotlin.String, kotlin.collections.List<kotlin.Int>>",
            ExportedTypeNames.render(typeOf<Map<String, List<Int>>>(), appLoader),
            "Map<String, List<Int>>"
        )
    }

    /** An ordinary public app class — the case the old whitelist rejected unless it began `afield.`. */
    private fun testUserVisibleClassIsNameable() {
        expect(
            "afield.ExportedTypeNamesPublicProbe",
            ExportedTypeNames.render(typeOf<ExportedTypeNamesPublicProbe>(), appLoader),
            "public app class"
        )
        expect(
            "kotlin.collections.List<afield.ExportedTypeNamesPublicProbe>",
            ExportedTypeNames.render(typeOf<List<ExportedTypeNamesPublicProbe>>(), appLoader),
            "List of public app class"
        )
    }

    /**
     * The regression. An unnameable *argument* must collapse to a star projection so the outer type
     * still lands; the old code emitted the argument's name and broke the receiving preamble.
     */
    private fun testUnnameableArgumentIsErasedNotEmitted() {
        val anonymous = object {}
        val listOfAnonymous = List::class.createType(
            listOf(KTypeProjection.invariant(anonymous::class.createType()))
        )
        val rendered = ExportedTypeNames.render(listOfAnonymous, appLoader)
        expect("kotlin.collections.List<*>", rendered, "List<anonymous>")
        require(!rendered.contains("$")) { "rendered type leaked a synthetic class name: $rendered" }
    }

    private fun testAnonymousClassIsOpaque() {
        val anonymous = object {}
        expect(
            ExportedTypeNames.OPAQUE,
            ExportedTypeNames.render(anonymous::class.createType(), appLoader),
            "anonymous class"
        )
    }

    /** Compiles here, unresolvable in the consuming box — so it must not be emitted. */
    private fun testNonPublicClassIsOpaque() {
        expect(
            ExportedTypeNames.OPAQUE,
            ExportedTypeNames.render(typeOf<ExportedTypeNamesInternalProbe>(), appLoader),
            "internal class"
        )
    }

    /**
     * The identity check. A loader that cannot reach the class stands in for the real case: a class
     * defined in another box's snippet loader, invisible to the shared base loader.
     */
    private fun testUnreachableFromLoaderIsOpaque() {
        val blind = ClassLoader.getPlatformClassLoader()
        expect(
            ExportedTypeNames.OPAQUE,
            ExportedTypeNames.render(typeOf<ExportedTypeNamesPublicProbe>(), blind),
            "app class via a loader that cannot see it"
        )
        // ...while a JDK type is still reachable from that same loader, so the rejection above is
        // about reachability and not about the loader being rejected wholesale.
        expect("kotlin.String", ExportedTypeNames.render(typeOf<String>(), blind), "String via platform loader")
    }

    private fun testFunctionTypes() {
        val fn = ExportedTypeNames.render(typeOf<(Int) -> String>(), appLoader)
        require(fn.startsWith("kotlin.Function1<")) { "function type should be nameable, got <$fn>" }
        require(fn.contains("kotlin.String")) { "function type lost its return type: <$fn>" }
        println("  ok  (Int) -> String -> $fn")

        // A suspend function type reaches us with its continuation already erased: it reflects as
        // kotlin.Function2<Int, String>, two arguments for a classifier declaring three. That is a
        // compile error if emitted, so the arity check must reject it. `typeOf` refuses to build a
        // suspend type, so this comes off a KProperty — the same route the real export path takes.
        expect(
            ExportedTypeNames.OPAQUE,
            ExportedTypeNames.render(ExportedTypeNamesSuspendProbe::fn.returnType, appLoader),
            "suspend function type"
        )
    }
}

/** Public probe for [ExportedTypeNamesSmokeTest]; nameable from the app classloader. */
class ExportedTypeNamesPublicProbe

/** Non-public probe: resolvable in this module, not from a box's preamble. */
internal class ExportedTypeNamesInternalProbe

/** Carries a suspend function type, which `typeOf` cannot express directly. */
class ExportedTypeNamesSuspendProbe {
    val fn: suspend (Int) -> String = { it.toString() }
}
