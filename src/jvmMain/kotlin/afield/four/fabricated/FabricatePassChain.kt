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
 */
object FabricatePassChain {

    /** Reference name for the texture entering the chain — the stack input, not a pass. */
    const val CHAIN_INPUT = "@input"

    /** The sampler name a pass's primary input is always bound to. */
    const val PRIMARY_SAMPLER = "sourceTex"

    /**
     * Plan [passes] against a chain input of [inputWidth] x [inputHeight] in [inputFormat].
     *
     * Validates the chain, resolves every pass's target shape, computes each pass's last consumer,
     * and packs passes with disjoint lifetimes onto shared target slots.
     *
     * @throws IllegalArgumentException for a blank or duplicate pass name, a reference to a pass that
     * does not exist or does not run *earlier*, or a size that resolves to zero.
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

        // --- resolve references, sizes and formats, front to back ---

        val shapes = ArrayList<FabricatePassShape>(passes.size)
        val resolvedInputs = ArrayList<List<FabricateResolvedInput>>(passes.size)

        passes.forEachIndexed { index, spec ->
            fun resolve(reference: String, role: String): FabricatePassRef {
                if (reference == CHAIN_INPUT) return FabricatePassRef.ChainInput
                val target = indexByName[reference]
                    ?: throw IllegalArgumentException(
                        "pass '${spec.name}' $role names '$reference', which is not a pass in this chain"
                    )
                require(target < index) {
                    "pass '${spec.name}' $role names '$reference', which runs at or after it " +
                            "(index $target vs $index) — a chain may only read backwards"
                }
                return FabricatePassRef.Pass(target, reference)
            }

            // The primary input defaults to the previous pass, or to the chain input for pass 0.
            val primary = when {
                spec.source != null -> resolve(spec.source, "source")
                index == 0 -> FabricatePassRef.ChainInput
                else -> FabricatePassRef.Pass(index - 1, passes[index - 1].name)
            }

            fun shapeOf(ref: FabricatePassRef): FabricatePassShape = when (ref) {
                FabricatePassRef.ChainInput -> FabricatePassShape(inputWidth, inputHeight, inputFormat, 1)
                is FabricatePassRef.Pass -> shapes[ref.index]
            }

            val sourceShape = shapeOf(primary)
            val inputShape = FabricatePassShape(inputWidth, inputHeight, inputFormat, 1)
            val (width, height) = spec.size.resolve(sourceShape, inputShape)
            require(width > 0 && height > 0) {
                "pass '${spec.name}' resolved to ${width}x$height from ${spec.size}"
            }
            require(spec.mipLevels >= 1) { "pass '${spec.name}' asked for ${spec.mipLevels} mip levels" }

            shapes += FabricatePassShape(width, height, spec.format ?: sourceShape.format, spec.mipLevels)

            val inputs = ArrayList<FabricateResolvedInput>(spec.extraInputs.size + 1)
            inputs += FabricateResolvedInput(PRIMARY_SAMPLER, primary)
            spec.extraInputs.forEach { extra ->
                require(inputs.none { it.samplerName == extra.samplerName }) {
                    "pass '${spec.name}' binds sampler '${extra.samplerName}' twice"
                }
                inputs += FabricateResolvedInput(extra.samplerName, resolve(extra.fromPass, "input '${extra.samplerName}'"))
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

        // --- pack onto shared slots ---
        //
        // Walking front to back, a slot written by pass j is free to be overwritten by pass i as
        // soon as `lastConsumer[j] < i`: nothing from pass i onwards will ever sample it again. That
        // is also exactly the condition that rules out a read-after-write hazard, because if pass i
        // *did* read pass j then `lastConsumer[j] >= i` and the slot would not be offered.

        val slots = ArrayList<FabricateTargetSlot>()
        val occupant = ArrayList<Int>()                  // slot -> pass index currently living there
        val slotOf = IntArray(passes.size) { -1 }

        passes.indices.forEach { index ->
            val shape = shapes[index]
            var chosen = -1
            for (slot in slots.indices) {
                val living = occupant[slot]
                if (living >= 0 && lastConsumer[living] >= index) continue   // still needed
                if (slots[slot].shape != shape) continue                    // wrong shape to reuse
                chosen = slot
                break
            }
            if (chosen < 0) {
                chosen = slots.size
                slots += FabricateTargetSlot(chosen, shape, mutableListOf())
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

    /** The size of the pass's own primary input — the historical, and still the default, behaviour. */
    data object MatchSource : FabricatePassSize {
        override fun resolve(source: FabricatePassShape, chainInput: FabricatePassShape) = source.width to source.height
    }

    /** The size of the texture entering the chain, whatever the pass immediately before did. */
    data object MatchChainInput : FabricatePassSize {
        override fun resolve(source: FabricatePassShape, chainInput: FabricatePassShape) =
            chainInput.width to chainInput.height
    }

    /** A fixed size in texels, independent of the chain. */
    data class Absolute(val width: Int, val height: Int) : FabricatePassSize {
        init {
            require(width > 0 && height > 0) { "Absolute size must be positive, got ${width}x$height" }
        }

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
}

/** An extra named sampler a pass wants bound to an earlier pass's output. */
data class FabricatePassInput(val samplerName: String, val fromPass: String) {
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
 */
data class FabricatePassSpec(
    val name: String,
    val fragment: String,
    val size: FabricatePassSize = FabricatePassSize.MatchSource,
    val format: Int? = null,
    val source: String? = null,
    val extraInputs: List<FabricatePassInput> = emptyList(),
    val push: Map<String, Any> = emptyMap(),
    val mipLevels: Int = 1
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
}

/** A physical target, and every pass that takes a turn writing to it. */
data class FabricateTargetSlot(
    val index: Int,
    val shape: FabricatePassShape,
    val passIndices: MutableList<Int>
)

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
    val plannedBytes: Long get() = slots.sumOf { it.shape.bytes }

    /** Color-attachment bytes one full-resolution, chain-input-format target per pass would need. */
    val naiveBytes: Long get() = input.bytes * passes.size

    /** Bytes the multi-resolution shapes alone save, before any slot sharing. */
    val unsharedBytes: Long get() = passes.sumOf { it.shape.bytes }

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
                }
                "${input.samplerName}<-$from"
            }
            val death = if (pass.lastConsumer >= passes.size) "output" else "dead after #${pass.lastConsumer}"
            appendLine("  #${pass.index} ${pass.name}: ${pass.shape} slot=${pass.slot} [$inputs] ($death)")
        }
        slots.forEach { slot ->
            val sharers = slot.passIndices.map { passes[it].name }
            appendLine("  slot ${slot.index}: ${slot.shape} <- ${sharers.joinToString(" then ")}")
        }
        appendLine("  memory: planned ${mib(plannedBytes)} MiB, unshared ${mib(unsharedBytes)} MiB, " +
                "one-full-res-target-per-pass ${mib(naiveBytes)} MiB")
        append("  saving: ${"%.2f".format(naiveBytes.toDouble() / plannedBytes.toDouble())}x " +
                "vs one full-resolution target per pass")
    }

    private fun mib(bytes: Long): String = "%.2f".format(bytes.toDouble() / (1024.0 * 1024.0))
}
