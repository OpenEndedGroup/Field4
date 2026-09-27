@file:afield.CompletionCategory("Graphics")

package afield.four.modules

import afield.four.buffers.Structure
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Told when a module pointer swaps in a rebuilt module (a full shader reload), with the old and new
 * modules' live uniform structures, before the old one is dropped.
 *
 * A reload builds every uniform structure afresh, so whatever was set on the old ones is gone unless
 * a hook carries it. `stack.auto` number blocks are carried this way (`StackAutoNumbers.carry`); an
 * in-place pipeline update keeps its structures and does not come here.
 */
object ModuleRebuild {
    /** Called with the pointer (a `ModulePointer`, `ComputeModulePointer` or `MeshModulePointer`) and the structures before and after. */
    val hooks = CopyOnWriteArrayList<(pointer: Any, old: Map<String, Structure>, new: Map<String, Structure>) -> Unit>()

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

    internal fun rebuilt(pointer: Any, old: Map<String, Structure>, new: Map<String, Structure>) =
        hooks.forEach { runCatching { it(pointer, old, new) } }
}
