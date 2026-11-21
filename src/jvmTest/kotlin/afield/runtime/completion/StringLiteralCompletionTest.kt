package afield.runtime.completion

import afield.document.graph.Box
import afield.document.workspace.NamedDirectoryNames
import afield.graphics.paint.PaintToolNames
import afield.graphics.paint.PaintToolbox
import afield.document.graph.needsResync
import afield.geometry.rhino.RhinoLayerPaths
import afield.geometry.rhino.RhinoModel
import afield.runtime.kotlin.KotlinContext
import afield.runtime.kotlin.KotlinScriptEngine
import afield.runtime.kotlin.KotlinVariableInfo
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.reflect.full.memberFunctions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

object StringLiteralCompletionFixtures {
    object Fruit : StringCompletions {
        override fun candidates(context: StringCompletionContext) = listOf("banana/peach", "banana/pear", "apple")
    }

    fun pick(count: Int, @CompletesWith(Fruit::class) argme: String, @CompletesPath("*.csv") table: String = "") = Unit

    /** Offers the layer names of the [Model] the call is on, as RhinoLayerPaths does for a RhinoModel. */
    object ReceiverLayers : StringCompletions {
        override fun candidates(context: StringCompletionContext) = (context.receiver as? Model)?.layerNames.orEmpty()
    }

    class Model(val layerNames: List<String>) {
        fun layer(@CompletesWith(ReceiverLayers::class) name: String) = name
    }

    /** Index access through an annotated property, declared in the body and in the constructor. */
    class Shelf {
        @CompletesWith(Fruit::class)
        val stock = mutableMapOf<String, Int>()
    }

    class Crate(@CompletesWith(Fruit::class) val stock: Map<String, Int>)

    /** Index access through annotated `get` / `set` operators; remembers the receiver it was handed. */
    object ToolNames : StringCompletions {
        var lastReceiver: Any? = "never asked"
        override fun candidates(context: StringCompletionContext): List<String> {
            lastReceiver = context.receiver
            return (context.receiver as? Toolbox)?.names.orEmpty()
        }
    }

    class Toolbox(val names: List<String>) {
        operator fun get(@CompletesWith(ToolNames::class) name: String) = names.indexOf(name)
        operator fun set(@CompletesWith(ToolNames::class) name: String, value: Int) = Unit
    }

    class Studio(val tools: Toolbox) {
        val computed: Toolbox get() = Toolbox(listOf("made-by-a-getter"))
    }
}

private val fixtureDescriptor: Any? = null

class StringLiteralCompletionTest {
    private fun at(codeWithCaret: String): Pair<String, Int> =
        codeWithCaret.replace("|", "") to codeWithCaret.indexOf('|')

    @Test
    fun `the caret inside a string literal reports what has been typed in it`() {
        val (code, pos) = at("Rhino3dm.load(\"/Users/marc/D|\")")
        assertEquals("/Users/marc/D", StringLiteralCompletion.stringPrefixAt(code, pos))
    }

    @Test
    fun `outside a string, after one closes, or in a comment there is no literal`() {
        at("val x = foo|").let { (c, p) -> assertNull(StringLiteralCompletion.stringPrefixAt(c, p)) }
        at("val x = \"/tmp\" + y|").let { (c, p) -> assertNull(StringLiteralCompletion.stringPrefixAt(c, p)) }
        at("// \"/tmp/|").let { (c, p) -> assertNull(StringLiteralCompletion.stringPrefixAt(c, p)) }
    }

    @Test
    fun `escaped quotes and raw strings are followed`() {
        at("val s = \"a\\\"b /x|").let { (c, p) -> assertEquals("a\\\"b /x", StringLiteralCompletion.stringPrefixAt(c, p)) }
        at("val s = \"\"\"/tmp/|").let { (c, p) -> assertEquals("/tmp/", StringLiteralCompletion.stringPrefixAt(c, p)) }
    }

    private fun callAt(codeWithCaret: String): StringLiteralCompletion.CallSite? {
        val (code, pos) = at(codeWithCaret)
        return StringLiteralCompletion.callSiteOf(code, StringLiteralCompletion.literalAt(code, pos)!!.quote)
    }

    @Test
    fun `the call site names the receiver, callee and the argument's name or position`() {
        val named = callAt("foo.bar(1, argme=\"banana/peac|")!!
        assertEquals(Triple("foo", "bar", "argme"), Triple(named.receiver, named.callee, named.parameterName))

        val positional = callAt("val m = afield.geometry.rhino.Rhino3dm.load(\"x\".length.toString(), listOf(\"a,b\") { it }, \"|")!!
        assertEquals("afield.geometry.rhino.Rhino3dm", positional.receiver)
        assertEquals(2, positional.index)
        assertNull(positional.parameterName)

        val nested = callAt("outer(1, inner(\"/tmp/|")!!
        assertEquals("inner" to 0, nested.callee to nested.index)

        assertNull(callAt("val s = \"/tmp/|"))
        assertNull(callAt("foo(\"a\" + \"/tmp/|"))
    }

    @Test
    fun `a parameter's annotation is found by name or by position`() {
        val functions = StringLiteralCompletionFixtures::class.memberFunctions.filter { it.name == "pick" }
        fun annotationFor(source: String) = StringLiteralCompletion.annotationIn(functions.asSequence(), callAt(source)!!)

        assertIs<CompletesWith>(annotationFor("F.pick(3, argme = \"ban|"))
        assertIs<CompletesWith>(annotationFor("F.pick(3, \"ban|"))
        assertEquals("*.csv", assertIs<CompletesPath>(annotationFor("F.pick(3, \"x\", \"da|")).mask)
        assertNull(annotationFor("F.pick(count = \"3|"))
    }

    @Test
    fun `an annotated library parameter resolves from its receiver, with no box`() {
        val code = "import afield.geometry.rhino.Rhino3dm\nRhino3dm.load(\"/tmp/"
        val call = StringLiteralCompletion.callSiteOf(code, StringLiteralCompletion.literalAt(code, code.length)!!.quote)!!
        assertEquals("*.3dm", assertIs<CompletesPath>(StringLiteralCompletion.parameterAnnotation(null, code, call)).mask)
    }

    @Test
    fun `an absolute path completes from the directory, directories first, replacing the whole literal`() {
        val dir = Files.createTempDirectory("string-literal-completion").toFile()
        try {
            dir.resolve("Documents").mkdir()
            dir.resolve("Desktop").mkdir()
            dir.resolve("data.3dm").writeText("x")
            dir.resolve("other.txt").writeText("x")
            dir.resolve(".hidden").mkdir()
            val base = dir.canonicalPath.trimEnd('/') + "/"
            val (code, pos) = at("val x = foo(\"${base}D|\")")
            val completion = StringLiteralCompletion.complete(null, code, pos)!!
            assertEquals(listOf("Desktop/", "Documents/"), completion.metadata.map { it.displayText }.take(2))
            assertEquals("${base}Desktop/", completion.metadata.first().text)
            assertEquals(code.indexOf(base), completion.boundsStart)
            assertEquals(pos, completion.boundsEnd)
            assertTrue(completion.metadata.all { it.category == StringLiteralCompletion.PATHS })

            // Rhino3dm.load's @CompletesPath("*.3dm") hides other.txt; a plain string shows it.
            val (rhino, rhinoPos) = at("import afield.geometry.rhino.Rhino3dm\nRhino3dm.load(\"$base|\")")
            val shown = StringLiteralCompletion.complete(null, rhino, rhinoPos)!!.metadata.map { it.displayText }
            assertEquals(listOf("../", "Desktop/", "Documents/", "data.3dm"), shown)
            val (plain, plainPos) = at("println(\"$base|\")")
            assertTrue("other.txt" in StringLiteralCompletion.complete(null, plain, plainPos)!!.metadata.map { it.displayText })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `ordinary strings, unknown directories and templates are left to Kotlin completion`() {
        listOf(
            "display(\"main\", \"<div>|\")",
            "load(\"no-such-directory-anywhere-for-this-test/|\")",
            "load(\"https://example.com/|\")",
            "load(\"\${home}/|\")",
            "load(\"/no/such/directory/anywhere/|\")",
            "String.format(\"%.2f|\", x)",
        ).forEach { source ->
            val (code, pos) = at(source)
            assertNull(StringLiteralCompletion.complete(null, code, pos), source)
        }
    }

    /** A box whose Kotlin context holds [variables], as it would after a run, with no session or compiler. */
    private fun boxWithVariables(vararg variables: Pair<String, Any?>): Box {
        val context = KotlinContext()
        val engine = Proxy.newProxyInstance(KotlinScriptEngine::class.java.classLoader, arrayOf(KotlinScriptEngine::class.java)) { proxy, method, _ ->
            when (method.name) {
                "getContext" -> context
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> false
                "toString" -> "FakeEngine"
                "dispose" -> null
                else -> throw UnsupportedOperationException("FakeEngine.${method.name}")
            }
        } as KotlinScriptEngine
        return Box("string-literal-receiver").also {
            it.ki = engine
            for ((name, value) in variables) context.vars[name] = KotlinVariableInfo(value, ::fixtureDescriptor, it)
        }
    }

    private fun Box.rows(codeWithCaret: String): List<String>? {
        val (code, pos) = at(codeWithCaret)
        return StringLiteralCompletion.complete(this, code, pos)?.metadata?.map { it.displayText }
    }

    @Test
    fun `a provider is handed the live object a member call is on`() {
        val model = StringLiteralCompletionFixtures.Model(listOf("Site", "Building::Walls", "Building::Roof"))
        val box = boxWithVariables("model" to model)
        try {
            val (code, pos) = at("model.layer(\"Wal|\")")
            val completion = StringLiteralCompletion.complete(box, code, pos)!!
            assertEquals(listOf("Building::Walls"), completion.metadata.map { it.displayText })
            assertTrue(completion.metadata.all { it.category == StringLiteralCompletion.CHOICES })

            // Completion evaluates nothing: an expression receiver is not run to find its value.
            val (expression, expressionPos) = at("StringLiteralCompletionFixtures.Model(listOf(\"Site\")).layer(\"|\")")
            assertNull(StringLiteralCompletion.complete(box, expression, expressionPos))
        } finally {
            needsResync.removeAll { (b, _) -> b === box }
        }
    }

    @Test
    fun `RhinoModel layer and on complete layer paths`() {
        val functions = RhinoModel::class.memberFunctions.filter { it.name == "layer" || it.name == "on" }
        for (source in listOf("model.layer(\"Bu|", "model.on(layer = \"Bu|")) {
            assertEquals(RhinoLayerPaths::class, assertIs<CompletesWith>(StringLiteralCompletion.annotationIn(functions.asSequence(), callAt(source)!!)).provider)
        }
    }

    @Test
    fun `inside brackets the dotted name before them is the receiver of index access`() {
        val call = callAt("foo.bar[\"pea|")!!
        assertEquals(Triple("foo.bar", StringLiteralCompletion.INDEX, 0), Triple(call.receiver, call.callee, call.index))
        assertEquals("a.b.c", callAt("a!!.b?.c[\"|")!!.receiver)
        assertNull(callAt("f()[\"|"), "a call result would have to run")
        assertNull(callAt("a[\"x\"][\"|"), "so would an index result")
        assertNull(callAt("val xs = [\"|"), "a collection literal is not index access")
        assertNull(callAt("@Suppress([\"|"))
    }

    @Test
    fun `an annotated property completes the strings that index it`() {
        val box = boxWithVariables("foo" to StringLiteralCompletionFixtures.Shelf(), "crate" to StringLiteralCompletionFixtures.Crate(mapOf()))
        try {
            assertEquals(listOf("banana/peach", "banana/pear"), box.rows("foo.stock[\"pea|\"]"))
            assertEquals(listOf("banana/peach", "banana/pear"), box.rows("foo.stock[\"pea|\"] = 3"))
            assertEquals(listOf("banana/peach", "banana/pear"), box.rows("crate.stock[\"pea|"), "declared in the constructor")
        } finally {
            needsResync.removeAll { (b, _) -> b === box }
        }
    }

    @Test
    fun `get and set operators complete their key and are handed the indexed object`() {
        val tools = StringLiteralCompletionFixtures.Toolbox(listOf("sky", "earth", "skin"))
        val box = boxWithVariables("tools" to tools, "studio" to StringLiteralCompletionFixtures.Studio(tools))
        try {
            assertEquals(listOf("sky", "skin"), box.rows("tools[\"sk|"))
            assertEquals(listOf("sky", "skin"), box.rows("studio.tools[\"sk|\"] = 2"), "through a property's backing field")
            assertEquals(tools, StringLiteralCompletionFixtures.ToolNames.lastReceiver)

            // A property with a getter is not run: its type still finds the annotation, but there is no receiver.
            assertEquals(emptyList<String>(), box.rows("studio.computed[\"|"))
            assertNull(StringLiteralCompletionFixtures.ToolNames.lastReceiver)
        } finally {
            needsResync.removeAll { (b, _) -> b === box }
        }
    }

    @Test
    fun `FileManager and PaintToolbox complete their names`() {
        val code = "import afield.document.workspace.FileManager\nFileManager[\"te"
        val call = StringLiteralCompletion.callSiteOf(code, StringLiteralCompletion.literalAt(code, code.length)!!.quote)!!
        assertEquals(NamedDirectoryNames::class, assertIs<CompletesWith>(StringLiteralCompletion.parameterAnnotation(null, code, call)).provider)
        // Box code names FileManager without an import, and two shaded IntelliJ classes share the name.
        val bare = "FileManager[\"te"
        val bareCall = StringLiteralCompletion.callSiteOf(bare, StringLiteralCompletion.literalAt(bare, bare.length)!!.quote)!!
        assertEquals(NamedDirectoryNames::class, assertIs<CompletesWith>(StringLiteralCompletion.parameterAnnotation(null, bare, bareCall)).provider)

        val functions = PaintToolbox::class.memberFunctions.asSequence()
        for (source in listOf("tools.brush(\"|", "tools.waterBrush(name = \"|")) {
            assertEquals(PaintToolNames::class, assertIs<CompletesWith>(StringLiteralCompletion.annotationIn(functions, callAt(source)!!)).provider)
        }
        val index = callAt("tools[\"|")!!
        assertEquals(PaintToolNames::class, assertIs<CompletesWith>(StringLiteralCompletion.annotationIn(functions.filter { it.isOperator }, index)).provider)
    }
}
