package afield.four.modules

import afield.four.buffers.BufferParameters
import afield.four.buffers.Structure
import afield.four.device.UpdatesDescriptorSet
import afield.logger.lprintln
import org.lwjgl.system.MemoryUtil

/** Point a module's uniform structure's refused writes ([Structure.reportTo]) at the module's error handler. */
internal fun Structure.reportingTo(parameters: Parameters): Structure {
    reportTo = { message -> parameters.errorHandler(message, null) }
    return this
}

/**
 * What a module pointer's full `reload` did to the interface: the [transition] old→new (null when the
 * pointer could not compare, for want of an introspection) and [notes], one per value that could not be
 * carried to the new module. Read by the box-side reload (`reloadShader` and siblings) to say it.
 */
data class ModuleReloadReport(val transition: ShaderInterfaceTransition?, val notes: List<String> = emptyList())

/**
 * The carry-over policy of a full reload, shared by the four module pointers. Given the
 * [ShaderInterfaceTransition] for the reload:
 *
 * - a **kept** or **renamed** uniform block keeps its buffer and its values (a rename is the same set,
 *   binding and layout, so the bytes still mean the same thing);
 * - a **resized or retyped** block gets a new, zeroed buffer, and the values set on the old one are
 *   re-applied where they still type-check against the new reflection ([Structure.carryValuesFrom]);
 * - an **added** block starts zeroed;
 * - a buffer the module allocated that the new module no longer uses — a **removed** block, or the old
 *   buffer of a resized one — is retired through the renderer's deferred queue.
 *
 * Only buffers the module allocated for itself are carried by layout, zeroed or retired. One somebody
 * supplied — found by name in the context, or handed back by a custom resolver — is resolved again the
 * way it was the first time, and never freed here.
 *
 * Until 2026-09-29 the graphics pointer carried a block's buffer by *name* alone, so a grown block kept
 * its old, smaller buffer; the compute, mesh and ray-tracing pointers carried nothing, so every reload
 * reset their uniforms; and no pointer carried values except through `stack.auto`'s own hook.
 */
internal object ReloadCarryOver {
    /**
     * For a pointer's uniform-block resolver: the previous module's buffer for [request] when it can be
     * kept (same layout, module-allocated), else what [original] resolves.
     */
    fun ubo(
        previous: Map<String, UpdatesDescriptorSet>,
        transition: ShaderInterfaceTransition?,
        request: BufferParameters,
        original: (BufferParameters) -> BufferParameters?,
    ): BufferParameters? {
        val oldName = transition?.oldNameOf(ShaderBindingKind.UNIFORM_BLOCK, request.name) ?: request.name
        val prev = previous[oldName]
        val source = prev?.source
        val oldBlock = prev?.binding as? Introspected.UBO
        val newBlock = request.source
        if (source != null && source.allocatedByModule && source.result != null &&
            oldBlock != null && newBlock != null && sameBlockLayout(oldBlock, newBlock)
        ) return source
        return original(request)
    }

    /**
     * Resolve [request] through [resolver] and remember whether the buffer is the module's own: the
     * resolver handed back the request itself, and nothing has been allocated for it yet.
     */
    fun resolve(request: BufferParameters, resolver: (BufferParameters) -> BufferParameters?): BufferParameters? {
        val resolved = resolver(request) ?: return null
        if (resolved === request && request.result == null) request.allocatedByModule = true
        return resolved
    }

    /**
     * What [finish] did: [notes], the lines worth telling the person (values that did not survive), and
     * [carriedFrom], for each new structure it filled, the old structure's name.
     */
    class Finished(val notes: List<String>, val carriedFrom: Map<String, String>)

    /**
     * [finish], then the [ModuleRebuild] hooks — in that order, which is the contract: the generic carry
     * decides what survives, and a hook only fills in what it left (see [ModuleRebuild]). Every module
     * pointer's reload finishes through here. Returns [Finished.notes].
     */
    fun finishAndNotify(
        pointer: Any,
        transition: ShaderInterfaceTransition?,
        previousUniforms: Collection<UpdatesDescriptorSet>,
        nextUniforms: Map<String, UpdatesDescriptorSet>,
        previousStructures: Map<String, Structure>,
        nextStructures: Map<String, Structure>,
    ): List<String> {
        val finished = finish(transition, previousUniforms, nextUniforms, previousStructures, nextStructures)
        ModuleRebuild.rebuilt(ModuleRebuild.Rebuild(pointer, previousStructures, nextStructures, finished.carriedFrom))
        return finished.notes
    }

    /**
     * Finish a reload that installed a new module: carry values between the structures, zero the
     * uniform blocks allocated for this reload, and retire the old module's own buffers the new one
     * does not use.
     */
    fun finish(
        transition: ShaderInterfaceTransition?,
        previousUniforms: Collection<UpdatesDescriptorSet>,
        nextUniforms: Map<String, UpdatesDescriptorSet>,
        previousStructures: Map<String, Structure>,
        nextStructures: Map<String, Structure>,
    ): Finished {
        val notes = mutableListOf<String>()
        val carriedFrom = LinkedHashMap<String, String>()
        val previousSources = previousUniforms.mapNotNull { it.source }
        val nextSources = nextUniforms.values.mapNotNull { it.source }

        // Zero the blocks this reload allocated, before anything writes them: a fresh allocation is
        // whatever memory it was given, and a shader reading a member nobody has set yet should read 0.
        nextUniforms.values.forEach { u ->
            val source = u.source ?: return@forEach
            if (u.binding !is Introspected.UBO || isStorage(u) || !source.allocatedByModule) return@forEach
            if (previousSources.any { it === source }) return@forEach
            zero(source)
        }

        nextStructures.forEach { (name, structure) ->
            val kind = if (isStorage(nextUniforms[name])) ShaderBindingKind.STORAGE_BUFFER else ShaderBindingKind.UNIFORM_BLOCK
            val oldName = transition?.oldNameOf(kind, name) ?: name
            val old = previousStructures[oldName] ?: return@forEach
            notes += structure.carryValuesFrom(old, name)
            carriedFrom[name] = oldName
        }

        previousUniforms.forEach { u ->
            val source = u.source ?: return@forEach
            if (!source.allocatedByModule || nextSources.any { it === source }) return@forEach
            retire(source, u.binding.name)
        }
        return Finished(notes, carriedFrom)
    }

    /** An SSBO: its buffer parameters ask for storage usage. Only uniform blocks are zeroed here. */
    private fun isStorage(u: UpdatesDescriptorSet?): Boolean {
        val source = u?.source ?: return false
        return source.usage and org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT != 0 &&
            source.usage and org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT == 0
    }

    private fun zero(source: BufferParameters) {
        val group = source.result ?: return
        group.buffers.forEach { buffer ->
            runCatching {
                buffer.mapAndWrite_bytes { MemoryUtil.memSet(MemoryUtil.memAddress(this, 0), 0, capacity().toLong()) }
            }.onFailure { lprintln("warning,graphics", "could not zero the new buffer for `${source.name}`: ${it.message}") }
        }
    }

    private fun retire(source: BufferParameters, name: String) {
        val group = source.result ?: return
        // VulkanBuffer.cleanup defers destruction through the renderer's retirement queue, so a frame
        // still recording or executing with the old module finds its buffer valid.
        group.buffers.forEach { runCatching { it.cleanup() }.onFailure { e -> lprintln("warning,graphics", "retiring `$name`: ${e.message}") } }
    }
}
