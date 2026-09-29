@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import afield.Box
import afield.four.buffers.Structure
import afield.stacksl.StackSLCompileResult
import afield.stacksl.StackSLCompiler
import afield.stacksl.StackSLDanglingWire
import afield.stacksl.cpu.StackSLWires
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * The numbers in a `{{ stack.auto.foo }}` slot, live on the GPU: each number typed in the StackSL is
 * compiled as a member of a uniform block of the slot's own, `foo_numbers`, so it can be turned
 * without a recompile — from the box's sliders, from Kotlin, or from a keyframe.
 *
 * The block is an ordinary uniform block of the module, so it is where every other uniform is:
 * `module.foo_numbers` is its live `Structure`, and `scope.target("drift@foo_wire_3", module.foo_numbers)`
 * keys one number the way `scope.target("tint", module.look)` keys any uniform. Its members are
 * `foo_live` and `foo_wire_0` … `foo_wire_127` — the number of a program's wires in compile order,
 * which is the order the study's knobs use.
 *
 * Until something turns a number, `foo_live` is 0 and every number reads as the literal it was
 * compiled from, so a slot nobody touches draws exactly what it always did, and a module this system
 * never seeds — the StackSL study's own, a gallery harness's — is unaffected. [set] seeds every member
 * from the program's literals and turns `foo_live` on. Writing members of the block directly (through
 * a keyframe target, say) works once the slot is live; [live] makes it so without changing a number.
 *
 * The block keeps one layout — `foo_live` and [CAPACITY] numbers — whatever the program, so an edit
 * to the StackSL still updates the running pipeline in place: Field refuses an in-place update that
 * changes any uniform block. A program with more numbers than that compiles as it always did, with
 * the numbers baked in, and its report says so. Assigning new StackSL (`module.foo = …`) re-seeds the
 * numbers from its literals if the slot was live; assigning the same text again changes nothing, so a
 * box can re-run without losing what was turned. A full shader reload carries the block's values over.
 */
object StackAutoNumbers {
    /** How many numbers a slot's block holds. */
    const val CAPACITY = 128

    /** The uniform block a slot's numbers live in. */
    fun blockName(slot: String) = "${slot}_numbers"

    /** The block member holding number [index] of [slot]. */
    fun member(slot: String, index: Int) = StackSLWires.globalName(slot, index)

    /** The block member that turns [slot]'s numbers on. */
    fun liveMember(slot: String) = "${slot}_live"

    /** Whether a uniform block [name] with these [members] is some slot's numbers: `foo_numbers`, holding `foo_live`. */
    fun isNumbersBlock(name: String, members: Collection<String>) =
        name.endsWith("_numbers") && liveMember(name.removeSuffix("_numbers")) in members

    /** One number of a slot as compiled: its [index], the literal it was compiled from and where it is. */
    data class Number(val index: Int, val initial: Double, val wire: StackSLDanglingWire)

    /** A slot of a box as last compiled: its [source], its [numbers] (empty when baked), and whether it has gone [live]. */
    class Slot(val box: Box, val name: String) {
        @Volatile var source: String = ""
        @Volatile var entrypoint: String? = null
        @Volatile var numbers: List<Number> = emptyList()
        @Volatile var live = false
        /** What turning has set, by index, so a structure rebuilt by a reload or seen for the first time gets it. */
        val values = ConcurrentHashMap<Int, Double>()
    }

    private val slots = Collections.synchronizedMap(WeakHashMap<Box, MutableMap<String, Slot>>())

    /** [box]'s slots, by name, as last compiled. */
    fun slots(box: Box): Map<String, Slot> = synchronized(slots) { slots[box]?.toMap().orEmpty() }

    /** [box]'s slot [name], created on first use. */
    fun slot(box: Box, name: String): Slot = synchronized(slots) { slots.getOrPut(box) { ConcurrentHashMap() }.getOrPut(name) { Slot(box, name) } }

    /** Listeners told when a slot of a box compiles with new source — the box's card redraws. */
    val onCompiled = java.util.concurrent.CopyOnWriteArrayList<(Slot) -> Unit>()

    /**
     * The binding [slot]'s block takes in set 0: 20 on, one per slot, in the order the slots first
     * appear across [shaderTexts] (all of the box's stages, so every stage agrees), skipping any binding
     * the texts already declare.
     */
    fun binding(slot: String, shaderTexts: List<String>): Int {
        val names = shaderTexts.flatMap { t -> Regex("""stack\.auto\.([A-Za-z_][A-Za-z0-9_]*)""").findAll(t).map { it.groupValues[1] }.toList() }.distinct()
        val used = shaderTexts.flatMap { t -> Regex("""binding\s*=\s*(\d+)""").findAll(t).map { it.groupValues[1].toInt() }.toList() }.toSet()
        val free = generateSequence(20) { it + 1 }.filter { it !in used }.iterator()
        var chosen = -1
        for (n in names.ifEmpty { listOf(slot) }) { val b = free.next(); if (n == slot) { chosen = b; break } }
        return if (chosen >= 0) chosen else free.next()
    }

    /** [slot]'s uniform block, at [binding] in set 0: `foo_live` and [CAPACITY] numbers, whatever the program. */
    fun block(slot: String, binding: Int): String = buildString {
        append("layout(std140, set = 0, binding = $binding) uniform ${blockName(slot)} {\n    float ${liveMember(slot)};\n")
        (0 until CAPACITY).forEach { append("    float ${member(slot, it)};\n") }
        append("};\n")
    }

    /**
     * [source] compiled for [slot] with its numbers read from the slot's [block]: the function a template
     * expands to (after the block), and the compile; null when the program has more than [CAPACITY]
     * numbers (compile it baked). A failed compile comes back with an empty function.
     */
    fun compileLive(slot: String, source: String, sampleTexture: String, entrypoint: String?): Pair<String, StackSLCompileResult>? {
        val result = StackSLCompiler.compile(source, StackSLWires.wiredOptions(slot, sampleTexture, entrypoint))
        if (!result.ok) return "" to result
        val wires = result.analysis.danglingWires
        if (wires.size > CAPACITY) return null
        val lowered = StackSLWires.lower(result.glsl, result.analysis.prefix, wires.size)
        val declaration = Regex("""^float ${Regex.escape(slot)}_wire_\d+;\s*$""")
        val body = lowered.lines().filterNot { declaration.matches(it) }.joinToString("\n").trim()
        val live = liveMember(slot)
        val initial = wires.associate { it.index to it.initial }
        // Each number reads the block once the slot is live, and its own literal until then.
        val guarded = Regex("""\b${Regex.escape(slot)}_wire_(\d+)\b""").replace(body) { m ->
            val i = m.groupValues[1].toInt()
            "($live != 0.0 ? ${m.value} : ${glslFloat(initial[i] ?: "0.0")})"
        }
        return guarded to result
    }

    private fun glslFloat(text: String) = if (text.contains('.') || text.contains('e') || text.contains('E')) text else "$text.0"

    /** Record that [box]'s [slot] compiled [source] with these [wires]; a live slot whose source changed is re-seeded from it. */
    fun compiled(box: Box, slot: String, source: String, entrypoint: String?, wires: List<StackSLDanglingWire>) {
        val s = slot(box, slot)
        val changed = s.source != source
        s.entrypoint = entrypoint
        s.numbers = wires.map { Number(it.index, it.initial.toDoubleOrNull() ?: 0.0, it) }
        if (changed) {
            s.source = source
            s.values.clear()
            // New text, new literals: a live slot's GPU numbers follow them.
            if (s.live) structures(box, slot).forEach { seed(s, it) }
            onCompiled.forEach { runCatching { it(s) } }
        }
    }

    /** Record that [box]'s [slot] compiled with its numbers baked in (too many, or not a stage that can hold them). */
    fun baked(box: Box, slot: String, source: String) {
        val s = slot(box, slot)
        val changed = s.source != source || s.numbers.isNotEmpty()
        s.source = source
        s.numbers = emptyList()
        s.values.clear()
        if (changed) onCompiled.forEach { runCatching { it(s) } }
    }

    /** The structures [box]'s modules hold for [slot]'s block, one per module that declares it. */
    fun structures(box: Box, slot: String): List<Structure> = moduleStructures(box).mapNotNull { it[blockName(slot)] }

    /** Every live-uniform map of the modules [box] owns, found through the resolver Field registers when it builds them. */
    var moduleStructures: (Box) -> List<Map<String, Structure>> = { emptyList() }

    /** Number [index] of [box]'s [slot] as the GPU sees it: what was set, else its literal. */
    fun value(box: Box, slot: String, index: Int): Double? {
        val s = slots(box)[slot] ?: return null
        return s.values[index] ?: s.numbers.firstOrNull { it.index == index }?.initial
    }

    /** Make [box]'s [slot] live — every member seeded from what was set or the literals, `foo_live` on — without changing a number. */
    fun live(box: Box, slot: String) {
        val s = slot(box, slot)
        s.live = true
        structures(box, slot).forEach { seed(s, it) }
    }

    /** Turn number [index] of [box]'s [slot] to [value], on every module that holds the slot, live from now on. */
    fun set(box: Box, slot: String, index: Int, value: Double) {
        val s = slot(box, slot)
        s.values[index] = value
        if (!s.live) live(box, slot)
        else structures(box, slot).forEach { it[member(slot, index)] = value.toFloat() }
    }

    /** Put every number of [box]'s [slot] back to its literal. */
    fun reset(box: Box, slot: String) {
        val s = slots(box)[slot] ?: return
        s.values.clear()
        if (s.live) structures(box, slot).forEach { seed(s, it) }
    }

    private fun seed(s: Slot, structure: Structure) {
        s.numbers.forEach { n -> structure[member(s.name, n.index)] = (s.values[n.index] ?: n.initial).toFloat() }
        structure[liveMember(s.name)] = 1f
    }

    /**
     * A module has just made its uniform structures: each numbers block's `foo_live` is written 0, so its
     * numbers read as their literals. A uniform buffer starts as whatever memory it was given; left
     * unwritten, `foo_live` was garbage, and a slot nobody had touched drew black.
     */
    fun created(structures: Map<String, Structure>) = structures.forEach { (name, structure) ->
        if (isNumbersBlock(name, structure.members.map { it.name })) structure[liveMember(name.removeSuffix("_numbers"))] = 0f
    }

    /**
     * A module of [box] (null when its owner is not known) was rebuilt, and the generic carry-over has
     * already run ([afield.four.modules.ModuleRebuild]): it moved every value and keyframe binding of a
     * numbers block that still fits, and dropped the rest. This only fills in after it, for a live slot:
     * a block the generic carry had nothing for is seeded whole; a carried block gets, for each number
     * it left without a value or binding, what was set on the slot or its literal. Nothing the generic
     * carry put there is overwritten.
     */
    fun carry(box: Box?, rebuild: afield.four.modules.ModuleRebuild.Rebuild) {
        val b = box ?: return
        rebuild.new.forEach { (name, structure) ->
            if (!isNumbersBlock(name, structure.members.map { it.name })) return@forEach
            val s = slots(b)[name.removeSuffix("_numbers")]?.takeIf { it.live } ?: return@forEach
            if (!rebuild.carried(name)) {
                seed(s, structure)
                return@forEach
            }
            fun held(member: String) = structure.bindingFor(member) != null || structure.valueFor(member) != null
            s.numbers.forEach { n ->
                val member = member(s.name, n.index)
                if (!held(member)) structure[member] = (s.values[n.index] ?: n.initial).toFloat()
            }
            if (!held(liveMember(s.name))) structure[liveMember(s.name)] = 1f
        }
    }
}
