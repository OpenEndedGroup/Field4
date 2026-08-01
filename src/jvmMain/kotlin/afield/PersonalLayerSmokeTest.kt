package afield

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/*
FIELD_TEST
id: core.personal-layer.smoke
runner: jvm-main
entrypoint: afield.PersonalLayerSmokeTest
tags: [smoke, core, kotlin, repl, classloader]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "PersonalLayer tests passed"
*/
/**
 * Guards the [PersonalLayer] boundary: `src/personal` is off the app `-cp` and reachable only
 * through its own classloader, so the ways it can quietly stop working are all invisible.
 *
 * Each of these fails silently rather than loudly if it regresses — box code stops resolving
 * personal symbols with an ordinary "unresolved reference", which looks like a user typo; and
 * completion just returns fewer candidates. Hence a test rather than trust:
 *
 *  1. personal really is off `-cp` (otherwise the layer is decorative and Phase 2 can never drop it),
 *  2. [PersonalLayer.wrap] is idempotent (Server.main installs the layer as the context classloader,
 *     so KI2's `wrap(contextClassLoader)` must not stack a second layer on top of the first),
 *  3. and — the one that actually matters — the K2 REPL both *compiles* and *runs* against it.
 *     Compilation is the subtle half: it works only because
 *     `dependenciesFromClassloader(wholeClasspath = true)` walks the parent chain collecting each
 *     loader's URLs, which is behaviour of the Kotlin scripting library, not of anything here.
 *
 * Skips (exit 0) when there is no `src/personal`, which is the normal state for everyone but Marc.
 */
object PersonalLayerSmokeTest {

    // A pure-Kotlin personal class with no native or heavyweight dependencies: kiwi is a
    // self-contained Cassowary port, so a failure here is about the classloader, not about
    // whether some transitive JNI library happened to initialise.
    private const val PROBE_CLASS = "kiwi.Variable"

    fun runTests(): Boolean {
        return try {
            println("Running PersonalLayer tests...")
            if (!PersonalLayer.isPresent) {
                println("  no src/personal on this checkout — skipping")
                println("✅ PersonalLayer tests passed (skipped)")
                return true
            }
            println("  roots: ${PersonalLayer.roots.joinToString { it.path }}")
            testPersonalIsOffTheAppClasspath()
            testWrapIsIdempotent()
            testReplCompilesAndRunsAgainstTheLayer()
            testImportHelpStillScansBothSides()
            // Retires a layer, so everything above it must have run already.
            testReloadRetiresAndRebuilds()
            println("✅ PersonalLayer tests passed")
            true
        } catch (t: Throwable) {
            println("❌ PersonalLayer tests failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "PersonalLayer tests failed" }
    }

    private fun testPersonalIsOffTheAppClasspath() {
        println("  Testing personal is reachable only through the layer...")
        val app = PersonalLayer::class.java.classLoader
        val leaked = runCatching { app.loadClass(PROBE_CLASS) }.isSuccess
        require(!leaked) {
            "$PROBE_CLASS resolved from the app classloader — the personal jar is back on -cp, " +
                "so the layer no longer isolates anything"
        }
        val viaLayer = runCatching { PersonalLayer.current.loadClass(PROBE_CLASS) }
        require(viaLayer.isSuccess) {
            "$PROBE_CLASS did not resolve through PersonalLayer either: ${viaLayer.exceptionOrNull()}"
        }
    }

    private fun testWrapIsIdempotent() {
        println("  Testing wrap() does not stack layers...")
        val once = PersonalLayer.current
        require(PersonalLayer.wrap(once) === once) {
            "wrap(current) built a second layer on top of the first; Server.main installs the layer " +
                "as the context classloader and KI2 then wraps that, so this would nest on every engine"
        }
    }

    private fun testReplCompilesAndRunsAgainstTheLayer() = runBlocking {
        println("  Testing the K2 REPL resolves personal code...")
        val ki = KotlinInterface2(filePrefix = "personal-layer")
        val result = withTimeout(120_000) { ki.exec("$PROBE_CLASS(\"probe\").name()") }
        require(result.toString().contains("probe")) {
            "expected the snippet to construct a $PROBE_CLASS and read back its name; got: $result"
        }
        ki.dispose()
    }

    /**
     * `importHelp`'s ClassGraph scan names [PersonalLayer] explicitly, because ClassGraph stops
     * auto-detecting classloaders as soon as you add one. Both halves have to survive that: the
     * layer (personal classes, which are off `-cp`) and everything it delegates to (core).
     */
    private fun testImportHelpStillScansBothSides() {
        println("  Testing import help sees both core and personal...")
        val personal = findClassesNamed(PROBE_CLASS.substringAfterLast('.')).orEmpty()
        require(personal.contains(PROBE_CLASS)) {
            "import help did not find $PROBE_CLASS — the ClassGraph scan is missing the personal layer; got: $personal"
        }
        val core = findClassesNamed("Box").orEmpty()
        require(core.contains("afield.Box")) {
            "import help lost afield.Box — adding a classloader dropped the default scan; got: ${core.take(10)}"
        }
    }

    /**
     * The reload itself, minus the box plumbing: retire a layer, confirm the replacement is genuinely
     * a *different* one, and confirm the one-peel scan spots an engine still holding the old classes.
     *
     * The "different Class object" assertion is the one that earns its keep. A `URLClassLoader` keeps
     * its jars open and cached, so a reload that reused the same jar path could hand back the old
     * bytes and look like it worked — which is why [PersonalLayer] stages each generation separately.
     */
    private fun testReloadRetiresAndRebuilds() = runBlocking {
        println("  Testing reload retires the layer and rebuilds it...")
        val before = PersonalLayer.current
        val staleClass = before.loadClass(PROBE_CLASS)

        // One engine per route into the scan, all built while gen0 is still live. Each declaration
        // is shaped so that exactly one branch can find it: `declared` is null at runtime so only
        // the declared type betrays it; `boxed` and `peeled` are typed `Any`/`List<Any>` so the
        // declared type says nothing and only the value (and, for `peeled`, one level inside the
        // list) does; `signature` never holds a value at all.
        val engines = linkedMapOf(
            "declared type" to "val declared: $PROBE_CLASS? = null",
            "runtime value" to "val boxed: Any = $PROBE_CLASS(\"boxed\")",
            "peeled collection" to "val peeled: List<Any> = listOf($PROBE_CLASS(\"peeled\"))",
            "function signature" to "fun signature(v: $PROBE_CLASS) = v.name()",
        ).entries.associate { (route, code) ->
            val ki = KotlinInterface2(filePrefix = "personal-stale-${route.substringBefore(' ')}")
            withTimeout(120_000) { ki.exec(code) }
            require(scanEngineForStaleClasses(ki) == null) {
                "nothing has been retired yet, so the scan must find nothing for '$route'"
            }
            route to ki
        }

        val retired = PersonalLayer.advance()
        require(retired == 0) { "expected to retire gen0, got $retired" }
        KotlinInterface2.sharedClassLoader = null

        val after = PersonalLayer.current
        require(after !== before) { "advance() did not produce a new layer" }
        require(generateSequence(after) { it.parent }.none { it === before }) {
            "the new layer was stacked on top of the retired one — gen0 classes would still win " +
                "parent-first delegation, and the old layer could never be collected"
        }
        require(after.loadClass(PROBE_CLASS) !== staleClass) {
            "$PROBE_CLASS resolved to the same Class after reload — the new loader is serving the " +
                "old bytes, so a real edit to src/personal would not have taken effect"
        }
        require(PersonalLayer.retiredGenerationOf(staleClass) == 0) {
            "the old $PROBE_CLASS should be attributable to gen0"
        }
        require(PersonalLayer.retiredGenerationOf(Box::class.java) == null) {
            "a core class must never be reported as stale"
        }

        engines.forEach { (route, ki) ->
            val found = scanEngineForStaleClasses(ki)
            require(found != null && found.contains("gen0")) {
                "the scan missed the '$route' route into a gen0 class; got: $found"
            }
            println("    $route → $found")
            ki.dispose()
        }

        // And a fresh engine is clean, which is what makes resetting a box the fix.
        val fresh = KotlinInterface2(filePrefix = "personal-fresh")
        withTimeout(120_000) { fresh.exec("val probeOne = $PROBE_CLASS(\"three\")") }
        require(scanEngineForStaleClasses(fresh) == null) {
            "an engine created after the reload must hold no stale classes; got: ${scanEngineForStaleClasses(fresh)}"
        }
        fresh.dispose()
    }
}
