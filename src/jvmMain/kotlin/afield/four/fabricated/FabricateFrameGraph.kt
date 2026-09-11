package afield.four.fabricated

import afield.four.Renderer
import afield.four.Target
import afield.four.targets.RenderTarget
import afield.four.util.Window
import java.util.WeakHashMap

/**
 * Ordered, inspectable pass contract for [StandardRenderer].
 *
 * It is thin: it does not hide the underlying RenderToTexture/Target objects or
 * prevent direct pass manipulation. Instead it gives the fabricated renderer a stable ordered
 * surface for built-in passes and user-inserted post/utility passes before syncing that order into
 * a RenderTarget's preTargets map.
 */
class FabricateFrameGraph private constructor(
    private val graph: FabricatePassGraph<Target>
) {
    constructor(bundle: StandardRenderer.Bundle) : this(
        FabricatePassGraph(
            listOf(
                PASS_GBUFFER to bundle.gBuffer,
                PASS_SHADOW to bundle.shadowMap,
                PASS_AO to bundle.ao,
                PASS_LIGHTING to bundle.lighting,
                PASS_TRANSPARENCY to bundle.transparentComposite
            ) + bundle.postStack.effects().map { effect -> passNameFor(effect) to effect.target }
        )
    )

    private val attachedNames = WeakHashMap<RenderTarget, MutableSet<String>>()

    fun passNames(): List<String> = graph.names()

    fun passes(): List<Pair<String, Target>> = graph.entries()

    operator fun get(name: String): Target? = graph[name]

    fun replace(name: String, target: Target): FabricateFrameGraph {
        graph.replace(name, target)
        return this
    }

    fun append(name: String, target: Target): FabricateFrameGraph {
        graph.addLast(name, target)
        return this
    }

    fun insertBefore(name: String, before: String, target: Target): FabricateFrameGraph {
        graph.insertBefore(name, before, target)
        return this
    }

    fun insertAfter(name: String, after: String, target: Target): FabricateFrameGraph {
        graph.insertAfter(name, after, target)
        return this
    }

    fun remove(name: String): Target? = graph.remove(name)

    fun attachTo(renderer: Renderer, windowIndex: Int = 0): RenderTarget =
        attachTo(renderer.renderTargetFor(windowIndex))

    fun attachTo(renderer: Renderer, window: Window): RenderTarget =
        attachTo(renderer, renderer.windowIndex(window))

    fun attachTo(target: RenderTarget): RenderTarget {
        val currentNames = graph.names().toSet()
        val previouslyAttached = attachedNames.getOrPut(target) { linkedSetOf() }

        (previouslyAttached - currentNames).forEach { stale ->
            target.preTargets.remove(stale)
        }

        graph.entries().forEach { (name, pass) ->
            target.preTargets.remove(name)
            target.preTargets[name] = pass
        }

        previouslyAttached.clear()
        previouslyAttached.addAll(currentNames)
        return target
    }

    companion object {
        const val PASS_GBUFFER = "std-gbuffer"
        const val PASS_SHADOW = "std-shadow"
        const val PASS_AO = "std-ao"
        const val PASS_LIGHTING = "std-lighting"
        const val PASS_TRANSPARENCY = "std-transparency"
        const val PASS_MOTION_BLUR = "std-motion-blur"
        const val PASS_ANTI_ALIAS = "std-aa"

        val STANDARD_PASS_ORDER = listOf(
            PASS_GBUFFER,
            PASS_SHADOW,
            PASS_AO,
            PASS_LIGHTING,
            PASS_TRANSPARENCY,
            PASS_MOTION_BLUR,
            PASS_ANTI_ALIAS
        )

        fun passNameFor(effect: FabricatePostEffect): String =
            when (effect.kind) {
                FabricatePostEffect.Kind.MOTION_BLUR -> PASS_MOTION_BLUR
                FabricatePostEffect.Kind.FXAA -> PASS_ANTI_ALIAS
                else -> "std-post-${effect.name}"
            }
    }
}
