package afield.stacksl.study

import afield.Box
import afield.KotlinExecution
import afield.SessionInformation
import afield.runtime.installMainLanguageActions
import afield.runtime.mainLanguage
import afield.stdlib.actions
import afield.thisSessionInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * StackSL is a main language: starting from scratch is "Set Main Language: StackSL" on a box, and its
 * children become StackSL programs that edit `stackSL`. This drives the real registry and the real
 * `applyRuntimeToggle`, the functions Field's `TopologyWatcher` calls; no window or GPU is involved.
 */
class StackSLLanguageTest {
    private fun session(label: String): SessionInformation {
        val root = Box("$label-root")
        val session = SessionInformation(filename = "<$label>", documentName = label, root = root, ephemeral = true)
        root.inside = session
        session.loading = false
        // Box.connect bumps the session's topology generation off the thread-local.
        thisSessionInfo.set(session)
        return session
    }

    private fun boxIn(session: SessionInformation, id: String): Box = Box(id).also {
        it.inside = session
        session.boxes[id] = it
    }

    @AfterTest
    fun clearSessionBinding() = thisSessionInfo.remove()

    @Test
    fun stackSLIsOfferedAsAMainLanguage() {
        installStackSLLanguage()
        val root = session("stacksl-actions").root
        installMainLanguageActions(root)
        val action = assertNotNull(root.actions["set-main-language-stacksl"])
        assertEquals("Set Main Language: StackSL", action.desc)
    }

    @Test
    fun aChildOfAStackSLRootIsAProgramThatEditsStackSL() {
        installStackSLLanguage()
        val session = session("stacksl-toggle")
        val window = boxIn(session, "window")
        val program = boxIn(session, "program")
        window.connect(program)

        window.mainLanguage = StackSLPrograms.LANGUAGE
        stackSLTopologyNeedsUpdating(listOf(window))
        assertTrue(program.ke is StackSLExecution, "the child did not become a StackSL program")
        assertEquals("stackSL", program.getPropertyRaw("default_property", null))
        assertEquals("", program.getPropertyRaw("stackSL", null), "a new program starts with an empty stackSL")

        window.mainLanguage = null
        stackSLTopologyNeedsUpdating(listOf(window))
        assertTrue(program.ke is KotlinExecution, "the child should go back to Kotlin")
    }
}
