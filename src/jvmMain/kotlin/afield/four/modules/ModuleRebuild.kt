@file:afield.CompletionCategory("Graphics")

package afield.four.modules

import afield.four.buffers.Structure
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Told when a module pointer swaps in a rebuilt module (a full shader reload), with the old and new
 * modules' live uniform structures, before the old one is dropped.
 *
 * **Order.** The generic carry-over (`ReloadCarryOver.finish`) runs first and is authoritative: it has
 * already moved every value and binding that still fits the new reflection, and dropped (and reported)
 * the rest. Only then are [hooks] called, with a [Rebuild] saying which new structures were carried and
 * from which old one. A hook fills in what the generic carry could not know — a `stack.auto` numbers
 * block with no old counterpart is seeded from its slot — and must not re-apply what it carried: before
 * 2026-09-29 the `stack.auto` hook copied every old value and binding again after the generic carry,
 * resurrecting bindings the carry had dropped as retyped and re-setting values it had refused. Both
 * steps run in [ReloadCarryOver.finishAndNotify], the one place the order is written down.
 *
 * An in-place pipeline update keeps its structures and does not come here.
 */
object ModuleRebuild {
    /**
     * One rebuild: the [pointer] (a `ModulePointer`, `ComputeModulePointer`, `MeshModulePointer` or
     * `RayTracingModulePointer`), the structures [old] and [new], and [carriedFrom] — for each new
     * structure the generic carry filled, the name of the old structure it was filled from (itself, or
     * the old name of a renamed block).
     */
    class Rebuild(
        val pointer: Any,
        val old: Map<String, Structure>,
        val new: Map<String, Structure>,
        val carriedFrom: Map<String, String>,
    ) {
        /** Whether the generic carry filled the new structure [name] from an old one. */
        fun carried(name: String) = name in carriedFrom
    }

    /** Called after the generic carry-over, once per rebuild; see the ordering above. */
    val hooks = CopyOnWriteArrayList<(Rebuild) -> Unit>()

    /**
     * Called when Kotlin reaches a module's uniform structure by name (`module.look`), with the pointer,
     * the name and the structure — how a `stack.auto` numbers block goes live when code or a keyframe
     * target takes hold of it.
     */
    val accessed = CopyOnWriteArrayList<(pointer: Any, name: String, structure: Structure) -> Unit>()

    internal fun access(pointer: Any, name: String, structure: Structure): Structure {
        accessed.forEach { runCatching { it(pointer, name, structure) } }
        return structure
    }

    /**
     * Called when a module has just made its uniform structures, before anything has written them. A
     * uniform buffer starts as whatever memory it was given, so a member that must read as zero until
     * set — a `stack.auto` numbers block's `foo_live` — is written here.
     */
    val onCreated = CopyOnWriteArrayList<(structures: Map<String, Structure>) -> Unit>()

    internal fun created(structures: Map<String, Structure>) = onCreated.forEach { runCatching { it(structures) } }

    internal fun rebuilt(rebuild: Rebuild) = hooks.forEach { runCatching { it(rebuild) } }
}
