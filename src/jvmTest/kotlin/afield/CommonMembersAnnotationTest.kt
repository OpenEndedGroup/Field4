package afield

import afield.graphics.FLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `@CommonMembers` is a list of member names as *strings*, so a typo, a rename, or a deleted member turns
 * the annotation into a silent no-op: the popup keeps ranking, just without the prior, and nothing fails.
 * That is the whole risk of naming members in a string, and it is what this test removes.
 *
 * The annotated types are enumerated explicitly rather than found by a classpath scan. A scan here would
 * be both slow on this classpath and self-defeating — `PropMacroRegressionTest` has already OOM'd a test
 * worker inside ClassGraph — and, more to the point, a scan that silently finds nothing still passes. A
 * literal list cannot.
 */
class CommonMembersAnnotationTest {

    /** Every class carrying `@CommonMembers`. Add new ones here; [theRegistryIsNotEmpty] guards the list. */
    private val annotated: List<Class<*>> = listOf(FLine::class.java)

    @Test
    fun `every name in a CommonMembers list is really a member of that class`() {
        for (type in annotated) {
            val declared = CommonMemberPrior.declaredNames(type)
            assertTrue(
                declared.isNotEmpty(),
                "${type.simpleName} is listed here as annotated but carries no @CommonMembers names"
            )
            val members = memberNamesOf(type)
            for (name in declared) {
                assertTrue(
                    name in members,
                    "@CommonMembers on ${type.simpleName} names `$name`, which is not a member of it — " +
                        "the completion prior for that name is silently doing nothing. " +
                        "Members starting with the same letter: " +
                        members.filter { it.firstOrNull() == name.firstOrNull() }.sorted()
                )
            }
        }
    }

    @Test
    fun `a common member outranks a member the type says nothing about`() {
        // The point of the prior, expressed as the comparison it exists to make.
        val moveTo = CommonMemberPrior.rankOf("afield.graphics.FLine", "moveTo")
        val breakNext = CommonMemberPrior.rankOf("afield.graphics.FLine", "breakNext")
        assertEquals(1, moveTo, "FLine lists moveTo first, so it should rank 1")
        assertEquals(0, breakNext, "FLine does not call out breakNext, so it should have no prior")
        assertTrue(
            CommonMemberPrior.scoreOf(moveTo) > CommonMemberPrior.scoreOf(breakNext),
            "the prior has to actually separate them"
        )
    }

    @Test
    fun `an overload and a qualified spelling resolve to the same rank`() {
        // Completion hands over display text, not a clean identifier, and one annotation entry is meant to
        // cover all four `moveTo` overloads.
        for (spelling in listOf("moveTo", "moveTo(v: Vec3)", "FLine.moveTo", "moveTo(x: Number, y: Number)")) {
            assertEquals(
                1,
                CommonMemberPrior.rankOf("afield.graphics.FLine", spelling),
                "`$spelling` should resolve to FLine's first common member"
            )
        }
    }

    @Test
    fun `an unannotated or unknown receiver has no prior`() {
        assertEquals(0, CommonMemberPrior.rankOf("java.lang.String", "length"))
        assertEquals(0, CommonMemberPrior.rankOf("not.a.Class", "moveTo"))
        assertEquals(0, CommonMemberPrior.rankOf(null, "moveTo"))
        assertEquals(0, CommonMemberPrior.rankOf("afield.graphics.FLine", null))
        assertEquals(0, CommonMemberPrior.rankOf("afield.graphics.FLine", "noSuchMember"))
    }

    @Test
    fun `the score decays with position and stays inside its documented band`() {
        val first = CommonMemberPrior.scoreOf(1)
        assertEquals(CommonMemberPrior.BASE, first)
        assertTrue(
            CommonMemberPrior.scoreOf(2) < first,
            "a later entry must score less, or the declared order means nothing"
        )
        // The band is the whole design: above CATEGORY_WEIGHT's ceiling so a called-out member clears an
        // uncategorised page, below recency's so demonstrated use still wins. Those two numbers live in
        // `fieldSuggest.js`; see FieldSuggestPresentationTest for the assertion that they agree.
        assertTrue(first > 28, "the prior must outrank the strongest category weight (28), was $first")
        assertTrue(first < 62, "the prior must not outrank recency (~62), was $first")
        // Never negative, however long the list.
        assertTrue(CommonMemberPrior.scoreOf(500) >= 1, "a long list must not produce a penalty")
    }

    @Test
    fun theRegistryIsNotEmpty() {
        assertTrue(
            annotated.isNotEmpty(),
            "no types carry @CommonMembers, so the prior is dead code — either annotate one or delete it"
        )
    }

    private fun memberNamesOf(type: Class<*>): Set<String> =
        (type.methods.map { it.name } + type.declaredMethods.map { it.name } + type.fields.map { it.name })
            .flatMap { name ->
                // Kotlin properties arrive as `getHere`/`setHere`; the annotation names the property.
                val stripped = when {
                    name.startsWith("get") && name.length > 3 -> name.removePrefix("get")
                    name.startsWith("set") && name.length > 3 -> name.removePrefix("set")
                    else -> null
                }
                listOfNotNull(name, stripped?.replaceFirstChar { it.lowercase() })
            }
            .toSet()
}
