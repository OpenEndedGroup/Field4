package afield.lang.clojure

import afield.app.server.SessionInformation
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import afield.document.graph.needsResync
import afield.ui.output.controllerControlsForBox
import afield.ui.output.getCheckboxCallback
import afield.ui.output.getSliderCallback
import afield.ui.output.updateControllerControlValue
import clojure.java.api.Clojure
import clojure.lang.IFn
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The parts of `field.core` that a Clojure box got on 2026-10-18 to match Python boxes: `slider` /
 * `checkbox` with callbacks, and `context` (inherited properties and `fromContext` by type).
 *
 * Each test evaluates the real `field.core` forms through [ClojureHost] with [ClojureInterface.thisBox]
 * set, as a run does, then drives [ClojureInterface.flushControls] and the real slider/checkbox
 * callback registry: the same path a move on the page takes, minus the websocket.
 */
class ClojureFieldCoreTest {

    private val made = mutableListOf<Box>()
    private var counter = 0

    private fun box(name: String) = Box("clj-core-$name-${counter++}").also { made += it }

    @BeforeTest
    fun direct() {
        ClojureInterface.controlDispatch = { it() }
    }

    @AfterTest
    fun reset() {
        ClojureInterface.controlDispatch = { work -> afield.runtime.lifecycle.inMain { work() } }
        ClojureInterface.thisBox = null
        made.forEach { ClojureInterface.clearControls(it) }
        needsResync.removeAll { (b, _) -> b in made }
        thisSessionInfo.remove()
    }

    /** Evaluate [code] in a fresh namespace with `field.core` referred, as if run inside [inside]. */
    private fun run(inside: Box, code: String): Any? {
        val host = ClojureHost(defaultNs = "field.test.core${counter++}")
        ClojureInterface.thisBox = inside
        try {
            val result = host.evalPage(code, executeIfFn = false, onStdout = { _, _ -> }, onStderr = { _, _ -> })
            return (result as? EvalResult.Ok ?: error("expected success, got $result")).result.value
        } finally {
            ClojureInterface.thisBox = null
        }
    }

    private fun kw(name: String) = clojure.lang.Keyword.intern(name)

    private fun deref(v: Any?): Any? = (Clojure.`var`("clojure.core", "deref") as IFn).invoke(v)

    @Test
    fun `a slider and a checkbox reach a fn and an atom when moved`() {
        val b = box("controls")
        b.output.displayProxy = { _, _ -> }
        val state = run(
            b,
            """
            (def seen (atom []))
            (def on (atom false))
            (slider "speed" (fn [v] (swap! seen conj v)) :min 0 :max 10 :step 0.5 :value 2)
            (checkbox "trails" on)
            {:seen seen :on on}
            """.trimIndent()
        ) as Map<*, *>
        assertEquals(emptyList(), controllerControlsForBox(b.id), "nothing is drawn until the run ends")
        ClojureInterface.flushControls(b)

        val controls = controllerControlsForBox(b.id)
        assertEquals(listOf("slider" to "speed", "checkbox" to "trails"), controls.map { it.type to it.label })
        assertEquals(2.0, controls[0].value)

        // What the slider-update and checkbox-update handlers do with a move from the page.
        getSliderCallback(controls[0].id)!!.invoke(7.5)
        getCheckboxCallback(controls[1].id)!!.invoke(true)
        assertEquals(listOf(7.5), deref(state[kw("seen")]))
        assertEquals(true, deref(state[kw("on")]))
    }

    @Test
    fun `a callback that throws is reported, not swallowed`() {
        val b = box("throws")
        val errors = mutableListOf<String>()
        b.output.displayProxy = { _, _ -> }
        b.output.errorProxy = { message, _, _ -> if (message != null) errors += message.toString() }
        run(b, "(slider \"bad\" (fn [v] (throw (ex-info \"no good\" {}))))")
        ClojureInterface.flushControls(b)
        getSliderCallback(controllerControlsForBox(b.id).single().id)!!.invoke(0.3)
        assertEquals(listOf("bad: no good"), errors)
    }

    @Test
    fun `a re-run keeps a moved slider's value and tells the callback`() {
        val b = box("rerun")
        b.output.displayProxy = { _, _ -> }
        val code = """
            (def level (atom 0.0))
            (slider "level" level :min 0 :max 1 :value 0.25)
            level
        """.trimIndent()
        run(b, code)
        ClojureInterface.flushControls(b)
        val id = controllerControlsForBox(b.id).single().id
        // The page moves it, which is what the slider-update handler records.
        updateControllerControlValue(id) { it.copy(value = 0.8) }

        val level = run(b, code)
        assertEquals(0.0, deref(level), "the run itself starts the atom from scratch")
        ClojureInterface.flushControls(b)
        assertEquals(0.8, deref(level), "the restored value reaches the atom when the controls are drawn")
        assertEquals(0.8, controllerControlsForBox(b.id).single().value)
    }

    @Test
    fun `context reads a property a parent exports, and the nearest value of a class`() {
        val root = Box("clj-core-root-${counter++}").also { made += it }
        val session = SessionInformation(filename = "<clj-core>", documentName = "clj-core", root = root, ephemeral = true)
        root.inside = session
        session.loading = false
        thisSessionInfo.set(session)
        fun inSession(name: String) = box(name).also { it.inside = session; session.boxes[it.id] = it }
        val grand = inSession("grand")
        val parent = inSession("parent")
        val child = inSession("child")
        grand.connect(parent)
        parent.connect(child)
        grand.setPropertyRaw("speed", 9.0)
        parent.setPropertyRaw("tint", "red")
        grand.setPropertyRaw("scale", java.lang.StringBuilder("sb"))

        assertEquals(9.0, run(child, "(context :speed)"))
        assertEquals("red", run(child, "(context \"tint\")"))
        assertEquals(1.5, run(child, "(context :missing 1.5)"))
        assertEquals("sb", run(child, "(str (context java.lang.StringBuilder))"))
        assertNull(run(child, "(context :missing)"))
    }
}
