package afield.four.fabricated

import afield.four.targets.RenderToTexture
import afield.four.modules.ModulePointer

/**
 * Ordered post-processing stack for Fabricate, intentionally close to Three.js'
 * EffectComposer model: one source texture enters the stack, named passes run in order,
 * and the final pass is the image to present.
 */
class FabricatePostStack(
    val input: RenderToTexture,
    initial: Iterable<FabricatePostEffect> = emptyList()
) {
    private val graph = FabricatePassGraph<FabricatePostEffect>()

    init {
        initial.forEach { effect -> append(effect) }
    }

    fun names(): List<String> = graph.names()

    fun effects(): List<FabricatePostEffect> = graph.entries().map { it.second }

    operator fun get(name: String): FabricatePostEffect? = graph[name]

    fun append(effect: FabricatePostEffect): FabricatePostStack {
        graph.addLast(effect.name, effect)
        return this
    }

    fun insertBefore(effect: FabricatePostEffect, before: String): FabricatePostStack {
        graph.insertBefore(effect.name, before, effect)
        return this
    }

    fun insertAfter(effect: FabricatePostEffect, after: String): FabricatePostStack {
        graph.insertAfter(effect.name, after, effect)
        return this
    }

    fun replace(name: String, effect: FabricatePostEffect): FabricatePostStack {
        require(name == effect.name) { "replacement effect name '${effect.name}' must match '$name'" }
        graph.replace(name, effect)
        return this
    }

    fun remove(name: String): FabricatePostEffect? = graph.remove(name)

    fun output(): RenderToTexture = effects().lastOrNull()?.target ?: input
}

data class FabricatePostEffect(
    val name: String,
    val target: RenderToTexture,
    val module: ModulePointer,
    val kind: Kind = Kind.SHADER
) {
    enum class Kind {
        SHADER,
        MOTION_BLUR,
        FXAA,
        CUSTOM
    }
}
