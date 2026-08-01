package afield.stdlib

import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A coverage ledger for `keyframer.kt`, in the shape [FnOpCoverageTest] uses for the op vocabulary:
 * every public behavioural declaration is enumerated **by reflection** and must be classified in
 * [SURFACE] as either exercised by [KeyframerTest] or unreachable with a stated reason. Adding a
 * public declaration to `keyframer.kt` fails this test until it is classified, so the ledger cannot
 * quietly go stale — which is the only thing that makes the coverage number on
 * `public/docs/keyframer_examples.md` worth printing.
 *
 * The number is deliberately a count of *declarations reached*, not of lines or branches. It says
 * "no public entry point is silently untested"; it does not say every path through `interpolate` is
 * explored. Read it as a floor.
 *
 * **What is excluded, and why.** For a `data class` Kotlin generates `componentN`, `copy`, `equals`,
 * `hashCode` and `toString`; those are the compiler's code, not this file's, and the codebase
 * reference does not count them either (`public/docs/reference/index.md`: "the members Kotlin
 * generates for a `data class` are not counted at all"). They are dropped here for the same reason.
 * A `toString` that is genuinely hand-written is kept, via [HANDWRITTEN_TO_STRING].
 */
class KeyframerCoverageTest {

    private enum class Cov {
        /** Exercised by at least one assertion in [KeyframerTest]. */
        COVERED,

        /** Cannot run in a unit test — needs a live `Renderer` or the AWT clipboard. Reason required. */
        UNREACHABLE_HEADLESS,
    }

    private data class Entry(val cov: Cov, val reason: String = "")

    private companion object {
        /** `data class`es whose `toString` is a real override rather than the generated one. */
        val HANDWRITTEN_TO_STRING = setOf("KeyDiff", "StackSLString")

        val GENERATED = setOf("copy", "equals", "hashCode", "toString")

        private fun covered() = Entry(Cov.COVERED)
        private fun headless(reason: String) = Entry(Cov.UNREACHABLE_HEADLESS, reason)

        /**
         * The ledger. Keys are `SimpleName::member(paramTypes)` exactly as [signatureOf] renders
         * them, so a signature change shows up as an unclassified entry rather than passing silently.
         */
        val SURFACE: Map<String, Entry> = mapOf(
            // ---- KeyframeScope: registration -------------------------------------------------
            "KeyframeScope::target(String, Structure)" to covered(),
            "KeyframeScope::target(String, () -> Structure?)" to covered(),
            "KeyframeScope::target(ModulePointer, String)" to
                headless("resolves module.current.liveUniformStructures; needs a live Renderer"),
            "KeyframeScope::target(ComputeModulePointer, String)" to
                headless("resolves module.current.liveUniformStructures; needs a live Renderer"),
            "KeyframeScope::target(MeshModulePointer, String)" to
                headless("resolves module.current.liveUniformStructures; needs a live Renderer"),
            "KeyframeScope::stackAuto(ModulePointer, String, String)" to
                headless("writes shader source through stackAutoShaderSource; needs a live Renderer"),
            "KeyframeScope::stackAuto(ComputeModulePointer, String, String)" to
                headless("writes shader source through stackAutoShaderSource; needs a live Renderer"),
            "KeyframeScope::stackAuto(MeshModulePointer, String, String)" to
                headless("writes shader source through stackAutoShaderSource; needs a live Renderer"),
            "KeyframeScope::camera(Camera, String)" to
                headless("Camera(val renderer: Renderer = fromContext()) (Camera.kt:16) needs a Renderer"),
            "KeyframeScope::findByName(String)" to covered(),
            "KeyframeScope::targets" to covered(),
            "KeyframeScope::schemaVersion" to covered(),
            "KeyframeScope::remap" to covered(),

            // ---- KeyframeScope: snapshot + blend ---------------------------------------------
            "KeyframeScope::capture()" to covered(),
            "KeyframeScope::interpolate(Key, Key, Double)" to covered(),

            // ---- KeyframeScope: persistence --------------------------------------------------
            "KeyframeScope::saveKey(Path, Key, String?)" to covered(),
            "KeyframeScope::loadKey(Path, Int?)" to covered(),
            "KeyframeScope::loadKeyVersion(Path, Int)" to covered(),
            "KeyframeScope::loadLatestKey(Path)" to covered(),
            "KeyframeScope::keyVersions(Path)" to covered(),
            "KeyframeScope::missingKey(String, Any?)" to covered(),
            "KeyframeScope::missingKey(String, () -> Any?)" to covered(),
            "KeyframeScope::missingKey(Int, String, Any?)" to covered(),
            "KeyframeScope::missingKey(Int, String, () -> Any?)" to covered(),

            // ---- Key -------------------------------------------------------------------------
            "Key::invoke()" to covered(),
            "Key::get(String)" to covered(),
            "Key::set(String, Any?)" to covered(),
            "Key::contains(String)" to covered(),
            "Key::put(String, Any?)" to covered(),
            "Key::putAll(Array<out Pair<String, Any?>>)" to covered(),
            "Key::remove(String)" to covered(),
            "Key::copy()" to covered(),
            "Key::diff(Key, String, String)" to covered(),
            "Key::diffHtml(Key, String, String)" to covered(),
            "Key::diffText(Key, String, String)" to covered(),
            "Key::update(Key.() -> Unit)" to covered(),
            "Key::applyKey()" to covered(),
            "Key::updateAndApply(Key.() -> Unit)" to covered(),
            "Key::save(Path, String?)" to covered(),
            "Key::updateCode(String, Boolean)" to covered(),
            "Key::copyUpdateCodeToClipboard(String, Boolean)" to
                headless("Toolkit.getDefaultToolkit().systemClipboard throws on a headless JVM"),
            "Key::toHTML()" to covered(),
            "Key::values" to covered(),

            // ---- KValue and its variants -----------------------------------------------------
            "KValue::toAny()" to covered(),
            "Num::kind" to covered(),
            "Num::value" to covered(),
            "Vec2::x" to covered(), "Vec2::y" to covered(),
            "Vec3::x" to covered(), "Vec3::y" to covered(), "Vec3::z" to covered(),
            "Vec4::x" to covered(), "Vec4::y" to covered(),
            "Vec4::z" to covered(), "Vec4::w" to covered(),
            "Quat::x" to covered(), "Quat::y" to covered(),
            "Quat::z" to covered(), "Quat::w" to covered(),
            "DualQuat::realX" to covered(), "DualQuat::realY" to covered(),
            "DualQuat::realZ" to covered(), "DualQuat::realW" to covered(),
            "DualQuat::dualX" to covered(), "DualQuat::dualY" to covered(),
            "DualQuat::dualZ" to covered(), "DualQuat::dualW" to covered(),
            "CameraPoseValue::realX" to covered(), "CameraPoseValue::realY" to covered(),
            "CameraPoseValue::realZ" to covered(), "CameraPoseValue::realW" to covered(),
            "CameraPoseValue::dualX" to covered(), "CameraPoseValue::dualY" to covered(),
            "CameraPoseValue::dualZ" to covered(), "CameraPoseValue::dualW" to covered(),
            "CameraPoseValue::focusDistance" to covered(),
            "StackSL::value" to covered(),
            "Str::value" to covered(),
            "SourceBlock::boxId" to covered(),
            "SourceBlock::source" to covered(),

            // ---- DTOs, diffs, wrappers -------------------------------------------------------
            "KeyDTO::schemaVersion" to covered(),
            "KeyDTO::values" to covered(),
            "KeyVersionInfo::index" to covered(),
            "KeyVersionInfo::timestamp" to covered(),
            "KeyVersionInfo::note" to covered(),
            "KeyVersionInfo::file" to covered(),
            "StackSLString::value" to covered(),
            "StackSLString::toString()" to covered(),
            "KeyDiff::leftName" to covered(),
            "KeyDiff::rightName" to covered(),
            "KeyDiff::entries" to covered(),
            "KeyDiff::toString()" to covered(),
            "KeyDiff::toHTML()" to covered(),
            "KeyDiffEntry::name" to covered(),
            "KeyDiffEntry::kind" to covered(),
            "KeyDiffEntry::left" to covered(),
            "KeyDiffEntry::right" to covered(),

            // ---- reachable only outside the class list above ---------------------------------
            "KValue.Companion::fromAny(Any?)" to covered(),
            "<top-level>::stackSLString(String)" to covered(),
            "<top-level>::Key.h" to covered(),
            "NumKind::<enum entries>" to covered(),
        )

        /** Members that reflection over the class list cannot reach, classified above by hand. */
        val MANUAL = setOf(
            "KValue.Companion::fromAny(Any?)",
            "<top-level>::stackSLString(String)",
            "<top-level>::Key.h",
            "NumKind::<enum entries>",
        )

        val CLASSES: List<KClass<*>> = listOf(
            KeyframeScope::class, KeyframeScope.Key::class,
            KValue::class, KValue.Num::class, KValue.Vec2::class, KValue.Vec3::class,
            KValue.Vec4::class, KValue.Quat::class, KValue.DualQuat::class,
            KValue.CameraPoseValue::class, KValue.StackSL::class, KValue.Str::class,
            KValue.SourceBlock::class,
            KeyDTO::class, KeyVersionInfo::class, StackSLString::class,
            KeyDiff::class, KeyDiffEntry::class,
        )
    }

    /** `Pair<kotlin.String, kotlin.Any?>` → `Pair<String, Any?>`, and `Function0<X>` → `() -> X`. */
    private fun readableType(raw: String): String {
        var t = raw
        Regex("""\bkotlin\.collections\.|\bkotlin\.reflect\.|\bkotlin\.|\bjava\.nio\.file\.|\bjava\.lang\.|\bafield\.stdlib\.linalg\.|\bafield\.stdlib\.|\bafield\.four\.buffers\.|\bafield\.four\.util\.|\bafield\.four\.modules\.""")
            .let { t = it.replace(t, "") }
        t = t.replace("KeyframeScope.Key", "Key")
        // Function0<X> / Function1<Key, Unit> read far better as lambda types in a ledger.
        Regex("""^Function0<(.*)>$""").find(t)?.let { return "() -> ${it.groupValues[1]}" }
        Regex("""^Function1<Key, Unit>$""").find(t)?.let { return "Key.() -> Unit" }
        return t
    }

    private fun signatureOf(owner: String, name: String, params: List<String>?): String =
        if (params == null) "$owner::$name" else "$owner::$name(${params.joinToString(", ")})"

    private fun actualSurface(): Set<String> {
        val out = sortedSetOf<String>()
        for (k in CLASSES) {
            val owner = k.simpleName!!
            val isData = k.isData
            val keepToString = owner in HANDWRITTEN_TO_STRING
            k.declaredMemberFunctions
                .filter { it.visibility == KVisibility.PUBLIC }
                .forEach { f ->
                    if (isData && f.name in GENERATED && !(f.name == "toString" && keepToString)) return@forEach
                    if (isData && f.name.matches(Regex("""component\d+"""))) return@forEach
                    val params = f.parameters.drop(1).map { readableType(it.type.toString()) }
                    out += signatureOf(owner, f.name, params)
                }
            k.declaredMemberProperties
                .filter { it.visibility == KVisibility.PUBLIC }
                .forEach { out += signatureOf(owner, it.name, null) }
        }
        out += MANUAL
        return out
    }

    @Test
    fun everyPublicDeclarationIsClassified() {
        val actual = actualSurface()
        val declared = SURFACE.keys

        val missing = (actual - declared).sorted()
        val stale = (declared - actual).sorted()

        assertTrue(
            missing.isEmpty(),
            "keyframer.kt has public declaration(s) not classified in SURFACE — add them with a " +
                "reason:\n" + missing.joinToString("\n") { "  \"$it\" to covered()," }
        )
        assertTrue(
            stale.isEmpty(),
            "SURFACE classifies declaration(s) that no longer exist — remove them:\n" +
                stale.joinToString("\n") { "  $it" }
        )
        assertEquals(actual, declared)
    }

    @Test
    fun coverageIsWhatThePageClaims() {
        val total = SURFACE.size
        val covered = SURFACE.count { it.value.cov == Cov.COVERED }
        val unreachable = SURFACE.count { it.value.cov == Cov.UNREACHABLE_HEADLESS }
        val pct = covered * 100.0 / total

        println("KeyframerCoverage: $covered/$total declarations covered (${"%.1f".format(pct)}%), " +
            "$unreachable unreachable headless")

        // The exact numbers published on public/docs/keyframer_examples.md. If this test has to
        // change, that page has to change in the same commit.
        assertEquals(101, total, "total public behavioural declarations")
        assertEquals(93, covered, "declarations covered by KeyframerTest")
        assertEquals(8, unreachable, "declarations unreachable in a headless unit test")

        // Every uncovered declaration must carry a reason — no silent gaps.
        SURFACE.filter { it.value.cov == Cov.UNREACHABLE_HEADLESS }.forEach { (name, e) ->
            assertTrue(e.reason.isNotBlank(), "$name is uncovered with no stated reason")
        }
    }

    @Test
    fun everyUnreachableDeclarationNeedsARendererOrAwt() {
        // Guards the category itself: "unreachable" must mean a live Renderer or the AWT clipboard,
        // not "we did not get round to it". Anything else belongs in KeyframerTest.
        SURFACE.filter { it.value.cov == Cov.UNREACHABLE_HEADLESS }.forEach { (name, e) ->
            assertTrue(
                e.reason.contains("Renderer") || e.reason.contains("clipboard", ignoreCase = true),
                "$name claims to be unreachable for a reason that is not a Renderer or AWT: ${e.reason}"
            )
        }
    }
}
