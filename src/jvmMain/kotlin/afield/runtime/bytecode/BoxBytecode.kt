package afield.runtime.bytecode

import afield.app.diagnostics.logging.lprintln
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.jvm.impl.KJvmCompiledModuleInMemory
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript

/**
 * The rewrite of a freshly compiled Kotlin box snippet: a list of passes, each a method visitor,
 * run over every class the compiler emitted for the snippet, just before it is evaluated.
 *
 * [BoxBytecode] is the whole entry point. `KotlinInterface2.evaluate*` calls [BoxBytecode.instrument];
 * [BoxBytecodePass] is what a pass implements, [ClassScope] and [MethodScope] are what it is handed.
 * The runaway-box interrupt (`afield.runtime.lifecycle.BoxInterrupt`) is the first pass.
 * `dev_docs/BOX_BYTECODE_PASSES.md` has the shared contract and the rules every pass keeps.
 */

/**
 * One rewrite of box bytecode: the interrupt poll, a line counter, a call-site anchor.
 *
 * **The rule every pass keeps**, because the pipeline writes with `COMPUTE_MAXS` only and so never
 * recomputes stack-map frames (which would need class loading, through `getCommonSuperClass`):
 * inserted code is straight-line and stack-neutral. A pass may push constants (or `DUP` a value
 * already on the stack) and then make a static call that consumes exactly what it pushed. It must
 * not insert a jump, a label, a `try`, a new local, or a frame. Anything that needs a branch goes
 * inside the static method the pass calls. Insert at `visitCode`, or immediately before an original
 * instruction (from its `visit*Insn`), never from `visitLabel`, `visitLineNumber` or `visitFrame`:
 * those come before the offset's frame, and code there separates a branch target from its frame.
 * [BoxMethodVisitor.atLineStart] is the safe "start of a line" point.
 *
 * Passes are chained in [BoxBytecode.passes] order, the first outermost. A pass sees the original
 * instructions plus whatever the passes *before* it inserted, so a pass that inspects calls skips
 * owners in [ClassScope.isInserted].
 */
interface BoxBytecodePass {
    /** Short stable name, used in logs, dumps and the record of what a module was rewritten with. */
    val id: String

    /** Internal names of the classes whose static methods this pass inserts calls to. */
    val inserts: Set<String>

    /**
     * Whether this pass rewrites code compiled for [origin], decided now (at evaluation). Must be
     * cheap and must not throw. A pass that is off adds nothing at all to the code.
     */
    fun wants(origin: SnippetOrigin): Boolean

    /**
     * Wrap [next] to rewrite method [m] of [cls], or return [next] to leave it alone. Only methods
     * with code are offered.
     */
    fun method(cls: ClassScope, m: MethodScope, next: MethodVisitor): MethodVisitor

    /** Told once [cls] has been rewritten by every wanted pass and the result will be used. */
    fun classRewritten(cls: ClassScope) {}
}

/**
 * A pass's method visitor with the one insertion point that is easy to get wrong done for it:
 * [atLineStart] runs once per `LINENUMBER`, immediately before the first *instruction* of that
 * line, which is after any label and stack-map frame at the same offset.
 *
 * Inserting in `visitLineNumber` itself is wrong whenever a frame follows: `ClassReader` visits an
 * offset as label, line number, frame, instruction, so code inserted at the line number lands
 * between the label and its frame, and the verifier rejects the method ("Expecting a stackmap frame
 * at branch target": every `catch` block's first line does this). Inserting before an original
 * instruction, from a `visit*Insn` override, is always after the frame and is fine.
 */
open class BoxMethodVisitor(next: MethodVisitor) : MethodVisitor(Opcodes.API_VERSION, next) {
    private var pendingLine = -1

    /** Insert here what belongs at the start of [line]. Called at most once per `LINENUMBER`. */
    protected open fun atLineStart(line: Int) {}

    override fun visitLineNumber(line: Int, start: Label) {
        super.visitLineNumber(line, start)
        pendingLine = line
    }

    private fun flush() {
        val l = pendingLine
        if (l < 0) return
        pendingLine = -1
        atLineStart(l)
    }

    override fun visitInsn(opcode: Int) { flush(); super.visitInsn(opcode) }
    override fun visitIntInsn(opcode: Int, operand: Int) { flush(); super.visitIntInsn(opcode, operand) }
    override fun visitVarInsn(opcode: Int, varIndex: Int) { flush(); super.visitVarInsn(opcode, varIndex) }
    override fun visitTypeInsn(opcode: Int, type: String?) { flush(); super.visitTypeInsn(opcode, type) }
    override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) { flush(); super.visitFieldInsn(opcode, owner, name, descriptor) }
    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) { flush(); super.visitMethodInsn(opcode, owner, name, descriptor, isInterface) }
    override fun visitInvokeDynamicInsn(name: String?, descriptor: String?, bsm: org.jetbrains.org.objectweb.asm.Handle?, vararg args: Any?) { flush(); super.visitInvokeDynamicInsn(name, descriptor, bsm, *args) }
    override fun visitJumpInsn(opcode: Int, label: Label) { flush(); super.visitJumpInsn(opcode, label) }
    override fun visitLdcInsn(value: Any?) { flush(); super.visitLdcInsn(value) }
    override fun visitIincInsn(varIndex: Int, increment: Int) { flush(); super.visitIincInsn(varIndex, increment) }
    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) { flush(); super.visitTableSwitchInsn(min, max, dflt, *labels) }
    override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray?, labels: Array<out Label>) { flush(); super.visitLookupSwitchInsn(dflt, keys, labels) }
    override fun visitMultiANewArrayInsn(descriptor: String?, numDimensions: Int) { flush(); super.visitMultiANewArrayInsn(descriptor, numDimensions) }
}

/** The class being rewritten, shared by every pass that sees it. */
class ClassScope internal constructor(
    val origin: SnippetOrigin,
    /** Internal name, `a/b/C`. */
    val className: String,
    private val inserted: Set<String>,
) {
    /** The class's `SourceFile` attribute (the snippet's source name), when it has one. */
    var sourceFile: String? = null
        internal set

    /** Whether a call to [owner] is one a pass inserted, not one the box's code makes. */
    fun isInserted(owner: String): Boolean = owner in inserted

    /** A new [Site] in this class at [m]'s current line (see [SnippetSites]). */
    fun site(m: MethodScope, kind: String, detail: Any? = null, line: Int = m.line): Site =
        SnippetSites.allocate(origin, className, m.name, m.desc, line, kind, detail)

    private val attachments = HashMap<Any, Any?>()

    /**
     * Per-class state for a pass (one site per line, say), made by [init] the first time [key] is
     * asked for in this class. A class is rewritten on one thread, so this needs no locking.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> attachment(key: Any, init: () -> T): T = attachments.getOrPut(key) { init() } as T

    internal val facts = HashMap<String, MethodFacts>()
}

/**
 * One call instruction in a method's original code, found before any pass runs. [ordinalOnLine] and
 * [countOnLine] count the calls to the same `owner.name` on the same line, in bytecode order (which
 * is evaluation order: for `f(f(x))` the inner call is ordinal 0 although it is written second).
 */
class CallFact(
    val opcode: Int,
    val owner: String,
    val name: String,
    val desc: String,
    val line: Int,
    val ordinalOnLine: Int,
) {
    var countOnLine: Int = 1
        internal set
}

/**
 * What a method's original code contains, read in a first, cheap pass over the class, so a pass can
 * decide before it emits anything: the calls in order (with their per-line counts), the distinct
 * lines in order, how many backward jumps (loop back-edges) it has, and how many instructions.
 */
class MethodFacts internal constructor(
    val calls: List<CallFact>,
    val lines: IntArray,
    val backEdges: Int,
    val instructionCount: Int,
) {
    /** A method with no calls and no loops: straight-line code whose cost is its caller's. */
    val isLeaf: Boolean get() = calls.isEmpty() && backEdges == 0

    companion object {
        internal val EMPTY = MethodFacts(emptyList(), IntArray(0), 0, 0)
    }
}

/**
 * Walks a method's [CallFact]s in step with the instruction stream. A pass calls [next] from
 * `visitMethodInsn` for every call that is not [ClassScope.isInserted]; it returns the fact for that
 * call, or null if the stream and the scan disagree (then the pass should leave the call alone).
 */
class CallCursor internal constructor(private val calls: List<CallFact>) {
    private var at = 0

    fun next(owner: String, name: String, desc: String): CallFact? {
        val f = calls.getOrNull(at) ?: return null
        at++
        return if (f.owner == owner && f.name == name && f.desc == desc) f else null
    }
}

/** The method being rewritten. [line] follows the bytecode's line numbers as the method is visited. */
class MethodScope internal constructor(
    val access: Int,
    val name: String,
    val desc: String,
    /** The method's original code, scanned before any pass ran. */
    val facts: MethodFacts,
) {
    /** A fresh cursor over [MethodFacts.calls], one per pass that inspects calls in this method. */
    fun callCursor(): CallCursor = CallCursor(facts.calls)

    /**
     * The compiled-source line of the instruction being visited now: the last `LINENUMBER` seen,
     * 0 before the first. Lines above [SnippetOrigin.sourceLineCount] are inlined library code.
     */
    var line: Int = 0
        internal set

    /** `<init>` or `<clinit>`: code before `super()` runs on an uninitialised `this`. */
    val isInitializer: Boolean get() = name == "<init>" || name == "<clinit>"
    val isStatic: Boolean get() = access and Opcodes.ACC_STATIC != 0
    val isSynthetic: Boolean get() = access and Opcodes.ACC_SYNTHETIC != 0
    val isBridge: Boolean get() = access and Opcodes.ACC_BRIDGE != 0
}

/**
 * The box-bytecode rewrite pipeline: which passes exist, and the per-module bookkeeping that keeps
 * a rerun from being rewritten twice.
 *
 * A module is rewritten from its **original** compiler output every time, with the passes that
 * [BoxBytecodePass.wants] it at that evaluation. The originals are kept (weakly, with the module),
 * so when the set of passes changes between two evaluations of the same compiled snippet (a pass
 * switched on for a box, then the rerun compile cache re-evaluates that box's snippet) the next
 * evaluation gets the new set, and switching a pass off really removes it. An evaluation with the
 * same set as last time leaves the module alone, which is what stops a re-evaluation from
 * instrumenting twice. A class that is already loaded keeps the code it was loaded with.
 */
object BoxBytecode {

    /**
     * Every pass, in chain order (first is outermost). Read at each instrumentation; the list itself
     * is fixed once built.
     *
     * The order is decided here, once, for all of them: the interrupt poll outermost; then the two
     * passes that insert a call immediately before a call in the box's code (thread guard, then
     * call-site anchor, so the anchor's slot is armed last, right before the target is entered);
     * then the line counter after each line number; then cost, innermost, at entry and at each
     * return. The planned passes are named rather than referenced so they can be built on separate
     * branches without touching this file; a name with no class behind it yet is skipped. Once they
     * have all landed these become plain references.
     */
    val passes: List<BoxBytecodePass> by lazy {
        listOfNotNull(
            afield.runtime.lifecycle.BoxInterrupt.PollPass,
            planned("afield.runtime.bytecode.ThreadOwnershipPass"),
            planned("afield.runtime.bytecode.CallSiteAnchorPass"),
            planned("afield.runtime.bytecode.LineHeatPass"),
            planned("afield.runtime.bytecode.BoxCostPass"),
        )
    }

    /** The Kotlin `object` named [className] if it exists and is a [BoxBytecodePass], else null. */
    private fun planned(className: String): BoxBytecodePass? = try {
        Class.forName(className, true, BoxBytecode::class.java.classLoader).getField("INSTANCE").get(null) as? BoxBytecodePass
    } catch (_: ClassNotFoundException) {
        null
    } catch (t: Throwable) {
        lprintln("warn,execution,bytecode", "box bytecode pass $className did not load: $t")
        null
    }

    private class Applied(val signature: String, val originals: Map<String, ByteArray>)

    private val applied = WeakHashMap<Any, Applied>()

    /** Source text by compiled script, noted at compile time (the script itself does not carry it). */
    private val sources = WeakHashMap<CompiledScript, String>()

    private val warnedImmutable = AtomicBoolean(false)

    /** `AFIELD_BOX_BYTECODE_DUMP=<dir>`: write every rewritten class, before and after, under it. */
    private val dumpDir: File? = (System.getProperty("afield.boxBytecode.dump") ?: System.getenv("AFIELD_BOX_BYTECODE_DUMP"))
        ?.takeIf { it.isNotBlank() }?.let { File(it) }
    private val dumpSerial = AtomicInteger()

    /** Remember the text [script] was compiled from, for [SnippetOrigin.sourceText]. */
    fun noteSource(script: CompiledScript, text: String) {
        synchronized(sources) { sources[script] = text }
    }

    /** The text noted for [script] by [noteSource], or null. */
    fun sourceOf(script: CompiledScript): String? = synchronized(sources) { sources[script] }

    /**
     * Rewrite [script]'s module with every pass that wants [origin], from the module's original
     * bytes; a no-op when the set of passes is the one it already has. A module of an unknown shape
     * is left alone (and a read-only one is logged once).
     */
    fun instrument(script: CompiledScript, origin: SnippetOrigin) {
        val k = script as? KJvmCompiledScript ?: return
        val module = k.getCompiledModule() as? KJvmCompiledModuleInMemory ?: return
        @Suppress("UNCHECKED_CAST")
        instrumentFiles(module, module.compilerOutputFiles as? MutableMap<String, ByteArray> ?: return, origin, passes)
    }

    /**
     * [instrument] on a module's class-file map, keyed (weakly) by [module]; separate so tests can
     * drive it without a compiler. Returns the passes the module now carries.
     */
    internal fun instrumentFiles(module: Any, files: MutableMap<String, ByteArray>, origin: SnippetOrigin, passes: List<BoxBytecodePass>): List<BoxBytecodePass> {
        val wanted = passes.filter { p -> runCatching { p.wants(origin) }.getOrDefault(false) }
        val signature = wanted.joinToString(",") { it.id }
        // One module is rewritten by one thread at a time; different modules (boxes) in parallel.
        synchronized(module) {
            val previous = synchronized(applied) { applied[module] }
            if ((previous?.signature ?: "") == signature) return wanted
            val originals = previous?.originals ?: files.filterKeys { it.endsWith(".class") }.toMap()
            try {
                for ((name, bytes) in originals) {
                    files[name] = if (wanted.isEmpty()) bytes else rewriteClass(bytes, origin, wanted) ?: bytes
                }
                synchronized(applied) { applied[module] = Applied(signature, originals) }
            } catch (t: UnsupportedOperationException) {
                if (warnedImmutable.compareAndSet(false, true))
                    lprintln("warn,execution,bytecode", "snippet module is read-only; box code is not rewritten (no interrupt, no probes)")
            }
        }
        return wanted
    }

    /**
     * [bytes] rewritten by [passes] for [origin]; null when it could not be rewritten, and then the
     * class runs as compiled. Records the class in [SnippetClasses] and tells each pass.
     */
    fun rewriteClass(bytes: ByteArray, origin: SnippetOrigin, passes: List<BoxBytecodePass>): ByteArray? {
        if (passes.isEmpty()) return null
        return try {
            val reader = ClassReader(bytes)
            val inserted = passes.flatMapTo(HashSet()) { it.inserts }
            val cls = ClassScope(origin, reader.className, inserted)
            reader.accept(Scanner(cls), ClassReader.SKIP_FRAMES)
            // COMPUTE_MAXS only: see the rule on [BoxBytecodePass]. No frames are computed, so no
            // class is loaded and getCommonSuperClass is never called.
            val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
            reader.accept(Pipeline(writer, cls, passes), 0)
            val out = writer.toByteArray()
            SnippetClasses.record(cls.className, origin)
            passes.forEach { p -> runCatching { p.classRewritten(cls) } }
            dump(cls, origin, passes, bytes, out)
            out
        } catch (t: Throwable) {
            lprintln("warn,execution,bytecode", "could not rewrite a snippet class (${passes.joinToString { it.id }}): $t")
            null
        }
    }

    private fun dump(cls: ClassScope, origin: SnippetOrigin, passes: List<BoxBytecodePass>, before: ByteArray, after: ByteArray) {
        val dir = dumpDir ?: return
        runCatching {
            val tag = (origin.boxId ?: origin.label).filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40)
            val d = File(dir, "${dumpSerial.incrementAndGet().toString().padStart(5, '0')}_${tag}_${passes.joinToString("+") { it.id }}")
            d.mkdirs()
            val base = cls.className.replace('/', '.')
            File(d, "$base.orig.class").writeBytes(before)
            File(d, "$base.class").writeBytes(after)
            origin.sourceText?.let { File(d, "source.kts").writeText(it) }
        }
    }

    private class Pipeline(next: ClassVisitor, val cls: ClassScope, val passes: List<BoxBytecodePass>) :
        ClassVisitor(Opcodes.API_VERSION, next) {

        override fun visitSource(source: String?, debug: String?) {
            cls.sourceFile = source
            super.visitSource(source, debug)
        }

        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
            if (access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0) return mv
            val m = MethodScope(access, name, descriptor, cls.facts[name + descriptor] ?: MethodFacts.EMPTY)
            var chain = mv
            for (p in passes.asReversed()) chain = p.method(cls, m, chain)
            return LineTracker(m, chain)
        }
    }

    /** Head of every method's chain: keeps [MethodScope.line] current for the passes below it. */
    private class LineTracker(val m: MethodScope, next: MethodVisitor) : MethodVisitor(Opcodes.API_VERSION, next) {
        override fun visitLineNumber(line: Int, start: Label) {
            m.line = line
            super.visitLineNumber(line, start)
        }
    }

    /** The first, read-only pass over a class: fills [ClassScope.facts] for every method with code. */
    private class Scanner(val cls: ClassScope) : ClassVisitor(Opcodes.API_VERSION) {
        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
            if (access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0) return null
            val key = name + descriptor
            return object : MethodVisitor(Opcodes.API_VERSION) {
                val calls = ArrayList<CallFact>()
                val lines = LinkedHashSet<Int>()
                val seen = HashSet<Label>()
                val perLine = HashMap<String, MutableList<CallFact>>()
                var line = 0
                var backEdges = 0
                var instructions = 0

                override fun visitLineNumber(l: Int, start: Label) { line = l; lines += l }
                override fun visitLabel(label: Label) { seen += label }
                override fun visitInsn(opcode: Int) { instructions++ }
                override fun visitIntInsn(opcode: Int, operand: Int) { instructions++ }
                override fun visitVarInsn(opcode: Int, varIndex: Int) { instructions++ }
                override fun visitTypeInsn(opcode: Int, type: String?) { instructions++ }
                override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) { instructions++ }
                override fun visitInvokeDynamicInsn(name: String?, descriptor: String?, bsm: org.jetbrains.org.objectweb.asm.Handle?, vararg args: Any?) { instructions++ }
                override fun visitLdcInsn(value: Any?) { instructions++ }
                override fun visitIincInsn(varIndex: Int, increment: Int) { instructions++ }
                override fun visitMultiANewArrayInsn(descriptor: String?, numDimensions: Int) { instructions++ }

                override fun visitJumpInsn(opcode: Int, label: Label) {
                    instructions++
                    if (label in seen) backEdges++
                }

                override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) {
                    instructions++
                    if (dflt in seen || labels.any { it in seen }) backEdges++
                }

                override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray?, labels: Array<out Label>) {
                    instructions++
                    if (dflt in seen || labels.any { it in seen }) backEdges++
                }

                override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                    instructions++
                    val same = perLine.getOrPut("$line $owner.$name") { ArrayList(1) }
                    val f = CallFact(opcode, owner, name, descriptor, line, same.size)
                    same += f
                    same.forEach { it.countOnLine = same.size }
                    calls += f
                }

                override fun visitEnd() {
                    cls.facts[key] = MethodFacts(calls, lines.toIntArray(), backEdges, instructions)
                }
            }
        }
    }
}
