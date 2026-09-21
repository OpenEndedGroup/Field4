package afield.four.physics

import afield.four.Renderer
import afield.four.Target
import afield.four.buffers.FrameBufferGroup
import afield.four.buffers.buffer
import afield.four.modules.ComputeMesh
import afield.four.modules.ComputeModule
import afield.four.modules.ComputeModulePointer
import afield.four.modules.fromContext
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.four.modules.Mesh
import afield.four.modules.Module
import afield.stdlib.Vec4
import afield.stdlib.vec
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_ALPHA
import org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD
import afield.four.util.GpuNeighbourGrid
import afield.stdlib.glsl
import afield.stdlib.linalg.Rectanglef
import afield.stdlib.linalg.Vector2f
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Reynolds boids as a Vulkan compute shader, over a GPU neighbour grid, with the interaction rule
 * swappable for a trained network.
 *
 * Two declarations. [BoidSettings] carries the three classical weights, the speed limit, and two
 * forcing terms that contribute nothing until they are turned on. [ShaderBoids] owns one storage
 * buffer of particles, generates the GLSL that steps them, and installs the grid rebuild and the
 * rule into one ordered target map, in the order those two have to run.
 *
 * #### One system, three rules
 *
 * With neither `learned` nor `equivariant` set it computes the classical three-term force in
 * hand-written GLSL. Given [DeepSetsWeights] it evaluates a trained sum-then-combine network at
 * the same place in the same neighbour loop, and given [EquivariantWeights] it evaluates the
 * rotation-equivariant form. The buffer, the neighbour walk, the speed limit and the integration
 * are the same code in all three cases, so changing that one argument compares rules rather than
 * implementations. Passing both fails at construction.
 *
 * #### Sized to the grid
 *
 * The neighbour query is the 3x3 block of cells around a particle's own, which reaches one cell
 * width in each direction, so `BoidSettings.radius` cannot exceed the grid's `cellSize`. A radius
 * that does not fit still produces something that looks like a flock, computed from a subset of
 * each particle's neighbours, so `install` checks it. The particle count has to fit the grid's
 * capacity, and the constructor checks that.
 *
 * #### Graded against a CPU oracle
 *
 * `referenceAcceleration` is the same law in Kotlin over every other particle, and
 * `four.shader-boids.smoke` compares the two per particle, for a uniform flock and for a
 * clustered one. It runs them with `dt = 0`, where nothing moves and the accelerations are a
 * fixed function of a fixed configuration; boids is chaotic, so two trajectories compared after a
 * few hundred steps would measure how fast they separate and not whether the two implement the
 * same law. The force each particle
 * stores is the interaction alone, written before the containment and driver terms are added and
 * before the velocity is integrated.
 *
 * #### Drawing it
 *
 * `installDrawInto` adds a point-list pass that reads the same allocation the compute pass wrote,
 * with no copy, tinting from slow colour to fast colour by speed. The point the driver term pulls
 * toward comes from a lambda the push bindings call every frame, so a box can move it without
 * re-running anything.
 *
 * The prose, with pictures: [Neural particle systems](../neural_particles.md).
 */

/** The three classical weights, plus the speed limit that keeps a flock finite. */
data class BoidSettings(
    /** Prefix for the buffer, module and target names this system creates, so two flocks can share a renderer. */
    var name: String = "boids",
    /** Interaction radius. Must be at most the grid's cell size or the 3x3 query misses people. */
    var radius: Float = 0.06f,
    /** Push away from neighbours, as `1/r`. */
    var separation: Float = 0.02f,
    /** Match neighbours' velocity. */
    var alignment: Float = 1.2f,
    /** Move toward neighbours' centre of mass. */
    var cohesion: Float = 0.8f,
    /** Speed ceiling, applied after the acceleration. */
    var maxSpeed: Float = 0.35f,
    /** Integration step. Set to 0 to compute accelerations without moving anything. */
    var dt: Float = 1f / 60f,
    /**
     * Closest approach used in the separation term, as a fraction of [radius].
     *
     * Reynolds separation goes as `1/r` and is unbounded as `r` approaches zero, so a single
     * near-coincident pair can dominate the whole force — and, in a test, dominate the numerical
     * error too, since a huge term summed against small ones is where fp32 cancellation lives.
     * Flooring the distance costs nothing physical at this scale and makes the force bounded.
     */
    var minSeparationFraction: Float = 0.05f,
    /**
     * The separation term's range, as a fraction of [radius]. 1 means "the same range as
     * everything else", which is the default and what the tests assert against.
     *
     * Below 1 this is the classical three-radius formulation, and it is the difference between a
     * flock that can clump and one that cannot. With a single radius, cohesion acts on the *mean*
     * neighbour offset — which is near zero for a boid in the middle of a uniform cloud, so
     * cohesion does nothing in the bulk — while separation is a *sum*, so it grows with density
     * and is self-limiting. The only stable state is one uniform blob, whatever the weights.
     *
     * Give separation a shorter range and the two stop fighting over the same scale: cohesion
     * condenses a group over the full radius, separation sets its internal spacing, and groups
     * more than a radius apart can never find each other. That is what produces several clumps
     * instead of one disc.
     */
    var separationRadiusFraction: Float = 1f,
    /**
     * Pull back toward the origin once a boid is further out than [containmentRadius], per unit
     * of excess. Zero — the default — adds exactly nothing, which is what the tests rely on.
     *
     * A flock left alone leaves: the rule conserves nothing and there is no boundary. Wrapping
     * the domain would be the other option and is worse here, because the grid would not find
     * neighbours across the seam and the flock would tear along it.
     */
    var containment: Float = 0f,
    /** Where [containment] starts to bite, measured from [containmentCenter]. */
    var containmentRadius: Float = 0.8f,
    /** What the containment pulls toward — so two flocks can share a domain without merging. */
    var containmentCenter: Vector2f = Vector2f(0f, 0f),
    /**
     * Pull toward (positive) or push away from (negative) the moving point [ShaderBoids.driver]
     * supplies. Zero — the default — adds exactly nothing.
     *
     * A flock needs a reason to keep looking alive. Left alone at these weights the scene settles
     * into an evenly spaced disc that barely changes; the first learned rule hid that, because its
     * broken rotational symmetry produced a constant residual that kept shoving the flock along —
     * the drift was supplying the apparent motion. Fixing the symmetry removed the drift and the
     * flock promptly sat down, which is the honest behaviour of the rule it was asked to learn.
     *
     * Whether "settled" means genuinely still is a separate question and not one a still frame can
     * answer: measured on a smaller CPU flock at these weights the residual force oscillates
     * without trending, which is steady churn rather than rest.
     *
     * So the motion has to come from outside the interaction, as forcing rather than as a
     * modelling error. This is not part of the rule the network learned and needs no retraining:
     * it is added after the interaction, like [containment].
     */
    var driverStrength: Float = 0f,
    /** Distance over which [driverStrength] falls off. */
    var driverRadius: Float = 0.25f,
    /** Point size for [installDrawInto]. */
    var pointSize: Float = 2f,
    /** Slow boids take this colour. */
    var slowColor: Vec4 = vec(0.16, 0.40, 0.95, 1.0),
    /** Fast boids take this one. */
    var fastColor: Vec4 = vec(1.0, 0.75, 0.25, 1.0),
    /** Overall brightness of the draw. */
    var intensity: Float = 0.9f,
)

/**
 * Classical Reynolds boids, on the GPU, over a [GpuNeighbourGrid].
 *
 * This is deliberately the *hand-written* rule rather than a learned one. It exists to be the
 * reference that a trained interaction is later measured against: separation, alignment and
 * cohesion are one particular choice of `ψ` in the Deep-Sets form
 * `h_i = φ(x_i, Σ_j ψ(x_j - x_i, v_j - v_i))`, and there is no way to tell whether a learned `ψ`
 * is doing something interesting without a correct one beside it.
 *
 * ### The particle
 *
 * ```glsl
 * struct Boid { vec2 position; vec2 velocity; vec2 accel; vec2 previousVelocity; };
 * ```
 *
 * `accel` is stored rather than kept in a register, and that is for the test: it is the output of
 * the interaction and therefore the thing worth grading, and grading it per particle against a
 * CPU brute force is far sharper than looking at where the flock ended up.
 *
 * `previousVelocity` is the velocity the step integrated *from*, and it is there for the same
 * reason but a subtler one. Checking `v' = v + a·dt` by reading the buffer before and after a
 * frame requires knowing that exactly one step happened in between, and a caller cannot know
 * that: `dt` is read through a lambda when the push buffer is written, so a frame already in
 * flight can pick up a new value. Recording the input alongside the output makes the check
 * self-contained and independent of how many frames ran, which turned a flaky assertion into an
 * exact one.
 *
 * ### Order of operations, and why `dt = 0` is a supported state
 *
 * The acceleration is computed from the state at the top of the step and written before anything
 * is integrated, so with `dt = 0` nothing moves and the accelerations are a pure function of a
 * fixed configuration — which is exactly what a test wants, because boids is chaotic and
 * comparing two trajectories after a hundred steps measures nothing but divergence.
 *
 * ### The radius must fit the cell
 *
 * A 3x3 block of cells covers one cell width in every direction, so [BoidSettings.radius] cannot
 * exceed the grid's `cellSize`. Exceeding it does not fail: it silently returns a subset of the
 * neighbours, and the flock still looks like a flock. [install] checks it.
 */
class ShaderBoids(
    /**
     * The neighbour structure the rule reads. [install] rebuilds it from the current positions
     * before the rule runs, and its `cellSize` is the ceiling on [BoidSettings.radius].
     */
    val grid: GpuNeighbourGrid,
    /** How many particles. Must be in `1..grid.capacity`, which the constructor requires. */
    val count: Int,
    /** Where the buffers and the two modules are allocated. */
    val renderer: Renderer = fromContext(),
    /**
     * A trained Deep Sets interaction to use *instead of* the hand-written rule, or null for
     * classical boids.
     *
     * Everything else is identical — the same buffer, the same neighbour walk, the same
     * integration and the same speed limit — so switching this is an A/B of the rule alone. That
     * is the whole point of having built the hand-written one first.
     */
    val learned: afield.stdlib.DeepSetsWeights? = null,
    /**
     * A trained **equivariant** interaction, used instead of either other rule.
     *
     * The one to reach for. The plain [learned] form has no rotational symmetry, so it carries a
     * fixed-direction residual that grows with crowding — 0.50 on a forty-eight-neighbour ring
     * where the true force is exactly zero — and in a flock that integrates into a steady drift
     * out of containment. The equivariant form is zero there by construction and fits the rule
     * slightly *better* (0.262 against 0.302).
     */
    val equivariant: afield.stdlib.EquivariantWeights? = null,
    configure: BoidSettings.() -> Unit = {},
) {
    /**
     * The weights this system is running, after `configure`.
     *
     * Every field is read through the push bindings as the frame is submitted, so changing one
     * takes effect on the next frame with nothing reinstalled. [BoidSettings.name] is the
     * exception: the buffers and modules took their names from it at construction.
     */
    val settings = BoidSettings().apply(configure)

    /** 8 floats: position, velocity, accel, and the velocity this step integrated from. */
    val strideFloats = STRIDE_FLOATS

    /** The flock. */
    val boids: FrameBufferGroup = buffer(name = "${settings.name}Boids") {
        size = count.toLong() * STRIDE_FLOATS * 4L
        storage()
        addressable()
        hosted()
        dest()
    }.toBuffer(renderer)

    private var module: ComputeModulePointer? = null
    private var drawModule: afield.four.modules.ModulePointer? = null

    /**
     * Where the driver is, re-read **every frame** — the push buffer calls this when it writes, so
     * this one lambda is the whole animation and a box can move the point without re-running.
     */
    var driver: () -> Vector2f = { Vector2f(0f, 0f) }

    /** `psi`'s weights, when [learned] is set. */
    val psiBuffer: FrameBufferGroup? =
        (learned?.psi ?: equivariant?.psi)?.let { weightBuffer("${settings.name}Psi", it) }

    /** `phi`'s weights, when a learned rule is set. */
    val phiBuffer: FrameBufferGroup? =
        (learned?.phi ?: equivariant?.phi)?.let { weightBuffer("${settings.name}Phi", it) }

    private fun weightBuffer(bufferName: String, weights: afield.stdlib.MlpWeights): FrameBufferGroup =
        buffer(name = bufferName) {
            size = weights.parameterCount * 4L
            storage(); addressable(); hosted(); dest()
        }.toBuffer(renderer).also { group ->
            group.get(write = true).mapAndWrite { put(weights.flat) }
        }

    /** The compute shader as installed — editable, and generated against this grid's dimensions. */
    val computeSource: String = shader()

    init {
        require(count in 1..grid.capacity) { "count $count is outside the grid's 1..${grid.capacity}" }
        require(learned == null || equivariant == null) {
            "give ShaderBoids one learned rule or the other, not both"
        }
    }

    /**
     * Seeds the flock: uniform positions over [min] to `min + size`, and velocities of [speed] in
     * random directions.
     */
    fun reset(min: Vector2f, size: Float, speed: Float = 0.2f, seed: Long = 1L) {
        val random = java.util.Random(seed)
        boids.get(write = true).mapAndWrite {
            repeat(count) {
                put(min.x + random.nextFloat() * size)
                put(min.y + random.nextFloat() * size)
                val a = random.nextDouble() * 2 * Math.PI
                put((speed * kotlin.math.cos(a)).toFloat())
                put((speed * kotlin.math.sin(a)).toFloat())
                put(0f); put(0f)      // accel
                put(0f); put(0f)      // previousVelocity
            }
        }
    }

    /**
     * Installs the grid and then this rule into [into], in that order.
     *
     * The grid has to be rebuilt from the current positions *before* the rule reads it, and both
     * live in the same `others` map, which is ordered — so this is one call rather than two, so
     * that the ordering cannot be got wrong by a caller.
     */
    fun install(into: MutableMap<String, Target>) {
        require(settings.radius <= grid.cellSize + 1e-6f) {
            "a radius of ${settings.radius} does not fit a cell of ${grid.cellSize}; " +
                "the 3x3 query would silently miss neighbours. Use at most " +
                "${grid.cellsPerSide} cells for this radius, or a radius of ${grid.cellSize}"
        }

        grid.install(into, boids, count, strideFloats = STRIDE_FLOATS, offsetFloats = 0)

        val rule = module ?: ComputeModule(
            parameters(renderer) { name = "${settings.name}-rule"; compute = computeSource }
        ).pointer().also { module = it }

        ComputeMesh(renderer, rule).apply {
            x = ceil(count / LOCAL_SIZE.toDouble()).toInt().coerceAtLeast(1); y = 1; z = 1
            push.bind("boids") { this@ShaderBoids.boids }
            push.bind("values") { grid.values }
            push.bind("cellStart") { grid.cellStart }
            push.bind("cellEnd") { grid.cellEnd }
            push.bind("domainMin") { grid.domainMin }
            push.bind("invCellSize") { grid.cellsPerSide / grid.domainSize }
            push.bind("radius") { settings.radius }
            push.bind("separation") { settings.separation }
            push.bind("alignment") { settings.alignment }
            push.bind("cohesion") { settings.cohesion }
            push.bind("maxSpeed") { settings.maxSpeed }
            push.bind("dt") { settings.dt }
            push.bind("minSeparation") { settings.radius * settings.minSeparationFraction }
            push.bind("separationRadius") { settings.radius * settings.separationRadiusFraction }
            push.bind("containment") { settings.containment }
            push.bind("containmentRadius") { settings.containmentRadius }
            push.bind("containmentCenter") { settings.containmentCenter }
            push.bind("driverStrength") { settings.driverStrength }
            push.bind("driverRadius") { settings.driverRadius }
            push.bind("driverPosition") { driver() }
            push.bind("count") { count }
            if (learned != null || equivariant != null) {
                push.bind("psiWeights") { psiBuffer!! }
                push.bind("phiWeights") { phiBuffer!! }
            }
            rule.current.meshes["${settings.name}-rule"] = this
        }

        val bounds = Rectanglef(0f, 0f, 0f, 0f)
        into["${settings.name}-rule"] = Target { command, _ -> rule.render(command, bounds) }
    }

    /**
     * Takes the grid rebuild and the rule back out of [into], undoing [install].
     *
     * A draw pass added by [installDrawInto] went into a render target rather than into this map,
     * and is not removed here.
     */
    fun removeFrom(into: MutableMap<String, Target>) {
        grid.removeFrom(into)
        into.remove("${settings.name}-rule")
    }

    /**
     * Adds a point-list draw of the flock, reading the same allocation the rule writes — no copy,
     * the `afield_four_graphics.md` compute-then-draw shape.
     *
     * Colour runs from [BoidSettings.slowColor] to [BoidSettings.fastColor] by speed, which is the
     * cheapest way to make a flock's structure legible: the interesting thing about a flock is
     * where it is turning, and turning costs speed.
     */
    fun installDrawInto(target: afield.four.targets.RenderTarget = renderer.renderList) {
        val draw = drawModule ?: Module(
            parameters(renderer) {
                name = "${settings.name}-draw"
                depthTest = false
                blend = true
                srcColor = VK_BLEND_FACTOR_SRC_ALPHA
                dstColor = VK_BLEND_FACTOR_ONE
                colorBlendOp = VK_BLEND_OP_ADD
                srcAlpha = VK_BLEND_FACTOR_ONE
                dstAlpha = VK_BLEND_FACTOR_ONE
                alphaBlendOp = VK_BLEND_OP_ADD
                primitiveType = points
                vertex = VERTEX_SHADER
                fragment = FRAGMENT_SHADER
            }
        ).pointer().also { drawModule = it }

        Mesh(renderer, draw).apply {
            resize(count, 0, draw)
            push.bind("boids") { this@ShaderBoids.boids }
            push.bind("pointSize") { settings.pointSize }
            push.bind("aspectRatio") { renderer.window.width / renderer.window.height.toFloat() }
            push.bind("viewScale") { grid.domainSize * 0.5f }
            push.bind("maxSpeed") { settings.maxSpeed }
            push.bind("slowColor") { settings.slowColor }
            push.bind("fastColor") { settings.fastColor }
            push.bind("intensity") { settings.intensity }
            draw.current.meshes["${settings.name}-points"] = this
        }
        target += "${settings.name}-draw" to draw
    }

    /**
     * The same rule, in Kotlin, over every other particle — the oracle.
     *
     * Iterates in index order rather than in the grid's sorted order, deliberately: it is then an
     * independent implementation of the *law*, not a transcription of the traversal. The neighbour
     * sets are already proven exact by `four.neighbour-grid.smoke`, so what remains between the
     * two is the order a few hundred floats are added in, which is worth a small tolerance and
     * nothing more.
     */
    fun referenceAcceleration(state: FloatArray, index: Int): Pair<Float, Float> {
        val px = state[index * STRIDE_FLOATS]
        val py = state[index * STRIDE_FLOATS + 1]
        val vx = state[index * STRIDE_FLOATS + 2]
        val vy = state[index * STRIDE_FLOATS + 3]
        val floor = settings.radius * settings.minSeparationFraction

        var sepX = 0.0f; var sepY = 0.0f
        var velX = 0.0f; var velY = 0.0f
        var posX = 0.0f; var posY = 0.0f
        var n = 0f

        for (j in 0 until count) {
            if (j == index) continue
            val dx = state[j * STRIDE_FLOATS] - px
            val dy = state[j * STRIDE_FLOATS + 1] - py
            val distance = sqrt(dx * dx + dy * dy)
            if (distance > settings.radius || distance <= 0f) continue
            if (distance <= settings.radius * settings.separationRadiusFraction) {
                val soft = maxOf(distance, floor)
                sepX -= dx / (soft * soft); sepY -= dy / (soft * soft)
            }
            velX += state[j * STRIDE_FLOATS + 2]; velY += state[j * STRIDE_FLOATS + 3]
            posX += state[j * STRIDE_FLOATS]; posY += state[j * STRIDE_FLOATS + 1]
            n += 1f
        }
        if (n == 0f) return 0f to 0f
        val ax = settings.separation * sepX +
            settings.alignment * (velX / n - vx) +
            settings.cohesion * (posX / n - px)
        val ay = settings.separation * sepY +
            settings.alignment * (velY / n - vy) +
            settings.cohesion * (posY / n - py)
        return ax to ay
    }

    private fun shader(): String = buildString {
        append("#version 450\n")
        append("#extension GL_EXT_scalar_block_layout : require\n")
        append(grid.glsl("grid"))
        append("\n\n")
        if (learned != null) { append(learned.glsl("learned")); append("\n") }
        if (equivariant != null) { append(equivariant.glsl("learned")); append("\n") }
        append(
            """
            struct Boid {
                vec2 position;
                vec2 velocity;
                vec2 accel;
                vec2 previousVelocity;
            };

            layout(std430, buffer_reference, scalar) buffer Boids { Boid b[]; };

            layout(push_constant) uniform Push {
                Boids boids;
                gridUints values;
                gridUints cellStart;
                gridUints cellEnd;
                vec2 domainMin;
                float invCellSize;
                float radius;
                float separation;
                float alignment;
                float cohesion;
                float maxSpeed;
                float dt;
                float minSeparation;
                float separationRadius;
                float containment;
                float containmentRadius;
                vec2 containmentCenter;
                float driverStrength;
                float driverRadius;
                vec2 driverPosition;
                uint count;
                ${if (learned != null || equivariant != null) "learnedPsiWeights psiWeights;\n                learnedPhiWeights phiWeights;" else ""}
            } pc;

            layout(local_size_x = $LOCAL_SIZE) in;

            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i >= pc.count) return;

                Boid me = pc.boids.b[i];

                ${declareAccumulators()}
                float n = 0.0;

                // The 3x3 walk, written out rather than hidden behind a macro -- this is the loop
                // a learned rule replaces, so it is the one worth being able to read.
                ivec2 home = gridCellOf(me.position, pc.domainMin, pc.invCellSize);
                for (int dy = -1; dy <= 1; ++dy)
                for (int dx = -1; dx <= 1; ++dx) {
                    ivec2 c = home + ivec2(dx, dy);
                    if (c.x < 0 || c.y < 0 || c.x >= gridCells || c.y >= gridCells) continue;
                    uint ci = gridCellIndex(c);
                    for (uint k = pc.cellStart.v[ci]; k < pc.cellEnd.v[ci]; ++k) {
                        uint j = pc.values.v[k];
                        if (j == i) continue;
                        vec2 d = pc.boids.b[j].position - me.position;
                        float distance = length(d);
                        if (distance > pc.radius || distance <= 0.0) continue;
                        ${accumulate()}
                        n += 1.0;
                    }
                }

                ${combine()}

                // Both written before the integration, so together they describe exactly what
                // this step saw and what it did with it -- see the class KDoc.
                me.accel = accel;
                me.previousVelocity = me.velocity;

                // Zero by default, and then exactly zero rather than nearly so.
                // Forcing, not interaction: added after the rule, exactly like containment, and
                // zero by default so the tests see the rule alone.
                if (pc.driverStrength != 0.0) {
                    vec2 toDriver = pc.driverPosition - me.position;
                    float far = length(toDriver);
                    if (far > 1e-6) {
                        float falloff = (pc.driverRadius * pc.driverRadius)
                                      / (far * far + pc.driverRadius * pc.driverRadius);
                        accel += pc.driverStrength * falloff * toDriver / far;
                    }
                }

                vec2 fromHome = me.position - pc.containmentCenter;
                float excess = max(length(fromHome) - pc.containmentRadius, 0.0);
                if (excess > 0.0) accel -= pc.containment * excess * normalize(fromHome);

                vec2 v = me.velocity + accel * pc.dt;
                float speed = length(v);
                if (speed > pc.maxSpeed) v *= pc.maxSpeed / speed;
                me.velocity = v;
                me.position += v * pc.dt;

                pc.boids.b[i] = me;
            }
            """.trimIndent()
        )
        append("\n")
    }

    private fun declareAccumulators(): String = when {
        equivariant != null ->
            """vec2 messageSum[learnedChannels];
                for (int k = 0; k < learnedChannels; ++k) messageSum[k] = vec2(0.0);"""
        learned != null ->
            """float messageSum[learnedMessageWidth];
                for (int k = 0; k < learnedMessageWidth; ++k) messageSum[k] = 0.0;"""
        else ->
            """vec2 push = vec2(0.0);
                vec2 sumVelocity = vec2(0.0);
                vec2 sumPosition = vec2(0.0);"""
    }

    private fun accumulate(): String =
        if (equivariant != null) {
            // psi emits SCALARS; the vector comes from the neighbour's own direction and velocity
            // difference. That is the whole of the equivariance.
            """float f[5];
                        learnedInvariants(d, pc.boids.b[j].velocity - me.velocity, f);
                        float c[${2 * equivariant.channels}];
                        learnedPsi(pc.psiWeights, f, c);
                        vec2 u = distance > 0.0 ? d / distance : vec2(0.0);
                        vec2 dvn = (pc.boids.b[j].velocity - me.velocity) / learnedVelocityScale;
                        for (int k = 0; k < learnedChannels; ++k) {
                            messageSum[k] += c[k] * u + c[learnedChannels + k] * dvn;
                        }"""
        } else if (learned == null) {
            """if (distance <= pc.separationRadius) {
                            float soft = max(distance, pc.minSeparation);
                            push -= d / (soft * soft);
                        }
                        sumVelocity += pc.boids.b[j].velocity;
                        sumPosition += pc.boids.b[j].position;"""
        } else {
            """float f[8];
                        learnedFeatures(d, pc.boids.b[j].velocity - me.velocity, f);
                        float m[learnedMessageWidth];
                        learnedPsi(pc.psiWeights, f, m);
                        for (int k = 0; k < learnedMessageWidth; ++k) messageSum[k] += m[k];"""
        }

    private fun combine(): String =
        if (equivariant != null) {
            """vec2 accel = n > 0.0 ? learnedCombine(pc.phiWeights, me.velocity, messageSum, n) : vec2(0.0);"""
        } else if (learned == null) {
            """vec2 accel = vec2(0.0);
                if (n > 0.0) {
                    accel = pc.separation * push
                          + pc.alignment * (sumVelocity / n - me.velocity)
                          + pc.cohesion * (sumPosition / n - me.position);
                }"""
        } else {
            """vec2 accel = n > 0.0 ? learnedCombine(pc.phiWeights, me.velocity, messageSum, n) : vec2(0.0);"""
        }

    companion object {
        const val STRIDE_FLOATS = 8
        private const val LOCAL_SIZE = 64

        private val VERTEX_SHADER = """
            #version 450
            #extension GL_EXT_buffer_reference : require
            #extension GL_EXT_scalar_block_layout : require

            struct Boid { vec2 position; vec2 velocity; vec2 accel; vec2 previousVelocity; };
            layout(std430, buffer_reference, scalar) buffer Boids { Boid b[]; };

            layout(push_constant) uniform Push {
                Boids boids;
                float pointSize;
                float aspectRatio;
                float viewScale;
                float maxSpeed;
                vec4 slowColor;
                vec4 fastColor;
                float intensity;
            } pc;

            layout(location = 0) out vec4 tint;

            void main() {
                Boid me = pc.boids.b[gl_VertexIndex];
                vec2 c = me.position / pc.viewScale;
                c.x /= pc.aspectRatio;
                gl_Position = vec4(c.x, -c.y, 0.0, 1.0);
                gl_PointSize = pc.pointSize;
                float speed = clamp(length(me.velocity) / max(pc.maxSpeed, 1e-6), 0.0, 1.0);
                tint = vec4(mix(pc.slowColor.rgb, pc.fastColor.rgb, speed), pc.intensity);
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #version 450
            layout(location = 0) in vec4 tint;
            layout(location = 0) out vec4 outColor;
            void main() { outColor = tint; }
        """.trimIndent()
    }
}
