package afield.rectsl.study

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
 * RectSL is a main language: starting from scratch is "Set Main Language: RectSL" on a box, and its
 * children become RectSL scenes that edit `rectSL`. This drives the real registry and the real
 * `applyRuntimeToggle`, the functions Field's `TopologyWatcher` calls; no window or GPU is involved.
 */
class RectSLLanguageTest {
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
    fun rectSLIsOfferedAsAMainLanguage() {
        installRectSLLanguage()
        val root = session("rectsl-actions").root
        installMainLanguageActions(root)
        val action = assertNotNull(root.actions["set-main-language-rectsl"])
        assertEquals("Set Main Language: RectSL", action.desc)
    }

    @Test
    fun aChildOfARectSLRootIsASceneThatEditsRectSL() {
        installRectSLLanguage()
        val session = session("rectsl-toggle")
        val window = boxIn(session, "window")
        val program = boxIn(session, "program")
        window.connect(program)

        window.mainLanguage = RectSLPrograms.LANGUAGE
        rectSLTopologyNeedsUpdating(listOf(window))
        assertTrue(program.ke is RectSLExecution, "the child did not become a RectSL scene")
        assertEquals("rectSL", program.getPropertyRaw("default_property", null))
        assertEquals(RECTSL_STARTER, program.getPropertyRaw("rectSL", null), "a new scene starts with the starter")

        window.mainLanguage = null
        rectSLTopologyNeedsUpdating(listOf(window))
        assertTrue(program.ke is KotlinExecution, "the child should go back to Kotlin")
    }

    @Test
    fun theStarterScenesCompile() {
        val blank = java.io.File("dev_docs/agents/demos/rectsl_conversation/rectsl_source_rectSL_.rectsl").readText()
        listOf(RECTSL_STARTER, blank).forEach { source ->
            val compiled = afield.rectsl.compileRectSLWithSource(source)
            assertTrue(compiled.ok, compiled.problems.joinToString { it.summary })
        }
    }
}
