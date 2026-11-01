package afield.graphics.shader

import afield.graphics.render.pointer

import afield.graphics.render.AccelerationStructure

/**
 * What changed in a shader's interface across a full reload, binding by binding and member by member.
 *
 * [shaderInterfaceTransition] and [graphicsShaderInterfaceTransition] compare two [Introspection]s and
 * classify each binding as one [ShaderBindingChange]; the result names what was kept, added, removed,
 * renamed, resized or retyped, so a reload can re-apply the values that still fit. Tested in
 * `ShaderInterfaceTransitionTest`.
 */

/**
 * The kinds of thing a shader's interface binds, as a full reload compares them. [label] is how a
 * transition summary names one: "uniform block `Light` grew 16→32 bytes".
 */
enum class ShaderBindingKind(val label: String) {
    UNIFORM_BLOCK("uniform block"),
    STORAGE_BUFFER("buffer"),
    TEXTURE("texture"),
    IMAGE("image"),
    ACCELERATION_STRUCTURE("acceleration structure"),
    PUSH_CONSTANT("push constant"),
}

/**
 * What a full reload did to one binding (or one member of a block), old interface to new.
 *
 * [RENAMED] is the same set, binding and layout under a new name. [RESIZED] and [RETYPED] are the same
 * name with a different size, or the same size with a different layout (a member's type or offset, or a
 * moved set/binding). A reload treats those two alike: the buffer is reallocated and the values that
 * still fit are re-applied.
 */
enum class ShaderBindingChange { KEPT, ADDED, REMOVED, RENAMED, RESIZED, RETYPED }

/** A member of a uniform block, storage buffer or push-constant block, old to new, by name. */
data class ShaderMemberTransition(
    /** The member's name, which is what the two interfaces were matched on. */
    val name: String,
    /**
     * `KEPT`, `ADDED`, `REMOVED` or `RETYPED`: the four a member can be. Moving to a different
     * offset counts as `RETYPED`, and members are never classified as renamed or resized.
     */
    val change: ShaderBindingChange,
    /**
     * The member's type in the old interface, written the way a shader author would: `vec3`,
     * `float[]`, `struct`, `pointer`. Null when the member is new.
     */
    val oldType: String? = null,
    /** Its type in the new interface; null when it was removed. */
    val newType: String? = null,
)

/**
 * One binding's fate across a reload. [name] is its name in the new interface (the old one for
 * [ShaderBindingChange.REMOVED]); [oldName] differs from it only for [ShaderBindingChange.RENAMED].
 * Sizes are in bytes, for blocks. [members] lists the members of a kept-by-name block whose type or
 * offset changed, or that came or went.
 */
data class ShaderBindingTransition(
    /** Which sort of binding this is, and so how a summary line names it. */
    val kind: ShaderBindingKind,
    /** What the reload did to it. */
    val change: ShaderBindingChange,
    /**
     * The binding's name in the new interface. A removed binding has no new name, so this carries
     * the old one.
     */
    val name: String,
    /** Its name in the old interface, equal to the new name except for a rename. */
    val oldName: String = name,
    /**
     * The old size in bytes, for a uniform block or storage buffer. Null for the other kinds, which
     * have no size of their own, and for a block that is only in the new interface.
     */
    val oldSize: Int? = null,
    /** The new size in bytes, on the same terms; null for a block that is only in the old interface. */
    val newSize: Int? = null,
    /**
     * The old type, for the kinds a type describes: a texture (with its array count, as
     * `sampler2D[4]`), an image, an acceleration structure, or a push-constant member. Blocks say
     * their sizes and members instead, so both type fields stay null for them.
     */
    val oldType: String? = null,
    /** The new type, on the same terms. */
    val newType: String? = null,
    /**
     * For a block kept under the same name, only those of its members that did not stay the same.
     * Empty for every other case, a renamed block included — a rename is only recognised when the
     * layout matches, so there is nothing to list.
     */
    val members: List<ShaderMemberTransition> = emptyList(),
) {
    /** Kept, with no member changed. */
    val quiet: Boolean get() = change == ShaderBindingChange.KEPT && members.all { it.change == ShaderBindingChange.KEPT }
}

/**
 * Old interface to new, for one full reload of a module: every binding classified, and the stages that
 * came or went. What [shaderInterfaceTransition] computes and what the reload carries buffers and values
 * across by — see `dev_docs/stdlib_behaviour_changes.md`, "Full shader reload diffs the interface".
 */
data class ShaderInterfaceTransition(
    /**
     * Every binding either interface declares, each appearing once, grouped in a fixed order:
     * uniform blocks, storage buffers, textures, images, acceleration structures, then
     * push-constant members. Kept bindings are in here too; [changes] is the filtered view.
     */
    val bindings: List<ShaderBindingTransition>,
    /**
     * Stages the new module has and the old one did not. A ray-tracing module's stage list varies
     * with what it declares, and a repeated stage is numbered (`closesthit#2`), so adding a second
     * hit shader arrives here.
     */
    val stagesAdded: List<String> = emptyList(),
    /** Stages the old module had and the new one does not. */
    val stagesRemoved: List<String> = emptyList(),
) {
    /** Everything that is not simply kept. */
    val changes: List<ShaderBindingTransition> get() = bindings.filterNot { it.quiet }

    /** Nothing changed that a person needs to hear about. */
    val quiet: Boolean get() = changes.isEmpty() && stagesAdded.isEmpty() && stagesRemoved.isEmpty()

    /** The binding of [kind] called [name] in the new interface, if it has one. */
    fun of(kind: ShaderBindingKind, name: String): ShaderBindingTransition? =
        bindings.firstOrNull { it.kind == kind && it.name == name && it.change != ShaderBindingChange.REMOVED }

    /** The old name of the [kind] binding the new interface calls [name]: [name] unless it was renamed. */
    fun oldNameOf(kind: ShaderBindingKind, name: String): String = of(kind, name)?.oldName ?: name

    /**
     * The Kotlin names the box's preamble exposed for the old interface that no longer mean what they
     * did, each with why: a removed or renamed binding, a removed or retyped block member or push
     * constant. Code that uses one was written against the old preamble; the next run compiles against
     * the new one.
     */
    val staleNames: Map<String, String>
        get() = buildMap {
            for (b in bindings) {
                when (b.change) {
                    ShaderBindingChange.REMOVED -> put(b.oldName, "${b.kind.label} `${b.oldName}` was removed")
                    ShaderBindingChange.RENAMED -> put(b.oldName, "${b.kind.label} `${b.oldName}` is now `${b.name}`")
                    ShaderBindingChange.RETYPED, ShaderBindingChange.RESIZED ->
                        if (b.kind == ShaderBindingKind.PUSH_CONSTANT)
                            put(b.name, "push constant `${b.name}` is now ${b.newType} (was ${b.oldType})")
                    else -> {}
                }
                for (m in b.members) when (m.change) {
                    ShaderBindingChange.REMOVED -> putIfAbsent(m.name, "`${b.name}.${m.name}` was removed")
                    ShaderBindingChange.RETYPED -> putIfAbsent(m.name, "`${b.name}.${m.name}` is now ${m.newType} (was ${m.oldType})")
                    else -> {}
                }
            }
        }

    /**
     * One line saying what the reload did to the interface — "uniform block `Light` grew 16→32 bytes,
     * buffer reallocated; push constant `tint` removed" — or `""` when nothing changed.
     */
    fun summary(): String {
        val parts = mutableListOf<String>()
        stagesAdded.forEach { parts += "stage `$it` added" }
        stagesRemoved.forEach { parts += "stage `$it` removed" }
        for (b in changes) {
            val what = "${b.kind.label} `${b.name}`"
            val buffer = b.kind == ShaderBindingKind.UNIFORM_BLOCK
            parts += when (b.change) {
                ShaderBindingChange.ADDED -> "$what added" + if (buffer) ", zero-initialised" else ""
                ShaderBindingChange.REMOVED -> "$what removed"
                ShaderBindingChange.RENAMED -> "${b.kind.label} `${b.oldName}` renamed to `${b.name}`" +
                    if (buffer) ", buffer and values carried over" else ""
                ShaderBindingChange.RESIZED -> {
                    val verb = if ((b.newSize ?: 0) > (b.oldSize ?: 0)) "grew" else "shrank"
                    "$what $verb ${b.oldSize}→${b.newSize} bytes" + memberClause(b) +
                        if (buffer) ", buffer reallocated" else ""
                }
                ShaderBindingChange.RETYPED ->
                    if (b.kind == ShaderBindingKind.PUSH_CONSTANT || b.members.isEmpty() && b.newType != null)
                        "$what is now ${b.newType} (was ${b.oldType})"
                    else "$what changed layout" + memberClause(b) + if (buffer) ", buffer reallocated" else ""
                ShaderBindingChange.KEPT -> "$what kept" + memberClause(b)
            }
        }
        return parts.joinToString("; ")
    }

    private fun memberClause(b: ShaderBindingTransition): String {
        val changed = b.members.filter { it.change != ShaderBindingChange.KEPT }
        if (changed.isEmpty()) return ""
        return " (" + changed.joinToString(", ") { m ->
            when (m.change) {
                ShaderBindingChange.ADDED -> "`${m.name}` added"
                ShaderBindingChange.REMOVED -> "`${m.name}` removed"
                ShaderBindingChange.RETYPED -> "`${m.name}` ${m.oldType}→${m.newType}"
                else -> "`${m.name}` ${m.change.name.lowercase()}"
            }
        } + ")"
    }
}

/** [shaderInterfaceTransition] for a graphics (vertex + fragment) module. */
fun graphicsShaderInterfaceTransition(
    oldVertex: Introspection, oldFragment: Introspection,
    newVertex: Introspection, newFragment: Introspection,
): ShaderInterfaceTransition = shaderInterfaceTransition(
    listOf("vertex" to oldVertex, "fragment" to oldFragment),
    listOf("vertex" to newVertex, "fragment" to newFragment),
)

/**
 * Compare two module interfaces, stage by stage, and classify every binding — see
 * [ShaderInterfaceTransition]. Bindings are merged across a module's stages by name, as the module
 * itself merges them (one uniform per name, one push-constant layout per member name).
 *
 * A binding present only in the old interface and one present only in the new are paired as a rename
 * when they are the same kind at the same set and binding with the same layout — for blocks, the same
 * size and members; for push constants, the same offset and type.
 */
fun shaderInterfaceTransition(
    old: List<Pair<String, Introspection>>,
    new: List<Pair<String, Introspection>>,
): ShaderInterfaceTransition {
    val oldStages = old.map { it.first }
    val newStages = new.map { it.first }
    val oldI = old.map { it.second }
    val newI = new.map { it.second }
    val bindings = mutableListOf<ShaderBindingTransition>()

    bindings += diffBlocks(ShaderBindingKind.UNIFORM_BLOCK, oldI.flatMap { it.ubos }.distinctBy { it.name }, newI.flatMap { it.ubos }.distinctBy { it.name })
    bindings += diffBlocks(ShaderBindingKind.STORAGE_BUFFER, oldI.flatMap { it.ssbo }.distinctBy { it.name }, newI.flatMap { it.ssbo }.distinctBy { it.name })
    bindings += diffSimple(ShaderBindingKind.TEXTURE, oldI.flatMap { it.textures }.distinctBy { it.name }, newI.flatMap { it.textures }.distinctBy { it.name }) {
        (it as Introspected.Texture).type + if (it.arrayCount > 0) "[${it.arrayCount}]" else ""
    }
    bindings += diffSimple(ShaderBindingKind.IMAGE, oldI.flatMap { it.images }.distinctBy { it.name }, newI.flatMap { it.images }.distinctBy { it.name }) {
        (it as Introspected.Image).type
    }
    bindings += diffSimple(
        ShaderBindingKind.ACCELERATION_STRUCTURE,
        oldI.flatMap { it.accelerationStructures }.distinctBy { it.name },
        newI.flatMap { it.accelerationStructures }.distinctBy { it.name }
    ) { (it as Introspected.AccelerationStructure).type }
    bindings += diffPushConstants(
        oldI.flatMap { i -> i.pushConstants.flatMap { it.members } }.distinctBy { it.name },
        newI.flatMap { i -> i.pushConstants.flatMap { it.members } }.distinctBy { it.name },
    )

    return ShaderInterfaceTransition(
        bindings,
        stagesAdded = newStages.filter { it !in oldStages },
        stagesRemoved = oldStages.filter { it !in newStages },
    )
}

/** Same size and the same members at the same offsets with the same types — what a buffer can be carried across. */
internal fun sameBlockLayout(a: Introspected.UBO, b: Introspected.UBO): Boolean =
    a.size == b.size && blockMembersSignature(a.members) == blockMembersSignature(b.members)

private fun diffBlocks(kind: ShaderBindingKind, old: List<Introspected.UBO>, new: List<Introspected.UBO>): List<ShaderBindingTransition> {
    val out = mutableListOf<ShaderBindingTransition>()
    val newByName = new.associateBy { it.name }
    val oldByName = old.associateBy { it.name }
    for (o in old) {
        val n = newByName[o.name] ?: continue
        val members = diffMembers(o.members, n.members)
        val change = when {
            o.size != n.size -> ShaderBindingChange.RESIZED
            o.set != n.set || o.binding != n.binding || !sameBlockLayout(o, n) -> ShaderBindingChange.RETYPED
            else -> ShaderBindingChange.KEPT
        }
        out += ShaderBindingTransition(kind, change, n.name, oldSize = o.size, newSize = n.size, members = members.filter { it.change != ShaderBindingChange.KEPT })
    }
    val gone = old.filter { it.name !in newByName }.toMutableList()
    val arrived = new.filter { it.name !in oldByName }
    for (n in arrived) {
        val was = gone.firstOrNull { it.set == n.set && it.binding == n.binding && sameBlockLayout(it, n) }
        if (was != null) {
            gone.remove(was)
            out += ShaderBindingTransition(kind, ShaderBindingChange.RENAMED, n.name, oldName = was.name, oldSize = was.size, newSize = n.size)
        } else out += ShaderBindingTransition(kind, ShaderBindingChange.ADDED, n.name, newSize = n.size)
    }
    gone.forEach { out += ShaderBindingTransition(kind, ShaderBindingChange.REMOVED, it.name, oldSize = it.size) }
    return out
}

private fun diffSimple(
    kind: ShaderBindingKind,
    old: List<Introspected.Bindable>,
    new: List<Introspected.Bindable>,
    type: (Introspected.Bindable) -> String,
): List<ShaderBindingTransition> {
    val out = mutableListOf<ShaderBindingTransition>()
    val newByName = new.associateBy { it.name }
    val oldByName = old.associateBy { it.name }
    for (o in old) {
        val n = newByName[o.name] ?: continue
        val same = o.set == n.set && o.binding == n.binding && type(o) == type(n)
        out += ShaderBindingTransition(kind, if (same) ShaderBindingChange.KEPT else ShaderBindingChange.RETYPED, n.name, oldType = type(o), newType = type(n))
    }
    val gone = old.filter { it.name !in newByName }.toMutableList()
    for (n in new.filter { it.name !in oldByName }) {
        val was = gone.firstOrNull { it.set == n.set && it.binding == n.binding && type(it) == type(n) }
        if (was != null) {
            gone.remove(was)
            out += ShaderBindingTransition(kind, ShaderBindingChange.RENAMED, n.name, oldName = was.name, oldType = type(was), newType = type(n))
        } else out += ShaderBindingTransition(kind, ShaderBindingChange.ADDED, n.name, newType = type(n))
    }
    gone.forEach { out += ShaderBindingTransition(kind, ShaderBindingChange.REMOVED, it.name, oldType = type(it)) }
    return out
}

private fun diffPushConstants(old: List<Introspected.Member>, new: List<Introspected.Member>): List<ShaderBindingTransition> {
    val kind = ShaderBindingKind.PUSH_CONSTANT
    val out = mutableListOf<ShaderBindingTransition>()
    val newByName = new.associateBy { it.name }
    val oldByName = old.associateBy { it.name }
    for (o in old) {
        val n = newByName[o.name] ?: continue
        val same = o.offset == n.offset && memberTypeKey(o) == memberTypeKey(n)
        out += ShaderBindingTransition(kind, if (same) ShaderBindingChange.KEPT else ShaderBindingChange.RETYPED, n.name, oldType = memberTypeName(o), newType = memberTypeName(n))
    }
    val gone = old.filter { it.name !in newByName }.toMutableList()
    for (n in new.filter { it.name !in oldByName }) {
        val was = gone.firstOrNull { it.offset == n.offset && memberTypeKey(it) == memberTypeKey(n) }
        if (was != null) {
            gone.remove(was)
            out += ShaderBindingTransition(kind, ShaderBindingChange.RENAMED, n.name, oldName = was.name, oldType = memberTypeName(was), newType = memberTypeName(n))
        } else out += ShaderBindingTransition(kind, ShaderBindingChange.ADDED, n.name, newType = memberTypeName(n))
    }
    gone.forEach { out += ShaderBindingTransition(kind, ShaderBindingChange.REMOVED, it.name, oldType = memberTypeName(it)) }
    return out
}

private fun diffMembers(old: List<Introspected.Member>, new: List<Introspected.Member>): List<ShaderMemberTransition> {
    val newByName = new.associateBy { it.name }
    val out = old.map { o ->
        val n = newByName[o.name]
        when {
            n == null -> ShaderMemberTransition(o.name, ShaderBindingChange.REMOVED, oldType = memberTypeName(o))
            n.offset != o.offset || memberTypeKey(n) != memberTypeKey(o) ->
                ShaderMemberTransition(o.name, ShaderBindingChange.RETYPED, memberTypeName(o), memberTypeName(n))
            else -> ShaderMemberTransition(o.name, ShaderBindingChange.KEPT, memberTypeName(o), memberTypeName(n))
        }
    }.toMutableList()
    val oldNames = old.map { it.name }.toSet()
    new.filter { it.name !in oldNames }.forEach { out += ShaderMemberTransition(it.name, ShaderBindingChange.ADDED, newType = memberTypeName(it)) }
    return out
}

/** A member's type as a person would write it: `vec3`, `float[]`, `struct`, `pointer`. */
internal fun memberTypeName(m: Introspected.Member): String {
    val base = if (m.physicalPointer) "pointer" else m.type.fold({ it.name }, { "struct" })
    return if (m.arrayStride > 0) "$base[]" else base
}

/** [memberTypeName] with a nested struct's layout and an array's stride spelled out, for comparing two members. */
internal fun memberTypeKey(m: Introspected.Member): String {
    val base = if (m.physicalPointer) "pointer"
    else m.type.fold({ it.name }, { nested -> "struct{" + nested.joinToString(",") { "${it.name}:${memberTypeKey(it)}@${it.offset}" } + "}" })
    return if (m.arrayStride > 0) "$base[${m.arrayStride}]" else base
}

private fun blockMembersSignature(members: List<Introspected.Member>): String =
    members.sortedBy { it.offset }.joinToString(";") { "${it.offset}:${it.name}:${memberTypeKey(it)}" }
