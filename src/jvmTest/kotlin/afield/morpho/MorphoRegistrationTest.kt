package afield.morpho

import afield.Box
import afield.KotlinExecution
import afield.SessionInformation
import afield.remotejs.code_language
import afield.runtime.installMainLanguageActions
import afield.runtime.isMainLanguage
import afield.runtime.mainLanguage
import afield.stdlib.actions
import afield.thisSessionInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * That MorphoHDL is actually *registered* — not merely implemented.
 *
 * The three things in [afield.runtime.MainLanguageRegistry] that a new language has to get right
 * are easy to write plausibly and never notice are broken, because a language that fails to
 * register simply never appears and Field carries on. So: the action shows up on a root, marking a
 * box turns its children into morpho boxes, and unmarking it turns them back.
 *
 * This drives the real registry and the real [afield.runtime.applyRuntimeToggle], the same
 * functions Field's `TopologyWatcher` calls on the runloop.
 */
class MorphoRegistrationTest {

    private val made = mutableListOf<Box>()

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
        made += root
        return session
    }

    private fun boxIn(session: SessionInformation, id: String): Box = Box(id).also {
        it.inside = session
        session.boxes[id] = it
        made += it
    }

    @AfterTest
    fun clearSessionBinding() {
        made.clear()
        thisSessionInfo.remove()
    }

    @Test
    fun `morpho offers a Set Main Language action`() {
        installMorpho()

        val session = session("morpho-actions")
        val root = session.root
        installMainLanguageActions(root)

        val action = root.actions["set-main-language-morpho"]
        assertTrue(action != null, "no 'set-main-language-morpho' action; the registry never saw morpho")
        assertEquals("Set Main Language: MorphoHDL", action.desc)
    }

    /**
     * The `mainLanguage` property is the modern path — it is what
     * [afield.runtime.installMainLanguageActions]'s action writes.
     */
    @Test
    fun `marking a root turns its children into morpho boxes`() {
        installMorpho()

        val session = session("morpho-toggle")
        val root = session.root
        val child = boxIn(session, "child")
        root.connect(child)

        assertFalse(child.ke is MorphoExecution, "child started out as morpho")

        root.mainLanguage = "morpho"
        assertTrue(root.isMainLanguage("morpho"))
        morphoTopologyNeedsUpdating(listOf(root))

        assertTrue(child.ke is MorphoExecution, "child did not become a morpho box")
        assertEquals("morpho", (child.ke as MorphoExecution).languageName)
        assertEquals("MorphoHDL", child.code_language)
    }

    /**
     * The reverse direction, and the legacy per-box flag, which is what a persisted document
     * actually carries.
     *
     * For the modern path — reverting by clearing `mainLanguage`, which is what the shipped
     * "Set Main Language: Kotlin" action does — see
     * [the Set Main Language Kotlin action reverts a morpho root].
     */
    @Test
    fun `the isMorphoRoot flag confers the language and withdrawing it reverts`() {
        installMorpho()

        val session = session("morpho-flag")
        val root = session.root
        val child = boxIn(session, "flagged-child")
        root.connect(child)

        root.isMorphoRoot = true
        morphoTopologyNeedsUpdating(listOf(root))
        assertTrue(child.ke is MorphoExecution, "child did not become a morpho box")
        assertEquals("MorphoHDL", child.code_language)

        root.isMorphoRoot = false
        morphoTopologyNeedsUpdating(listOf(root))
        assertTrue(child.ke is KotlinExecution, "child did not revert to Kotlin")
        assertEquals("Kotlin", child.code_language)
    }

    /**
     * The shipped **"Set Main Language: Kotlin"** action, driven through `Action.target` exactly as
     * the command palette drives it.
     *
     * This is the pair to the action that *sets* a language, and it used to throw for every
     * registered language rather than revert anything: reverting means
     * `setMainLanguageFor(roots, null)`, which assigns null to `Box.mainLanguage`, and
     * `Delegation.setValue` ended in `setPropertyRaw(name, _v as Any)` —
     * `NullPointerException: null cannot be cast to non-null type kotlin.Any`. The delegate now
     * erases a property assigned null instead of casting, so the assertion that the property is
     * *absent* afterwards, not present-and-null, is load-bearing: absent is what an unset nullable
     * property means everywhere else, and it is what keeps `mainLanguage` out of the saved document.
     */
    @Test
    fun `the Set Main Language Kotlin action reverts a morpho root`() {
        installMorpho()

        val session = session("morpho-revert-action")
        val root = session.root
        val child = boxIn(session, "action-child")
        root.connect(child)
        installMainLanguageActions(root)

        val toMorpho = root.actions["set-main-language-morpho"]
        assertTrue(toMorpho != null, "no 'set-main-language-morpho' action")
        toMorpho.target(listOf(root))
        assertTrue(child.ke is MorphoExecution, "child did not become a morpho box")

        val toKotlin = root.actions["set-main-language-kotlin"]
        assertTrue(toKotlin != null, "no 'set-main-language-kotlin' action")
        toKotlin.target(listOf(root))

        assertTrue(child.ke is KotlinExecution, "child did not revert to Kotlin")
        assertEquals("Kotlin", child.code_language)
        assertFalse(root.isMainLanguage("morpho"), "root still claims morpho")
        assertEquals(null, root.mainLanguage)
        assertFalse(
            root.properties.containsKey("mainLanguage"),
            "mainLanguage was left in the property bag rather than erased",
        )
    }
}
