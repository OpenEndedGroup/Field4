package afield.app.server

import afield.data.query.graph.GraphWalkOptions
import afield.data.query.graph.LiveGraphEdge
import afield.data.query.graph.LiveGraphInspectable
import afield.data.query.graph.graphPath
import afield.ui.output.inspectorIframe
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
 * The server-side model behind `tree(...)` and `column(...)` (`afield/app/server/browsing.kt`): what a
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

    private fun root(value: Any, options: GraphWalkOptions = GraphWalkOptions()): JsonObject {
        val token = "test-${System.nanoTime()}"
        Inspector.linkFor(value, "/tree", token, options)
        return ask("getRoots", ""","root":"$token"""")
    }

    private fun open(id: String) = ask("open", ""","id":"$id"""")

    private class Row(val name: String, val id: String, val kind: String, val type: String, val summary: String, val flag: String, val path: String)

    private fun rows(resp: JsonObject): List<Row> = resp["entries"]!!.jsonArray.map { e ->
        val a = (e as JsonArray).map { it.jsonPrimitive.content }
        Row(a[0], a[1], a[2], a[3], a[4], a[5], a[6])
    }

    private fun query(rootId: String, pattern: String) = ask("query", ",\"id\":\"$rootId\",\"query\":\"$pattern\"")

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
    fun `a plain class shows its public properties, and a list child is a dir`() {
        val byName = rows(root(Plain())).associateBy { it.name }
        assertEquals(setOf("count", "tags"), byName.keys)
        assertEquals("dir", byName["tags"]!!.kind)
        assertEquals("2 items", byName["tags"]!!.summary)
    }

    @Test
    fun `private properties are listed when the walk options include them`() {
        val names = rows(root(Plain(), GraphWalkOptions(includePrivate = true))).map { it.name }.toSet()
        assertEquals(setOf("count", "tags", "secret"), names)
    }

    @Test
    fun `every row's path resolves with graphPath to the value the row shows`() {
        val scene = linkedMapOf<String, Any?>(
            "p" to Point(1.0, 2.0, "p"),
            "list" to listOf(Point(3.0, 4.0, "q")),
            "set" to linkedSetOf("a", "b"),
            "arr" to arrayOf("x", "y"),
            "byKey" to mapOf(Point(0.0, 0.0, "o") to "origin"),
        )
        val top = rows(root(scene)).associateBy { it.name }
        assertEquals("p", top["p"]!!.path)
        val p = rows(open(top["p"]!!.id)).associateBy { it.name }
        assertEquals("p/label", p["label"]!!.path)
        assertEquals("p", scene.graphPath(p["label"]!!.path)!!.get())
        val list = rows(open(top["list"]!!.id))
        assertEquals("[0]", list[0].name)
        assertEquals("list/0", list[0].path)
        val inner = rows(open(list[0].id)).associateBy { it.name }
        assertEquals(4.0, scene.graphPath(inner["y"]!!.path)!!.get())
        assertEquals("set/1", rows(open(top["set"]!!.id))[1].path)
        assertEquals("b", scene.graphPath("set/1")!!.get())
        assertEquals("arr/1", rows(open(top["arr"]!!.id))[1].path)
        val entry = rows(open(top["byKey"]!!.id)).single()
        assertEquals("byKey/Point(x=0.0, y=0.0, label=o)", entry.path)
        val kv = rows(open(entry.id)).associateBy { it.name }
        assertEquals("", kv["key"]!!.path, "a key object is not reachable by a path")
        assertEquals(entry.path, kv["value"]!!.path)
        assertEquals("origin", scene.graphPath(entry.path)!!.get())
    }

    @Test
    fun `primitive array elements and repeated map names have no path`() {
        val top = rows(root(linkedMapOf("w" to doubleArrayOf(0.5), "m" to mapOf(1 to "a", "1" to "b"))))
        assertEquals("", rows(open(top[0].id)).single().path)
        assertEquals(listOf("m/1", ""), rows(open(top[1].id)).map { it.path })
    }

    class Custom : LiveGraphInspectable {
        override fun liveGraphChildren() = listOf(LiveGraphEdge("gain") { 0.5 })
    }

    @Test
    fun `LiveGraphInspectable edges are rows, as graphPaths sees them`() {
        val c = rows(open(rows(root(mapOf("c" to Custom()))).single().id))
        assertEquals(listOf("gain"), c.map { it.name })
        assertEquals("c/gain", c.single().path)
    }

    @Test
    fun `a Browsable dir is reachable by graphPath`() {
        val b = object : Browsable { override fun dir() = mapOf("inner" to Point(1.0, 1.0, "i")) }
        val inner = rows(open(rows(root(mapOf("b" to b))).single().id)).first { it.name == "inner" }
        assertEquals("b/inner", inner.path)
        assertEquals("i", mapOf("b" to b).graphPath("b/inner/label")!!.get())
    }

    @Test
    fun `query runs graphPaths from the root and returns paths`() {
        val scene = linkedMapOf("a" to Point(1.0, 2.0, "first"), "b" to listOf(Point(3.0, 4.0, "second")))
        val id = root(scene).str("id")
        val q = query(id, "**/label")
        assertTrue(q["ok"]!!.jsonPrimitive.boolean, q.toString())
        val matches = q["matches"]!!.jsonArray.map { (it as JsonArray).map { x -> x.jsonPrimitive.content } }
        assertEquals(listOf("a/label", "b/0/label"), matches.map { it[0] })
        assertEquals("\"second\"", matches[1][3])
        assertEquals(2, q["total"]!!.jsonPrimitive.int)
        assertEquals(2, query(id, "a/x | a/y")["total"]!!.jsonPrimitive.int)
        assertFalse(query(id, "   ")["ok"]!!.jsonPrimitive.boolean)
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
        assertTrue(Inspector.edgeChildren(java.time.LocalTime.NOON, GraphWalkOptions()).isEmpty())
        assertNotNull(Inspector.edgeChildren(Point(0.0, 0.0, ""), GraphWalkOptions()).firstOrNull())
    }

    @Test
    fun `the embed iframe is closed, full width and carries its height cap`() {
        val html = inspectorIframe("/tree?root=abc", 300)
        assertTrue(html.contains("src='/tree?root=abc&maxh=300'"), html)
        assertTrue(html.contains("width:100%"))
        assertTrue(html.trim().endsWith("</iframe>"))
        assertTrue(inspectorIframe("/browse?root=abc", 300, width = 900).contains("width:900px"))
    }

    @Test
    fun `renderer adapters are registered and render graphics values as cards`() {
        registerRendererBrowsingAdapters()
        registerRendererBrowsingAdapters() // idempotent
        val r = root(afield.graphics.vulkan.device.ProfilingOptions(enabled = true, perfCounters = false))
        val text = r.toString()
        assertEquals("leaf", r.str("type"), text)
        assertTrue(text.contains("ProfilingOptions"), text)
        assertTrue(text.contains("perfCounters"), text)
        assertTrue(text.contains("false"), text)
    }

    @Test
    fun `a graphics node with children shows its card as the first summary row`() {
        val node = object : Browsable {
            override fun heading() = "Target"
            override fun dir() = linkedMapOf<String, Any?>("modules" to listOf(1, 2))
            override fun html() = "<div>card body</div>"
        }
        val wrapped = withSummaryRow(node)
        val r = root(wrapped)
        assertEquals("dir", r.str("type"))
        val entries = rows(open(r.str("id")))
        assertEquals(listOf("summary", "modules"), entries.map { it.name })
        val summary = open(entries.first().id)
        assertEquals("leaf", summary.str("type"))
        assertTrue(summary.toString().contains("card body"), summary.toString())
    }

    @Test
    fun `renderer adapters leave plain maps to the paged directory`() {
        registerRendererBrowsingAdapters()
        val r = root(linkedMapOf("a" to 1, "b" to 2))
        assertEquals("dir", r.str("type"))
        assertEquals(listOf("a", "b"), rows(open(r.str("id"))).map { it.name })
    }

    @Test
    fun `the server's adapter registration reaches the Assimp and glTF adapters`() {
        registerAllBrowsingAdapters()
        val trs = afield.graphics.scene.GltfSupport.TRS(
            afield.math.linalg.Vector3f(1f, 2f, 3f), afield.math.linalg.Quaternionf(), afield.math.linalg.Vector3f(1f, 1f, 1f)
        )
        val adapted = assertNotNull(BrowsableAdapters.adapt(trs, null, null), "no adapter for GltfSupport.TRS")
        assertEquals("Transform", adapted.heading())
    }

    @Test
    fun `every register-BrowsingAdapters function is called from registerAllBrowsingAdapters`() {
        val src = java.io.File("src/jvmMain/kotlin")
        assertTrue(src.isDirectory, "run from the repo root: ${src.absolutePath}")
        val defined = src.walkTopDown().filter { it.extension == "kt" }
            .flatMap { Regex("""\bfun\s+(register\w*BrowsingAdapters)\s*\(""").findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSortedSet()
        assertTrue("registerRendererBrowsingAdapters" in defined, defined.toString())
        val browsing = java.io.File(src, "afield/app/server/browsing.kt").readText()
        val body = browsing.substringAfter("fun registerAllBrowsingAdapters()").substringBefore("\n}\n")
        val missing = defined.filter { it != "registerAllBrowsingAdapters" && !Regex("""\b$it\s*\(\s*\)""").containsMatchIn(body) }
        assertTrue(missing.isEmpty(), "defined but never registered with the server: $missing")
    }
}
