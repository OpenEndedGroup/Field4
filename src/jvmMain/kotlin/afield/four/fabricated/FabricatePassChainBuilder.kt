package afield.four.fabricated

import afield.four.Renderer
import afield.four.modules.fromContext
import afield.four.targets.PingPongRenderToTexture
import afield.four.targets.RenderToTexture
import afield.four.texture.HasImageView

/*
 * Materializes a planned FabricatePassChain onto the GPU, through the post stack that already
 * exists.
 *
 * The division of labour is the point. FabricatePassChain.plan decides *everything* — every
 * resolution, every format, which texture every sampler reads, which passes could share a target.
 * This file decides *nothing*; it walks the plan and calls Bundle.addShaderEffect with sizes already
 * resolved to FabricatePassSize.Absolute, so there is no second, subtly different implementation of
 * the arithmetic sitting where only a GPU can reach it. That matters more here than it usually does,
 * because a resolution bug in a pass chain renders a picture that looks *fine*.
 *
 * The passes land in the same FabricatePostStack as everything else, so FabricateFrameGraph
 * schedules them, postStack.output() is the chain's output, insertBefore/remove still work on them
 * by name, and a chain composes with addBloom and setToneMapping rather than replacing them.
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

    val effects = arrayOfNulls<FabricatePostEffect>(plan.passes.size)

    // A persistent pass's previous frame may be read by a pass that runs *before* it, so its target
    // has to exist before the walk reaches it. Allocating every ping-pong target up front is what
    // makes that reference resolvable at all; the planner already guaranteed the shapes do not
    // depend on anything the walk computes.
    val persistentTargets = plan.persistentPasses.associate { pass ->
        pass.index to PingPongRenderToTexture(
            renderer,
            FabricatePassChain.PERSISTENT_IMAGE_COUNT,
            postEffectTargetParameters(pass.name, pass.shape) {
                // A simulation field is not an image that gets redrawn from scratch; clearing it
                // every frame would be clearing the state. Every pass writing one is fullscreen, so
                // there is nothing a per-frame clear would cover that the pass does not.
                clearFirst = false
                // It does have to be cleared *once*, though, and this is what buys that:
                // PingPongRenderToTexture.initializePingImagesNow clears every image in the ring at
                // construction only if the image carries TRANSFER_DST usage, and otherwise just
                // transitions the layout and leaves the contents undefined. Frame zero of a
                // simulation then advects whatever was in that memory — which on a warm GPU is
                // often plausible-looking noise rather than an obvious failure, and which does not
                // reproduce.
                colorTransferDst = true
            }
        )
    }

    fun textureFor(ref: FabricatePassRef): RenderToTexture = when (ref) {
        FabricatePassRef.ChainInput -> chainInput
        is FabricatePassRef.Pass -> requireNotNull(effects[ref.index]) {
            "pass '${ref.name}' has not been built yet"
        }.target
        is FabricatePassRef.PreviousFrame -> persistentTargets.getValue(ref.index)
    }

    /** Previous-frame refs bind a *view*, not a target — the half of the pair not being written. */
    fun viewFor(ref: FabricatePassRef): HasImageView? = when (ref) {
        is FabricatePassRef.PreviousFrame -> persistentTargets.getValue(ref.index).otherImageView
        else -> null
    }

    plan.passes.forEach { pass ->
        val primary = pass.inputs.first()
        val extras = pass.inputs.drop(1)
        val effect = addShaderEffect(
            name = pass.name,
            fragment = pass.spec.fragment,
            renderer = renderer,
            // Already resolved by the planner. Handing Absolute back deliberately: the builder must
            // not get a second chance to compute a size.
            size = FabricatePassSize.Absolute(pass.shape.width, pass.shape.height),
            format = pass.shape.format,
            source = textureFor(primary.from),
            extraInputs = extras.filter { viewFor(it.from) == null }
                .associate { it.samplerName to textureFor(it.from) },
            mipLevels = pass.shape.mipLevels,
            pingPong = pass.persistent,
            sourceView = viewFor(primary.from),
            extraViews = extras.mapNotNull { input -> viewFor(input.from)?.let { input.samplerName to it } }.toMap(),
            existingTarget = persistentTargets[pass.index]
        )
        effects[pass.index] = effect
        pass.spec.push.forEach { (name, value) -> effect.setPush(name, value) }
    }

    return FabricateBuiltChain(plan, effects.map { requireNotNull(it) }, chainInput)
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
     * The ping-pong target behind a [FabricatePassSpec.persistent] pass, or null if [name] is not one.
     *
     * This is the handle for everything outside the chain that wants the *state* rather than the
     * picture: `latestImageView` to sample the field from another module, and the ordinary
     * `RenderToTexture` readback path to pull it back to the CPU. `latestImageView`, not
     * `thisImageView` — outside the target's own render, "this" is the half about to be overwritten.
     */
    fun stateOf(name: String): PingPongRenderToTexture? = targetOf(name) as? PingPongRenderToTexture

    /** Persistent pass names, in run order — the chain's state fields. */
    fun stateNames(): List<String> = plan.persistentPasses.map { it.name }

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
