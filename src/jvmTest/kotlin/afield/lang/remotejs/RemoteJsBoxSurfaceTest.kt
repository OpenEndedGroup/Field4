package afield.lang.remotejs

import afield.document.graph.Box
import afield.document.graph.needsResync
import afield.ui.output.controllerControlsForBox
import afield.ui.output.getCheckboxCallback
import afield.ui.output.getSliderCallback
import org.json.JSONObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JVM half of the 2026-10-24 RemoteJS box-parity fixes (`dev_docs/REMOTEJS_BOX_PARITY.md`):
 * the head messages `box.display`, `box.controls` and late output, and the Stop script. Each test
 * feeds [RemoteJsBoxSurface.handle] the JSON a head sends; the browser half was driven live through
 * the backdoor (transcript `remotejs_box_parity.yaml`).
 */
class RemoteJsBoxSurfaceTest {

    private val made = mutableListOf<Box>()
    private var counter = 0

    private fun box(name: String) = Box("rjs-surface-$name-${counter++}").also { made += it }
    private fun codeName(b: Box) = target(b)
    private fun message(address: String, payload: JSONObject) = JSONObject().put("address", address).put("payload", payload)

    @AfterTest
    fun reset() {
        needsResync.removeAll { (b, _) -> b in made }
    }

    @Test
    fun `a code name maps back to its box uid`() {
        val b = box("named")
        assertEquals(b.id, RemoteJsBoxSurface.uidOf(codeName(b)))
        assertNull(RemoteJsBoxSurface.uidOf("unknown"))
        assertNull(RemoteJsBoxSurface.uidOf(null))
    }

    @Test
    fun `display from the head lands in the named channel of the right box`() {
        val b = box("display")
        val shown = mutableListOf<Pair<String, Any?>>()
        b.output.displayProxy = { channel, html -> shown += channel to html }
        val handled = RemoteJsBoxSurface.handle(
            message("box.display", JSONObject().put("codeName", codeName(b)).put("channel", "main").put("html", "<b>hi</b>")),
            find = { if (RemoteJsBoxSurface.uidOf(it) == b.id) b else null },
        )
        assertTrue(handled)
        assertEquals(listOf<Pair<String, Any?>>("main" to "<b>hi</b>"), shown)
    }

    @Test
    fun `output printed after the run ended still reaches the box, beside its line`() {
        val b = box("late")
        val printed = mutableListOf<Pair<Any?, Number>>()
        b.output.proxy = { content, _, line -> printed += content to line }
        val payload = JSONObject().put("kind", "SUCCESS").put("message", "<json>late print").put("line", 4).put("codeName", codeName(b))
        assertTrue(RemoteJsBoxSurface.handle(message("3f2c-dead-return-channel", payload), find = { b }))
        assertEquals(listOf<Pair<Any?, Number>>("late print" to 4), printed)

        // A message with no code name is still nobody's: the old "don't understand" path.
        assertFalse(RemoteJsBoxSurface.handle(message("other", JSONObject().put("kind", "SUCCESS")), find = { b }))
    }

    @Test
    fun `controls from the head are drawn, and a move goes back to the head's callback`() {
        val b = box("controls")
        b.output.displayProxy = { _, _ -> }
        val sent = mutableListOf<Pair<String, Any>>()
        val specs = org.json.JSONArray()
            .put(JSONObject().put("id", "c|slider|speed").put("kind", "slider").put("label", "speed")
                .put("channel", "controls").put("min", 0).put("max", 10).put("step", 0.5).put("value", 3))
            .put(JSONObject().put("id", "c|checkbox|trails").put("kind", "checkbox").put("label", "trails")
                .put("channel", "controls").put("value", false))
        RemoteJsBoxSurface.handle(
            message("box.controls", JSONObject().put("codeName", codeName(b)).put("controls", specs)),
            find = { b }, invokeControl = { id, v -> sent += id to v },
        )
        val controls = controllerControlsForBox(b.id)
        assertEquals(listOf("slider" to "speed", "checkbox" to "trails"), controls.map { it.type to it.label })
        assertEquals(3.0, controls[0].value)
        getSliderCallback(controls[0].id)!!.invoke(7.5)
        getCheckboxCallback(controls[1].id)!!.invoke(true)
        assertEquals(listOf<Pair<String, Any>>("c|slider|speed" to 7.5, "c|checkbox|trails" to true), sent)
        assertEquals("__fieldControl(\"c|slider|speed\", 7.5)", RemoteJsBoxSurface.controlScript("c|slider|speed", 7.5))
    }

    @Test
    fun `stop reaches launch() tasks as well as the box's own task`() {
        val script = RemoteJsBoxSurface.stopScript("box_a|uid1")
        assertTrue("k === \"box_a|uid1\"" in script, script)
        assertTrue("k.indexOf(\"box_a|uid1\") === 0" in script, script)
    }

    @Test
    fun `a run that ends with a value settles`() {
        assertTrue(RemoteJsBoxSurface.isTerminal(JSONObject().put("kind", "SUCCESS").put("message", "<json>42").put("terminal", true)))
        assertTrue(RemoteJsBoxSurface.isTerminal(JSONObject().put("kind", "SUCCESS").put("message", "OK")))
        assertTrue(RemoteJsBoxSurface.isTerminal(JSONObject().put("kind", "ERROR").put("message", "boom")))
        assertFalse(RemoteJsBoxSurface.isTerminal(JSONObject().put("kind", "SUCCESS").put("message", "<json>a print")))
    }
}
