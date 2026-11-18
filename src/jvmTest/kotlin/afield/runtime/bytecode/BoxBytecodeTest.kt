package afield.runtime.bytecode

import afield.runtime.lifecycle.BoxInterrupt
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runtime side of [CountingPass]: counts hits per site id. */
object TestLineProbe {
    val hits = AtomicIntegerArray(1 shl 16)

    @JvmStatic
    fun hit(site: Int) {
        hits.incrementAndGet(site)
    }
}

/** A pass that calls [TestLineProbe.hit] at every line of the snippet's own source. */
class CountingPass(override val id: String = "count", var on: Boolean = true) : BoxBytecodePass {
    override val inserts = setOf("afield/runtime/bytecode/TestLineProbe")
    val sites = ArrayList<Site>()
    val sawInsertedCalls = ArrayList<String>()

    override fun wants(origin: SnippetOrigin) = on

    override fun method(cls: ClassScope, m: MethodScope, next: MethodVisitor): MethodVisitor =
        object : BoxMethodVisitor(next) {
            override fun atLineStart(line: Int) {
                val site = cls.site(m, "test-line")
                synchronized(sites) { sites += site }
                super.visitLdcInsn(site.id)
                super.visitMethodInsn(Opcodes.INVOKESTATIC, "afield/runtime/bytecode/TestLineProbe", "hit", "(I)V", false)
            }

            override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                if (cls.isInserted(owner)) sawInsertedCalls += "$owner.$name"
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            }
        }
}

/** Code for the pipeline to rewrite: compiled by the test compiler, rewritten, reloaded. */
class PipelineShapes {
    fun sum(n: Int): Int {
        var s = 0
        for (i in 0 until n) {
            s += i
        }
        return s
    }

    fun withCatch(): Int = try {
        error("x")
    } catch (e: IllegalStateException) {
        7
    }

    fun id(x: Int): Int = x

    fun twice(): Int = id(id(1)) + id(2)

    fun here(): BoxFrame? = SnippetClasses.boxFrame()
}

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class TestAnchor(val tag: String)

interface AnchoredApi {
    @TestAnchor("interface")
    fun viaInterface(x: Int): Int
}

open class AnchoredBase : AnchoredApi {
    @TestAnchor("base")
    open fun inherited(x: Int): Int = x

    override fun viaInterface(x: Int): Int = x

    fun plain(x: Int): Int = x

    @TestAnchor("default")
    fun withDefault(x: Int, y: Int = 3): Int = x + y
}

class AnchoredLeaf : AnchoredBase()

@TestAnchor("top-level")
fun anchoredTopLevel(x: Int, y: Int = 1): Int = x + y

class BoxBytecodeTest {

    private fun bytesOf(c: Class<*>) = BoxBytecodeTestSupport.bytesOf(c)

    private fun load(name: String, rewritten: ByteArray) = BoxBytecodeTestSupport.load(name, rewritten, PipelineShapes::class.java.classLoader)

    private val shapes = PipelineShapes::class.java

    @Test
    fun chainedPassesVerifyAndCount() {
        val count = CountingPass()
        val origin = SnippetOrigin.detached("'pipeline' (test)")
        val out = assertNotNull(BoxBytecode.rewriteClass(bytesOf(shapes), origin, listOf(BoxInterrupt.PollPass, count)))
        val cls = load(shapes.name, out)
        val o = cls.getDeclaredConstructor().newInstance()
        assertEquals(45, cls.getMethod("sum", Int::class.java).invoke(o, 10))
        assertEquals(7, cls.getMethod("withCatch").invoke(o))
        val loopBody = count.sites.filter { it.methodName == "sum" }
        assertTrue(loopBody.isNotEmpty())
        // The `s += i` line runs ten times; some site on it must have counted ten.
        assertTrue(loopBody.any { TestLineProbe.hits.get(it.id) == 10 }, loopBody.joinToString { "${it.line}:${TestLineProbe.hits.get(it.id)}" })
        // The poll pass is outermost, so the counting pass sees its inserted calls and can skip them.
        assertTrue(count.sawInsertedCalls.any { it.endsWith("BoxInterrupt.poll") }, count.sawInsertedCalls.toString())
        assertEquals(origin, SnippetClasses.originOf(shapes.name))
        assertEquals("'pipeline' (test)", BoxInterrupt.culprit(arrayOf(StackTraceElement(shapes.name, "sum", null, 1))))
    }

    @Test
    fun aModuleIsRewrittenOncePerPassSetAndFromItsOriginals() {
        val a = CountingPass("a")
        val b = CountingPass("b", on = false)
        val original = bytesOf(shapes)
        val files = mutableMapOf("${shapes.name.replace('.', '/')}.class" to original, "META-INF/x.kotlin_module" to byteArrayOf(1))
        val module = Any()
        val origin = SnippetOrigin.detached("'module' (test)")

        assertEquals(listOf("a"), BoxBytecode.instrumentFiles(module, files, origin, listOf(a, b)).map { it.id })
        val once = files.values.first()
        val sitesAfterFirst = a.sites.size
        // Same pass set: a re-evaluation leaves the module alone (no second rewrite, no new sites).
        BoxBytecode.instrumentFiles(module, files, origin, listOf(a, b))
        assertTrue(files.values.first() === once)
        assertEquals(sitesAfterFirst, a.sites.size)

        // A pass switched on: rewritten again from the originals, so `a` appears once, not twice.
        b.on = true
        BoxBytecode.instrumentFiles(module, files, origin, listOf(a, b))
        val cls = load(shapes.name, files.values.first())
        val o = cls.getDeclaredConstructor().newInstance()
        assertEquals(45, cls.getMethod("sum", Int::class.java).invoke(o, 10))
        val newA = a.sites.drop(sitesAfterFirst).filter { it.methodName == "sum" }
        assertTrue(newA.any { TestLineProbe.hits.get(it.id) == 10 })

        // Everything off: the original bytes come back, untouched.
        a.on = false; b.on = false
        BoxBytecode.instrumentFiles(module, files, origin, listOf(a, b))
        assertTrue(files.values.first().contentEquals(original))
        assertEquals(1, files["META-INF/x.kotlin_module"]!!.size)
    }

    @Test
    fun scanFactsCountCallsPerLineInEvaluationOrder() {
        val seen = HashMap<String, MethodFacts>()
        val perClass = ArrayList<Int>()
        val spy = object : BoxBytecodePass {
            override val id = "spy"
            override val inserts = emptySet<String>()
            override fun wants(origin: SnippetOrigin) = true
            override fun method(cls: ClassScope, m: MethodScope, next: MethodVisitor): MethodVisitor {
                seen[m.name] = m.facts
                perClass += cls.attachment("n") { IntArray(1) }.also { it[0]++ }[0]
                val cursor = m.callCursor()
                return object : MethodVisitor(Opcodes.API_VERSION, next) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        val f = cursor.next(owner, name, descriptor)
                        if (m.name == "twice") assertNotNull(f, "cursor lost step at $owner.$name")
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
            }
        }
        BoxBytecodeTestSupport.rewriteAndLoad(shapes, listOf(spy))
        val twice = seen.getValue("twice").calls.filter { it.name == "id" }
        assertEquals(listOf(0, 1, 2), twice.map { it.ordinalOnLine })
        assertEquals(listOf(3, 3, 3), twice.map { it.countOnLine })
        assertTrue(seen.getValue("sum").backEdges >= 1)
        assertTrue(seen.getValue("id").isLeaf)
        assertTrue(!seen.getValue("twice").isLeaf)
        // One attachment per class, shared by its methods.
        assertEquals((1..perClass.size).toList(), perClass)
    }

    @Test
    fun aStackFrameOfRewrittenCodeNamesItsBoxLine() {
        val origin = BoxBytecodeTestSupport.originFor(shapes, "afield/runtime/bytecode/BoxBytecodeTest.kt")
        val cls = BoxBytecodeTestSupport.rewriteAndLoad(shapes, listOf(CountingPass("frame")), origin)
        val frame = assertNotNull(cls.getMethod("here").invoke(cls.getDeclaredConstructor().newInstance()) as BoxFrame?)
        val text = origin.sourceText!!.lines()[frame.line - 1]
        assertTrue("SnippetClasses.boxFrame()" in text, "line ${frame.line} is `$text`")
    }

    @Test
    fun linesMapByTextAcrossFragmentsPaddingAndMacroShifts() {
        val box = listOf("val a = 1", "println(a)", "//%-", "val b = a + 1", "", "repeat(3) {", "  println(b)", "}").joinToString("\n")
        // Second fragment, compiled on its own: its line 1 is box line 4.
        val fragment = listOf("//%-", "val b = a + 1", "", "repeat(3) {", "  println(b)", "}").joinToString("\n")
        val m = SourceLineMap.map(fragment, box)
        assertEquals(listOf(3, 4, -1, 6, 7, 8), (1..6).map { m[it] })
        // A selection run is padded with blank lines, so its lines are the box's own.
        val padded = "\n\n\n" + "val b = a + 1"
        assertEquals(4, SourceLineMap.map(padded, box)[4])
        // A macro that expanded one line into three shifts everything below it.
        val expanded = listOf("val a = 1", "run {", "  println(a)", "}", "//%-", "val b = a + 1").joinToString("\n")
        val e = SourceLineMap.map(expanded, box)
        assertEquals(1, e[1]); assertEquals(-1, e[2]); assertEquals(3, e[5]); assertEquals(4, e[6])
        assertEquals(-1, e[4], "a lone brace never re-anchors onto some other brace")
        // An edited box no longer proves the line.
        val origin = SnippetOrigin.detached("x", fragment, code = box.replace("println(b)", "println(b * 2)"))
        assertEquals(-1, origin.boxLine(5))
        assertEquals(6, origin.boxLine(4))
        assertEquals(-1, origin.boxLine(99), "a line past the source is inlined library code")
    }

    @Test
    fun callTargetAnnotationsAreReadWithoutLoading() {
        val loader = javaClass.classLoader
        val ann = "L${TestAnchor::class.java.name.replace('.', '/')};"
        val leaf = AnchoredLeaf::class.java.name.replace('.', '/')
        assertEquals("base", CallTargets.annotation(loader, leaf, "inherited", "(I)I", ann)?.values?.get("tag"))
        assertEquals("interface", CallTargets.annotation(loader, leaf, "viaInterface", "(I)I", ann)?.values?.get("tag"))
        assertNull(CallTargets.annotation(loader, leaf, "plain", "(I)I", ann))
        val base = AnchoredBase::class.java.name.replace('.', '/')
        assertEquals("default", CallTargets.annotation(loader, base, "withDefault\$default", "(L$base;IIILjava/lang/Object;)I", ann)?.values?.get("tag"))
        val facade = "afield/runtime/bytecode/BoxBytecodeTestKt"
        assertEquals("top-level", CallTargets.annotation(loader, facade, "anchoredTopLevel\$default", "(IIILjava/lang/Object;)I", ann)?.values?.get("tag"))
        assertEquals("top-level", CallTargets.annotation(loader, facade, "anchoredTopLevel", "(II)I", ann)?.values?.get("tag"))
        assertTrue(CallTargets.annotations(loader, "java/lang/String", "length", "()I").isEmpty())
        assertTrue(CallTargets.supertypes(loader, leaf).contains(AnchoredApi::class.java.name.replace('.', '/')))
    }
}
