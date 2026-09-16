package afield.four.fabricated

import afield.four.texture.vkFormatBytesPerPixel

/**
 * Declarative, **heterogeneous** pass chains for [FabricatePostStack] — every pass may have its own
 * resolution, its own format, and its own named inputs.
 *
 * ### Why this exists
 *
 * [FabricatePostStack] already gives Fabricate an ordered, named, insertable pass list, and
 * [FabricateFrameGraph] already schedules it. What neither could express was a pass that is *not the
 * same shape as the one before it*. [StandardRenderer.Bundle.addShaderEffect] builds each pass's
 * target with `width = source.width; height = source.height; format = source.colorFormats.first()`,
 * and the only hook it hands the caller is the *module pipeline* `Parameters` — not `RTTParameters`.
 * So a half-resolution reduce, a downsample pyramid, or a half-float intermediate was unreachable
 * through the stack API, and the only way to get one was to own the `RenderToTexture` yourself.
 *
 * That is why a real multi-resolution effect — reduce ½, reduce ¼, a wide aperture blur on the cheap
 * ¼ image, composite back at full resolution — gets hand-wired as one box per pass instead of one
 * declaration. Not because composition was missing. Because *heterogeneity* was.
 *
 * ### What a chain is
 *
 * A [FabricatePassSpec] list. Each spec names its pass, carries its fragment shader, and says how
 * big it wants to be ([FabricatePassSize]), what format it wants, which earlier pass is its primary
 * `sourceTex`, and what *other* earlier passes it wants bound to named samplers. The chain input is
 * the texture entering the stack, referred to as [CHAIN_INPUT].
 *
 * ### Why the planner is separate, and pure
 *
 * [FabricatePassChain.plan] resolves the whole chain — sizes, formats, input references, target
 * lifetimes and target sharing — **without touching Vulkan**. Two reasons:
 *
 * 1. Target sharing is only decidable if you can see the whole chain at once. An append-one-at-a-time
 *    API cannot know that pass 1's output is dead by pass 4 and its memory is free to take, so it
 *    allocates N targets for N passes and keeps them all alive forever. Declaring the chain up front
 *    is what buys that back; see [FabricatePassChainPlan.report].
 * 2. Everything above is arithmetic and graph reachability, and arithmetic that is wrong about a
 *    resolution produces a picture that is *plausible* — a slightly soft composite, a pyramid whose
 *    bottom level is one texel off. Those do not read as bugs in a rendered image. They do read as
 *    failures in an assertion, so the planner is tested on the CPU by `FabricatePassChainTest`, and
 *    the GPU builder below is left with nothing to be clever about.
 *
 * ### Persistent passes, and the one rule they break
 *
 * Everything above describes a chain that runs once, front to back, over one frame's input. A
 * *simulation* is not that: a stable fluid's velocity field this frame is a function of the velocity
 * field last frame, and no amount of reordering within a frame produces it. So a spec may declare
 * itself [FabricatePassSpec.persistent], which materializes its target as a
 * `PingPongRenderToTexture` — two images with the roles swapped every frame — and any pass may bind
 * a sampler to a persistent pass's **previous frame** with `FabricatePassInput(..., previousFrame =
 * true)`.
 *
 * A previous-frame reference is the one reference that may point *forwards*, or at the reading pass
 * itself. That is not a loosening of the backwards-only rule; it is the same rule applied to a
 * different frame. "The velocity field as of the end of last frame" is a value that already exists
 * when the first pass of this frame runs, whatever index the pass that wrote it happens to have.
 *
 * Two constraints fall out of that, and both are checked:
 *
 * 1. **A persistent pass's size and format may not depend on its source.** Its output is read a
 *    frame before the pass that produces it runs, so there is nothing to inherit from yet. A
 *    simulation field's resolution and precision are also not things that should drift with whatever
 *    happened to feed it this frame, so the constraint is the honest one: `Absolute`,
 *    `MatchChainInput` or `FractionOfChainInput`, and an explicit format.
 * 2. **A persistent pass's target is never shared.** It has to survive the whole frame *and* the gap
 *    to the next one, so slot packing neither offers it another pass's slot nor offers its slot to
 *    anyone. That is what [FabricateTargetSlot.persistent] records, and it is why a persistent slot
 *    costs two images rather than one.
 */
object FabricatePassChain {

    /** Reference name for the texture entering the chain — the stack input, not a pass. */
    const val CHAIN_INPUT = "@input"

    /** The sampler name a pass's primary input is always bound to. */
    const val PRIMARY_SAMPLER = "sourceTex"

    /** Images a persistent pass's target holds — the ping-pong depth. */
    const val PERSISTENT_IMAGE_COUNT = 2

    /**
     * Plan [passes] against a chain input of [inputWidth] x [inputHeight] in [inputFormat].
     *
     * Validates the chain, resolves every pass's target shape, computes each pass's last consumer,
     * and packs passes with disjoint lifetimes onto shared target slots.
     *
     * @throws IllegalArgumentException for a blank or duplicate pass name, a reference to a pass that
     * does not exist or does not run *earlier*, a size that resolves to zero, a previous-frame
     * reference to a pass that is not [FabricatePassSpec.persistent], or a persistent pass whose size
     * or format would have to be inherited from its source.
     */
    fun plan(
        passes: List<FabricatePassSpec>,
        inputWidth: Int,
        inputHeight: Int,
        inputFormat: Int
    ): FabricatePassChainPlan {
        require(passes.isNotEmpty()) { "a pass chain needs at least one pass" }
        require(inputWidth > 0 && inputHeight > 0) {
            "chain input must have a positive size, got ${inputWidth}x$inputHeight"
        }

        val indexByName = LinkedHashMap<String, Int>()
        passes.forEachIndexed { index, spec ->
            require(spec.name.isNotBlank()) { "pass $index has a blank name" }
            require(spec.name != CHAIN_INPUT) { "pass $index may not be named '$CHAIN_INPUT'" }
            require(spec.name !in indexByName) { "duplicate pass name '${spec.name}'" }
            indexByName[spec.name] = index
        }

        val inputShape = FabricatePassShape(inputWidth, inputHeight, inputFormat, 1)

        // --- persistent passes resolve first, because they are read before they run ---
        //
        // A previous-frame reference may point forwards, so by the time the front-to-back walk below
        // reaches the reader, the persistent pass it reads may not have been sized yet. There is
        // nothing to inherit from in that case, which is why a persistent pass has to state its own
        // size and format rather than take its source's.

        val persistentShapes = HashMap<Int, FabricatePassShape>()
        passes.forEachIndexed { index, spec ->
            if (!spec.persistent) return@forEachIndexed
            require(spec.size.independentOfSource) {
                "persistent pass '${spec.name}' has size ${spec.size}, which depends on its source — " +
                        "a persistent target is read a frame before the pass that writes it runs, so it " +
                        "must size itself with Absolute, MatchChainInput or FractionOfChainInput"
            }
            val format = spec.format
                ?: throw IllegalArgumentException(
                    "persistent pass '${spec.name}' inherits its format from its source, which is not " +
                            "resolved yet when its previous frame is read — state a format explicitly"
                )
            require(spec.mipLevels >= 1) { "pass '${spec.name}' asked for ${spec.mipLevels} mip levels" }
            val (width, height) = spec.size.resolve(inputShape, inputShape)
            require(width > 0 && height > 0) {
                "pass '${spec.name}' resolved to ${width}x$height from ${spec.size}"
            }
            persistentShapes[index] = FabricatePassShape(width, height, format, spec.mipLevels)
        }

        // --- resolve references, sizes and formats, front to back ---

        val shapes = ArrayList<FabricatePassShape>(passes.size)
        val resolvedInputs = ArrayList<List<FabricateResolvedInput>>(passes.size)

        passes.forEachIndexed { index, spec ->
            fun resolve(reference: String, role: String, previousFrame: Boolean): FabricatePassRef {
                if (reference == CHAIN_INPUT) {
                    require(!previousFrame) {
                        "pass '${spec.name}' $role asks for the previous frame of '$CHAIN_INPUT', which is " +
                                "not a pass and holds no history — only a persistent pass does"
                    }
                    return FabricatePassRef.ChainInput
                }
                val target = indexByName[reference]
                    ?: throw IllegalArgumentException(
                        "pass '${spec.name}' $role names '$reference', which is not a pass in this chain"
                    )
                if (previousFrame) {
                    // Deliberately *not* required to run earlier. "As of the end of last frame" is a
                    // value that already exists when this frame's first pass runs, whatever index the
                    // pass that wrote it has — including this pass's own index.
                    require(passes[target].persistent) {
                        "pass '${spec.name}' $role asks for the previous frame of '$reference', which is " +
                                "not persistent — only a persistent pass keeps one, so mark '$reference' " +
                                "persistent or drop previousFrame"
                    }
                    return FabricatePassRef.PreviousFrame(target, reference)
                }
                require(target < index) {
                    "pass '${spec.name}' $role names '$reference', which runs at or after it " +
                            "(index $target vs $index) — a chain may only read backwards"
                }
                return FabricatePassRef.Pass(target, reference)
            }

            // The primary input defaults to the previous pass, or to the chain input for pass 0.
            val primary = when {
                spec.source != null -> resolve(spec.source, "source", spec.sourcePreviousFrame)
                spec.sourcePreviousFrame -> throw IllegalArgumentException(
                    "pass '${spec.name}' asks for a previous frame without naming a source"
                )
                index == 0 -> FabricatePassRef.ChainInput
                else -> FabricatePassRef.Pass(index - 1, passes[index - 1].name)
            }

            fun shapeOf(ref: FabricatePassRef): FabricatePassShape = when (ref) {
                FabricatePassRef.ChainInput -> inputShape
                is FabricatePassRef.Pass -> shapes[ref.index]
                is FabricatePassRef.PreviousFrame -> persistentShapes.getValue(ref.index)
            }

            val sourceShape = shapeOf(primary)
            val shape = persistentShapes[index] ?: run {
                val (width, height) = spec.size.resolve(sourceShape, inputShape)
                require(width > 0 && height > 0) {
                    "pass '${spec.name}' resolved to ${width}x$height from ${spec.size}"
                }
                require(spec.mipLevels >= 1) { "pass '${spec.name}' asked for ${spec.mipLevels} mip levels" }
                FabricatePassShape(width, height, spec.format ?: sourceShape.format, spec.mipLevels)
            }
            shapes += shape

            val inputs = ArrayList<FabricateResolvedInput>(spec.extraInputs.size + 1)
            inputs += FabricateResolvedInput(PRIMARY_SAMPLER, primary)
            spec.extraInputs.forEach { extra ->
                require(inputs.none { it.samplerName == extra.samplerName }) {
                    "pass '${spec.name}' binds sampler '${extra.samplerName}' twice"
                }
                inputs += FabricateResolvedInput(
                    extra.samplerName,
                    resolve(extra.fromPass, "input '${extra.samplerName}'", extra.previousFrame)
                )
            }
            resolvedInputs += inputs
        }

        // --- lifetimes: the last pass that reads each pass's output ---

        val lastConsumer = IntArray(passes.size) { -1 }
        resolvedInputs.forEachIndexed { consumer, inputs ->
            inputs.forEach { input ->
                val ref = input.from
                if (ref is FabricatePassRef.Pass) {
                    if (consumer > lastConsumer[ref.index]) lastConsumer[ref.index] = consumer
                }
            }
        }
        // The chain's output is read by whoever presents it, so it outlives the chain.
        lastConsumer[passes.size - 1] = passes.size
        // A persistent pass's output is read by *next* frame, which is later than every index here.
        passes.forEachIndexed { index, spec -> if (spec.persistent) lastConsumer[index] = passes.size }

        // --- pack onto shared slots ---
        //
        // Walking front to back, a slot written by pass j is free to be overwritten by pass i as
        // soon as `lastConsumer[j] < i`: nothing from pass i onwards will ever sample it again. That
        // is also exactly the condition that rules out a read-after-write hazard, because if pass i
        // *did* read pass j then `lastConsumer[j] >= i` and the slot would not be offered.
        //
        // Persistent passes sit outside that entirely: their contents have to survive the gap to the
        // next frame, so they neither take another pass's slot nor offer theirs. The lastConsumer
        // above already makes them un-offerable; `persistent` on the slot is what keeps them from
        // taking one, and what records that the slot costs two images.

        val slots = ArrayList<FabricateTargetSlot>()
        val occupant = ArrayList<Int>()                  // slot -> pass index currently living there
        val slotOf = IntArray(passes.size) { -1 }

        passes.indices.forEach { index ->
            val shape = shapes[index]
            val persistent = passes[index].persistent
            var chosen = -1
            if (!persistent) {
                for (slot in slots.indices) {
                    if (slots[slot].persistent) continue                        // never reusable
                    val living = occupant[slot]
                    if (living >= 0 && lastConsumer[living] >= index) continue   // still needed
                    if (slots[slot].shape != shape) continue                    // wrong shape to reuse
                    chosen = slot
                    break
                }
            }
            if (chosen < 0) {
                chosen = slots.size
                slots += FabricateTargetSlot(chosen, shape, mutableListOf(), persistent)
                occupant += -1
            }
            slots[chosen].passIndices += index
            occupant[chosen] = index
            slotOf[index] = chosen
        }

        val planned = passes.mapIndexed { index, spec ->
            FabricatePlannedPass(
                index = index,
                spec = spec,
                shape = shapes[index],
                inputs = resolvedInputs[index],
                lastConsumer = lastConsumer[index],
                slot = slotOf[index]
            )
        }

        return FabricatePassChainPlan(
            passes = planned,
            slots = slots,
            input = FabricatePassShape(inputWidth, inputHeight, inputFormat, 1)
        )
    }
}

/** The resolved shape of a pass target: what [FabricatePassSize] and the format request came to. */
data class FabricatePassShape(val width: Int, val height: Int, val format: Int, val mipLevels: Int = 1) {
    /** Bytes of color attachment this shape occupies, mip chain included. */
    val bytes: Long
        get() {
            val bpp = vkFormatBytesPerPixel(format, "FabricatePassShape").toLong()
            var total = 0L
            var w = width
            var h = height
            repeat(mipLevels) {
                total += w.toLong() * h.toLong() * bpp
                w = (w / 2).coerceAtLeast(1)
                h = (h / 2).coerceAtLeast(1)
            }
            return total
        }

    override fun toString(): String = "${width}x$height fmt=$format" + if (mipLevels > 1) " mips=$mipLevels" else ""
}

/**
 * How a pass's target is sized.
 *
 * Fractions floor and clamp at one texel, which is the convention a downsample pyramid needs: ½ of
 * 1023 is 511, and the bottom of a long pyramid is 1x1 rather than 0x0.
 */
sealed interface FabricatePassSize {

    /** @return the resolved (width, height) for a pass whose primary input is [source] in a chain fed [chainInput]. */
    fun resolve(source: FabricatePassShape, chainInput: FabricatePassShape): Pair<Int, Int>

    /**
     * True if [resolve] ignores its `source` argument.
     *
     * Only such a size can size a [FabricatePassSpec.persistent] pass, whose target has to exist
     * before the front-to-back walk has resolved whatever feeds it this frame.
     */
    val independentOfSource: Boolean get() = false

    /** The size of the pass's own primary input — the historical, and still the default, behaviour. */
    data object MatchSource : FabricatePassSize {
        override fun resolve(source: FabricatePassShape, chainInput: FabricatePassShape) = source.width to source.height
    }

    /** The size of the texture entering the chain, whatever the pass immediately before did. */
    data object MatchChainInput : FabricatePassSize {
        override val independentOfSource: Boolean get() = true

        override fun resolve(source: FabricatePassShape, chainInput: FabricatePassShape) =
            chainInput.width to chainInput.height
    }

    /** A fixed size in texels, independent of the chain. */
    data class Absolute(val width: Int, val height: Int) : FabricatePassSize {
        init {
            require(width > 0 && height > 0) { "Absolute size must be positive, got ${width}x$height" }
        }

        override val independentOfSource: Boolean get() = true

        override fun resolve(source: FabricatePassShape, chainInput: FabricatePassShape) = width to height
    }

    /** [numerator]/[denominator] of the pass's primary input. Chain these to build a pyramid. */
    data class FractionOfSource(val numerator: Int, val denominator: Int) : FabricatePassSize {
        init {
            require(numerator > 0 && denominator > 0) { "fraction must be positive, got $numerator/$denominator" }
        }

        override fun resolve(source: FabricatePassShape, chainInput: FabricatePassShape) =
            scale(source.width, numerator, denominator) to scale(source.height, numerator, denominator)
    }

    /** [numerator]/[denominator] of the chain input — a stable absolute rung, whatever precedes it. */
    data class FractionOfChainInput(val numerator: Int, val denominator: Int) : FabricatePassSize {
        init {
            require(numerator > 0 && denominator > 0) { "fraction must be positive, got $numerator/$denominator" }
        }

        override val independentOfSource: Boolean get() = true

        override fun resolve(source: FabricatePassShape, chainInput: FabricatePassShape) =
            scale(chainInput.width, numerator, denominator) to scale(chainInput.height, numerator, denominator)
    }

    companion object {
        /** Half of the primary input — the pyramid rung. */
        val Half: FabricatePassSize = FractionOfSource(1, 2)

        /** A quarter of the primary input. */
        val Quarter: FabricatePassSize = FractionOfSource(1, 4)

        internal fun scale(value: Int, numerator: Int, denominator: Int): Int =
            ((value.toLong() * numerator) / denominator).toInt().coerceAtLeast(1)
    }
}

/** Which texture a pass sampler is bound to. */
sealed interface FabricatePassRef {

    /** The texture entering the chain. */
    data object ChainInput : FabricatePassRef

    /** The output of an earlier pass. */
    data class Pass(val index: Int, val name: String) : FabricatePassRef

    /**
     * A [FabricatePassSpec.persistent] pass's output *as of the end of last frame* — the other half
     * of its ping-pong pair.
     *
     * [index] may be greater than or equal to the reading pass's own index. That is the point: the
     * value is already there when this frame starts, so the run order within the frame says nothing
     * about whether it can be read.
     */
    data class PreviousFrame(val index: Int, val name: String) : FabricatePassRef
}

/**
 * An extra named sampler a pass wants bound to another pass's output.
 *
 * @param previousFrame bind the *previous frame's* copy instead of this frame's. Only legal against
 *   a [FabricatePassSpec.persistent] pass, and the only reference that may name a pass running at or
 *   after the reader — including the reader itself, which is how a simulation field advances.
 */
data class FabricatePassInput(
    val samplerName: String,
    val fromPass: String,
    val previousFrame: Boolean = false
) {
    init {
        require(samplerName.isNotBlank()) { "sampler name must not be blank" }
        require(fromPass.isNotBlank()) { "input pass name must not be blank" }
    }
}

/** A resolved [FabricatePassInput] — sampler name plus the texture the planner bound it to. */
data class FabricateResolvedInput(val samplerName: String, val from: FabricatePassRef)

/**
 * One declared pass.
 *
 * @param name the pass's name in the stack; also the name other passes reference it by.
 * @param fragment the fullscreen fragment shader. Samplers are bound in [inputs] order from
 *   `set = 0, binding = 0` upwards, primary first.
 * @param size how big the pass's target is. Defaults to its primary input's size.
 * @param format the pass target's Vulkan format, or null to inherit the primary input's. This is the
 *   knob that makes a half-float intermediate possible.
 * @param source the name of the pass supplying `sourceTex`, or [FabricatePassChain.CHAIN_INPUT], or
 *   null for "the pass immediately before me" (the chain input, for the first pass).
 * @param extraInputs additional named samplers bound to earlier passes.
 * @param push push-constant values to seed on the pass's fullscreen quad, by name. Names the shader
 *   does not declare are ignored, so one spec can drive several shader variants.
 * @param mipLevels mip levels on the pass target; >1 also turns on automatic mip generation.
 * @param persistent give the pass a ping-pong target whose previous frame other passes (and this one)
 *   may read with `FabricatePassInput(..., previousFrame = true)`. This is what makes a simulation
 *   expressible as a chain rather than as hand-wired boxes: the state field is a pass, and last
 *   frame's value of it is a sampler. A persistent pass must state its own [size] — one that does not
 *   depend on its source — and its own [format], and its target is never shared with another pass.
 * @param sourcePreviousFrame bind the *primary* `sourceTex` to [source]'s previous frame. The advect
 *   pass of a fluid wants this: the field it transports and the velocity that transports it are both
 *   last frame's.
 */
data class FabricatePassSpec(
    val name: String,
    val fragment: String,
    val size: FabricatePassSize = FabricatePassSize.MatchSource,
    val format: Int? = null,
    val source: String? = null,
    val extraInputs: List<FabricatePassInput> = emptyList(),
    val push: Map<String, Any> = emptyMap(),
    val mipLevels: Int = 1,
    val persistent: Boolean = false,
    val sourcePreviousFrame: Boolean = false
)

/** A pass target after planning: its shape, its bound inputs, its lifetime and the slot it shares. */
data class FabricatePlannedPass(
    val index: Int,
    val spec: FabricatePassSpec,
    val shape: FabricatePassShape,
    val inputs: List<FabricateResolvedInput>,
    val lastConsumer: Int,
    val slot: Int
) {
    val name: String get() = spec.name

    /** True if this pass's target is a ping-pong pair rather than a single image. */
    val persistent: Boolean get() = spec.persistent
}

/**
 * A physical target, and every pass that takes a turn writing to it.
 *
 * A [persistent] slot has exactly one pass and holds
 * [FabricatePassChain.PERSISTENT_IMAGE_COUNT] images rather than one, which is what [bytes] counts.
 */
data class FabricateTargetSlot(
    val index: Int,
    val shape: FabricatePassShape,
    val passIndices: MutableList<Int>,
    val persistent: Boolean = false
) {
    /** Images this slot allocates — two for a ping-pong pair, one otherwise. */
    val imageCount: Int get() = if (persistent) FabricatePassChain.PERSISTENT_IMAGE_COUNT else 1

    /** Color-attachment bytes this slot occupies, ping-pong pair included. */
    val bytes: Long get() = shape.bytes * imageCount
}

/**
 * A resolved chain: what to allocate, in what order to run it, and what it costs.
 *
 * The counterfactual [naiveBytes] is not a straw man. It is what
 * [StandardRenderer.Bundle.addShaderEffect] allocates for the same chain today — one target per
 * pass, every one of them the chain input's size and format, because that is the only thing the old
 * API could express.
 */
class FabricatePassChainPlan(
    val passes: List<FabricatePlannedPass>,
    val slots: List<FabricateTargetSlot>,
    val input: FabricatePassShape
) {
    /** Physical targets this chain needs. */
    val targetCount: Int get() = slots.size

    /** Targets a one-target-per-pass allocator would need. */
    val passCount: Int get() = passes.size

    /** Color-attachment bytes this chain needs. */
    val plannedBytes: Long get() = slots.sumOf { it.bytes }

    /** Color-attachment bytes one full-resolution, chain-input-format target per pass would need. */
    val naiveBytes: Long get() = input.bytes * passes.size

    /** Bytes the multi-resolution shapes alone save, before any slot sharing. */
    val unsharedBytes: Long
        get() = passes.sumOf { pass ->
            pass.shape.bytes * if (pass.persistent) FabricatePassChain.PERSISTENT_IMAGE_COUNT else 1
        }

    /** Passes whose target is a ping-pong pair — the chain's state, if it has any. */
    val persistentPasses: List<FabricatePlannedPass> get() = passes.filter { it.persistent }

    /** The chain's final pass. */
    val output: FabricatePlannedPass get() = passes.last()

    operator fun get(name: String): FabricatePlannedPass? = passes.firstOrNull { it.name == name }

    /** Passes sharing [slot]'s target, in the order they write it. */
    fun passesOn(slot: Int): List<FabricatePlannedPass> = slots[slot].passIndices.map { passes[it] }

    /**
     * A human-readable audit of the plan: every pass with its resolved shape, its bound samplers, the
     * slot it writes and when its output dies, then the memory totals against the counterfactual.
     */
    fun report(): String = buildString {
        appendLine("pass chain: ${passes.size} passes on $targetCount target(s), input $input")
        passes.forEach { pass ->
            val inputs = pass.inputs.joinToString(", ") { input ->
                val from = when (val ref = input.from) {
                    FabricatePassRef.ChainInput -> FabricatePassChain.CHAIN_INPUT
                    is FabricatePassRef.Pass -> ref.name
                    is FabricatePassRef.PreviousFrame -> "${ref.name}@prev"
                }
                "${input.samplerName}<-$from"
            }
            val death = when {
                pass.persistent -> "persistent"
                pass.lastConsumer >= passes.size -> "output"
                else -> "dead after #${pass.lastConsumer}"
            }
            appendLine("  #${pass.index} ${pass.name}: ${pass.shape} slot=${pass.slot} [$inputs] ($death)")
        }
        slots.forEach { slot ->
            val sharers = slot.passIndices.map { passes[it].name }
            val pair = if (slot.persistent) " x${slot.imageCount} (ping-pong)" else ""
            appendLine("  slot ${slot.index}: ${slot.shape}$pair <- ${sharers.joinToString(" then ")}")
        }
        appendLine("  memory: planned ${mib(plannedBytes)} MiB, unshared ${mib(unsharedBytes)} MiB, " +
                "one-full-res-target-per-pass ${mib(naiveBytes)} MiB")
        append("  saving: ${"%.2f".format(naiveBytes.toDouble() / plannedBytes.toDouble())}x " +
                "vs one full-resolution target per pass")
    }

    private fun mib(bytes: Long): String = "%.2f".format(bytes.toDouble() / (1024.0 * 1024.0))
}
