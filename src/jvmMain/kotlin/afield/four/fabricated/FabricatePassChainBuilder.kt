package afield.four.fabricated

import afield.four.Renderer
import afield.four.modules.fromContext
import afield.four.targets.RenderToTexture

/**
 * Materializes a planned pass chain onto the GPU, through the post stack that already exists.
 *
 * The division of labour is strict. [FabricatePassChain.plan] decides *everything*: every
 * resolution, every format, which texture every sampler reads, which passes can share a target.
 * This file decides *nothing*. It walks the plan and calls
 * [StandardRenderer.Bundle.addShaderEffect] with sizes already resolved to
 * [FabricatePassSize.Absolute], so there is no second, subtly different implementation of the
 * arithmetic sitting where only a GPU can reach it. That matters more here than it usually would,
 * because a resolution bug in a pass chain renders a picture that looks *fine*.
 *
 * The passes land in the same [FabricatePostStack] as everything else, so [FabricateFrameGraph]
 * schedules them, the stack's `output()` is the chain's output, `insertBefore` and `remove` still
 * work on them by name, and a chain composes with `addBloom` and `setToneMapping` rather than
 * replacing them.
 *
 * [FabricateBuiltChain] comes back holding the plan, the built effects and the chain input, and is
 * how a push constant is retuned afterwards without a rebuild or a reallocation.
 */

/**
 * Plan [passes] against the current post-stack output and append the whole chain to it.
 *
 * The chain input is `postStack.output()` at the moment of the call, so a chain appended after
 * `addBloom` sees the bloomed image, and a chain appended to a fresh bundle sees the lit scene.
 *
 * Remember to `attachTo(renderer)` afterwards, exactly as with [StandardRenderer.Bundle.addShaderEffect]
 * — the frame graph has to re-sync before the new passes are scheduled.
 *
 * @param printReport print [FabricatePassChainPlan.report] — every pass with its resolved shape and
 *   bound samplers, and the memory totals. Cheap, and the only way to notice a chain that planned
 *   itself at the wrong resolution *before* looking at a picture that will not obviously be wrong.
 */
fun StandardRenderer.Bundle.addPassChain(
    passes: List<FabricatePassSpec>,
    renderer: Renderer = fromContext(),
    printReport: Boolean = false
): FabricateBuiltChain {
    val chainInput = postStack.output()
    val plan = FabricatePassChain.plan(
        passes = passes,
        inputWidth = chainInput.width,
        inputHeight = chainInput.height,
        inputFormat = chainInput.colorFormats.firstOrNull() ?: chainInput.parameters.format
    )
    if (printReport) println(plan.report())

    val effects = ArrayList<FabricatePostEffect>(plan.passes.size)

    fun textureFor(ref: FabricatePassRef): RenderToTexture = when (ref) {
        FabricatePassRef.ChainInput -> chainInput
        is FabricatePassRef.Pass -> effects[ref.index].target
    }

    plan.passes.forEach { pass ->
        val primary = pass.inputs.first()
        val effect = addShaderEffect(
            name = pass.name,
            fragment = pass.spec.fragment,
            renderer = renderer,
            // Already resolved by the planner. Handing Absolute back deliberately: the builder must
            // not get a second chance to compute a size.
            size = FabricatePassSize.Absolute(pass.shape.width, pass.shape.height),
            format = pass.shape.format,
            source = textureFor(primary.from),
            extraInputs = pass.inputs.drop(1).associate { it.samplerName to textureFor(it.from) },
            mipLevels = pass.shape.mipLevels
        )
        effects += effect
        pass.spec.push.forEach { (name, value) -> effect.setPush(name, value) }
    }

    return FabricateBuiltChain(plan, effects, chainInput)
}

/** A chain that has been planned and built: the plan, the effects, and live push-constant access. */
class FabricateBuiltChain(
    val plan: FabricatePassChainPlan,
    val effects: List<FabricatePostEffect>,
    val input: RenderToTexture
) {
    /** The chain's final image. */
    val output: RenderToTexture get() = effects.last().target

    /** Pass names, in run order. */
    fun names(): List<String> = effects.map { it.name }

    operator fun get(name: String): FabricatePostEffect? = effects.firstOrNull { it.name == name }

    /** The target a named pass renders into, or null if there is no such pass. */
    fun targetOf(name: String): RenderToTexture? = get(name)?.target

    /**
     * Retune a pass's push constant in place, with no rebuild and no reallocation.
     *
     * This is the live-coding half of declaring a chain: an aperture radius or a bloom threshold is a
     * number a person turns while watching, and turning it must not cost six target allocations. A
     * name the shader does not declare is ignored, so the same call is safe across shader variants.
     *
     * @return true if the pass exists and declares [pushName].
     */
    fun setPush(passName: String, pushName: String, value: Any): Boolean {
        val effect = get(passName) ?: return false
        return effect.setPush(pushName, value)
    }

    /** Sum of every pass target's color-attachment bytes, as actually allocated. */
    val allocatedBytes: Long get() = plan.unsharedBytes

    override fun toString(): String = "FabricateBuiltChain(${effects.size} passes -> ${output.width}x${output.height})"
}

/**
 * Set one push constant on a post effect's fullscreen quad.
 *
 * Names the shader does not declare are skipped rather than throwing, which is what lets a
 * [FabricatePassSpec]'s `push` map be a superset of any one shader's constants — `separableBlur` and
 * `aperture` can be driven from the same spec shape.
 *
 * @return true if the shader declares [name] and the value was written.
 */
fun FabricatePostEffect.setPush(name: String, value: Any): Boolean {
    val push = quad(this.name)?.push ?: return false
    if (!push.offsets.containsKey(name)) return false
    push[name] = value
    return true
}
