package afield

import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.jvm.kotlinFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A coverage guard tying `public/docs/animation_time_model.md` to `Phased.kt`, in the shape
 * [afield.stdlib.KeyframerCoverageTest] uses for `keyframer.kt`.
 *
 * The page's job is to be the one place a reader can see Field's whole frame vocabulary. That is
 * only true if it cannot fall behind the source, so every public declaration in `Phased.kt` is
 * enumerated **by reflection** and must appear in the page's ledger table — and every name the
 * table lists must still exist. Adding a builder to `Phased.kt` fails this test until the page has
 * a row for it.
 *
 * **Names, not signatures.** Unlike the keyframer ledger this keys on `Owner.name` and never on
 * parameter types. That is deliberate: this build resolves a mutable `2.4.255-SNAPSHOT` Kotlin fork
 * whose rendering of function types has flipped between erased and arrow form under a green
 * baseline (see [afield.stdlib.KeyframerCoverageTest.readableType]'s note). A docs page lists names
 * anyway, so keying on names buys the guard immunity from that drift for free. Overloads collapse
 * to one row, which is what the page wants — `launchFrames` is one idea with two spellings.
 */
class PhasedPageCoverageTest {

    private companion object {
        val pagePath: Path = Path.of("public/docs/animation_time_model.md")

        /**
         * Owners whose declared members the page must list. `EndedException` is deliberately
         * absent: it is a bare `Throwable` subclass with no members of its own, and it is listed
         * on the page as a type rather than as a table of members.
         */
        val OWNERS: List<KClass<*>> = listOf(
            Frames::class,
            Framer::class,
            Generator::class,
            GeneratorScope::class,
        )

        /** Top-level declarations of `Phased.kt`, which Kotlin compiles into this facade class. */
        const val FACADE = "afield.PhasedKt"

        /**
         * Types declared by `Phased.kt` that the page must name, over and above the members.
         * Reflection cannot enumerate "classes declared in one source file", so this is the one
         * hand-kept list here — kept honest by [everyDeclaredTypeStillExists].
         */
        val TYPES = listOf("Frames", "EndedException", "Framer", "GeneratorScope", "Generator")

        /**
         * Members inherited or generated rather than written in `Phased.kt`. `Companion` is the
         * synthetic property for `Generator`'s companion object; `isMain` inside it is the flag the
         * [Framer] sets while draining, and *is* listed.
         */
        val NOT_THIS_FILES_CODE = setOf("Companion")

        /** Inherited from `Any`; not declarations of this file. `Frames.toString` is, and is kept. */
        val ANY_MEMBERS = setOf("equals", "hashCode", "toString")

        /**
         * `generator` and `generator2` carry `@JvmName("generator_1")` / `@JvmName("generator_2")`
         * (they would otherwise clash after erasure), and that is the name reflection reports here.
         * The page lists the names a person types. If a `@JvmName` changes, this map goes stale and
         * the guard fails — which is the correct outcome.
         */
        val JVM_NAME_ALIASES = mapOf("generator_1" to "generator", "generator_2" to "generator2")
    }

    /**
     * `Frames`, `Generator`, ... for a plain receiver, and no owner at all for the function-typed
     * receiver of `(suspend () -> Unit).then` — whose classifier name (`Function0` vs
     * `SuspendFunction0`) is precisely the spelling that drifts between compiler snapshots. `then`
     * is unique among the top-level names, so dropping its receiver costs no disambiguation.
     */
    private fun ownerOf(receiverSimpleName: String?): String = when {
        receiverSimpleName == null -> ""
        receiverSimpleName.contains("Function") -> ""
        else -> receiverSimpleName
    }

    /**
     * Member *functions* carry `()`; properties and top-level functions do not.
     *
     * This is what separates the two `Frames.end`s — the `suspend fun end()` member that stops a
     * running animation, and the top-level `fun Frames.end(block)` builder that chains a cleanup
     * block onto one. They are different declarations doing different jobs, so they get different
     * rows; keyed on the bare name they would silently collapse into one and the guard would stop
     * noticing if either disappeared.
     */
    private fun signature(owner: String, name: String, isMemberFunction: Boolean = false): String {
        val suffix = if (isMemberFunction) "()" else ""
        return if (owner.isEmpty()) "$name$suffix" else "$owner.$name$suffix"
    }

    /** Every public declaration `Phased.kt` contributes, as `Owner.name` / bare `name` strings. */
    private fun actualSurface(): Set<String> {
        val out = sortedSetOf<String>()

        for (k in OWNERS) {
            val owner = k.simpleName!!
            k.declaredMemberFunctions
                .filter { it.visibility == KVisibility.PUBLIC }
                .forEach { out += signature(owner, it.name, isMemberFunction = true) }
            k.declaredMemberProperties
                .filter { it.visibility == KVisibility.PUBLIC && it.name !in NOT_THIS_FILES_CODE }
                .forEach { out += signature(owner, it.name) }
        }
        // Generator.Companion.isMain — the flag Framer.frame() sets while it drains the queue.
        Generator.Companion::class.declaredMemberProperties
            .filter { it.visibility == KVisibility.PUBLIC }
            .forEach { out += signature("Generator.Companion", it.name) }

        // Top-level declarations. Going through the *Java* methods and back with `kotlinFunction`
        // is what recovers the extension receiver: reading `Class.forName(FACADE).kotlin.functions`
        // instead presents every top-level function as a static member with a null extension
        // receiver, which silently collapses `Frames.plus` and `Generator.plus` onto one row.
        Class.forName(FACADE).declaredMethods
            .mapNotNull { it.kotlinFunction }
            .filter { it.visibility == KVisibility.PUBLIC && it.name !in ANY_MEMBERS }
            .forEach { f ->
                val receiver = (f.extensionReceiverParameter?.type?.classifier as? KClass<*>)?.simpleName
                out += signature(ownerOf(receiver), JVM_NAME_ALIASES[f.name] ?: f.name)
            }

        out += TYPES.map { "type $it" }
        return out
    }

    /** The `| `name` | … |` rows of the page's ledger table, marked in the source by a comment. */
    private fun listedOnPage(): List<String> {
        val text = pagePath.toFile().readText()
        val marker = "<!-- phased-surface -->"
        val start = text.indexOf(marker)
        assertTrue(start >= 0, "$pagePath has lost its `$marker` ledger marker")
        val end = text.indexOf("<!-- /phased-surface -->", start)
        assertTrue(end > start, "$pagePath has lost its `<!-- /phased-surface -->` ledger marker")
        val table = text.substring(start, end)
        return Regex("""(?m)^\|\s*`([^`|]+)`\s*\|""").findAll(table).map { it.groupValues[1].trim() }.toList()
    }

    @Test
    fun everyPublicDeclarationInPhasedIsOnThePage() {
        val actual = actualSurface()
        val listed = listedOnPage()

        val duplicates = listed.groupBy { it }.filterValues { it.size > 1 }.keys.sorted()
        assertTrue(duplicates.isEmpty(), "$pagePath lists these more than once: ${duplicates.joinToString(", ")}")

        val missing = (actual - listed.toSet()).sorted()
        assertTrue(
            missing.isEmpty(),
            "Phased.kt has public declaration(s) with no row on $pagePath — add them:\n" +
                missing.joinToString("\n") { "| `$it` | … |" }
        )

        val stale = (listed.toSet() - actual).sorted()
        assertTrue(
            stale.isEmpty(),
            "$pagePath lists name(s) Phased.kt no longer has — remove them:\n" +
                stale.joinToString("\n") { "  $it" }
        )

        assertEquals(actual, listed.toSet())
    }

    @Test
    fun everyDeclaredTypeStillExists() {
        // TYPES is the one hand-kept list in this guard, so check it resolves rather than trusting it.
        TYPES.forEach { name ->
            val loaded = runCatching { Class.forName("afield.$name") }.getOrNull()
            assertTrue(loaded != null, "Phased.kt no longer declares `afield.$name` — fix TYPES and the page")
        }
    }

    @Test
    fun thePageIsInTheSiteNavigation() {
        // A page absent from the nav is published but unreachable, which is the failure mode this
        // whole page exists to fix for Phased.kt in the first place.
        val nav = Path.of("public/zensical.yml").toFile().readText()
        assertTrue(
            "animation_time_model.md" in nav,
            "public/zensical.yml does not list animation_time_model.md, so the page is unreachable"
        )
    }
}
