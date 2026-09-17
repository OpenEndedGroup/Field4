package afield.four.fabricated

import afield.four.Renderer
import afield.four.modules.fromContext
import afield.four.targets.FrameHistory
import afield.four.targets.PingPongRenderToTexture
import afield.four.targets.RenderToTexture
import afield.four.texture.HasImageView
import afield.stdlib.linalg.Matrix4f

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
 * The g-buffer colour attachment holding per-pixel motion vectors.
 *
 * Fixed by `StandardRenderer`'s g-buffer layout — `colorFormats` is
 * `[hdr, normal, position, motion]` and its fragment stage writes
 * `outMotionVectors` at `location = 3`. Named here so the binding site does not carry a bare 3.
 */
const val GBUFFER_MOTION_ATTACHMENT = 3

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
        // Reuse the ping-pong pair a previous run of this same declaration left behind, when the
        // shape still matches. For a persistent pass the target *is* the state, so this is the
        // difference between re-running the box and keeping your fluid, and re-running it and
        // silently starting from an empty one while leaking the old pair.
        val carried = (postStack[pass.name]?.target as? PingPongRenderToTexture)?.takeIf { old ->
            old.width == pass.shape.width &&
                    old.height == pass.shape.height &&
                    (old.colorFormats.firstOrNull() ?: old.parameters.format) == pass.shape.format
        }
        pass.index to (carried ?: PingPongRenderToTexture(
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
        ))
    }

    fun textureFor(ref: FabricatePassRef): RenderToTexture = when (ref) {
        FabricatePassRef.ChainInput -> chainInput
        is FabricatePassRef.Pass -> requireNotNull(effects[ref.index]) {
            "pass '${ref.name}' has not been built yet"
        }.target
        is FabricatePassRef.PreviousFrame -> persistentTargets.getValue(ref.index)
        // The planner refuses an external reference as a pass's source, so this is unreachable
        // rather than merely unused: an external texture binds a view, never a target.
        is FabricatePassRef.External -> throw IllegalStateException(
            "'${ref.reference}' binds as a view, not as a target — the planner should have refused it " +
                    "as a source before the build got here"
        )
    }

    /**
     * Refs that bind a *view* rather than a target: a previous-frame ref binds the half of the
     * ping-pong pair that is not being written, and an external ref binds a texture the renderer
     * owns outright.
     *
     * The g-buffer's motion vectors are colour attachment **3**, and its depth prefers the sampled
     * view, which is what the shadow-map and depth-blit paths in `standardRenderer` already do.
     */
    fun viewFor(ref: FabricatePassRef): HasImageView? = when (ref) {
        is FabricatePassRef.PreviousFrame -> persistentTargets.getValue(ref.index).otherImageView
        is FabricatePassRef.External -> when (ref.reference) {
            FabricatePassChain.GBUFFER_MOTION -> {
                require(gBuffer.width == chainInput.width && gBuffer.height == chainInput.height) {
                    "a pass asked for '${FabricatePassChain.GBUFFER_MOTION}', whose vectors are in the " +
                            "g-buffer's UV space at ${gBuffer.width}x${gBuffer.height}, but this chain's " +
                            "input is ${chainInput.width}x${chainInput.height} — sampling one by the " +
                            "other's coordinates reprojects to the right fraction of the wrong picture, " +
                            "which renders and merely looks soft"
                }
                requireNotNull(gBuffer.imageViewArray.getOrNull(GBUFFER_MOTION_ATTACHMENT)) {
                    "the g-buffer has ${gBuffer.imageViewArray.size} colour attachments, and the motion " +
                            "vectors are attachment $GBUFFER_MOTION_ATTACHMENT"
                }
            }
            FabricatePassChain.GBUFFER_DEPTH -> requireNotNull(
                gBuffer.depthSampledImageView ?: gBuffer.depthImageView
            ) {
                "the g-buffer exposes no sampleable depth view, so '${FabricatePassChain.GBUFFER_DEPTH}' " +
                        "cannot be bound"
            }
            else -> throw IllegalArgumentException("unknown external reference '${ref.reference}'")
        }
        else -> null
    }

    // One FrameHistory per chain, not per pass. Every pass that declares `frameHistory` binds
    // against this same object, and `FrameHistory.capture` is idempotent for a frame id it already
    // stands at — so whichever pass's push constant is written first advances the history and the
    // rest see the same answer. Per-pass histories would make each pass reproject against the
    // *previous pass* instead of the previous frame, and the ghost that produces looks like a
    // slightly soft image rather than like a bug.
    //
    // Fresh per build, deliberately. A rebuild carries the persistent *target* (see above) but not
    // the history, so the frame after a rebuild reports unavailable and the accumulation restarts
    // once. That is the right answer rather than a limitation: the thing that was rebuilt may be the
    // shader, the resolution or the camera wiring, and none of those leave last frame comparable.
    val history = FrameHistory()

    // Scratch, reused every frame. The binding below runs inside the render loop, where allocating a
    // matrix per pass per frame is a real cost and an avoidable one.
    val viewProjection = Matrix4f()

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

        if (pass.frameHistory) {
            // Bound, not set: `Structure.bind` keeps the supplier and re-invokes it every time the
            // push-constant buffer is written, which is once per pass per frame. A value written
            // here at build time would be last frame's camera forever.
            //
            // `capture` is called from inside the binding rather than from a frame callback on
            // purpose. There is no "start of frame" hook that is guaranteed to run before the first
            // pass writes its constants, and a capture that lands *after* a pass has already read
            // the history is a one-frame-stale reprojection that looks like a slightly wrong motion
            // vector. Calling it from the binding makes the first reader the one that advances it,
            // and idempotence makes every later reader agree.
            effect.setPushBinding("reprojection") {
                history.capture(
                    frameId = renderer.frameCounter,
                    viewProjection = currentViewProjection(viewProjection),
                    width = chainInput.width,
                    height = chainInput.height,
                    epoch = frameHistoryEpoch
                )
                history.reprojection
            }
            effect.setPushBinding("historyParams") {
                history.capture(
                    frameId = renderer.frameCounter,
                    viewProjection = currentViewProjection(viewProjection),
                    width = chainInput.width,
                    height = chainInput.height,
                    epoch = frameHistoryEpoch
                )
                history.params()
            }
        }
    }

    return FabricateBuiltChain(plan, effects.map { requireNotNull(it) }, chainInput, history)
}

/** A chain that has been planned and built: the plan, the effects, and live push-constant access. */
class FabricateBuiltChain(
    val plan: FabricatePassChainPlan,
    val effects: List<FabricatePostEffect>,
    val input: RenderToTexture,
    /**
     * The chain's frame history — shared by every pass that declared
     * [FabricatePassSpec.frameHistory], and inert if none did.
     *
     * Exposed because the two things a caller needs from it are not expressible as a push constant:
     * [FrameHistory.invalidate], for a cut this chain cannot see, and [FrameHistory.available], which
     * is the only honest way to answer "is my accumulation warm yet" from the CPU.
     */
    val history: FrameHistory = FrameHistory()
) {
    /** The chain's final image. */
    val output: RenderToTexture get() = effects.last().target

    /** Pass names, in run order. */
    fun names(): List<String> = effects.map { it.name }

    /** The named pass, or null if this chain has no such pass. */
    fun pass(name: String): FabricatePostEffect? = effects.firstOrNull { it.name == name }

    /**
     * Set a push constant on **every pass in the chain that declares it**.
     *
     * ```kotlin
     * fluid["splatCenter"] = vec(u, v)          // a value, written now
     * fluid["splatCenter"] = { vec(u, v) }      // a supplier, re-read every frame
     * ```
     *
     * By name across the whole chain rather than per pass, because that is how the library's specs
     * are written: `stableFluid`'s `force` and `dye` passes deliberately share `splatCenter`,
     * `splatVelocity` and `splatMass` so that one assignment is one gesture. A pass whose shader does
     * not declare the name is skipped, which is the same tolerance [setPush] has and what lets a
     * `push` map be a superset of any one shader's constants.
     *
     * **A lambda binds rather than sets.** `Structure.bind` keeps the supplier and re-invokes it
     * every time the buffer is written, so `fluid["splatCenter"] = { vec(mouseU, mouseV) }` tracks
     * two `var`s from then on with nothing to call per frame. That is the difference between driving
     * a parameter and poking it, and it is why this takes `Any` and branches on `Function0` instead
     * of overloading: an overload pair of `Any` and `() -> Any` is ambiguous for a bare lambda.
     */
    operator fun set(pushName: String, value: Any) {
        effects.forEach { effect ->
            val push = effect.quad(effect.name)?.push ?: return@forEach
            if (!push.offsets.containsKey(pushName)) return@forEach
            if (value is Function0<*>) push.bind(pushName) { value() as Any } else push[pushName] = value
        }
    }

    /** The target a named pass renders into, or null if there is no such pass. */
    fun targetOf(name: String): RenderToTexture? = pass(name)?.target

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

    /** Names of the passes that receive frame history — the chain's reprojecting passes. */
    fun historyNames(): List<String> = plan.historyPasses.map { it.name }

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
        val effect = pass(passName) ?: return false
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

/**
 * *Bind* one push constant on a post effect's fullscreen quad to a supplier re-read every frame.
 *
 * The difference from [setPush] is the difference between driving a parameter and poking it:
 * `Structure.bind` keeps the lambda and re-invokes it every time the push-constant buffer is
 * written, which for a post pass is once per frame. Anything derived from the camera has to come
 * through here — a matrix written once at build time is that frame's camera forever, and the
 * resulting image is not obviously wrong, it is a correct render of a stale viewpoint.
 *
 * @return true if the shader declares [name] and the supplier was bound.
 */
fun FabricatePostEffect.setPushBinding(name: String, value: () -> Any): Boolean {
    val push = quad(this.name)?.push ?: return false
    if (!push.offsets.containsKey(name)) return false
    push.bind(name) { value() }
    return true
}
