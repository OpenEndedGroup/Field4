package afield.server

import afield.inspectorIframe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The server-side model behind `tree(...)` and `column(...)` (`afield/server/browsing.kt`): what a
 * value turns into on the `__browsing__` wire, one request at a time, without a browser.
 */
class InspectorTest {

    data class Point(val x: Double, val y: Double, val label: String)
    class Node(val name: String) { var next: Node? = null }
    class Plain { val count = 3; val tags = listOf("a", "b"); private val secret = "s" }

    private var seq = 0
    private fun ask(op: String, extra: String = ""): JsonObject {
        val text = """{"channel":"__browsing__","reqId":"${++seq}","op":"$op"$extra}"""
        return Json.parseToJsonElement(Inspector.handle(text)).jsonObject
    }

    private fun root(value: Any): JsonObject {
        val token = "test-${System.nanoTime()}"
        Inspector.linkFor(value, "/tree", token)
        return ask("getRoots", ""","root":"$token"""")
    }

    private fun open(id: String) = ask("open", ""","id":"$id"""")

    private class Row(val name: String, val id: String, val kind: String, val type: String, val summary: String, val flag: String)

    private fun rows(resp: JsonObject): List<Row> = resp["entries"]!!.jsonArray.map { e ->
        val a = (e as JsonArray).map { it.jsonPrimitive.content }
        Row(a[0], a[1], a[2], a[3], a[4], a[5])
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun `a data class opens to its fields with types and summaries`() {
        val r = root(Point(1.0, 2.5, "p"))
        assertEquals("dir", r.str("type"))
        assertEquals("Point", r.str("valueType"))
        val byName = rows(r).associateBy { it.name }
        assertEquals(listOf("x", "y", "label"), rows(r).map { it.name })
        assertEquals("Double", byName["x"]!!.type)
        assertEquals("1.0", byName["x"]!!.summary)
        assertEquals("leaf", byName["label"]!!.kind)
        assertEquals("\"p\"", byName["label"]!!.summary)
    }

    @Test
    fun `a plain class shows its fields, private ones too, and a list child is a dir`() {
        val byName = rows(root(Plain())).associateBy { it.name }
        assertEquals(setOf("count", "tags", "secret"), byName.keys)
        assertEquals("dir", byName["tags"]!!.kind)
        assertEquals("2 items", byName["tags"]!!.summary)
    }

    @Test
    fun `a long list arrives one page at a time`() {
        val r = root((0 until 5000).toList())
        assertEquals(5000, r["total"]!!.jsonPrimitive.int)
        assertTrue(r["more"]!!.jsonPrimitive.boolean)
        assertEquals(Inspector.DEFAULT_PAGE, rows(r).size)
        val next = ask("more", ""","id":"${r.str("id")}","offset":${Inspector.DEFAULT_PAGE}""")
        assertEquals("[${Inspector.DEFAULT_PAGE}]", rows(next).first().name)
        val last = ask("more", ""","id":"${r.str("id")}","offset":4900""")
        assertEquals(100, rows(last).size)
        assertFalse(last["more"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `primitive arrays are directories of their elements`() {
        val r = root(intArrayOf(4, 5, 6))
        assertEquals("IntArray", r.str("valueType"))
        assertEquals(listOf("4", "5", "6"), rows(r).map { it.summary })
    }

    @Test
    fun `map keys that print alike stay apart, and object keys open to key and value`() {
        val r = root(mapOf(1 to "int one", "1" to "string one", Point(0.0, 0.0, "o") to "origin"))
        val names = rows(r).map { it.name }
        assertEquals(listOf("1", "1 (2)", "Point(x=0.0, y=0.0, label=o)"), names)
        val entry = rows(r)[2]
        assertEquals("dir", entry.kind)
        assertEquals(listOf("key", "value"), rows(open(entry.id)).map { it.name })
    }

    @Test
    fun `a child that is one of its ancestors is flagged as a cycle`() {
        val a = Node("a"); val b = Node("b"); a.next = b; b.next = a
        val next = rows(root(a)).first { it.name == "next" }
        assertEquals("", next.flag)
        val back = rows(open(next.id)).first { it.name == "next" }
        assertEquals("cycle", back.flag)
        assertTrue(back.summary.startsWith("↺"))
    }

    @Test
    fun `one throwing element does not take its siblings down`() {
        val bad = object : AbstractList<Int>() {
            override val size = 3
            override fun get(index: Int): Int = if (index == 1) error("no element 1") else index
        }
        val rs = rows(root(bad))
        assertEquals(listOf("", "error", ""), rs.map { it.flag })
        assertTrue(rs[1].summary.contains("no element 1"), rs[1].summary)
        val leaf = open(rs[1].id)
        assertTrue(leaf.str("value").contains("no element 1"))
    }

    @Test
    fun `re-opening a directory reuses its child ids`() {
        val r = root(mapOf("a" to listOf(1, 2), "b" to 3))
        val first = rows(open(r.str("id"))).map { it.id }
        val before = Inspector.nodeCount()
        val second = rows(open(r.str("id"))).map { it.id }
        assertEquals(first, second)
        assertEquals(before, Inspector.nodeCount())
    }

    @Test
    fun `a watched plain list pushes changed when it is mutated`() {
        val list = mutableListOf(1, 2, 3)
        val token = "watch-${System.nanoTime()}"
        Inspector.linkFor(list, "/tree", token)
        val pushes = mutableListOf<String>()
        val client = Any()
        fun req(body: String) = Json.parseToJsonElement(Inspector.handle(body, client) { pushes += it }).jsonObject
        val r = req("""{"channel":"__browsing__","reqId":"w1","op":"getRoots","root":"$token"}""")
        val id = r.str("id")
        req("""{"channel":"__browsing__","reqId":"w2","op":"open","id":"$id","watch":true}""")
        Inspector.pollVersions()
        assertTrue(pushes.isEmpty(), "no change yet: $pushes")
        list += 4
        Inspector.pollVersions()
        assertTrue(pushes.any { it.contains("\"changed\"") && it.contains("\"$id\"") }, "pushes: $pushes")
        req("""{"channel":"__browsing__","reqId":"w3","op":"unsubscribe","id":"$id"}""")
    }

    @Test
    fun `plain values are leaves whose value is the full text`() {
        val r = root("hello\nworld")
        assertEquals("leaf", r.str("type"))
        assertEquals("hello\nworld", r.str("value"))
        assertEquals("String", r.str("valueType"))
    }

    @Test
    fun `an unknown link explains itself`() {
        val r = ask("getRoots", ""","root":"never-issued"""")
        assertFalse(r["ok"]!!.jsonPrimitive.boolean)
        assertTrue(r.str("error").contains("expired"))
    }

    @Test
    fun `summaries name sizes and clip long strings`() {
        assertEquals("3 items", InspectorValues.summary(listOf(1, 2, 3)))
        assertEquals("1 entry", InspectorValues.summary(mapOf(1 to 2)))
        val long = "x".repeat(500)
        val s = InspectorValues.summary(long)
        assertTrue(s.endsWith("(500 chars)") && s.length < 200, s)
        assertEquals(null, InspectorValues.inspectableFields("a string"))
        assertEquals(null, InspectorValues.inspectableFields(java.time.LocalTime.NOON))
        assertNotNull(InspectorValues.inspectableFields(Point(0.0, 0.0, "")))
    }

    @Test
    fun `the embed iframe is closed, full width and carries its height cap`() {
        val html = inspectorIframe("/tree?root=abc", 300)
        assertTrue(html.contains("src='/tree?root=abc&maxh=300'"), html)
        assertTrue(html.contains("width:100%"))
        assertTrue(html.trim().endsWith("</iframe>"))
    }
}
