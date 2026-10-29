package afield.graphics.scene.fabricated

import afield.graphics.vulkan.targets.RenderToTexture
import afield.graphics.render.ModulePointer

/**
 * The ordered post-processing stack a Fabricate pass lands in, and the record describing one pass.
 *
 * One source texture enters the stack, named passes run in order, and the last one's target is the
 * image to present; a stack with no passes presents its input unchanged. Ordering is delegated to
 * [FabricatePassGraph], so passes are addressed by name: a feature can insert before or after a pass
 * it did not add, and two features that each append a pass compose rather than overwriting one
 * another.
 *
 * [FabricatePostEffect] is what the stack stores: a name, the target the pass renders into, the
 * module holding its pipeline, and a kind tag. That tag is how [FabricateFrameGraph] recognises the
 * two passes with a fixed place in the standard pass order, motion blur and anti-aliasing; every
 * other pass is named after its effect.
 */

/**
 * Ordered post-processing stack for Fabricate, following Three.js' EffectComposer model:
 * one source texture enters the stack, named passes run in order, and the final pass is
 * the image to present.
 */
class FabricatePostStack(
    val input: RenderToTexture,
    initial: Iterable<FabricatePostEffect> = emptyList()
) {
    private val graph = FabricatePassGraph<FabricatePostEffect>()

    /** Called after every edit, so whatever has the stack attached can re-attach it. */
    internal val changeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    private fun changed() {
        effects().forEach { register(it.target, this) }
        changeListeners.forEach { it() }
    }

    init {
        initial.forEach { effect -> append(effect) }
    }

    fun names(): List<String> = graph.names()

    fun effects(): List<FabricatePostEffect> = graph.entries().map { it.second }

    operator fun get(name: String): FabricatePostEffect? = graph[name]

    fun append(effect: FabricatePostEffect): FabricatePostStack {
        graph.addLast(effect.name, effect)
        changed()
        return this
    }

    fun insertBefore(effect: FabricatePostEffect, before: String): FabricatePostStack {
        graph.insertBefore(effect.name, before, effect)
        changed()
        return this
    }

    fun insertAfter(effect: FabricatePostEffect, after: String): FabricatePostStack {
        graph.insertAfter(effect.name, after, effect)
        changed()
        return this
    }

    fun replace(name: String, effect: FabricatePostEffect): FabricatePostStack {
        require(name == effect.name) { "replacement effect name '${effect.name}' must match '$name'" }
        graph.replace(name, effect)
        changed()
        return this
    }

    fun remove(name: String): FabricatePostEffect? = graph.remove(name)?.also { changed() }

    fun output(): RenderToTexture = effects().lastOrNull()?.target ?: input

    companion object {
        /** Stacks that have held each target, weakly, so a pass that is closing can leave them. */
        private val holders = java.util.WeakHashMap<RenderToTexture, MutableSet<FabricatePostStack>>()

        private fun register(target: RenderToTexture, stack: FabricatePostStack) = synchronized(holders) {
            holders.getOrPut(target) { java.util.Collections.newSetFromMap(java.util.WeakHashMap()) }.add(stack)
        }

        /**
         * Remove every effect rendering into [target] from each stack it was added to. Each stack then
         * re-attaches to the windows it is attached to, so the closed pass leaves their frame graphs.
         * Returns how many effects were removed.
         */
        internal fun retire(target: RenderToTexture): Int {
            val stacks = synchronized(holders) { holders.remove(target)?.toList().orEmpty() }
            return stacks.sumOf { stack ->
                stack.effects().filter { it.target === target }.count { stack.remove(it.name) != null }
            }
        }
    }
}

/**
 * One pass in a [FabricatePostStack]: its name, output target, optional shader module and kind.
 * Shader effects own a module. Native effects record compute from their target and leave module null.
 * [FabricateFrameGraph] gives motion blur and anti-aliasing their reserved names in the standard
 * pass order; other effects use their own name. Native effects are excluded from shader inspection
 * and reload.
 */
data class FabricatePostEffect(
    val name: String,
    val target: RenderToTexture,
    val module: ModulePointer?,
    val kind: Kind = Kind.SHADER
) {
    init {
        require((kind==Kind.NATIVE)==(module==null)) { "Native effects have no shader module; shader effects require one" }
    }
    /** Which reserved slot in the standard pass order this pass claims, if any. */
    enum class Kind {
        SHADER,
        MOTION_BLUR,
        FXAA,
        CUSTOM,
        /** Target records a native compute pass outside a render pass. No fragment module. */
        NATIVE
    }
}
