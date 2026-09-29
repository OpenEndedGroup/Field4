package afield.four.buffers

import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import afield.four.device.CommandBuffer
import afield.four.modules.Introspected
import afield.four.modules.layoutSizeInBytes
import afield.four.modules.memberTypeKey
import afield.four.modules.memberTypeName
import afield.logger.lprintln
import afield.four.util.Vec2h
import afield.four.util.Vec3h
import afield.four.util.Vec4h
import afield.four.util.packHalf1x16
import afield.four.util.packHalf2x16
import afield.stdlib.NeedsStartingValue
import afield.stdlib.linalg.*
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.reflect.KProperty

interface HasUniform<T> : () -> T {
    fun evaluateUniform(): T

    override fun invoke() = evaluateUniform()
}

/**
 * Marker for uniforms that progress over time and need to keep being written
 * until they report completion.
 */
interface ProgressingUniform {
    val isComplete: Boolean
}

interface WritesToBuffer {
    fun writeTo(b : ByteBuffer, baseOffset : Int)
}

class MissingRefException(override val message: String) : Exception(message)

fun <T : Structure> HasBuffer.asStructure(structure: T, members: List<Introspected.Member>): T {
    structure.setMembers(members)
    structure.proxyTo(this)
    return structure
}

fun <T : Structure> BufferParameters.asStructure(structure: T, members: List<Introspected.Member>): T =
    buffer.asStructure(structure, members)

/**
 * Sets a push-constant value by name, or invokes [notFound] if [name] isn't a known offset.
 * Shared by the `FromStructure`/`BoundFromStructure` delegates on [Structure]'s various hosts
 * (`Mesh`, `MeshMesh`, `ComputeMesh`, `RayTracingDispatch`, and their groups), which otherwise
 * each hand-rolled this same offset-check-then-write shape.
 */
fun <T> Structure.setPushConstant(name: String, value: T, notFound: () -> Nothing) {
    if (offsets.containsKey(name)) set(name, value as Any) else notFound()
}

/** Reads a push-constant value by name, or invokes [notFound] if [name] isn't a known offset. */
fun <T> Structure.getPushConstant(name: String, notFound: () -> Nothing): T =
    if (offsets.containsKey(name)) get(name) else notFound()

/** Binds a live push-constant supplier by name, or invokes [notFound] if [name] isn't a known offset. */
fun Structure.bindPushConstant(name: String, value: Function0<*>, notFound: () -> Nothing) {
    if (offsets.containsKey(name)) bind(name, value) else notFound()
}

/** Reads a bound push-constant supplier by name, or invokes [notFound] if [name] isn't a known offset. */
@Suppress("UNCHECKED_CAST")
fun <T> Structure.getBoundPushConstant(name: String, notFound: () -> Nothing): () -> T =
    if (offsets.containsKey(name)) bindings[name] as () -> T else notFound()

/**
 * Binds [value] as a push constant on every element of a group, initializing each element's
 * offsets on first use via [ensureInitialized]. Throws (via [notFound], given the offending
 * element) if some element in the group doesn't have [name].
 */
fun <E> Iterable<E>.bindPushConstantAcrossGroup(
    name: String,
    value: Function0<*>,
    push: (E) -> Structure,
    ensureInitialized: (E) -> Unit,
    notFound: (E) -> Nothing,
) {
    forEach { element ->
        ensureInitialized(element)
        push(element).bindPushConstant(name, value) { notFound(element) }
    }
}

/**
 * Finds the first element of a group that has [name] bound, initializing each element's offsets
 * on first use via [ensureInitialized]. Throws [notFound] if no element has it.
 */
@Suppress("UNCHECKED_CAST")
fun <E, T> Iterable<E>.firstBoundPushConstant(
    name: String,
    push: (E) -> Structure,
    ensureInitialized: (E) -> Unit,
    notFound: () -> Nothing,
): () -> T {
    forEach { element ->
        ensureInitialized(element)
        val s = push(element)
        if (s.offsets.containsKey(name)) return s.bindings[name] as () -> T
    }
    notFound()
}

@Suppress("UNCHECKED_CAST")
open class Structure(
    var members: List<Introspected.Member> = emptyList(),
    private val baseOffset: Int = 0,
    private val pathPrefix: String = "",
    private val root: Structure? = null
) : BufferWriteable, BufferReadable, HasBuffer {


    class FromStructure<T> {
        operator fun setValue(inside: Structure, property: KProperty<*>, any: T) {
            inside.set(property.name, any as Any)
        }

        operator fun getValue(inside: Structure, property: KProperty<*>): T {
            return inside.get(property.name) as T
        }
    }

    var nullPointerChecks: MutableMap<String, Boolean> = mutableMapOf()
    var offsets = mutableMapOf<String, Int>()
    var sizes = mutableMapOf<String, Int>()
    var pointers = mutableMapOf<String, Boolean>()

    // structures elements either have live bindings (evaluated on demand) or values
    val bindings: MutableMap<String, Function0<*>> = root?.bindings ?: mutableMapOf()
    val values: MutableMap<String, Any> = root?.values ?: mutableMapOf()
    var allBinding : (() -> WritesToBuffer)? = null

    /**
     * Bumped by every change a write has to carry ([set], [bind], [bindAll], a carry-over). A per-frame
     * uniform is a [FrameBufferGroup] with one buffer per swapchain image, and a change has to reach
     * each of them: [writtenGeneration] records, per buffer slot, the generation that slot last got, so
     * [needsWrite] is per slot. A single `dirty` flag cleared after writing the current image's buffer
     * left the others holding the old value, and frames alternated between the two.
     */
    @Volatile
    private var generation = 0L
    private val writtenGeneration = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    // How many slots the buffer this structure is written into has — the swapchain image count for a
    // per-frame block; 1 until a write says otherwise.
    @Volatile
    private var slotCount = 1

    /**
     * Whether some buffer slot may still hold older values than this structure — setting it true is
     * [markDirty]; setting it false records every slot as current.
     */
    var dirty: Boolean
        get() {
            val r = rootStructure()
            return (0 until r.slotCount).any { r.writtenGeneration.getOrDefault(it, 0L) != r.generation }
        }
        set(value) {
            val r = rootStructure()
            if (value) markDirty() else (0 until r.slotCount).forEach { r.writtenGeneration[it] = r.generation }
        }

    /**
     * Whether buffer [slot] needs this structure written into it: it has live bindings (evaluated per
     * write), a whole-block binding, or a change newer than the last write to that slot.
     */
    fun needsWrite(slot: Int): Boolean {
        val r = rootStructure()
        return r.bindings.isNotEmpty() || r.allBinding != null ||
            r.writtenGeneration.getOrDefault(slot, 0L) != r.generation
    }

    /**
     * Write this structure into [target]'s buffer for the current swapchain image, if that buffer is
     * stale ([needsWrite]). The one path a module's per-frame uniform blocks are written by.
     */
    fun writeIfStale(target: FrameBufferGroup, cmd: CommandBuffer?, autowireReferences: (String) -> () -> HasBuffer) {
        val slots = maxOf(1, target.buffers.size)
        writeSlotIfStale(target.renderer.currentImage % slots, slots, cmd, autowireReferences) { body -> target.mapAndWrite_bytes(body) }
    }

    /** [writeIfStale] for an explicit [slot] of [slots] and writer — what [writeIfStale] and a test share. */
    internal fun writeSlotIfStale(
        slot: Int,
        slots: Int,
        cmd: CommandBuffer?,
        autowireReferences: (String) -> () -> HasBuffer,
        mapAndWrite: (ByteBuffer.() -> Unit) -> Unit,
    ): Boolean {
        val r = rootStructure()
        r.slotCount = slots
        if (!needsWrite(slot)) return false
        // Record the generation this write carries, read before writing: a set() that lands while the
        // write runs bumps it again and the slot stays stale.
        val carried = r.generation
        mapAndWrite { r.writeToBuffer(this, cmd, autowireReferences) }
        r.writtenGeneration.merge(slot, carried) { a, b -> maxOf(a, b) }
        return true
    }

    var proxying: FrameBufferGroup? = null
    private var proxyBuffer: HasBuffer? = null

    private fun rootStructure(): Structure = root?.rootStructure() ?: this

    private fun storageKey(property: String): String =
        if (pathPrefix.isBlank()) property else "$pathPrefix.$property"

    private fun markDirty() {
        generation++
        root?.markDirty()
    }

    fun setMembers(members: List<Introspected.Member>): Structure {
        this.members = members
        offsets = members.associate { it.name to baseOffset + it.offset }.toMutableMap()
        sizes = members.associate { it.name to it.layoutSizeInBytes() }.toMutableMap()
        pointers = members.associate {
            it.name to it.physicalPointer
        }.toMutableMap()
        return this
    }

    /**
     * Set [property] to [value], held until the next write to the buffer.
     *
     * Once this structure has a layout, [property] must be one of its members and [value] must fit the
     * member's reflected type ([refusalFor]); otherwise nothing is stored, and the executing box is
     * warned on the lines of its code that name [property]. Both used to be stored anyway — an unknown
     * member with a stdout `println`, a mistyped one with nothing at all — and the mistyped one was then
     * written by its Kotlin type over whatever came next in the block. A structure with no layout yet
     * (a mesh's push constants before its module is known) stores what it is given, as before.
     */
    operator fun set(property: String, value: Any) {
        val key = storageKey(property)
        if (members.isNotEmpty()) {
            val member = members.firstOrNull { it.name == property }
            val refusal = if (member == null)
                "`$property` is not in this block — the shader declares ${members.joinToString { "`${it.name}`" }.ifEmpty { "nothing" }}"
            else member.refusalFor(value)
            if (refusal != null) {
                refuseSet(property, "$refusal; the value was not stored")
                return
            }
        }
        markDirty()
        (value as? NeedsStartingValue<*>)?.startAt(bindings[key] ?: values[key])
        values[key] = value
        bindings.remove(key)
    }

    /**
     * Where a write this structure refuses is reported (see [refusalFor]). A module points its uniform
     * structures at its error handler, which puts the message on the box; unset, it goes to the log.
     */
    var reportTo: ((String) -> Unit)? = null

    // One report per distinct refusal: a value set every frame would otherwise report every frame.
    private val reported: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    private fun refuseSet(property: String, message: String) {
        val box = afield.RunScope.executingBox()
        if (!rootStructure().reported.add("set:${box?.id}:$message")) return
        lprintln("warning,graphics", message)
        if (box != null) afield.stdlib.markShaderReferenceLines(box, mapOf(property to message))
    }

    private fun refuseWrite(key: String, why: String) {
        val root = rootStructure()
        if (!root.reported.add("write:$key:$why")) return
        val message = "Not writing `$key` to the shader: $why. The value was set for a different interface; " +
            "set one of the type the shader now declares."
        root.reportTo?.let { runCatching { it(message) } } ?: lprintln("warning,graphics", message)
    }

    /**
     * Take over [old]'s values and bindings wherever they still fit this structure's layout — the
     * value-carrying half of a full shader reload. A plain value is kept when its member still exists and
     * the value fits the member's new type; a binding (a lambda, whose result cannot be judged without
     * running it) when its member exists with the same type as before. Returns one line per thing
     * dropped, naming it as `[blockName].member`.
     */
    internal fun carryValuesFrom(old: Structure, blockName: String): List<String> {
        val dropped = mutableListOf<String>()
        for ((key, value) in old.values.toMap()) {
            val member = memberAtPath(members, key)
            val why = if (member == null) "no longer in the shader" else member.refusalFor(value)
            if (why == null) values[key] = value else dropped += "`$blockName.$key` dropped: $why"
        }
        for ((key, binding) in old.bindings.toMap()) {
            val member = memberAtPath(members, key)
            val was = memberAtPath(old.members, key)
            when {
                member == null -> dropped += "`$blockName.$key` (bound) dropped: no longer in the shader"
                was != null && memberTypeKey(member) != memberTypeKey(was) ->
                    dropped += "`$blockName.$key` (bound) dropped: now ${memberTypeName(member)}, was ${memberTypeName(was)}"
                else -> bindings[key] = binding
            }
        }
        if (old.allBinding != null) {
            if (old.members == members) allBinding = old.allBinding
            else dropped += "`$blockName` whole-block binding dropped: the layout changed"
        }
        if (values.isNotEmpty() || bindings.isNotEmpty() || allBinding != null) markDirty()
        return dropped
    }

    fun bind(property: String, value: Function0<*>) {
        markDirty()
        val key = storageKey(property)
        (value as? NeedsStartingValue<*>)?.startAt(bindings[key] ?: values[key])
        bindings[key] = value
    }

    fun bindAll(value : () -> WritesToBuffer){
        markDirty()
        allBinding = value
    }

    operator fun <T> get(property: String) = (bindings[storageKey(property)]?.invoke() ?: values[storageKey(property)]) as T

    /**
     * The live supplier for [property] if it is *bound*, or null if it is held as a plain value or is
     * not held at all — [get] without invoking the binding, for a caller that wants the supplier itself.
     * Honours this structure's nested path, which reading [bindings] directly does not.
     */
    fun bindingFor(property: String): Function0<*>? = bindings[storageKey(property)]

    /**
     * The plain value for [property] if it is held as one, or null if it is bound or absent — the other
     * half of [bindingFor]. A bound property reads null here even though [get] returns something.
     */
    fun valueFor(property: String): Any? = values[storageKey(property)]

    fun setProxyFor(p: FrameBufferGroup) {
        proxyTo(p)
    }

    /**
     * Forwards this structure's buffer access to [p] and returns this structure
     * for chaining.
     *
     * When [p] is a [FrameBufferGroup], it is also recorded as the active proxy
     * target for direct member writes.
     */
    fun proxyTo(p: HasBuffer): Structure {
        rootStructure().proxyBuffer = p
        if (p is FrameBufferGroup) rootStructure().proxying = p
        return this
    }

    fun <T : Structure> childStructure(
        property: String,
        builder: (List<Introspected.Member>, Int, String, Structure) -> T
    ): T {
        val member = members.firstOrNull { it.name == property }
            ?: throw IllegalArgumentException("Can't find '$property' in this Structure, options are ${members.map { it.name }}")
        val nested = member.type.fold({ null }, { it })
            ?: throw IllegalArgumentException("'$property' is not a nested structure")
        return builder(nested, baseOffset + member.offset, storageKey(property), rootStructure()).also {
            it.setMembers(nested)
        }
    }

    override fun mapAndWrite(builder: FloatBuffer.() -> Unit): Structure {
        val target = rootStructure().proxyBuffer
        when (target) {
            is FrameBufferGroup -> target.mapAndWrite(builder)
            is VulkanBuffer -> target.mapAndWrite(builder)
            is BufferWriteable -> target.mapAndWrite(builder)
            else -> null
        }?.let {
            return this
        } ?: throw IllegalArgumentException("This Structure is not bound to a writable Buffer")
        return this
    }


    fun write(name: String, builder: FloatBuffer.() -> Unit) {
        rootStructure().proxying?.let {
            val m = offsets[name]
                ?: throw IllegalArgumentException("Can't find '$name' in this Structure, options are ${offsets.keys}")
            it.mapAndWrite {
                this.position(m)
                builder()
            }
        } ?: throw IllegalArgumentException("This Structure is not bound to any Buffer")
    }

    override fun mapAndWrite_bytes(builder: ByteBuffer.() -> Unit): Structure {
        val target = rootStructure().proxyBuffer
        when (target) {
            is FrameBufferGroup -> target.mapAndWrite_bytes(builder)
            is VulkanBuffer -> target.mapAndWrite_bytes(builder)
            is BufferWriteable -> target.mapAndWrite_bytes(builder)
            else -> null
        } ?: throw IllegalArgumentException("This Structure is not bound to a writable Buffer")
        return this
    }

    override fun mapAndWrite_ints(builder: IntBuffer.() -> Unit): Structure {
        val target = rootStructure().proxyBuffer
        when (target) {
            is FrameBufferGroup -> target.mapAndWrite_ints(builder)
            is VulkanBuffer -> target.mapAndWrite_ints(builder)
            is BufferWriteable -> target.mapAndWrite_ints(builder)
            else -> null
        } ?: throw IllegalArgumentException("This Structure is not bound to a writable Buffer")
        return this
    }

    override fun clean(hostReading: Boolean, cmd: CommandBuffer?): Structure {
        flushIfNeeded(cmd)
        return this
    }

    fun primeBufferReferences(cmd: CommandBuffer, autowireReferences: (String) -> () -> HasBuffer) {
        nullPointerChecks = pointers.filter { it.value }.toMutableMap()
        members.forEach { primeMember(it, cmd, pathPrefix, autowireReferences) }
        if (nullPointerChecks.isNotEmpty()) {
            nullPointerChecks.keys.toList().forEach { name ->
                val buffer = autowireReferences(name)
                bindings[name] = { buffer }
                primeValue(buffer, cmd, name)
            }
        }
    }

    private fun primeMember(
        member: Introspected.Member,
        cmd: CommandBuffer,
        prefix: String,
        autowireReferences: (String) -> () -> HasBuffer
    ) {
        val key = if (prefix.isBlank()) member.name else "$prefix.${member.name}"
        val raw = bindings[key]?.invoke() ?: values[key]
        raw?.let { primeValue(it, cmd, key) }
        if (!member.physicalPointer) {
            member.type.fold(
                {},
                { nested -> nested.forEach { primeMember(it, cmd, key, autowireReferences) } }
            )
        }
    }

    private fun primeValue(v: Any, cmd: CommandBuffer, name: String) {
        when (v) {
            is HasUniform<*> -> {
                val resolved = v.evaluateUniform() ?: return
                primeValue(resolved, cmd, name)
            }
            is HasBuffer -> {
                val address = v.getGPUAddress(cmd)
                if (address <= 0) {
                    throw IllegalArgumentException(" buffer for member ${name} does not have a gpu address. Make sure you call .addressable() on it's buffer parameters")
                }
                nullPointerChecks.remove(name)
            }
        }
    }


    fun writeToBuffer(f: ByteBuffer, cmd : CommandBuffer?, autowireReferences: (String) -> () -> HasBuffer) {
        nullPointerChecks = pointers.filter { it.value }.toMutableMap()
        var keepDirty = false
        allBinding?.let {
            it().writeTo(f, 0)
        }
        keepDirty = writeMembersToBuffer(members, baseOffset, pathPrefix, f, cmd) || keepDirty
        if (nullPointerChecks.isNotEmpty()) {

            nullPointerChecks.keys.forEach { name ->
                println("WARNING: trying to autowire $name (and ultimately ${nullPointerChecks.keys}) into push constants")
                val buffer = autowireReferences(name)
                bindings[name] = { buffer }
                writeToBuffer(offsets[name]!!, buffer, f, cmd, name = name)
            }
        }
        // A progressing uniform still animating: every slot has to be written again.
        if (keepDirty) markDirty()
    }

    private fun writeMembersToBuffer(
        members: List<Introspected.Member>,
        baseOffset: Int,
        prefix: String,
        f: ByteBuffer,
        cmd: CommandBuffer?
    ): Boolean {
        var keepDirty = false
        members.forEach { member ->
            val key = if (prefix.isBlank()) member.name else "$prefix.${member.name}"
            val raw = bindings[key]?.invoke() ?: values[key]
            if (raw != null) {
                val resolved = if (raw is HasUniform<*>) {
                    if (raw is ProgressingUniform && !raw.isComplete) keepDirty = true
                    raw.evaluateUniform()
                } else raw
                val refusal = resolved?.let { member.refusalFor(it) }
                if (refusal != null) refuseWrite(key, refusal)
                else if (resolved != null) writeToBuffer(baseOffset + member.offset, resolved, f, cmd, name = key)
            } else if (!member.physicalPointer) {
                member.type.fold(
                    {},
                    { nested ->
                        keepDirty = writeMembersToBuffer(nested, baseOffset + member.offset, key, f, cmd) || keepDirty
                    }
                )
            }
        }
        return keepDirty
    }

    internal fun writeToBuffer(offset: Int, v: Any, f: ByteBuffer, cmd : CommandBuffer?, name: String = "unnamed") {
        when (v) {
            is Float -> f.putFloat(offset, v)
            is Int -> f.putInt(offset, v)
            is Long -> f.putLong(offset, v)
            is Vector2f -> v.get(offset, f)
            is Vector3f -> v.get(offset, f)
            is Vector4f -> v.get(offset, f)
            is Matrix2f -> v.get(offset, f)
            is Matrix3f -> v.get(offset, f)
            is Matrix4f -> v.get(offset, f)
            is Quaternionf -> {
                f.putFloat(offset, v.x)
                f.putFloat(offset + 4, v.y)
                f.putFloat(offset + 8, v.z)
                f.putFloat(offset + 12, v.w)
            }
            is Vec2h -> {
                val t = v.v
                f.putInt(offset, packHalf2x16(t.x, t.y))
            }
            is Vec3h -> {
                val t = v.v
                f.putInt(offset, packHalf2x16(t.x, t.y))
                f.putInt(offset + 4, packHalf1x16(t.z))
            }
            is Vec4h -> {
                val t = v.v
                f.putInt(offset, packHalf2x16(t.x, t.y))
                f.putInt(offset + 4, packHalf2x16(t.z, t.w))
            }
            // 8-lane fp16 vector: its own writer emits the 16-byte uvec4 layout (two halfs per lane).
            is Vector8h -> v.get(offset, f)
            is UInt -> f.putInt(offset, v.toInt())
            is ByteBuffer -> {
                var was = f.position()
                f.position(offset)
                f.put(v)
                f.position(was)
            }
            is WritesToBuffer -> v.writeTo(f, offset)
            is HasUniform<*> -> writeToBuffer(offset, v.evaluateUniform()!!, f, cmd, name)

            is HasBuffer -> {
                val address = v.getGPUAddress(cmd ?: throw IllegalStateException("writing buffer reference `$name` needs a command buffer"))
                if (address <= 0) {
                    throw IllegalArgumentException(" buffer for member ${name} does not have a gpu address. Make sure you call .addressable() on it's buffer parameters")
                }
                nullPointerChecks.remove(name)
                writeToBuffer(offset, address, f, cmd, name)
            }

            else -> println("Warning: unknown type: $v")
        }
    }

    override fun mapAndRead(builder: FloatBuffer.() -> Unit): Structure {
        val target = rootStructure().proxyBuffer
        when (target) {
            is FrameBufferGroup -> target.mapAndRead(builder)
            is VulkanBuffer -> target.mapAndRead(builder)
            is BufferReadable -> target.mapAndRead(builder)
            else -> null
        } ?: throw IllegalArgumentException("This Structure is not bound to a readable Buffer")
        return this
    }

    override fun mapAndRead_bytes(builder: ByteBuffer.() -> Unit): Structure {
        val target = rootStructure().proxyBuffer
        when (target) {
            is FrameBufferGroup -> target.mapAndRead_bytes(builder)
            is VulkanBuffer -> target.mapAndRead_bytes(builder)
            is BufferReadable -> target.mapAndRead_bytes(builder)
            else -> null
        } ?: throw IllegalArgumentException("This Structure is not bound to a readable Buffer")
        return this
    }

    override fun mapAndRead_ints(builder: IntBuffer.() -> Unit): Structure {
        val target = rootStructure().proxyBuffer
        when (target) {
            is FrameBufferGroup -> target.mapAndRead_ints(builder)
            is VulkanBuffer -> target.mapAndRead_ints(builder)
            is BufferReadable -> target.mapAndRead_ints(builder)
            else -> null
        } ?: throw IllegalArgumentException("This Structure is not bound to a readable Buffer")
        return this
    }

    override fun getBuffer(): Long =
        rootStructure().proxyBuffer?.getBuffer()
            ?: throw IllegalArgumentException("This Structure is not bound to any Buffer")

    override fun getGPUAddress(cmd: CommandBuffer): Long {
        flushIfNeeded(cmd)
        return rootStructure().proxyBuffer?.getGPUAddress(cmd)
            ?: throw IllegalArgumentException("This Structure is not bound to any Buffer")
    }

    private fun flushIfNeeded(cmd: CommandBuffer?) {
        val r = rootStructure()
        if (cmd == null) return
        val target = r.proxyBuffer
        val slots = if (target is FrameBufferGroup) maxOf(1, target.buffers.size) else 1
        val slot = if (target is FrameBufferGroup) target.renderer.currentImage % slots else 0
        r.writeSlotIfStale(slot, slots, cmd, { name ->
            throw MissingRefException("No autowire resolver is available for '$name' while writing a Structure-backed buffer")
        }) { body -> r.mapAndWrite_bytes(body) }
    }
}
