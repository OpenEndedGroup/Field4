package afield.four.fabricated

import afield.four.Renderer
import afield.four.Target
import afield.four.targets.RenderTarget
import afield.four.util.Window
import java.util.WeakHashMap

/**
 * The renderer's off-screen passes as a named, ordered list, and the one place that order is
 * written into a `RenderTarget`.
 *
 * A [StandardRenderer.Bundle] holds its five built-in passes as five separate fields, which is a
 * set rather than a sequence. [FabricateFrameGraph] takes a bundle and makes it a sequence:
 * g-buffer, shadow map, ambient occlusion, lighting, transparent composite, then one entry for
 * each effect currently in the bundle's [FabricatePostStack]. Every built-in name is a constant on
 * the companion, and `passNameFor` supplies the two that arrive as post effects rather than as
 * bundle fields, motion blur and anti-aliasing; anything else is named after its effect. So
 * `insertAfter("my-pass", PASS_LIGHTING, target)` works without knowing how the bundle was built.
 *
 * `attachTo` is where the order stops being advisory. A `RenderTarget` renders its `preTargets`
 * before its own pass and in map order, so attaching removes and re-adds every pass by name, which
 * leaves `preTargets` iterating in the graph's order. It also remembers, per target, which names
 * this graph put there last time and drops the ones that have since gone, while a pass some other
 * code added under a name this graph never used is left alone. Attaching a second time is
 * therefore a resync rather than a duplicate.
 *
 * The graph is a view. `StandardRenderer.Bundle.frameGraph()` builds a fresh one on each call, it
 * does not own the targets it names, and it does not stop a box reaching past it to a
 * `RenderToTexture` directly.
 */

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
