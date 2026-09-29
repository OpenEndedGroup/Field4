package afield

import afield.macros.withPreambleSupplierTag
import afield.stdlib.whyDoesThisResolve
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `why` ([ContextResolution.explain]) on a graph with a shadowed candidate and an equidistant tie: the
 * child has two parents at the same distance holding different values, and one of them has a parent of
 * its own that holds a third. Properties only, so no engine is needed; the live-engine version is the
 * `context.why.tie-and-shadow` smoke.
 */
class ContextWhyTest {
    class Marker(val label: String) {
        override fun toString() = "Marker($label)"
    }

    private val made = mutableListOf<Box>()

    private fun session(label: String): SessionInformation {
        val root = Box("$label-root")
        val s = SessionInformation(filename = "<$label>", documentName = label, root = root, ephemeral = true)
        root.inside = s
        s.loading = false
        thisSessionInfo.set(s)
        made += root
        return s
    }

    private fun boxIn(s: SessionInformation, id: String): Box =
        Box(id).also {
            it.inside = s
            it.name = id
            s.boxes[id] = it
            made += it
        }

    @AfterTest
    fun cleanUp() {
        val mine = made.toSet()
        needsResync.removeAll { (box, _) -> box in mine }
        made.clear()
        thisSessionInfo.remove()
    }

    private fun JsonObject.uidAt(key: String) = this[key]!!.jsonObject["uid"]!!.jsonPrimitive.content
    private fun JsonObject.suppliers(key: String) = (this[key] as JsonArray).map { it.jsonObject.uidAt("supplier") }

    private class Graph(val child: Box, val a: Box, val b: Box, val grand: Box)

    private fun graph(label: String): Graph {
        val s = session(label)
        val grand = boxIn(s, "grand")
        val a = boxIn(s, "a")
        val b = boxIn(s, "b")
        val child = boxIn(s, "child")
        a.connect(child)
        b.connect(child)
        grand.connect(a)
        a.setPropertyRaw("speed", 1.0)
        b.setPropertyRaw("speed", 2.0)
        grand.setPropertyRaw("speed", 9.0)
        return Graph(child, a, b, grand)
    }

    @Test
    fun aNameIsExplainedWithItsSupplierPathTieAndShadows() {
        val g = graph("why-name")
        val why = ContextResolution.explain(g.child, name = "speed")

        assertTrue(why["found"]!!.jsonPrimitive.boolean)
        assertEquals("FROM_CONTEXT_NAMED", why["mechanism"]!!.jsonPrimitive.content)
        assertEquals("a", why.uidAt("supplier"), "first in parents order")
        assertEquals("PROPERTY", why["matchKind"]!!.jsonPrimitive.content)
        assertEquals(1, why["depth"]!!.jsonPrimitive.int)
        assertEquals(listOf("child", "a"), why["path"]!!.jsonArray.map { it.jsonObject["uid"]!!.jsonPrimitive.content })
        assertTrue(why["ambiguous"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("b"), why.suppliers("competing"))
        assertTrue(why.suppliers("shadowed").containsAll(listOf("b", "grand")), why.toString())
        val summary = why["summary"]!!.jsonPrimitive.content
        assertTrue("'a'" in summary && "ambiguous" in summary && "'grand'" in summary, summary)
    }

    @Test
    fun aTypeIsExplainedAsFromContextFindsIt() {
        val g = graph("why-type")
        g.grand.setPropertyRaw("marker", Marker("grand"))
        val why = ContextResolution.explain(g.child, type = Marker::class.java.name)
        assertEquals("FROM_CONTEXT", why["mechanism"]!!.jsonPrimitive.content)
        assertEquals("grand", why.uidAt("supplier"))
        assertEquals(listOf("child", "a", "grand"), why["path"]!!.jsonArray.map { it.jsonObject["uid"]!!.jsonPrimitive.content })
        assertTrue(why["crossesEdges"]!!.jsonPrimitive.boolean)
        assertEquals("grand", ContextResolution.explain(g.child, type = "Marker").uidAt("supplier"), "a simple name is enough")
        assertFalse(ContextResolution.explain(g.child, type = "NoSuchType")["found"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun aDeclaredNameIsResolvedByItsDelegateAndNamesItsPreambleLine() {
        val g = graph("why-declared")
        g.a.setPropertyRaw("_gain", 0.5)
        g.b.setPropertyRaw("_gain", 0.75)
        val line = withPreambleSupplierTag("var Box._gain by Inheritance<Double>() /**/", g.a.id)
        g.child.setPropertyRaw("preamble", line)

        val why = ContextResolution.explain(g.child, name = "_gain")
        assertEquals("INHERITANCE", why["mechanism"]!!.jsonPrimitive.content)
        assertEquals("Inheritance", why["delegate"]!!.jsonPrimitive.content)
        assertEquals("a", why.uidAt("supplier"))
        val declaration = why["declaration"]!!.jsonObject
        assertEquals("a", declaration.uidAt("suppliedBy"))
        assertEquals(g.a.id, declaration["tag"]!!.jsonPrimitive.content)
        assertTrue("var Box._gain by Inheritance<Double>()" in declaration["line"]!!.jsonPrimitive.content)
        assertTrue("declared by" in why["summary"]!!.jsonPrimitive.content)

        // The AI tool answers from the same explanation.
        val answer = whyDoesThisResolve(g.child, "_gain", null)
        assertEquals(why["summary"]!!.jsonPrimitive.content, answer.summary)
        assertEquals("INHERITANCE", answer.explanation["mechanism"])
    }
}
