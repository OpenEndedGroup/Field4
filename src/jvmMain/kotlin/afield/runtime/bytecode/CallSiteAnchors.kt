package afield.runtime.bytecode

import afield.document.graph.Box
import afield.runtime.lifecycle.BoxInterrupt
import afield.ui.output.Location
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes

/**
 * Call-site anchors: a box helper that draws next to the line that called it (`history`, `toggle`,
 * `liveNumber`, ...) learns that line from a slot the box's own bytecode armed just before the call,
 * instead of building a stack trace and searching the box's text on every call.
 *
 * [StackAware] marks such a helper. [CallSiteAnchorPass] finds every call to one in box code and
 * inserts `LDC site; INVOKESTATIC CallSites.arm(I)V` immediately before it. The helper's first act is
 * [CallSites.take], which reads and clears the slot and answers the proven box line and the column of
 * this call's occurrence on it, or null; on null it falls back to `locationForTextStringOnStack`.
 * `dev_docs/box_bytecode/CALLSITE_ANCHORS.md` has the design and the measurements.
 */

/**
 * Marks a function whose behaviour depends on where in box code it is called from, and whose first
 * act is [CallSites.take]. [CallSiteAnchorPass] arms the call-site slot before every call to it in
 * box code. Read from class files only (`BINARY`), through [CallTargets]; never by reflection.
 *
 * Inline functions cannot be targets (the call disappears into the caller), and a function that does
 * not call [CallSites.take] first should not carry this: the slot it leaves armed is refused later,
 * but it is wasted work.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class StackAware

/**
 * What a call-site [Site] carries: the target's name (without `$default`), and this call's [ordinal]
 * among the [count] calls to a method of that name on the same compiled line, in bytecode order.
 *
 * It also caches the last answer per version of the box's code, so a call made every frame from an
 * unchanged box costs one identity compare after the slot read.
 */
class CallSiteAnchor(val target: String, val ordinal: Int, val count: Int) {
    private class Answer(val code: String, val box: Box, val text: String?, val end: Boolean, val offset: Int, val location: Location?)

    @Volatile private var last: Answer? = null

    internal fun locate(site: Site, box: Box, text: String?, end: Boolean, offset: Int): Location? {
        val code = site.origin.currentCode() ?: return null
        val a = last
        if (a != null && a.code === code && a.box === box && a.text == text && a.end == end && a.offset == offset) return a.location
        val location = compute(site, box, code, text, end, offset)
        last = Answer(code, box, text, end, offset, location)
        return location
    }

    private fun compute(site: Site, box: Box, code: String, text: String?, end: Boolean, offset: Int): Location? {
        val line = site.boxLine()
        if (line < 1) { CallSites.unproven++; return null }
        val lineText = lineOf(code, line) ?: return null
        if (text == null) return Location(box, site.origin.property, line, 0)
        // The ordinal-th occurrence: calls on one line are numbered in bytecode order, which is
        // source order except for nested calls (`f(f(x))` evaluates the inner one first).
        var at = -1
        for (k in 0..ordinal) {
            at = lineText.indexOf(text, at + 1)
            if (at < 0) { CallSites.unproven++; return null }
        }
        return Location(box, site.origin.property, line, at + (if (end) text.length else 0) + offset)
    }

    /** `history #1/2`: the target, then this call's ordinal of the count on its line. */
    override fun toString(): String = "$target #$ordinal/$count"

    companion object {
        /** Line [line] (1-based) of [code], without its newline, or null if it has fewer lines. */
        internal fun lineOf(code: String, line: Int): String? {
            var start = 0
            for (i in 1 until line) {
                val nl = code.indexOf('\n', start)
                if (nl < 0) return null
                start = nl + 1
            }
            val nl = code.indexOf('\n', start)
            return (if (nl < 0) code.substring(start) else code.substring(start, nl)).removeSuffix("\r")
        }
    }
}

/**
 * The per-thread call-site slot. Box code arms it ([arm], inserted by [CallSiteAnchorPass]) right
 * before calling a [StackAware] function; that function reads and clears it ([take]) first thing.
 *
 * Nothing runs between the two but the call itself, so a slot found armed belongs to this call,
 * with one exception: a call that throws after the arm and before the target's `take` (a default
 * argument that throws, a parameter null check, a class that fails to initialise) leaves the slot
 * armed. [take] refuses a slot whose site is for another box or another target name, and the next
 * [arm] overwrites it; what it cannot refuse is a stale slot of the *same* box and target taken by an
 * uninstrumented call of that target (a library helper calling it), which then reports the line of
 * the call that threw.
 */
object CallSites {
    /** Internal name, for the inserted `INVOKESTATIC`. */
    const val OWNER = "afield/runtime/bytecode/CallSites"

    private class Slot {
        @JvmField var site = -1
    }

    private val slot: ThreadLocal<Slot> = ThreadLocal.withInitial { Slot() }

    /** Slots refused because they were left by another box's or another target's call. Racy count. */
    @JvmField var refused = 0L

    /** Takes whose site's line is not proven in the box's current code (edited, inlined, macro). Racy count. */
    @JvmField var unproven = 0L

    /** Inserted by [CallSiteAnchorPass] immediately before a call to a [StackAware] target. */
    @JvmStatic
    fun arm(site: Int) {
        slot.get().site = site
    }

    /** Read and clear this thread's slot: the site id armed, or -1. */
    @JvmStatic
    fun takeSite(): Int {
        val s = slot.get()
        val id = s.site
        if (id >= 0) s.site = -1
        return id
    }

    /**
     * The [Site] this thread's slot was armed with for a call to [name] in [boxId]'s code, clearing
     * the slot; null when it is empty, or armed for another box or target (a stale slot, refused).
     */
    fun takeSite(boxId: String?, name: String): Site? {
        val id = takeSite()
        if (id < 0) return null
        val site = SnippetSites[id] ?: return null
        val a = site.detail as? CallSiteAnchor
        if (a == null || a.target != name || boxId == null || site.origin.boxId != boxId) {
            refused++
            return null
        }
        return site
    }

    /**
     * Where the call to [name] that entered the caller is, in [box]'s current code, read from the slot
     * armed in bytecode (and cleared): the site's proven box line, and the column of this call's
     * occurrence of [text] on it, positioned as `locationForTextStringOnStack(text, box, end = end,
     * offset = offset)` positions its match. With [text] null only the line is found (column 0).
     *
     * Null when the slot is empty (an uninstrumented caller: library code, Python, the pass off),
     * stale, or the line cannot be proven against the box's current code (it was edited, or the call
     * is in inlined code). The caller then falls back to the stack search. Always clears the slot, so
     * call it first even when [box] is null.
     */
    fun take(box: Box?, name: String, text: String? = name, end: Boolean = true, offset: Int = 1): Location? {
        val site = takeSite(box?.id, name) ?: return null
        // The helpers read `box.code` at the returned line; another source property is not that text.
        if (site.origin.property != "code") return null
        return (site.detail as CallSiteAnchor).locate(site, box!!, text, end, offset)
    }
}

/**
 * The call-site anchor pass: `LDC site; INVOKESTATIC CallSites.arm(I)V` immediately before every call
 * in box code whose target [CallTargets] reports as [StackAware] (a `$default` stub counts as its
 * function). Inserted from `visitMethodInsn` only, so it is always after the instruction's frame; it
 * adds no jump, local or frame.
 *
 * On by default; `AFIELD_BOX_CALLSITE_ANCHORS=off` (or `-Dafield.boxBytecode.callSiteAnchors=off`)
 * or [enabled] turns it off for code compiled from then on, and the helpers fall back to the stack.
 */
object CallSiteAnchorPass : BoxBytecodePass {
    /** `anchors`, in logs, dumps and a module's record of its passes. */
    override val id = "anchors"

    /** Only [CallSites]: its `arm` is the one call this pass inserts. */
    override val inserts = setOf(CallSites.OWNER)

    private const val STACK_AWARE = "Lafield/runtime/bytecode/StackAware;"

    /** Whether newly compiled box code arms call sites. Read once per evaluation, in [wants]. */
    @Volatile
    @JvmStatic
    var enabled: Boolean = BoxInterrupt.setting("AFIELD_BOX_CALLSITE_ANCHORS", "afield.boxBytecode.callSiteAnchors")
        .let { it == null || (it != "0" && it != "off" && it != "false") }

    /** While [enabled], for any snippet whose loader is alive (annotations are read through it). */
    override fun wants(origin: SnippetOrigin): Boolean = enabled && origin.classLoader != null

    private fun baseName(name: String) = name.removeSuffix("\$default")

    /**
     * [next] itself for a method with no call to a [StackAware] target (decided from the scan, before
     * anything is emitted); otherwise a visitor that arms a fresh site before each such call.
     */
    override fun method(cls: ClassScope, m: MethodScope, next: MethodVisitor): MethodVisitor {
        val loader = cls.origin.classLoader ?: return next
        val calls = m.facts.calls
        if (calls.isEmpty()) return next
        val anchored = calls.filter { f -> CallTargets.annotation(loader, f.owner, f.name, f.desc, STACK_AWARE) != null }
        if (anchored.isEmpty()) return next
        // Ordinals by *name* on the line, not by owner.name: the column is found by text, and
        // `history(x)` and `history(x, 50)` (a `$default` stub and the function) are both "history".
        val ordinals = HashMap<CallFact, Pair<Int, Int>>()
        anchored.forEach { f ->
            val same = calls.filter { it.line == f.line && baseName(it.name) == baseName(f.name) }
            ordinals[f] = same.indexOf(f) to same.size
        }
        return object : MethodVisitor(Opcodes.API_VERSION, next) {
            val cursor = m.callCursor()

            override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                if (!cls.isInserted(owner)) {
                    val f = cursor.next(owner, name, descriptor)
                    val o = f?.let { ordinals[it] }
                    if (o != null) {
                        val site = cls.site(m, "callsite", CallSiteAnchor(baseName(name), o.first, o.second))
                        super.visitLdcInsn(site.id)
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, CallSites.OWNER, "arm", "(I)V", false)
                    }
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            }
        }
    }
}
