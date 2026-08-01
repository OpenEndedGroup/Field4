package afield.runtime

import afield.Box
import afield.KotlinExecution
import afield.SessionInformation
import afield.forth.ForthExecution
import afield.forth.installForth
import afield.forth.isForthRoot
import afield.remotejs.code_language
import afield.stdlib.Delegation
import afield.stdlib.actions
import afield.thisSessionInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "Set Main Language: **Kotlin**" half of [installMainLanguageActions] — the one that takes a
 * language back off.
 *
 * Reverting is `setMainLanguageFor(roots, null)`, which assigns null to [Box.mainLanguage], and for
 * a long time that threw for *every* registered language rather than reverting anything:
 * `mainLanguage` is a `Delegation<String?>`, but `Delegation.setValue` ended in
 * `setPropertyRaw(name, _v as Any)` — `NullPointerException: null cannot be cast to non-null type
 * kotlin.Any`. Nothing caught it because each language's own tests reverted through its legacy
 * `is<Language>Root` flag, which is a non-null `Boolean` and so never took that path.
 *
 * Forth stands in for "any registered language" here: the action, the registry and
 * [applyRuntimeToggle] are shared, so the language a test picks only decides which
 * [MainLanguageRuntime] the registry hands back.
 */
class MainLanguageRegistryTest {

    private fun session(label: String): SessionInformation {
        val root = Box("$label-root")
        val session = SessionInformation(
            filename = "<$label>", documentName = label, root = root, ephemeral = true
        )
        root.inside = session
        session.loading = false
        // Box.connect bumps the session's topology generation off the thread-local, so a test that
        // wires boxes together has to bind it the way the server does.
        thisSessionInfo.set(session)
        return session
    }

    @AfterTest
    fun clearSessionBinding() {
        thisSessionInfo.remove()
    }

    @Test
    fun `the Set Main Language Kotlin action reverts a language root`() {
        installForth()

        val session = session("revert-action")
        val root = session.root
        val child = Box("action-child").also {
            it.inside = session
            session.boxes[it.id] = it
        }
        root.connect(child)
        installMainLanguageActions(root)

        val toForth = root.actions["set-main-language-forth"]
        assertTrue(toForth != null, "no 'set-main-language-forth' action; the registry never saw forth")
        toForth.target(listOf(root))
        assertTrue(child.ke is ForthExecution, "child did not become a forth box")

        val toKotlin = root.actions["set-main-language-kotlin"]
        assertTrue(toKotlin != null, "no 'set-main-language-kotlin' action")
        toKotlin.target(listOf(root))

        assertTrue(child.ke is KotlinExecution, "child did not revert to Kotlin")
        assertEquals("Kotlin", child.code_language)
        assertFalse(root.isMainLanguage("forth"), "root still claims forth")
        assertEquals(false, root.isForthRoot, "the legacy flag was left set")
        assertNull(root.mainLanguage)
    }

    /**
     * The shape of the fix, rather than one symptom of it: a nullable [Delegation] handed null
     * *erases* its property.
     *
     * Erased and never-set have to read back the same, because that is what every nullable delegate
     * in the tree already assumes — none of them carries a non-null `defaultValue` that an erase
     * could resurrect. Leaving the key behind with a null under it would also persist a null into
     * saved documents for the delegates in `Box.ioPersistentProperties`, `mainLanguage` among them.
     */
    @Test
    fun `assigning null to a nullable delegated property erases it from the bag`() {
        val session = session("erase")
        val box = session.root

        box.mainLanguage = "forth"
        assertTrue(box.properties.containsKey("mainLanguage"))

        box.mainLanguage = null

        assertNull(box.mainLanguage)
        assertFalse(
            box.properties.containsKey("mainLanguage"),
            "mainLanguage was left in the property bag rather than erased",
        )
    }
}
